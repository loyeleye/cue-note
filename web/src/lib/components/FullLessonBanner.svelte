<script lang="ts">
  // Where this lesson stands for this teacher. By default they hear the
  // takes on "Practice this" cards and what the student shares; the rest is
  // the student's. The whole lesson opens for 14 days once both say yes.
  import { api } from '$lib/app.svelte';
  import { date } from '$lib/format';
  import { passState, type FullLessonPass } from '$lib/types';

  let { lessonId, studentName, onchanged = () => {} }: {
    lessonId: string;
    studentName: string;
    /** Called after the pass starts or ends, so the page can load what's now open. */
    onchanged?: () => void;
  } = $props();

  let passes = $state<FullLessonPass[] | null>(null);
  let asking = $state(false);
  let note = $state('');
  let busy = $state(false);
  let error = $state('');

  const first = $derived(studentName.split(' ')[0] || 'The student');
  const current = $derived(passes?.find((p) => p.lesson_id === lessonId && (p.status === 'pending' || passState(p) === 'active')) ?? null);
  const lastNo = $derived(passes?.find((p) => p.lesson_id === lessonId && p.status === 'declined' && p.asked_by === 'teacher') ?? null);

  async function reload() {
    try { passes = await api().listFullLessonPasses(); error = ''; }
    catch (e) { error = e instanceof Error ? e.message : String(e); passes = []; }
  }
  $effect(() => { void lessonId; reload(); });

  async function run(f: () => Promise<void>, changed = false) {
    busy = true; error = '';
    try { await f(); await reload(); if (changed) onchanged(); }
    catch (e) { error = e instanceof Error ? e.message : String(e); }
    finally { busy = false; }
  }
</script>

{#if passes}
  <section class="pass card" class:open={current && current.status === 'active'} aria-label="Who hears this lesson">
    {#if current?.status === 'active'}
      <p><strong>You can hear all of this lesson until {date(current.ends_at!, { weekday: 'long', month: 'long', day: 'numeric' })}.</strong>
        {first} said yes{current.note ? `: “${current.note}”` : '.'}</p>
      <button class="link" disabled={busy} onclick={() => run(() => api().endFullLesson(current.id), true)}>End it now</button>
    {:else if current?.asked_by === 'student'}
      <p><strong>{first} invited you to hear this whole lesson.</strong>
        {#if current.note}<span class="quote">“{current.note}”</span>{/if}
        It stays open for 14 days.</p>
      <div class="row">
        <button class="primary" disabled={busy} onclick={() => run(() => api().answerFullLesson(current.id, true), true)}>Accept</button>
        <button class="ghost" disabled={busy} onclick={() => run(() => api().answerFullLesson(current.id, false))}>Not this time</button>
      </div>
    {:else if current}
      <p>You asked {first} for the whole lesson. It opens when they say yes.</p>
      <button class="link" disabled={busy} onclick={() => run(() => api().endFullLesson(current.id))}>Take back</button>
    {:else}
      <p>You're hearing the <span class="chip accent">Practice this</span> moments and anything {first} sends. The rest of the lesson is {first}'s.</p>
      {#if lastNo}<p class="muted small">{first} said “not this time” on {date(lastNo.decided_at!, { month: 'short', day: 'numeric' })}.</p>{/if}
      {#if asking}
        <label class="small" for="pass-note">Why, so {first} knows (optional)</label>
        <input id="pass-note" maxlength="500" bind:value={note} placeholder="Recital in two weeks. I'd like to hear the run-through." />
        <div class="row">
          <button class="primary" disabled={busy} onclick={() => run(async () => { await api().askFullLesson(lessonId, note); asking = false; note = ''; })}>Ask {first}</button>
          <button class="ghost" disabled={busy} onclick={() => (asking = false)}>Cancel</button>
        </div>
        <p class="muted small">If {first} says yes, you can hear every take for 14 days.</p>
      {:else}
        <button class="link" onclick={() => (asking = true)}>Ask to hear the whole lesson</button>
      {/if}
    {/if}
    {#if error}<p class="error small" role="alert">{error}</p>{/if}
  </section>
{/if}

<style>
  .pass { display: grid; gap: 0.5rem; padding: 0.75rem 1rem; margin: 0.75rem 0; }
  .pass.open { border-color: var(--accent, currentColor); }
  .pass p { margin: 0; }
  .pass > button { justify-self: start; }
  .row { display: flex; gap: 0.5rem; flex-wrap: wrap; }
  .quote { display: block; font-style: italic; margin: 0.25rem 0; }
  input { width: 100%; box-sizing: border-box; }
</style>
