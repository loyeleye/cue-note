<script lang="ts">
  // Edit one card. Saving writes the change and a new version together, so
  // the AI's original and every correction stay in the history.
  //
  // Two teachers can have the same card open. The editor remembers what the
  // card looked like when it opened and sends that along: if someone else
  // changed a different field meanwhile, both changes are kept; if they
  // changed the same field, the save stops and asks which to keep.
  import { untrack } from 'svelte';
  import { api } from '$lib/app.svelte';
  import { EditConflict } from '$lib/api';
  import { FIELD_LABEL, showField } from '$lib/cardfields';
  import { clock } from '$lib/format';
  import { findTeacher, teacherLabel } from '$lib/people';
  import type { CardFields, Feedback, PracticeFlag, Song, Take, Teacher } from '$lib/types';
  import { CARD_FIELDS } from '$lib/types';
  import Who from './Who.svelte';

  let { card, takes, songs, teachers = [], me = null, lastEditor = null, onsaved, oncancel }: {
    card: Feedback;
    takes: Take[];
    songs: Song[];
    teachers?: Teacher[];
    me?: string | null;
    /** Who saved the card most recently, to name them if it changes under us. */
    lastEditor?: string | null;
    onsaved: (f: Feedback) => void;
    oncancel: () => void;
  } = $props();

  const CATEGORIES = ['breath', 'resonance', 'vowel', 'registration', 'phrasing', 'pitch', 'diction', 'posture', 'warm-up', 'encouragement'];

  const pick = (f: Feedback | Partial<CardFields>): CardFields => ({
    cue: f.cue ?? '', category: f.category ?? null, her_words: f.her_words ?? '',
    practice_flag: f.practice_flag ?? null, take_id: f.take_id ?? null, song_id: f.song_id ?? null,
    bar_start: f.bar_start ?? null, bar_end: f.bar_end ?? null
  });

  // The card as it was when the editor opened, and a working copy. The card
  // itself only changes once the save succeeds.
  let base = $state(untrack(() => pick(card)));
  let draft = $state(untrack(() => pick(card)));
  let restoredFrom = $state<string | null>(null);
  let saving = $state(false);
  let error = $state('');
  let conflict = $state<EditConflict | null>(null);
  let unresolved = $state<(keyof CardFields)[]>([]);

  /** Load an older version into the editor, to save as a restore. */
  export function load(fields: Partial<CardFields>, versionId: string | null = null) {
    draft = pick({ ...pick(card), ...fields });
    restoredFrom = versionId;
  }

  const changes = $derived.by(() => {
    const out: Partial<CardFields> = {};
    for (const k of CARD_FIELDS) {
      if (draft[k] !== base[k]) (out as Record<string, unknown>)[k] = draft[k];
    }
    return out;
  });

  // Someone saved this card since it opened (it updates live).
  const movedOn = $derived(CARD_FIELDS.some((k) => pick(card)[k] !== base[k]));
  const other = $derived(lastEditor && lastEditor !== me ? findTeacher(teachers, lastEditor) : null);
  const conflictBy = $derived(conflict?.by && conflict.by !== me ? findTeacher(teachers, conflict.by) : null);

  const rec = (o: object) => o as Record<string, unknown>;

  function keep(k: keyof CardFields, which: 'mine' | 'theirs') {
    if (!conflict) return;
    const theirs = rec(conflict.theirs)[k] ?? null;
    // From now on, compare against their value: it is what's saved.
    rec(base)[k] = theirs;
    if (k === 'bar_start' || k === 'bar_end') {
      const other = k === 'bar_start' ? 'bar_end' : 'bar_start';
      if (other in conflict.theirs) rec(base)[other] = rec(conflict.theirs)[other] ?? null;
    }
    if (which === 'theirs') rec(draft)[k] = theirs;
    unresolved = unresolved.filter((x) => x !== k);
    if (!unresolved.length) conflict = null;
  }
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
      const expected = Object.fromEntries(Object.keys(fields).map((k) => [k, base[k as keyof CardFields]]));
      onsaved(await api().saveCard(card.id, fields, { expected, restoredFrom: restoredFrom ?? undefined }));
    } catch (err) {
      if (err instanceof EditConflict) {
        conflict = err;
        unresolved = err.fields.filter((k, i, all) => !(k === 'bar_end' && all.includes('bar_start')));
      } else {
        error = err instanceof Error ? err.message : String(err);
      }
    } finally {
      saving = false;
    }
  }
</script>

<form class="editor" onsubmit={save}>
  {#if conflict}
    <div class="clash" role="alert">
      <p><strong>{conflictBy ? teacherLabel(conflictBy, me) : 'Someone else'} changed this card while you were editing.</strong>
        Choose what to keep for each field below, then save. You can still edit the text after choosing.</p>
      {#each unresolved as k (k)}
        <div class="clash-field">
          <span class="field">{FIELD_LABEL[k]}</span>
          <div class="sides">
            <div class="side">
              <span class="muted small">Yours</span>
              <p>{showField(k, draft, takes, songs)}</p>
              <button type="button" class="ghost small" onclick={() => keep(k, 'mine')}>Keep mine</button>
            </div>
            <div class="side">
              <span class="muted small">{#if conflictBy}<Who teacher={conflictBy} {me} />{:else}Theirs{/if}</span>
              <p>{showField(k, conflict.theirs, takes, songs)}</p>
              <button type="button" class="ghost small" onclick={() => keep(k, 'theirs')}>Keep theirs</button>
            </div>
          </div>
        </div>
      {/each}
    </div>
  {:else if movedOn}
    <p class="note">
      {#if other}<Who teacher={other} {me} />{:else}Someone{/if} saved this card while you had it open.
      Saving keeps their changes to fields you didn't touch.
    </p>
  {/if}
  {#if restoredFrom}<p class="note">Restoring an older version. Check it, then save.</p>{/if}
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
    <button type="submit" class="primary" disabled={!dirty || saving || barsInvalid || !!conflict}>
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
  .note { margin: 0; font-size: 13px; background: var(--chip); border-radius: 8px; padding: 8px 10px; display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
  .clash { background: var(--warn-bg); color: var(--warn-fg); border-radius: 10px; padding: 10px 12px; display: flex; flex-direction: column; gap: 10px; font-size: 13px; }
  .clash p { margin: 0; }
  .clash-field { display: flex; flex-direction: column; gap: 4px; }
  .field { font-weight: 600; font-size: 12px; }
  .sides { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
  .side { background: var(--surface); color: var(--fg); border-radius: 8px; padding: 8px; display: flex; flex-direction: column; gap: 4px; align-items: flex-start; }
  .side p { margin: 0; white-space: pre-wrap; }
  .small { font-size: 12px; }
  @media (max-width: 520px) { .sides { grid-template-columns: 1fr; } }
</style>
