<script lang="ts">
  // Home: the sign-in form, or the students who shared with her.
  import { api, app, enterDemo, refreshMe } from '$lib/app.svelte';
  import { isConfigured, supabase } from '$lib/supabase';
  import { date, ago } from '$lib/format';
  import type { FullLessonPass, LessonViewRequest, Share, StudentSummary } from '$lib/types';

  let email = $state('');
  let status = $state('');
  let sending = $state(false);
  let students = $state<StudentSummary[] | null>(null);
  let invites = $state<(Share & { student_name: string })[]>([]);
  let error = $state('');
  let requests = $state<LessonViewRequest[]>([]);
  // Students' invites to hear a whole lesson, waiting for this teacher's answer.
  let passInvites = $state<FullLessonPass[]>([]);
  // A debug account uses the teacher dashboard too, seeing every student.
  const teacherView = $derived(app.me?.role === 'teacher' || !!app.me?.debug);

  async function reload() {
    try {
      // A new account is a student until it accepts an invite, so look for
      // invites whatever the role; only a teacher has students to list.
      invites = await api().listInvites();
      students = teacherView ? await api().listStudents() : [];
      requests = app.me?.role === 'teacher' ? await api().listLessonViewRequests() : [];
      passInvites = app.me?.role === 'teacher'
        ? (await api().listFullLessonPasses()).filter((p) => p.status === 'pending' && p.asked_by === 'student')
        : [];
    } catch (e) {
      error = (e as Error).message;
    }
  }

  $effect(() => {
    if (app.me) reload();
  });

  async function sendLink(e: SubmitEvent) {
    e.preventDefault();
    sending = true;
    status = '';
    const { error } = await supabase.auth.signInWithOtp({
      email: email.trim(),
      options: {
        // Every new account starts as a student. It becomes a teacher
        // account when it accepts a student's invite (accept_share).
        emailRedirectTo: `${location.origin}/auth/callback`
      }
    });
    sending = false;
    status = error ? `Couldn't send the link: ${error.message}` : 'Check your email for the sign-in link.';
  }

  async function decide(r: LessonViewRequest, allow: boolean) {
    try {
      await api().decideLessonView(r.id, allow);
      requests = await api().listLessonViewRequests();
    } catch (e) {
      error = (e as Error).message;
    }
  }

  async function answerPass(p: FullLessonPass, yes: boolean) {
    try {
      await api().answerFullLesson(p.id, yes);
      await reload();
    } catch (e) {
      error = (e as Error).message;
    }
  }

  const nameOf = (studentId: string) =>
    students?.find((x) => x.student.id === studentId)?.student.display_name.split(' ')[0] || 'A student';

  async function accept(id: string) {
    try {
      await api().acceptInvite(id);
      await refreshMe();     // accepting an invite is what makes this a teacher account
      await reload();
    } catch (e) {
      error = (e as Error).message;
    }
  }
</script>

{#if !app.me}
  <section class="hero card">
    <img class="hero-mark" src="/favicon.svg" alt="" width="56" height="56" />
    <p class="eyebrow">Your voice lessons, remembered</p>
    <h1>Cue Note for teachers</h1>
    <p>Correct your students' lesson cards, leave notes and set this week's practice. We'll email you a link; no password needed.</p>
    {#if isConfigured}
      <form onsubmit={sendLink}>
        <label for="email">Email</label>
        <input id="email" type="email" autocomplete="email" required bind:value={email} />
        <button type="submit" class="primary" disabled={sending}>{sending ? 'Sending…' : 'Email me a link'}</button>
      </form>
      {#if status}<p role="status">{status}</p>{/if}
    {:else}
      <p class="muted">Supabase isn't configured. Copy <code>.env.example</code> to <code>.env</code> and fill it in.</p>
    {/if}
    <p class="demo">No lessons yet? <button class="link" onclick={enterDemo}>Try the demo</button> with sample students and lessons.</p>
  </section>
{:else if !teacherView && invites.length}
  <h1>Welcome to Cue Note</h1>
  <p>A student has invited you to see their lessons. Accepting makes this a teacher account.</p>
  {#if error}<p class="error" role="alert">{error}</p>{/if}
  <section class="invites">
    {#each invites as inv (inv.id)}
      <div class="card invite">
        <span>Invite to {inv.invite_email} <span class="muted">({date(inv.created_at)})</span></span>
        <button class="primary small" onclick={() => accept(inv.id)}>Accept invite</button>
      </div>
    {/each}
  </section>
{:else if !teacherView}
  <h1>This dashboard is for teachers</h1>
  <p class="muted">
    You're signed in as {app.email}, which isn't a teacher account yet. An account becomes a teacher
    account when it accepts a student's invite: ask your student to invite this email address from
    the Cue Note phone app, then come back here.
  </p>
{:else}
  <h1>Students</h1>
  <p class="muted">Newest lesson first.</p>
  {#if app.me.debug}
    <p class="card debug" role="note"><strong>Debug view.</strong> You see every student and lesson, as a teacher with full access would. Nothing can be changed from here.</p>
  {/if}
  {#if error}<p class="error" role="alert">{error}</p>{/if}

  {#if requests.length}
    <section class="requests" id="requests" aria-label="Requests to see your lessons">
      {#each requests as r (r.id)}
        <div class="card request" class:pending={r.status === 'pending'}>
          <span>
            <strong>{r.viewer_alias || 'Another teacher'}</strong> would like to see the lessons you taught
            <strong>{r.student_name || 'your student'}</strong>.
            {#if r.status === 'granted'}<span class="muted">You said yes.</span>{:else if r.status === 'declined'}<span class="muted">You said no.</span>{/if}
          </span>
          <span class="actions">
            {#if r.status !== 'granted'}<button class="primary small" onclick={() => decide(r, true)}>Allow</button>{/if}
            {#if r.status !== 'declined'}<button class="small" onclick={() => decide(r, false)}>{r.status === 'granted' ? 'Take back' : 'Decline'}</button>{/if}
          </span>
        </div>
      {/each}
    </section>
  {/if}

  {#if passInvites.length}
    <section class="requests" aria-label="Invites to hear a whole lesson">
      {#each passInvites as p (p.id)}
        <div class="card request pending">
          <span>
            <strong>{nameOf(p.student_id)}</strong> invited you to hear their <a href="/lessons/{p.lesson_id}">whole lesson</a>, open for 14 days.
            {#if p.note}<span class="muted">“{p.note}”</span>{/if}
          </span>
          <span class="actions">
            <button class="primary small" onclick={() => answerPass(p, true)}>Accept</button>
            <button class="small" onclick={() => answerPass(p, false)}>Not this time</button>
          </span>
        </div>
      {/each}
    </section>
  {/if}

  {#if invites.length}
    <section class="invites">
      {#each invites as inv (inv.id)}
        <div class="card invite">
          <span>A student invited you to see their lessons <span class="muted">({date(inv.created_at)})</span>.</span>
          <button class="primary small" onclick={() => accept(inv.id)}>Accept</button>
        </div>
      {/each}
    </section>
  {/if}

  {#if students === null}
    <p class="muted">Loading…</p>
  {:else if !students.length}
    <div class="card empty">
      <p><strong>No students yet.</strong></p>
      <p class="muted">A student invites you from the phone app with your email address. The invite shows up here to accept.</p>
    </div>
  {:else}
    <ul class="students">
      {#each students as s (s.student.id)}
        <li>
          <a class="card student" href="/students/{s.student.id}">
            <span class="avatar" aria-hidden="true">{(s.student.display_name || '?').slice(0, 1)}</span>
            <span class="who">
              <strong>{s.student.display_name || 'Unnamed student'}</strong>
              <span class="muted">
                {s.lessonCount} lesson{s.lessonCount === 1 ? '' : 's'}{s.lastLessonAt ? ` · last ${ago(s.lastLessonAt)}` : ''}
              </span>
            </span>
            {#if s.share.next_lesson_at}<span class="chip">Next: {date(s.share.next_lesson_at)}</span>{/if}
          </a>
        </li>
      {/each}
    </ul>
  {/if}
{/if}

<style>
  .hero { max-width: 460px; margin: 40px auto; padding: 32px 28px; }
  .hero-mark { border-radius: 14px; display: block; margin-bottom: 18px; }
  .eyebrow { margin: 0 0 4px; font-size: 12px; font-weight: 600; letter-spacing: 0.08em; text-transform: uppercase; color: var(--muted); }
  .hero form { display: flex; flex-direction: column; gap: 8px; margin-top: 16px; }
  .demo { margin-top: 28px; padding-top: 16px; border-top: 1px solid var(--border); }
  .demo .link { font-size: inherit; }
  .invites { display: flex; flex-direction: column; gap: 8px; margin: 12px 0; }
  .invite { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; border-color: var(--accent); }
  .students { list-style: none; padding: 0; margin: 16px 0 0; display: grid; gap: 10px; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); }
  .student { display: flex; align-items: center; gap: 12px; text-decoration: none; color: inherit; }
  .student:hover { border-color: var(--accent); }
  .avatar {
    width: 40px; height: 40px; border-radius: 50%; background: var(--accent-soft); color: var(--accent-strong);
    display: grid; place-items: center; font-weight: 700; flex: none;
  }
  .who { display: flex; flex-direction: column; flex: 1; min-width: 0; }
  .who .muted { font-size: 13px; }
  .empty p { margin: 4px 0; }
  .error { color: var(--danger); }
  .debug { border-color: var(--accent); }
  .requests { display: flex; flex-direction: column; gap: 8px; margin: 12px 0; }
  .request { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
  .request.pending { border-color: var(--accent); }
  .request .actions { display: flex; gap: 8px; }
</style>
