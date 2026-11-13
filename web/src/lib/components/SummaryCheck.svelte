<script lang="ts">
  // "Did you say this?" about one card's summary: a three-stop slider (Yes,
  // Close, Wrong) answered with one tap or the arrow keys, then, for Close or
  // Wrong, room to say what she meant in words and/or a voice note.
  import { untrack } from 'svelte';
  import { api } from '$lib/app.svelte';
  import { ago } from '$lib/format';
  import type { Feedback, SummaryCheck } from '$lib/types';
  import ClipPlayer from './ClipPlayer.svelte';
  import VoiceRecorder from './VoiceRecorder.svelte';

  let { card, studentId, onanswered }: {
    card: Feedback;
    studentId: string;
    onanswered: (f: Feedback) => void;
  } = $props();

  const STOPS: { value: SummaryCheck; label: string }[] = [
    { value: 'said', label: 'Yes' },
    { value: 'close', label: 'Close' },
    { value: 'not_said', label: 'Wrong' }
  ];
  const HELP: Record<SummaryCheck, string> = {
    said: 'The student sees it marked as checked by you.',
    close: 'The student keeps the summary, marked as close. Say what you meant below if it helps.',
    not_said: "Taken off the student's card: they see your clip and your own words instead. Say what you meant below if it helps, and use Edit if the title is wrong too."
  };

  let busy = $state(false);
  let error = $state('');
  let note = $state(untrack(() => card.summary_note ?? ''));
  let voice = $state<Blob | null>(null);
  let recorderKey = $state(0);
  let saved = $state(false);

  // The thumb moves at once; the answer is sent after a short pause, so
  // arrowing from Yes to Wrong sends one answer, not two.
  let chosen = $state<SummaryCheck | null>(null);
  let timer: ReturnType<typeof setTimeout> | undefined;
  const current = $derived(chosen ?? card.summary_check ?? null);
  const index = $derived(STOPS.findIndex((s) => s.value === current));
  const dirty = $derived((note.trim() || null) !== (card.summary_note ?? null) || !!voice);

  async function answer(verdict: SummaryCheck, explain: { note?: string | null; clip?: string | null; voice?: Blob | null }) {
    busy = true;
    error = '';
    saved = false;
    try {
      onanswered(await api().answerSummary(card, studentId, verdict, explain));
      return true;
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
      return false;
    } finally {
      busy = false;
    }
  }

  // A tap on the slider keeps whatever she has already said.
  function pick(verdict: SummaryCheck) {
    if (busy || verdict === current) return;
    chosen = verdict;
    clearTimeout(timer);
    timer = setTimeout(async () => {
      if (verdict === (card.summary_check ?? null)) { chosen = null; return; }
      await answer(verdict, { note: card.summary_note ?? null, clip: card.summary_note_clip ?? null });
      chosen = null; // the card now says what was saved, or the old answer if it failed
    }, 450);
  }
  $effect(() => () => clearTimeout(timer));

  function keys(e: KeyboardEvent) {
    const at = index < 0 ? -1 : index;
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') { e.preventDefault(); pick(STOPS[Math.min(STOPS.length - 1, at + 1)].value); }
    if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') { e.preventDefault(); pick(STOPS[Math.max(0, at - 1)].value); }
  }

  async function explain(e: SubmitEvent) {
    e.preventDefault();
    if (!current) return;
    const ok = await answer(current, { note, clip: card.summary_note_clip ?? null, voice });
    if (ok) { voice = null; recorderKey += 1; saved = true; }
  }

  function dropVoice() {
    if (current) answer(current, { note: card.summary_note ?? null, clip: null });
  }
</script>

<div class="check">
  <div class="row">
    <span class="q" id="q-{card.id}">Did you say this?</span>
    <div class="slider" role="radiogroup" aria-labelledby="q-{card.id}" tabindex="-1" onkeydown={keys}
         class:set={index >= 0} style:--at={Math.max(index, 0)} data-at={current ?? 'none'}>
      <span class="thumb" aria-hidden="true"></span>
      {#each STOPS as s, i (s.value)}
        <button type="button" role="radio" aria-checked={current === s.value} tabindex={index === i || (index < 0 && i === 0) ? 0 : -1}
                class:on={current === s.value} aria-disabled={busy} onclick={() => pick(s.value)}>{s.label}</button>
      {/each}
    </div>
  </div>
  {#if current}
    <p class="muted small">{HELP[current]}{card.summary_checked_at ? ` Answered ${ago(card.summary_checked_at)}.` : ''}</p>
  {/if}
  {#if current === 'not_said' && card.summary_rejected}
    <p class="old small"><span class="muted">The app had:</span> “{card.summary_rejected}”</p>
  {/if}

  {#if current === 'close' || current === 'not_said'}
    <form class="explain" onsubmit={explain}>
      <label for="note-{card.id}" class="small">What did you mean? Optional. The student sees this on the point, with your clip.</label>
      <textarea id="note-{card.id}" rows="3" maxlength="4000" bind:value={note}
                placeholder="As much as helps: what you were after, what to listen for, how to practice it."></textarea>
      <div class="voice">
        {#if card.summary_note_clip && !voice}
          <ClipPlayer path={card.summary_note_clip} label="Your voice note" compact />
          <button type="button" class="link small" onclick={dropVoice} disabled={busy}>Remove voice note</button>
        {/if}
        {#key recorderKey}
          <div class="rec"><span class="small muted">{card.summary_note_clip ? 'Or record a new one' : 'Or say it'}</span>
            <VoiceRecorder maxSeconds={120} onrecorded={(b) => (voice = b)} /></div>
        {/key}
      </div>
      <div class="actions">
        <span class="small muted" role="status">{#if dirty}Not saved yet.{:else if saved}Saved. The student sees it next time they open the lesson.{/if}</span>
        <button type="submit" class="small" class:primary={dirty} disabled={busy || !dirty}>{busy ? 'Saving…' : 'Save'}</button>
      </div>
    </form>
  {/if}
  {#if error}<p class="error small" role="alert">{error}</p>{/if}
</div>

<style>
  .check { display: flex; flex-direction: column; gap: 6px; }
  .row { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
  .q { font-size: 13px; font-weight: 600; }
  .slider {
    position: relative; display: grid; grid-template-columns: repeat(3, 1fr);
    background: var(--surface); border: 1px solid var(--border); border-radius: 999px; padding: 3px; min-width: 240px;
  }
  .thumb {
    position: absolute; top: 3px; bottom: 3px; left: 3px; width: calc((100% - 6px) / 3);
    border-radius: 999px; background: var(--accent-soft); opacity: 0;
    transform: translateX(calc(var(--at) * 100%)); transition: transform 0.2s ease, background 0.2s, opacity 0.2s;
  }
  .slider.set .thumb { opacity: 1; }
  .slider[data-at='close'] .thumb { background: var(--warn-bg); }
  .slider[data-at='not_said'] .thumb { background: color-mix(in srgb, var(--danger) 18%, transparent); }
  .slider button {
    position: relative; z-index: 1; border: none; background: transparent; color: var(--fg);
    border-radius: 999px; padding: 8px 14px; font-size: 14px; min-height: 40px; cursor: pointer;
  }
  .slider button.on { font-weight: 600; }
  .slider[data-at='close'] button.on { color: var(--warn-fg); }
  .slider[data-at='not_said'] button.on { color: var(--danger); }
  .slider button[aria-disabled='true'] { cursor: progress; }
  .slider button:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }
  @media (prefers-reduced-motion: reduce) { .thumb { transition: none; } }
  .old { margin: 0; color: var(--muted); text-decoration: line-through; text-decoration-color: color-mix(in srgb, var(--muted) 60%, transparent); }
  .actions button:disabled { color: var(--muted); border: 1px solid var(--border); background: transparent; opacity: 1; }
  .explain { display: flex; flex-direction: column; gap: 6px; margin-top: 2px; }
  .explain textarea { width: 100%; resize: vertical; }
  .voice { display: flex; flex-direction: column; gap: 6px; }
  .rec { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
  .actions { display: flex; gap: 8px; justify-content: flex-end; align-items: center; }
  .small { font-size: 13px; }
  .muted { color: var(--muted); }
  .error { color: var(--danger); margin: 0; }
  p { margin: 0; }
</style>
