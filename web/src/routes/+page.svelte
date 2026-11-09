<script lang="ts">
  // Home: the sign-in form, or the students who shared with her.
  import { api, app, enterDemo } from '$lib/app.svelte';
  import { isConfigured, supabase } from '$lib/supabase';
  import { date, ago } from '$lib/format';
  import type { Share, StudentSummary } from '$lib/types';

  let email = $state('');
  let status = $state('');
  let sending = $state(false);
  let students = $state<StudentSummary[] | null>(null);
  let invites = $state<(Share & { student_name: string })[]>([]);
  let error = $state('');

  async function reload() {
    try {
      [students, invites] = await Promise.all([api().listStudents(), api().listInvites()]);
    } catch (e) {
      error = (e as Error).message;
    }
  }

  $effect(() => {
    if (app.me?.role === 'teacher') reload();
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

  async function accept(id: string) {
    try {
      await api().acceptInvite(id);
      await reload();
    } catch (e) {
      error = (e as Error).message;
    }
  }
</script>

{#if !app.me}
  <section class="hero">
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
{:else if app.me.role !== 'teacher'}
  <h1>This dashboard is for teachers</h1>
  <p class="muted">You're signed in with a student account ({app.email}). Students use the Cue Note phone app.</p>
{:else}
  <h1>Students</h1>
  <p class="muted">Newest lesson first.</p>
  {#if error}<p class="error" role="alert">{error}</p>{/if}

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
  .hero { max-width: 460px; margin: 24px auto; }
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
</style>
