<script lang="ts">
  // Suggested practice, the teacher's side: the student's phone puts your
  // "practice this" notes in an order each day and never adds any. Here you
  // steer it in a few seconds: the main point it always starts with, how
  // much, and rest. A point you came back to again and again but didn't mark
  // is offered to you, never to the student.
  import { api } from '$lib/app.svelte';
  import type { Feedback, PlanSetting } from '$lib/types';

  let { studentId, studentName, cards = [], onCardsChanged = () => {} }: {
    studentId: string;
    studentName: string;
    cards?: Feedback[];
    onCardsChanged?: () => void;
  } = $props();

  const DEFAULT_GENTLE = 'Hums on "mm", in the middle of your range. Easy and quiet; nothing high yet.';
  const DEFAULT_COOL = 'Gentle slides down on a hum or a lip trill, soft and slow. Then drink some water.';

  let setting = $state<PlanSetting | null>(null);
  let loaded = $state(false);
  let error = $state('');
  let saving = $state(false);
  let dismissed = $state<string[]>(readDismissed());

  $effect(() => {
    void studentId;
    loaded = false;
    api().getPlanSetting(studentId).then((s) => { setting = s; loaded = true; })
      .catch((e) => { error = e instanceof Error ? e.message : String(e); loaded = true; });
  });

  const first = $derived(studentName.split(' ')[0] || 'Your student');
  const homework = $derived(cards.filter((c) => c.practice_flag === 'practice_this'));
  const today = new Date().toISOString().slice(0, 10);
  const resting = $derived(!!setting?.rest_until && setting.rest_until >= today);

  async function save(changes: Partial<Omit<PlanSetting, 'student_id' | 'teacher_id'>>) {
    saving = true; error = '';
    try { setting = await api().savePlanSetting(studentId, changes); }
    catch (e) { error = e instanceof Error ? e.message : String(e); }
    finally { saving = false; }
  }

  const label = (c: Feedback) => (c.cue || c.her_words || 'A note').slice(0, 80);

  // Points she made several times in one lesson but didn't mark "practice this".
  const suggestion = $derived.by(() => {
    const groups = new Map<string, Feedback[]>();
    for (const c of cards) {
      if (!c.cue || c.category === 'chat' || c.category === 'encouragement') continue;
      const k = c.lesson_id + '|' + c.cue.trim().toLowerCase();
      groups.set(k, [...(groups.get(k) ?? []), c]);
    }
    return [...groups.values()]
      .filter((g) => g.length >= 2 && !g.some((c) => c.practice_flag === 'practice_this') && !dismissed.includes(g[0].id))
      .sort((a, b) => b.length - a.length || (b[0].weight ?? 0) - (a[0].weight ?? 0))[0] ?? null;
  });

  async function addToPractice(c: Feedback) {
    error = '';
    try { await api().saveCard(c.id, { practice_flag: 'practice_this' }); onCardsChanged(); }
    catch (e) { error = e instanceof Error ? e.message : String(e); }
  }

  function readDismissed(): string[] {
    try { return JSON.parse(localStorage.getItem('plan-not-yet') ?? '[]'); } catch { return []; }
  }
  function notYet(c: Feedback) {
    dismissed = [...dismissed, c.id];
    try { localStorage.setItem('plan-not-yet', JSON.stringify(dismissed)); } catch { /* private window */ }
  }

  function setLength(v: string) {
    const n = Number(v) as 10 | 20 | 30;
    // "Up to" is both the usual length and the longest sitting the student can pick.
    save({ minutes: n, max_minutes: n });
  }
</script>

<section class="card plan" aria-labelledby="plan-h">
  <h3 id="plan-h">Practice plan</h3>
  <p class="muted small">{first}'s phone puts your "practice this" notes in an order each day. It never adds any.</p>
  {#if error}<p class="error" role="alert">{error}</p>{/if}
  {#if !loaded}
    <p class="muted small">Loading…</p>
  {:else}
    <div class="row">
      <label for="plan-pin">Main point</label>
      <select id="plan-pin" disabled={saving} value={setting?.pinned_feedback_id ?? ''}
        onchange={(e) => save({ pinned_feedback_id: e.currentTarget.value || null })}>
        <option value="">Let the phone choose</option>
        {#each homework as c (c.id)}<option value={c.id}>“{label(c)}”</option>{/each}
      </select>
    </div>
    {#if setting?.pinned_feedback_id}<p class="hint">Every day, first after the warmup.</p>{/if}

    <div class="row">
      <label for="plan-len">How much</label>
      <span class="pair">
        <select id="plan-len" disabled={saving} value={String(setting?.max_minutes ?? 20)} onchange={(e) => setLength(e.currentTarget.value)}>
          <option value="10">Up to 10 min</option>
          <option value="20">Up to 20 min</option>
          <option value="30">Up to 30 min</option>
        </select>
        <label class="visually-hidden" for="plan-days">Days a week</label>
        <select id="plan-days" disabled={saving} value={String(setting?.days_per_week ?? 4)}
          onchange={(e) => save({ days_per_week: Number(e.currentTarget.value) })}>
          {#each [1, 2, 3, 4, 5, 6, 7] as d}<option value={String(d)}>{d} {d === 1 ? 'day' : 'days'} a week</option>{/each}
        </select>
      </span>
    </div>

    <div class="row">
      <label for="plan-rest">Rest until</label>
      <span class="pair">
        <input id="plan-rest" type="date" min={today} disabled={saving} value={setting?.rest_until ?? ''}
          onchange={(e) => save({ rest_until: e.currentTarget.value || null })} />
        {#if setting?.rest_until}<button class="ghost small" disabled={saving} onclick={() => save({ rest_until: null })}>Clear</button>{/if}
      </span>
    </div>
    {#if resting}<p class="hint">Listening only until then: {first} hears your words and demos, and doesn't sing.</p>{/if}

    <details>
      <summary>Gentle start and cool-down</summary>
      <label for="plan-gentle">Gentle start</label>
      <textarea id="plan-gentle" rows="2" maxlength="300" placeholder={DEFAULT_GENTLE} value={setting?.gentle_start ?? ''}
        onchange={(e) => save({ gentle_start: e.currentTarget.value.trim() || null })}></textarea>
      <label for="plan-cool">Cool-down</label>
      <textarea id="plan-cool" rows="2" maxlength="300" placeholder={DEFAULT_COOL} value={setting?.cool_down ?? ''}
        onchange={(e) => save({ cool_down: e.currentTarget.value.trim() || null })}></textarea>
      <p class="muted small">Both come every day and can't be skipped. Leave blank for the wording shown.</p>
    </details>
  {/if}
</section>

{#if suggestion}
  <section class="card offer" aria-labelledby="offer-h">
    <h3 id="offer-h">You came back to this {suggestion.length} times but didn't mark it</h3>
    <p class="quote">“{suggestion[0].her_words || suggestion[0].cue}”</p>
    <p class="muted small">{first} doesn't see this.</p>
    <div class="buttons">
      <button class="primary small" onclick={() => addToPractice(suggestion![0])}>Add to practice</button>
      <button class="ghost small" onclick={() => notYet(suggestion![0])}>Not yet</button>
    </div>
  </section>
{/if}

<style>
  .plan, .offer { display: flex; flex-direction: column; gap: 8px; margin-bottom: 16px; }
  .offer { border: 1px solid var(--brass-ink); }
  h3 { font-size: 17px; margin: 0; font-family: var(--font-display); }
  .row { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap;
    padding: 8px 0; border-top: 1px solid var(--border); }
  .row label { font-weight: 600; font-size: 14px; }
  .pair { display: flex; gap: 8px; flex-wrap: wrap; }
  select, input[type='date'] { min-height: 36px; max-width: 100%; }
  .hint { margin: -4px 0 0; font-size: 13px; color: var(--brass-ink); }
  details { border-top: 1px solid var(--border); padding-top: 8px; }
  details label { display: block; font-size: 13px; font-weight: 600; margin: 8px 0 4px; }
  summary { cursor: pointer; font-size: 14px; font-weight: 600; }
  textarea { width: 100%; }
  .quote { margin: 0; font-family: var(--font-display); font-style: italic; }
  .buttons { display: flex; gap: 8px; }
  p { margin: 0; }
  .muted { color: var(--muted); }
  .small { font-size: 13px; }
  .error { color: var(--danger); font-size: 13px; }
</style>
