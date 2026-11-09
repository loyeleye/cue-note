<script lang="ts">
  // Edit one card. Saving writes the change and a new version together, so
  // the AI's original and every correction stay in the history.
  import { untrack } from 'svelte';
  import { api } from '$lib/app.svelte';
  import { clock } from '$lib/format';
  import type { CardFields, Feedback, PracticeFlag, Song, Take } from '$lib/types';
  import { CARD_FIELDS } from '$lib/types';

  let { card, takes, songs, onsaved, oncancel }: {
    card: Feedback;
    takes: Take[];
    songs: Song[];
    onsaved: (f: Feedback) => void;
    oncancel: () => void;
  } = $props();

  const CATEGORIES = ['breath', 'resonance', 'vowel', 'registration', 'phrasing', 'pitch', 'diction', 'posture', 'warm-up', 'encouragement'];

  const pick = (f: Feedback | Partial<CardFields>): CardFields => ({
    cue: f.cue ?? '', category: f.category ?? null, her_words: f.her_words ?? '',
    practice_flag: f.practice_flag ?? null, take_id: f.take_id ?? null, song_id: f.song_id ?? null,
    bar_start: f.bar_start ?? null, bar_end: f.bar_end ?? null
  });

  // A working copy; the card itself only changes once the save succeeds.
  let draft = $state(untrack(() => pick(card)));
  let saving = $state(false);
  let error = $state('');

  export function load(fields: Partial<CardFields>) {
    draft = pick({ ...pick(card), ...fields });
  }

  const changes = $derived.by(() => {
    const orig = pick(card);
    const out: Partial<CardFields> = {};
    for (const k of CARD_FIELDS) {
      if (draft[k] !== orig[k]) (out as Record<string, unknown>)[k] = draft[k];
    }
    return out;
  });
  const dirty = $derived(Object.keys(changes).length > 0);
  const barsInvalid = $derived(draft.bar_start != null && draft.bar_end != null && draft.bar_end < draft.bar_start);

  function setFlag(f: PracticeFlag | null) { draft.practice_flag = f; }
  function num(v: string): number | null {
    const n = parseInt(v, 10);
    return Number.isFinite(n) && n > 0 ? n : null;
  }

  async function save(e: SubmitEvent) {
    e.preventDefault();
    if (!dirty || barsInvalid) return;
    saving = true;
    error = '';
    try {
      const fields = { ...changes };
      if ('bar_start' in fields && draft.bar_start != null && draft.bar_end == null) fields.bar_end = draft.bar_start;
      onsaved(await api().saveCard(card.id, fields));
    } catch (err) {
      error = err instanceof Error ? err.message : String(err);
    } finally {
      saving = false;
    }
  }
</script>

<form class="editor" onsubmit={save}>
  <label>
    <span>Cue</span>
    <textarea rows="2" bind:value={draft.cue} placeholder="The cue in a few words"></textarea>
  </label>
  <label>
    <span>Her exact words</span>
    <textarea rows="3" bind:value={draft.her_words}></textarea>
  </label>

  <div class="row">
    <label class="grow">
      <span>Category</span>
      <input list="cue-categories" value={draft.category ?? ''}
             oninput={(e) => (draft.category = e.currentTarget.value.trim() || null)} />
      <datalist id="cue-categories">
        {#each CATEGORIES as c (c)}<option value={c}></option>{/each}
      </datalist>
    </label>
    <fieldset>
      <legend>For practice</legend>
      <div class="seg">
        <button type="button" class:on={draft.practice_flag === 'practice_this'} onclick={() => setFlag('practice_this')}>Practice this</button>
        <button type="button" class:on={draft.practice_flag === 'just_listen'} onclick={() => setFlag('just_listen')}>Just listen</button>
        <button type="button" class:on={draft.practice_flag === null} onclick={() => setFlag(null)}>Neither</button>
      </div>
    </fieldset>
  </div>

  <label>
    <span>Linked take</span>
    <select bind:value={draft.take_id}>
      <option value={null}>No take</option>
      {#each takes as t, i (t.id)}
        <option value={t.id}>Take {i + 1} at {clock(t.start_ms)} ({Math.round((t.end_ms - t.start_ms) / 1000)} s)</option>
      {/each}
    </select>
  </label>

  <div class="row">
    <label class="grow">
      <span>Song</span>
      <select bind:value={draft.song_id}>
        <option value={null}>None</option>
        {#each songs as s (s.id)}<option value={s.id}>{s.title}</option>{/each}
      </select>
    </label>
    <label class="bar">
      <span>From bar</span>
      <input type="number" min="1" inputmode="numeric" disabled={!draft.song_id}
             value={draft.bar_start ?? ''} oninput={(e) => (draft.bar_start = num(e.currentTarget.value))} />
    </label>
    <label class="bar">
      <span>To bar</span>
      <input type="number" min="1" inputmode="numeric" disabled={!draft.song_id}
             value={draft.bar_end ?? ''} oninput={(e) => (draft.bar_end = num(e.currentTarget.value))} />
    </label>
  </div>
  {#if barsInvalid}<p class="error">The last bar comes before the first.</p>{/if}
  {#if error}<p class="error" role="alert">Couldn't save: {error}</p>{/if}

  <div class="actions">
    <button type="button" class="ghost" onclick={oncancel}>Cancel</button>
    <button type="submit" class="primary" disabled={!dirty || saving || barsInvalid}>
      {saving ? 'Saving…' : dirty ? 'Save as new version' : 'No changes'}
    </button>
  </div>
</form>

<style>
  .editor { display: flex; flex-direction: column; gap: 12px; }
  label, fieldset { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
  label > span, legend { font-size: 12px; font-weight: 600; color: var(--muted); }
  fieldset { border: none; padding: 0; margin: 0; }
  legend { padding: 0; margin-bottom: 4px; }
  .row { display: flex; gap: 12px; flex-wrap: wrap; align-items: flex-end; }
  .grow { flex: 1 1 180px; }
  .bar { width: 84px; }
  .seg { display: flex; border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
  .seg button { border: none; border-radius: 0; background: transparent; color: var(--fg); padding: 8px 10px; font-size: 13px; }
  .seg button + button { border-left: 1px solid var(--border); }
  .seg button.on { background: var(--accent-soft); color: var(--accent-strong); font-weight: 600; }
  .actions { display: flex; justify-content: flex-end; gap: 8px; }
  .error { color: var(--danger); margin: 0; font-size: 13px; }
</style>
