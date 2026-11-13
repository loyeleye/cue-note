<script lang="ts">
  // The next lesson's agenda: what the student practiced since the last
  // lesson, the takes they shared for you to hear, and their questions,
  // newest first. Everything here came from the student's phone because
  // they chose to send it.
  import { api } from '$lib/app.svelte';
  import { ago, clock, date } from '$lib/format';
  import type { Agenda, Feedback, Lesson, SharedTake, StudentQuestion } from '$lib/types';
  import ClipPlayer from './ClipPlayer.svelte';
  import VoiceRecorder from './VoiceRecorder.svelte';

  let { studentId, studentName, cards = [], lessons = [], nextLessonAt = null, keepLesson = () => true }: {
    studentId: string;
    studentName: string;
    cards?: Feedback[];
    lessons?: Lesson[];
    nextLessonAt?: string | null;
    /** Hides takes and questions from lessons this teacher chose not to see. */
    keepLesson?: (lessonId: string | null) => boolean;
  } = $props();

  let agenda = $state<Agenda | null>(null);
  let error = $state('');
  // One reply open at a time: to a question ('q-<id>') or a take ('t-<id>').
  let answering = $state<string | null>(null);
  let answerText = $state('');
  let answerVoice = $state<Blob | null>(null);
  let sending = $state(false);
  let showHeard = $state(false);

  async function reload() {
    try { agenda = await api().getAgenda(studentId); error = ''; }
    catch (e) { error = e instanceof Error ? e.message : String(e); }
  }
  $effect(() => { void studentId; reload(); });

  const first = $derived(studentName.split(' ')[0] || 'They');
  const lastLesson = $derived(lessons.map((l) => l.recorded_at).sort().at(-1) ?? null);
  const since = $derived(agenda?.sessions.filter((s) => !lastLesson || s.started_at >= lastLesson) ?? []);
  const minutesSince = $derived(since.reduce((n, s) => n + s.minutes, 0));
  // The notes they spent the most goes on, across the sittings.
  const topNotes = $derived.by(() => {
    const goes = new Map<string, number>();
    for (const s of since) for (const it of s.items) goes.set(it.feedback_id, (goes.get(it.feedback_id) ?? 0) + it.takes);
    return [...goes.entries()]
      .map(([id, n]) => ({ card: cards.find((c) => c.id === id), n }))
      .filter((x) => x.card && keepLesson(x.card.lesson_id))
      .sort((a, b) => b.n - a.n)
      .slice(0, 4);
  });
  // Sittings of suggested practice since the last lesson, and a tired-then-hoarse pattern worth a word.
  const sittings = $derived(agenda?.sittings.filter((s) => !lastLesson || s.started_at >= lastLesson) ?? []);
  const VOICE: Record<string, string> = { fine: 'voice fine', tired: 'a bit tired', hoarse: 'hoarse or sore' };
  const strained = $derived(sittings.filter((s) => s.voice !== 'fine' && Date.now() - Date.parse(s.started_at) < 7 * 864e5));
  const pattern = $derived(strained.some((s) => s.voice === 'hoarse') && strained.length >= 2
    ? strained.slice().reverse().map((s) => `${VOICE[s.voice]} ${date(s.started_at, { weekday: 'long' })}`).join(', then ')
    : null);
  const skippedLine = (ids: string[]) => {
    const names = ids.map((i) => cueOf(i)).filter(Boolean);
    return names.length ? 'skipped “' + names.join('”, “') + '”' : ids.length ? `skipped ${ids.length}` : null;
  };
  const takes = $derived(agenda?.takes.filter((t) => keepLesson(t.lesson_id)) ?? []);
  const waiting = $derived(takes.filter((t) => !t.heard_at));
  const heard = $derived(takes.filter((t) => t.heard_at));
  const questions = $derived(agenda?.questions.filter((q) => keepLesson(q.lesson_id)) ?? []);
  const open = $derived(questions.filter((q) => q.status === 'open'));
  const answered = $derived(questions.filter((q) => q.status === 'answered'));

  const cueOf = (id: string | null) => cards.find((c) => c.id === id)?.cue ?? null;

  /** One friendly line about a take's pitch, with no numbers. */
  function pitchLine(t: SharedTake): string | null {
    const p = t.pitch;
    if (!p?.length) return null;
    const judged = p.filter((n) => n.verdict === 'IN_TUNE' || n.verdict === 'FLAT' || n.verdict === 'SHARP');
    if (!judged.length) return null;
    const flat = judged.filter((n) => n.verdict === 'FLAT').length;
    const sharp = judged.filter((n) => n.verdict === 'SHARP').length;
    const unclear = p.filter((n) => n.verdict === 'UNCLEAR').length;
    const off = flat + sharp;
    const main = off === 0 ? 'With your demo all the way through'
      : flat && sharp ? 'A few notes drifted a little off'
      : flat ? (flat === 1 ? 'One note ran a little flat' : `${flat} notes ran a little flat`)
      : sharp === 1 ? 'One note ran a little sharp' : `${sharp} notes ran a little sharp`;
    return main + (unclear ? '; some notes were too quiet to judge' : '') + '.';
  }

  async function heardIt(t: SharedTake, on: boolean) {
    await api().markTakeHeard(t.id, on);
    await reload();
  }

  function openReply(key: string) { answering = key; answerText = ''; answerVoice = null; }
  function closeReply() { answering = null; answerText = ''; answerVoice = null; }

  async function send(f: () => Promise<void>) {
    sending = true; error = '';
    try { await f(); closeReply(); await reload(); }
    catch (e) { error = e instanceof Error ? e.message : String(e); }
    finally { sending = false; }
  }

  const answer = (q: StudentQuestion, words: string | null, voice: Blob | null = null) =>
    send(() => api().answerQuestion(q.id, words?.trim() || null, voice));

  const reply = (t: SharedTake) => send(() => api().replyToTake(t, answerText, answerVoice));

  async function reopen(q: StudentQuestion) {
    await api().reopenQuestion(q.id);
    await reload();
  }
</script>

{#snippet composer(key: string, placeholder: string, onsend: () => void)}
  <label class="visually-hidden" for="reply-{key}">Your reply</label>
  <textarea id="reply-{key}" rows="2" bind:value={answerText} {placeholder}></textarea>
  <div class="row between">
    <VoiceRecorder maxSeconds={60} onrecorded={(b) => (answerVoice = b)} />
    <span class="row">
      <button class="ghost small" onclick={closeReply}>Cancel</button>
      <button class="primary small" disabled={sending || (!answerText.trim() && !answerVoice)} onclick={onsend}>{sending ? 'Sending…' : 'Send'}</button>
    </span>
  </div>
{/snippet}

{#snippet yourReply(words: string | null, clip: string | null)}
  <div class="reply">
    <span class="muted small">You replied</span>
    {#if words}<p class="small">{words}</p>{/if}
    {#if clip}<ClipPlayer path={clip} label="Your voice note" compact />{/if}
  </div>
{/snippet}

{#if error}<p class="error" role="alert">{error}</p>{/if}
{#if !agenda}
  <p class="muted">Loading…</p>
{:else}
  <p class="lead">
    {#if nextLessonAt}For the lesson on {date(nextLessonAt, { weekday: 'long', month: 'long', day: 'numeric' })}.{:else}Set the next lesson's date above, and {first} sees it on the phone.{/if}
    {#if !waiting.length && !open.length}Nothing waiting from {first} yet.{/if}
  </p>

  <section>
    <h3>Practice since the last lesson</h3>
    {#if since.length}
      <p>{since.length} {since.length === 1 ? 'sitting' : 'sittings'}, {minutesSince} minutes in all.</p>
      {#if topNotes.length}
        <ul class="plain">
          {#each topNotes as n (n.card!.id)}<li>“{n.card!.cue}” <span class="muted small">· {n.n} {n.n === 1 ? 'go' : 'goes'}</span></li>{/each}
        </ul>
      {/if}
    {:else}
      <p class="muted">No practice logged since the last lesson.</p>
    {/if}
  </section>

  {#if sittings.length}
    <section>
      <h3>Suggested practice</h3>
      {#if pattern}<p class="caution" role="note"><span aria-hidden="true">⚠</span> Voice {pattern}. Worth asking about.</p>{/if}
      <ul class="plain">
        {#each sittings as s (s.id)}
          <li>
            {date(s.started_at, { weekday: 'short' })} · {s.voice === 'hoarse' ? 'listening' : `${s.minutes} min`}
            <span class="muted small">· {s.done} of {s.steps} · {[skippedLine(s.skipped), VOICE[s.voice]].filter(Boolean).join(' · ')}</span>
          </li>
        {/each}
      </ul>
    </section>
  {/if}

  <section>
    <h3>Takes to hear {#if waiting.length}<span class="count">{waiting.length}</span>{/if}</h3>
    {#each waiting as t (t.id)}
      <div class="card item">
        <div class="top">
          <strong>{cueOf(t.feedback_id) ? `“${cueOf(t.feedback_id)}”` : 'A practice take'}</strong>
          <span class="muted small">{ago(t.recorded_at)} · {clock(t.duration_ms)}</span>
        </div>
        {#if t.clip_path}<ClipPlayer path={t.clip_path} compact />{/if}
        {#if t.feeling_note}<p class="felt">How it felt: “{t.feeling_note}”</p>{/if}
        {#if pitchLine(t)}<p class="muted small">{pitchLine(t)}</p>{/if}
        {#if answering === 't-' + t.id}
          {@render composer('t-' + t.id, `A word for ${first}, or record a few seconds`, () => reply(t))}
        {:else}
          <div class="row">
            <button class="primary small" onclick={() => heardIt(t, true)}>I've listened</button>
            <button class="ghost small" onclick={() => openReply('t-' + t.id)}>Reply</button>
          </div>
        {/if}
      </div>
    {:else}
      <p class="muted">{first} hasn't shared a practice take since you last listened. They can share up to three at a time.</p>
    {/each}
    {#if heard.length}
      <button class="link" onclick={() => (showHeard = !showHeard)}>{showHeard ? 'Hide' : 'Show'} {heard.length} you've listened to</button>
      {#if showHeard}
        {#each heard as t (t.id)}
          <div class="card item faded">
            <div class="top">
              <span>{cueOf(t.feedback_id) ? `“${cueOf(t.feedback_id)}”` : 'A practice take'}</span>
              <span class="muted small">listened {ago(t.heard_at!)}</span>
            </div>
            {#if t.clip_path}<ClipPlayer path={t.clip_path} compact />{/if}
            {@render yourReply(t.reply ?? null, t.reply_clip_path ?? null)}
            <div class="row"><button class="link" onclick={() => heardIt(t, false)}>Put back on the list</button></div>
          </div>
        {/each}
      {/if}
    {/if}
  </section>

  <section>
    <h3>Questions {#if open.length}<span class="count">{open.length}</span>{/if}</h3>
    {#each open as q (q.id)}
      <div class="card item">
        <p class="where muted small">
          {cueOf(q.feedback_id) ? `On “${cueOf(q.feedback_id)}”` : 'General'}{q.bar ? `, bar ${q.bar}` : ''} · {ago(q.created_at)}
        </p>
        <p class="q">{q.body}</p>
        {#if answering === 'q-' + q.id}
          {@render composer('q-' + q.id, `A short answer ${first} sees on the phone`, () => answer(q, answerText, answerVoice))}
        {:else}
          <div class="row">
            <button class="primary small" onclick={() => openReply('q-' + q.id)}>Answer</button>
            <button class="ghost small" onclick={() => answer(q, null)}>We covered it in the lesson</button>
          </div>
        {/if}
      </div>
    {:else}
      <p class="muted">No open questions from {first}.</p>
    {/each}
    {#if answered.length}
      <h4>Answered</h4>
      {#each answered as q (q.id)}
        <div class="card item faded">
          <p class="q">{q.body}</p>
          {#if q.answer || q.answer_clip_path}
            {@render yourReply(q.answer, q.answer_clip_path ?? null)}
          {:else}
            <p class="small">Covered in the lesson.</p>
          {/if}
          <div class="row"><button class="link" onclick={() => reopen(q)}>Reopen</button></div>
        </div>
      {/each}
    {/if}
  </section>
{/if}

<style>
  .lead { margin: 0 0 12px; }
  section { margin-bottom: 22px; }
  h3 { font-size: 15px; margin: 0 0 8px; display: flex; gap: 8px; align-items: center; }
  h4 { font-size: 13px; margin: 14px 0 6px; color: var(--muted); }
  .count { background: var(--accent); color: var(--accent-fg, #fff); border-radius: 999px; font-size: 12px; padding: 1px 8px; }
  .item { display: flex; flex-direction: column; gap: 8px; margin-bottom: 8px; }
  .item.faded { opacity: 0.75; }
  .top { display: flex; justify-content: space-between; gap: 8px; flex-wrap: wrap; align-items: baseline; }
  .row { display: flex; gap: 8px; flex-wrap: wrap; }
  .row.between { justify-content: space-between; align-items: center; }
  .reply { display: grid; gap: 4px; padding-left: 10px; border-left: 2px solid var(--accent-soft, var(--muted)); }
  .felt { margin: 0; font-style: italic; }
  .caution { font-weight: 600; margin-bottom: 6px; }
  .caution span { color: var(--muted); }
  .q { margin: 0; font-size: 15px; }
  .where { margin: 0; }
  .plain { list-style: none; margin: 4px 0 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
  p { margin: 0; }
  .muted { color: var(--muted); }
  .small { font-size: 13px; }
  .error { color: var(--danger); font-size: 13px; }
  textarea { width: 100%; }
</style>
