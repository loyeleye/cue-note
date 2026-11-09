<script lang="ts">
  // Every saved version of a card, newest first, like a document's version
  // history: who saved it (in their color), when, and what changed. Saves by
  // the same teacher a few minutes apart fold into one entry. Named versions
  // of the lesson show as markers. Any two versions can be compared, and any
  // older one restored; the AI's original is always at the bottom.
  import { api } from '$lib/app.svelte';
  import { changedFields } from '$lib/cardfields';
  import { dateTime } from '$lib/format';
  import { findTeacher } from '$lib/people';
  import type { CardFields, CardVersion, Checkpoint, Song, Take, Teacher } from '$lib/types';
  import CompareView from './CompareView.svelte';
  import Who from './Who.svelte';

  let { feedbackId, takes, songs, teachers, checkpoints = [], me = null, refresh = 0, onrestore }: {
    feedbackId: string;
    takes: Take[];
    songs: Song[];
    teachers: Teacher[];
    checkpoints?: Checkpoint[];
    me?: string | null;
    refresh?: number;
    onrestore: (payload: Partial<CardFields>, versionId: string) => void;
  } = $props();

  /** Saves by one teacher closer together than this read as one session. */
  const SESSION_MS = 10 * 60 * 1000;

  let versions = $state<CardVersion[]>([]);
  let loading = $state(true);
  let open = $state<Record<string, boolean>>({});
  let comparing = $state(false);
  let picked = $state<string[]>([]);

  $effect(() => {
    void refresh;
    loading = true;
    api().listVersions(feedbackId).then((v) => { versions = v; loading = false; });
  });

  type Group = { head: CardVersion; index: number; rest: CardVersion[] };
  type Row = { kind: 'group'; group: Group } | { kind: 'name'; cp: Checkpoint };

  const groups = $derived.by<Group[]>(() => {
    const out: Group[] = [];
    versions.forEach((v, index) => {
      const last = out.at(-1);
      const prev = last ? (last.rest.at(-1) ?? last.head) : null;
      if (last && prev && v.author === 'teacher' && prev.author === 'teacher' && v.author_id === prev.author_id
          && !v.restored_from && !prev.restored_from
          && new Date(prev.created_at).getTime() - new Date(v.created_at).getTime() < SESSION_MS) {
        last.rest.push(v);
      } else {
        out.push({ head: v, index, rest: [] });
      }
    });
    return out;
  });

  // Named versions sit between the edits they followed.
  const rows = $derived.by<Row[]>(() => {
    const out: Row[] = [];
    const names = [...checkpoints].sort((a, b) => b.created_at.localeCompare(a.created_at));
    let n = 0;
    for (const g of groups) {
      while (n < names.length && names[n].created_at >= g.head.created_at) out.push({ kind: 'name', cp: names[n++] });
      out.push({ kind: 'group', group: g });
    }
    return out;
  });

  /** What changed in a group: its newest save against the version before it. */
  function diffOf(g: Group) {
    const before = versions[g.index + 1 + g.rest.length];
    return before ? changedFields(before.payload, g.head.payload, takes, songs) : [];
  }

  const byId = (id: string | null | undefined) => versions.find((v) => v.id === id);
  const label = (v: CardVersion) =>
    `${v.author === 'ai' ? 'AI original' : findTeacher(teachers, v.author_id).alias}, ${dateTime(v.created_at)}`;

  function togglePick(id: string) {
    picked = picked.includes(id) ? picked.filter((x) => x !== id) : [...picked.slice(-1), id];
  }
  const pair = $derived.by(() => {
    if (picked.length !== 2) return null;
    const [a, b] = picked.map(byId);
    if (!a || !b) return null;
    return a.created_at <= b.created_at ? { before: a, after: b } : { before: b, after: a };
  });
</script>

{#snippet entry(v: CardVersion, current: boolean, changes: ReturnType<typeof diffOf>, first: boolean)}
  <div class="head">
    {#if comparing}
      <input type="checkbox" aria-label="Compare this version" checked={picked.includes(v.id)} onchange={() => togglePick(v.id)} />
    {/if}
    <Who ai={v.author === 'ai'} teacher={v.author === 'ai' ? null : findTeacher(teachers, v.author_id)} {me} />
    <span class="muted">{dateTime(v.created_at)}</span>
    {#if current}<span class="muted">· current</span>{:else if !comparing}
      <button class="link" onclick={() => onrestore(v.payload, v.id)}>Restore</button>
    {/if}
  </div>
  {#if v.restored_from}
    {@const src = byId(v.restored_from)}
    <p class="muted small">Restored {src ? `the version from ${label(src)}` : 'an older version'}</p>
  {/if}
  {#if v.author === 'ai' || first}
    <p class="quote">“{v.payload.cue}”</p>
  {:else if changes.length}
    <ul class="changes">
      {#each changes as c (c.label)}
        <li><strong>{c.label}:</strong> <del>{c.before}</del> → <ins>{c.after}</ins></li>
      {/each}
    </ul>
  {:else}
    <p class="muted small">Saved with no field changes.</p>
  {/if}
{/snippet}

<section class="history">
  <div class="title">
    <h4>Version history</h4>
    {#if versions.length > 1}
      <button class="link" onclick={() => { comparing = !comparing; picked = []; }}>
        {comparing ? 'Done comparing' : 'Compare versions'}
      </button>
    {/if}
  </div>
  {#if comparing}
    <p class="muted small">Tick two versions to see what changed between them.</p>
    {#if pair}
      <CompareView before={pair.before.payload} after={pair.after.payload}
                   beforeLabel={label(pair.before)} afterLabel={label(pair.after)} {takes} {songs} />
    {/if}
  {/if}
  {#if loading && !versions.length}
    <p class="muted">Loading…</p>
  {:else}
    <ol>
      {#each rows as r (r.kind === 'name' ? `n-${r.cp.id}` : r.group.head.id)}
        {#if r.kind === 'name'}
          <li class="named">
            <span class="star" aria-hidden="true">★</span>
            <strong>{r.cp.label}</strong>
            <span class="muted small">named by {findTeacher(teachers, r.cp.created_by).alias}, {dateTime(r.cp.created_at)}</span>
          </li>
        {:else}
          {@const g = r.group}
          {@const t = g.head.author === 'ai' ? null : findTeacher(teachers, g.head.author_id)}
          <li class:current={g.index === 0} style:--edge={t ? `var(--t-${t.color})` : 'var(--border)'}>
            {@render entry(g.head, g.index === 0, diffOf(g), !versions[g.index + 1])}
            {#if g.rest.length}
              <button class="link more" onclick={() => (open[g.head.id] = !open[g.head.id])}>
                {open[g.head.id] ? 'Hide' : 'Show'} {g.rest.length} earlier {g.rest.length === 1 ? 'save' : 'saves'} in this session
              </button>
              {#if open[g.head.id]}
                <ol class="inner">
                  {#each g.rest as v, j (v.id)}
                    {@const i = g.index + 1 + j}
                    <li>{@render entry(v, false, versions[i + 1] ? changedFields(versions[i + 1].payload, v.payload, takes, songs) : [], !versions[i + 1])}</li>
                  {/each}
                </ol>
              {/if}
            {/if}
          </li>
        {/if}
      {/each}
    </ol>
  {/if}
</section>

<style>
  .title { display: flex; justify-content: space-between; align-items: baseline; gap: 8px; }
  .history h4 { margin: 0 0 8px; font-size: 13px; }
  ol { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
  ol > li { border-left: 3px solid var(--edge, var(--border)); padding-left: 10px; }
  ol > li.current { background: linear-gradient(90deg, var(--accent-soft), transparent 70%); border-radius: 0 6px 6px 0; padding-top: 4px; padding-bottom: 4px; }
  ol > li.named { border-left-color: var(--warn-fg); display: flex; gap: 6px; align-items: baseline; flex-wrap: wrap; font-size: 13px; }
  .star { color: var(--warn-fg); }
  .inner { margin-top: 8px; gap: 8px; }
  .inner > li { border-left: 1px dashed var(--border); }
  .head { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; font-size: 12px; }
  .quote { margin: 4px 0 0; font-size: 13px; }
  .changes { margin: 4px 0 0; padding: 0; list-style: none; font-size: 13px; display: flex; flex-direction: column; gap: 2px; }
  del { color: var(--muted); }
  ins { text-decoration: none; background: var(--accent-soft); border-radius: 3px; padding: 0 2px; }
  .muted { color: var(--muted); }
  .small { font-size: 12px; margin: 4px 0 0; }
  .more { margin-top: 4px; font-size: 12px; }
  .link { background: none; border: none; padding: 0; color: var(--accent); cursor: pointer; font-size: 12px; text-decoration: underline; }
</style>
