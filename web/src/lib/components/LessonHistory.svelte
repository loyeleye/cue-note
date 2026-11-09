<script lang="ts">
  // The whole lesson's history, like a document's: every card edit with who
  // made it, plus named versions ("After Lark's review"). A named version can
  // be compared with now, or the whole lesson put back to it. A restore saves
  // new versions, so nothing in the history is ever lost.
  import { api } from '$lib/app.svelte';
  import { dateTime } from '$lib/format';
  import { findTeacher } from '$lib/people';
  import type { CardFields, CardVersion, Checkpoint, Feedback, Song, Take, Teacher } from '$lib/types';
  import { CARD_FIELDS } from '$lib/types';
  import CompareView from './CompareView.svelte';
  import Who from './Who.svelte';

  let { lessonId, cards, versions, checkpoints, teachers, takes, songs, me = null, onchanged }: {
    lessonId: string;
    cards: Feedback[];
    versions: CardVersion[];
    checkpoints: Checkpoint[];
    teachers: Teacher[];
    takes: Take[];
    songs: Song[];
    me?: string | null;
    /** Something was named, renamed, removed or restored: reload. */
    onchanged: () => void;
  } = $props();

  let naming = $state(false);
  let newName = $state('');
  let namedOnly = $state(false);
  let who = $state('');
  let comparing = $state<string | null>(null);
  let renaming = $state<string | null>(null);
  let renameTo = $state('');
  let busy = $state(false);
  let error = $state('');
  let message = $state('');

  type Row = { kind: 'edit'; at: string; v: CardVersion } | { kind: 'name'; at: string; cp: Checkpoint };
  const rows = $derived.by<Row[]>(() => {
    const edits: Row[] = namedOnly ? [] : versions
      .filter((v) => v.author === 'teacher' && (!who || v.author_id === who))
      .map((v) => ({ kind: 'edit', at: v.created_at, v }));
    const names: Row[] = checkpoints.filter((c) => !who || c.created_by === who).map((cp) => ({ kind: 'name', at: cp.created_at, cp }));
    return [...edits, ...names].sort((a, b) => b.at.localeCompare(a.at));
  });

  const cueOf = (id: string) => cards.find((c) => c.id === id)?.cue || 'Untitled cue';
  const current = (f: Feedback): Partial<CardFields> => Object.fromEntries(CARD_FIELDS.map((k) => [k, f[k]]));

  /** Each card as it was at a moment: its newest version from then or before. */
  function stateAt(at: string): Map<string, CardVersion> {
    const out = new Map<string, CardVersion>();
    for (const v of versions) {
      if (v.created_at > at) continue;
      const seen = out.get(v.feedback_id);
      if (!seen || seen.created_at < v.created_at) out.set(v.feedback_id, v);
    }
    return out;
  }

  function changedSince(cp: Checkpoint) {
    const then = stateAt(cp.created_at);
    return cards.flatMap((f) => {
      const v = then.get(f.id);
      if (!v) return [];
      const now = current(f);
      const differs = Object.entries(v.payload).some(([k, val]) => (now[k as keyof CardFields] ?? null) !== (val ?? null));
      return differs ? [{ card: f, then: v.payload, now }] : [];
    });
  }

  async function run(work: () => Promise<unknown>, done = '') {
    busy = true;
    error = '';
    message = '';
    try {
      await work();
      message = done;
      onchanged();
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      busy = false;
    }
  }

  function addName(e: SubmitEvent) {
    e.preventDefault();
    const label = newName.trim();
    if (!label) return;
    run(async () => { await api().addCheckpoint(lessonId, label); newName = ''; naming = false; }, `Named this version “${label}”.`);
  }

  function rename(e: SubmitEvent, cp: Checkpoint) {
    e.preventDefault();
    const label = renameTo.trim();
    if (!label) return;
    run(async () => { await api().renameCheckpoint(cp.id, label); renaming = null; });
  }

  function remove(cp: Checkpoint) {
    if (!confirm(`Remove the name “${cp.label}”? No card changes; only the name goes.`)) return;
    run(() => api().deleteCheckpoint(cp.id));
  }

  function restore(cp: Checkpoint) {
    const n = changedSince(cp).length;
    if (!n) { message = 'Every card already matches this version.'; return; }
    if (!confirm(`Put ${n} ${n === 1 ? 'card' : 'cards'} back to how they were at “${cp.label}”? Each is saved as a new version, so you can undo this.`)) return;
    run(async () => {
      const changed = await api().restoreCheckpoint(cp.id);
      comparing = null;
      return changed;
    }, `Restored the lesson to “${cp.label}”.`);
  }
</script>

<section class="lesson-history card">
  <div class="top">
    <h2>Version history</h2>
    {#if !naming}<button class="ghost small" onclick={() => { naming = true; newName = ''; }}>Name current version</button>{/if}
  </div>
  {#if naming}
    <form class="name-form" onsubmit={addName}>
      <label for="cp-name" class="visually-hidden">Name for this version</label>
      <input id="cp-name" maxlength="80" bind:value={newName} placeholder="For example: After my review" />
      <button type="submit" class="primary small" disabled={busy || !newName.trim()}>Save name</button>
      <button type="button" class="ghost small" onclick={() => (naming = false)}>Cancel</button>
    </form>
  {/if}
  <div class="filters">
    <label class="toggle"><input type="checkbox" bind:checked={namedOnly} /> Only named versions</label>
    {#if teachers.length > 1}
      <label class="sel">
        <span class="visually-hidden">Show edits by</span>
        <select bind:value={who}>
          <option value="">Everyone</option>
          {#each teachers as t (t.id)}<option value={t.id}>{t.id === me ? `${t.alias} (you)` : t.alias}</option>{/each}
        </select>
      </label>
    {/if}
  </div>
  {#if message}<p class="ok small" role="status">{message}</p>{/if}
  {#if error}<p class="error small" role="alert">{error}</p>{/if}

  {#if rows.length}
    <ol>
      {#each rows as r (r.kind === 'edit' ? r.v.id : `n-${r.cp.id}`)}
        {#if r.kind === 'edit'}
          {@const t = findTeacher(teachers, r.v.author_id)}
          <li class="edit" style:--edge="var(--t-{t.color})">
            <Who teacher={t} {me} />
            <span class="muted small">{dateTime(r.v.created_at)}</span>
            <span class="what small">{r.v.restored_from ? 'restored' : 'edited'} <a href="#card-{r.v.feedback_id}">{cueOf(r.v.feedback_id)}</a></span>
          </li>
        {:else}
          {@const cp = r.cp}
          <li class="named">
            {#if renaming === cp.id}
              <form class="name-form" onsubmit={(e) => rename(e, cp)}>
                <input maxlength="80" bind:value={renameTo} aria-label="New name" />
                <button type="submit" class="primary small" disabled={busy || !renameTo.trim()}>Rename</button>
                <button type="button" class="ghost small" onclick={() => (renaming = null)}>Cancel</button>
              </form>
            {:else}
              <div class="name-head">
                <span class="star" aria-hidden="true">★</span>
                <strong>{cp.label}</strong>
                <span class="muted small">named by</span>
                <Who teacher={findTeacher(teachers, cp.created_by)} {me} />
                <span class="muted small">{dateTime(cp.created_at)}</span>
              </div>
            {/if}
            <div class="actions">
              <button class="link" onclick={() => (comparing = comparing === cp.id ? null : cp.id)}>
                {comparing === cp.id ? 'Hide comparison' : 'Compare with now'}
              </button>
              <button class="link" disabled={busy} onclick={() => restore(cp)}>Restore this version</button>
              {#if cp.created_by === me}
                <button class="link" onclick={() => { renaming = cp.id; renameTo = cp.label; }}>Rename</button>
                <button class="link danger" onclick={() => remove(cp)}>Remove name</button>
              {/if}
            </div>
            {#if comparing === cp.id}
              {@const diffs = changedSince(cp)}
              {#if diffs.length}
                <ul class="diffs">
                  {#each diffs as d (d.card.id)}
                    <li>
                      <a href="#card-{d.card.id}" class="small">{d.card.cue || 'Untitled cue'}</a>
                      <CompareView before={d.then} after={d.now} beforeLabel={cp.label} afterLabel="now" {takes} {songs} />
                    </li>
                  {/each}
                </ul>
              {:else}
                <p class="muted small">No card has changed since this version.</p>
              {/if}
            {/if}
          </li>
        {/if}
      {/each}
    </ol>
  {:else}
    <p class="muted small">{namedOnly ? 'No named versions yet. Name the current version to bookmark it.' : 'No teacher edits yet.'}</p>
  {/if}
</section>

<style>
  .lesson-history { margin-top: 20px; display: flex; flex-direction: column; gap: 10px; }
  .top { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
  .top h2 { margin: 0; }
  .name-form { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
  .name-form input { flex: 1 1 200px; }
  .filters { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; font-size: 13px; }
  .toggle { display: inline-flex; gap: 6px; align-items: center; }
  ol { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; max-height: 480px; overflow: auto; }
  li.edit { border-left: 3px solid var(--edge, var(--border)); padding-left: 10px; display: flex; gap: 6px; align-items: baseline; flex-wrap: wrap; }
  .what { min-width: 0; }
  li.named { border-left: 3px solid var(--warn-fg); padding: 6px 10px; background: var(--warn-bg); border-radius: 0 8px 8px 0; display: flex; flex-direction: column; gap: 6px; }
  .name-head { display: flex; gap: 6px; align-items: baseline; flex-wrap: wrap; }
  .star { color: var(--warn-fg); }
  .actions { display: flex; gap: 12px; flex-wrap: wrap; }
  .diffs { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 8px; }
  .diffs li { display: flex; flex-direction: column; gap: 4px; }
  .small { font-size: 12px; }
  .ok { color: var(--accent-strong); margin: 0; }
  .error { color: var(--danger); margin: 0; }
</style>
