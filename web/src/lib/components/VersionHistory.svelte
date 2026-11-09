<script lang="ts">
  // Every saved version of a card, newest first, showing what changed each
  // time. The AI's original is always at the bottom and can be restored.
  import { api } from '$lib/app.svelte';
  import { bars, clock, dateTime, FLAG_LABEL } from '$lib/format';
  import type { CardFields, CardVersion, Song, Take } from '$lib/types';
  import { CARD_FIELDS } from '$lib/types';

  let { feedbackId, takes, songs, refresh = 0, onrestore }: {
    feedbackId: string;
    takes: Take[];
    songs: Song[];
    refresh?: number;
    onrestore: (payload: Partial<CardFields>) => void;
  } = $props();

  let versions = $state<CardVersion[]>([]);
  let loading = $state(true);

  $effect(() => {
    void refresh;
    loading = true;
    api().listVersions(feedbackId).then((v) => { versions = v; loading = false; });
  });

  const LABEL: Record<keyof CardFields, string> = {
    cue: 'Cue', her_words: 'Her words', category: 'Category', practice_flag: 'Practice',
    take_id: 'Take', song_id: 'Song', bar_start: 'Bars', bar_end: 'Bars'
  };

  function show(k: keyof CardFields, p: Partial<CardFields>): string {
    switch (k) {
      case 'practice_flag': return p.practice_flag ? FLAG_LABEL[p.practice_flag] : 'Neither';
      case 'take_id': {
        const i = takes.findIndex((t) => t.id === p.take_id);
        return i < 0 ? 'No take' : `Take ${i + 1} at ${clock(takes[i].start_ms)}`;
      }
      case 'song_id': return songs.find((s) => s.id === p.song_id)?.title ?? 'None';
      case 'bar_start': case 'bar_end': return bars(p.bar_start ?? null, p.bar_end ?? null) || 'None';
      default: return String(p[k] ?? '') || '(empty)';
    }
  }

  /** The fields that differ from the version before it. */
  function diff(i: number): { label: string; before: string; after: string }[] {
    const cur = versions[i].payload;
    const prev = versions[i + 1]?.payload;
    if (!prev) return [];
    const out: { label: string; before: string; after: string }[] = [];
    const seen = new Set<string>();
    for (const k of CARD_FIELDS) {
      if ((cur[k] ?? null) === (prev[k] ?? null)) continue;
      const label = LABEL[k];
      if (seen.has(label)) continue;
      seen.add(label);
      out.push({ label, before: show(k, prev), after: show(k, cur) });
    }
    return out;
  }
</script>

<section class="history">
  <h4>Version history</h4>
  {#if loading && !versions.length}
    <p class="muted">Loading…</p>
  {:else}
    <ol>
      {#each versions as v, i (v.id)}
        {@const changes = diff(i)}
        <li class:current={i === 0}>
          <div class="head">
            <span class="badge" class:ai={v.author === 'ai'}>{v.author === 'ai' ? 'AI original' : 'Teacher'}</span>
            <span class="muted">{dateTime(v.created_at)}</span>
            {#if i === 0}<span class="muted">· current</span>{:else}
              <button class="link" onclick={() => onrestore(v.payload)}>Restore</button>
            {/if}
          </div>
          {#if v.author === 'ai' || !versions[i + 1]}
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
        </li>
      {/each}
    </ol>
  {/if}
</section>

<style>
  .history h4 { margin: 0 0 8px; font-size: 13px; }
  ol { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
  ol > li { border-left: 2px solid var(--border); padding-left: 10px; }
  ol > li.current { border-left-color: var(--accent); }
  .head { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; font-size: 12px; }
  .badge { font-size: 11px; font-weight: 600; padding: 2px 6px; border-radius: 4px; background: var(--accent-soft); color: var(--accent-strong); }
  .badge.ai { background: var(--chip); color: var(--muted); }
  .quote { margin: 4px 0 0; font-size: 13px; }
  .changes { margin: 4px 0 0; padding: 0; list-style: none; font-size: 13px; display: flex; flex-direction: column; gap: 2px; }
  del { color: var(--muted); }
  ins { text-decoration: none; background: var(--accent-soft); border-radius: 3px; padding: 0 2px; }
  .muted { color: var(--muted); }
  .small { font-size: 12px; margin: 4px 0 0; }
  .link { background: none; border: none; padding: 0; color: var(--accent); cursor: pointer; font-size: 12px; text-decoration: underline; }
</style>
