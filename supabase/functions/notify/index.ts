// notify: sends the queued "card updated" pushes to students' phones.
//
// The database wakes this function (through pg_net) every time a teacher saves
// a card. The wake carries no data: the function reads public.notification_outbox
// with the service role, sends each pending push through Firebase Cloud
// Messaging (HTTP v1), and marks it sent. Anyone calling it can at most make
// queued pushes go out a little sooner, so it runs without a JWT check.
//
// Secrets (supabase secrets set ...):
//   FCM_SERVICE_ACCOUNT  the Firebase service-account JSON, as one string
// SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are provided by Supabase.
import { createClient } from 'npm:@supabase/supabase-js@2';

const MAX_ATTEMPTS = 5;

type Outbox = {
  id: string;
  recipient_id: string;
  feedback_id: string | null;
  lesson_id: string | null;
  title: string;
  body: string;
  attempts: number;
};

type ServiceAccount = { client_email: string; private_key: string; project_id: string };

function b64url(data: ArrayBuffer | string): string {
  const bytes = typeof data === 'string' ? new TextEncoder().encode(data) : new Uint8Array(data);
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

// Trade the service account for a one-hour Google access token.
async function googleAccessToken(sa: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const claims = b64url(JSON.stringify({
    iss: sa.client_email,
    scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token',
    iat: now,
    exp: now + 3600,
  }));
  const pem = sa.private_key.replace(/-----[^-]+-----/g, '').replace(/\s+/g, '');
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey(
    'pkcs8', der, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign'],
  );
  const sig = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(`${header}.${claims}`));
  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion: `${header}.${claims}.${b64url(sig)}`,
    }),
  });
  if (!res.ok) throw new Error(`Google token: ${res.status} ${await res.text()}`);
  return (await res.json()).access_token;
}

Deno.serve(async () => {
  const db = createClient(Deno.env.get('SUPABASE_URL')!, Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!);
  const saJson = Deno.env.get('FCM_SERVICE_ACCOUNT');
  if (!saJson) return Response.json({ sent: 0, note: 'FCM_SERVICE_ACCOUNT is not set; pushes stay queued' });
  const sa: ServiceAccount = JSON.parse(saJson);

  const { data: pending, error } = await db
    .from('notification_outbox')
    .select('id, recipient_id, feedback_id, lesson_id, title, body, attempts')
    .is('sent_at', null)
    .lt('attempts', MAX_ATTEMPTS)
    .order('created_at')
    .limit(50);
  if (error) return Response.json({ error: error.message }, { status: 500 });
  if (!pending?.length) return Response.json({ sent: 0 });

  const token = await googleAccessToken(sa);
  let sent = 0;
  for (const item of pending as Outbox[]) {
    const { data: devices } = await db.from('device_token').select('token').eq('user_id', item.recipient_id);
    let lastError: string | null = devices?.length ? null : 'no registered phone';
    for (const d of devices ?? []) {
      const res = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
        method: 'POST',
        headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
        body: JSON.stringify({
          message: {
            token: d.token,
            notification: { title: item.title, body: item.body },
            data: { kind: 'card_updated', feedback_id: item.feedback_id ?? '', lesson_id: item.lesson_id ?? '' },
            android: { priority: 'high' },
          },
        }),
      });
      if (res.ok) continue;
      const text = await res.text();
      // The phone uninstalled the app or the token rotated: forget it.
      if (res.status === 404 || text.includes('UNREGISTERED')) {
        await db.from('device_token').delete().eq('token', d.token);
      } else {
        lastError = `${res.status} ${text.slice(0, 300)}`;
      }
    }
    const done = lastError === null || lastError === 'no registered phone';
    await db.from('notification_outbox').update({
      sent_at: done ? new Date().toISOString() : null,
      attempts: item.attempts + 1,
      last_error: lastError,
    }).eq('id', item.id);
    if (lastError === null) sent++;
  }
  return Response.json({ sent, checked: pending.length });
});
