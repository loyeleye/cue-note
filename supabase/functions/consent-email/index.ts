// consent-email: the dashboard's emails to teachers, sent through Resend.
//   - Invites: a student invited this address to see their lessons.
//   - Consent: another teacher would like to see the lessons this teacher
//     taught a student; links to the dashboard to answer.
//
// The database wakes this function (through pg_net) on a new invite and when
// it adds requests to public.lesson_view_consent. The wake carries no data:
// the function reads what hasn't been emailed yet with the service role,
// sends it, and marks it emailed. Opening its URL also works as a wake. Anyone calling it can at most make those emails go out
// sooner, so it runs without a JWT check. The request also shows on the
// owner's dashboard, so a missing email setup only delays the news.
//
// Secrets (supabase secrets set ...):
//   RESEND_API_KEY     Resend API key; without it nothing is sent
//   CONSENT_EMAIL_FROM sender, "Cue Note <notifications@cuenote.cc>" (sign-in links come from login@)
//   DASHBOARD_URL      the teacher dashboard, e.g. https://cue-note.pages.dev
// SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are provided by Supabase.
import { createClient } from 'npm:@supabase/supabase-js@2';

type Request = { id: string; student_id: string; owner_id: string; viewer_id: string };

const escape = (s: string) =>
  s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);

Deno.serve(async () => {
  const key = Deno.env.get('RESEND_API_KEY');
  const from = Deno.env.get('CONSENT_EMAIL_FROM');
  const dashboard = (Deno.env.get('DASHBOARD_URL') ?? '').replace(/\/+$/, '');
  if (!key || !from || !dashboard) {
    return Response.json({ sent: 0, note: 'RESEND_API_KEY, CONSENT_EMAIL_FROM or DASHBOARD_URL is not set' });
  }
  const db = createClient(Deno.env.get('SUPABASE_URL')!, Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!);
  const send = (to: string, subject: string, html: string) =>
    fetch('https://api.resend.com/emails', {
      method: 'POST',
      headers: { authorization: `Bearer ${key}`, 'content-type': 'application/json' },
      body: JSON.stringify({ from, to, subject, html }),
    });

  // ---------------------------------------------------------------- invites
  let invited = 0;
  const { data: invites, error: inviteError } = await db
    .from('teacher_student_share')
    .select('id, student_id, invite_email')
    .eq('status', 'pending')
    .is('invite_emailed_at', null)
    .order('created_at')
    .limit(50);
  if (inviteError) return Response.json({ error: inviteError.message }, { status: 500 });
  for (const inv of invites ?? []) {
    const { data: p } = await db.from('profile').select('display_name').eq('id', inv.student_id).maybeSingle();
    const student = p?.display_name?.trim() || 'A student';
    const res = await send(
      inv.invite_email,
      `${student} invited you to Cue Note`,
      `<p>${escape(student)} uses Cue Note to remember their voice lessons, and has invited you to see them.</p>` +
        `<p>Sign in to the teacher dashboard with this email address (${escape(inv.invite_email)}) and accept the invite. ` +
        `There's no password: you'll get a sign-in link by email.</p>` +
        `<p><a href="${dashboard}/">Open the Cue Note dashboard</a></p>`,
    );
    if (!res.ok) continue; // stays unemailed; the next wake tries again
    await db.from('teacher_student_share').update({ invite_emailed_at: new Date().toISOString() }).eq('id', inv.id);
    invited++;
  }

  // ---------------------------------------------------------------- consent
  const { data: pending, error } = await db
    .from('lesson_view_consent')
    .select('id, student_id, owner_id, viewer_id')
    .eq('status', 'pending')
    .is('emailed_at', null)
    .order('created_at')
    .limit(50);
  if (error) return Response.json({ error: error.message }, { status: 500 });
  if (!pending?.length) return Response.json({ sent: 0, invited });

  let sent = 0;
  for (const r of pending as Request[]) {
    // The owner's address is the one the student invited; names as the owner would see them.
    const [{ data: share }, { data: people }] = await Promise.all([
      db.from('teacher_student_share').select('invite_email')
        .eq('student_id', r.student_id).eq('teacher_id', r.owner_id).eq('status', 'active').maybeSingle(),
      db.from('profile').select('id, display_name, alias').in('id', [r.student_id, r.viewer_id]),
    ]);
    const to = share?.invite_email;
    if (!to) continue;
    const student = people?.find((p) => p.id === r.student_id)?.display_name?.trim() || 'Your student';
    const viewer = people?.find((p) => p.id === r.viewer_id)?.alias?.trim() || 'Another teacher';
    const link = `${dashboard}/#requests`;
    const res = await send(
      to,
      `${viewer} would like to see your lessons with ${student}`,
      `<p>${escape(student)} has asked Cue Note to let ${escape(viewer)}, another of their teachers, ` +
        `see all of ${escape(student)}'s lessons.</p>` +
        `<p>That includes the lessons you taught, so ${escape(viewer)} will see them only if you agree. ` +
        `You can change your answer at any time.</p>` +
        `<p><a href="${link}">Answer on your dashboard</a></p>`,
    );
    if (!res.ok) continue; // stays unemailed; the next wake tries again
    await db.from('lesson_view_consent').update({ emailed_at: new Date().toISOString() }).eq('id', r.id);
    sent++;
  }
  return Response.json({ sent, invited });
});
