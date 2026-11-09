<script lang="ts">
  // Two versions of one card side by side: what changed between them.
  import { changedFields, TEXT_FIELDS } from '$lib/cardfields';
  import type { CardFields, Song, Take } from '$lib/types';
  import DiffText from './DiffText.svelte';

  let { before, after, beforeLabel, afterLabel, takes, songs }: {
    before: Partial<CardFields>;
    after: Partial<CardFields>;
    beforeLabel: string;
    afterLabel: string;
    takes: Take[];
    songs: Song[];
  } = $props();

  const changes = $derived(changedFields(before, after, takes, songs));
</script>

<div class="compare">
  <p class="muted small">From <strong>{beforeLabel}</strong> to <strong>{afterLabel}</strong></p>
  {#if changes.length}
    <dl>
      {#each changes as c (c.label)}
        <dt>{c.label}</dt>
        <dd>
          {#if TEXT_FIELDS.includes(c.key)}
            <DiffText before={String(before[c.key] ?? '')} after={String(after[c.key] ?? '')} />
          {:else}
            <del>{c.before}</del> → <ins>{c.after}</ins>
          {/if}
        </dd>
      {/each}
    </dl>
  {:else}
    <p class="muted small">These two versions are the same.</p>
  {/if}
</div>

<style>
  .compare { border: 1px solid var(--border); border-radius: 8px; padding: 10px; background: var(--bg); }
  .small { font-size: 12px; margin: 0 0 6px; }
  dl { margin: 0; display: grid; grid-template-columns: auto 1fr; gap: 4px 10px; font-size: 13px; }
  dt { font-weight: 600; color: var(--muted); }
  dd { margin: 0; min-width: 0; }
  del { color: var(--danger); }
  ins { text-decoration: none; background: var(--accent-soft); border-radius: 3px; padding: 0 2px; }
</style>
