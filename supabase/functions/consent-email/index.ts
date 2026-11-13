// consent-email: tells a teacher that another teacher would like to see the
// lessons they taught a student, and links to the dashboard to answer.
//
// The database wakes this function (through pg_net) when it adds requests to
// public.lesson_view_consent. The wake carries no data: the function reads the
// requests not emailed yet with the service role, emails each owner, and
// marks them emailed. Anyone calling it can at most make those emails go out
// sooner, so it runs without a JWT check. The request also shows on the
// owner's dashboard, so a missing email setup only delays the news.
//
// Secrets (supabase secrets set ...):
//   RESEND_API_KEY     Resend API key; without it nothing is sent
//   CONSENT_EMAIL_FROM sender, e.g. "Cue Note <hello@yourdomain>"
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

  const { data: pending, error } = await db
    .from('lesson_view_consent')
    .select('id, student_id, owner_id, viewer_id')
    .eq('status', 'pending')
    .is('emailed_at', null)
    .order('created_at')
    .limit(50);
  if (error) return Response.json({ error: error.message }, { status: 500 });
  if (!pending?.length) return Response.json({ sent: 0 });

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
    const res = await fetch('https://api.resend.com/emails', {
      method: 'POST',
      headers: { authorization: `Bearer ${key}`, 'content-type': 'application/json' },
      body: JSON.stringify({
        from,
        to,
        subject: `${viewer} would like to see your lessons with ${student}`,
        html: `<p>${escape(student)} has asked Cue Note to let ${escape(viewer)}, another of their teachers, ` +
          `see all of ${escape(student)}'s lessons.</p>` +
          `<p>That includes the lessons you taught, so ${escape(viewer)} will see them only if you agree. ` +
          `You can change your answer at any time.</p>` +
          `<p><a href="${link}">Answer on your dashboard</a></p>`,
      }),
    });
    if (!res.ok) continue; // stays unemailed; the next wake tries again
    await db.from('lesson_view_consent').update({ emailed_at: new Date().toISOString() }).eq('id', r.id);
    sent++;
  }
  return Response.json({ sent });
});
