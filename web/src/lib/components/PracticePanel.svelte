<script lang="ts">
  // This week's practice list for a student, grouped by week. Cards she
  // flagged "practice this" that aren't on the list yet are offered as
  // one-tap suggestions.
  import { api } from '$lib/app.svelte';
  import { weekLabel, weekOf } from '$lib/format';
  import type { Feedback, PracticeItem } from '$lib/types';

  let { studentId, cards = [], lessonId = null, keep = () => true }: {
    studentId: string;
    cards?: Feedback[];
    lessonId?: string | null;
    /** Hides items, for a teacher who shows only their own lessons. */
    keep?: (p: PracticeItem) => boolean;
  } = $props();

  let items = $state<PracticeItem[]>([]);
  let body = $state('');
  let week = $state(weekOf());
  let feedbackId = $state<string | null>(null);
  let editing = $state<string | null>(null);
  let editBody = $state('');
  let error = $state('');

  const thisWeek = weekOf();
  const nextWeek = weekOf(new Date(Date.now() + 7 * 864e5));

  async function reload() { items = await api().listPractice(studentId); }
  $effect(() => { void studentId; reload(); });

  const groups = $derived.by(() => {
    const by = new Map<string, PracticeItem[]>();
    for (const it of items.filter((i) => keep(i))) {
      const k = it.due_week ?? '';
      by.set(k, [...(by.get(k) ?? []), it]);
    }
    return [...by.entries()].sort(([a], [b]) => (b || '0').localeCompare(a || '0'));
  });

  const suggestions = $derived(
    cards.filter((c) => c.practice_flag === 'practice_this' && !items.some((i) => i.feedback_id === c.id)).slice(0, 4)
  );

  const cueOf = (id: string | null) => cards.find((c) => c.id === id)?.cue;

  async function add(e?: SubmitEvent, from?: Feedback) {
    e?.preventDefault();
    const text = from ? from.cue : body.trim();
    if (!text) return;
    error = '';
    try {
      await api().addPractice({
        student_id: studentId,
        lesson_id: from?.lesson_id ?? lessonId,
        feedback_id: from?.id ?? feedbackId,
        body: text,
        due_week: from ? thisWeek : week || null
      });
      if (!from) { body = ''; feedbackId = null; }
      await reload();
    } catch (err) {
      error = err instanceof Error ? err.message : String(err);
    }
  }

  async function saveEdit(it: PracticeItem) {
    if (!editBody.trim()) return;
    await api().updatePractice(it.id, { body: editBody.trim() });
    editing = null;
    await reload();
  }

  async function move(it: PracticeItem, to: string | null) {
    await api().updatePractice(it.id, { due_week: to });
    await reload();
  }

  async function remove(it: PracticeItem) {
    await api().deletePractice(it.id);
    await reload();
  }
</script>

<form class="card add" onsubmit={add}>
  <label for="practice-body-{studentId}" class="visually-hidden">Practice item</label>
  <textarea id="practice-body-{studentId}" rows="2" bind:value={body} placeholder="What to practice, and how"></textarea>
  <div class="row">
    <label>
      <span>Week</span>
      <select bind:value={week}>
        <option value={thisWeek}>This week</option>
        <option value={nextWeek}>Next week</option>
        <option value="">No week</option>
      </select>
    </label>
    {#if cards.length}
      <label class="grow">
        <span>Linked card</span>
        <select bind:value={feedbackId}>
          <option value={null}>None</option>
          {#each cards as c (c.id)}<option value={c.id}>{c.cue}</option>{/each}
        </select>
      </label>
    {/if}
    <button type="submit" class="primary" disabled={!body.trim()}>Add</button>
  </div>
  {#if error}<p class="error" role="alert">{error}</p>{/if}
</form>

{#if suggestions.length}
  <div class="suggest">
    <p class="muted small">Flagged "practice this" and not on the list yet:</p>
    <ul>
      {#each suggestions as s (s.id)}
        <li><span>{s.cue}</span> <button class="ghost small" onclick={() => add(undefined, s)}>Add to this week</button></li>
      {/each}
    </ul>
  </div>
{/if}

{#each groups as [wk, list] (wk)}
  <h4>{weekLabel(wk || null)}</h4>
  <ul class="items">
    {#each list as it (it.id)}
      <li class="card">
        {#if editing === it.id}
          <textarea rows="2" bind:value={editBody}></textarea>
          <div class="row end">
            <button class="ghost small" onclick={() => (editing = null)}>Cancel</button>
            <button class="primary small" onclick={() => saveEdit(it)}>Save</button>
          </div>
        {:else}
          <p>{it.body}</p>
          {#if cueOf(it.feedback_id)}<p class="muted small">From the card: “{cueOf(it.feedback_id)}”</p>{/if}
          <div class="row tools">
            <button class="link" onclick={() => { editing = it.id; editBody = it.body; }}>Edit</button>
            {#if it.due_week !== thisWeek}<button class="link" onclick={() => move(it, thisWeek)}>Move to this week</button>{/if}
            {#if it.due_week !== nextWeek}<button class="link" onclick={() => move(it, nextWeek)}>Move to next week</button>{/if}
            <button class="link danger" onclick={() => remove(it)}>Remove</button>
          </div>
        {/if}
      </li>
    {/each}
  </ul>
{:else}
  <p class="muted">Nothing on the practice list yet.</p>
{/each}

<style>
  .add { display: flex; flex-direction: column; gap: 10px; }
  .row { display: flex; gap: 10px; align-items: flex-end; flex-wrap: wrap; }
  .row.end { justify-content: flex-end; }
  .row label { display: flex; flex-direction: column; gap: 4px; }
  .row label span { font-size: 12px; font-weight: 600; color: var(--muted); }
  .grow { flex: 1 1 200px; min-width: 0; }
  .grow select { width: 100%; }
  .suggest ul { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
  .suggest li { display: flex; justify-content: space-between; align-items: center; gap: 8px; font-size: 14px; }
  .suggest { margin-top: 12px; }
  h4 { margin: 18px 0 8px; font-size: 14px; }
  .items { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
  .items p { margin: 0 0 4px; }
  .tools { gap: 14px; }
  .muted { color: var(--muted); }
  .small { font-size: 12px; }
  .error { color: var(--danger); font-size: 13px; margin: 0; }
</style>
