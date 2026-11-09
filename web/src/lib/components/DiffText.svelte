<script lang="ts">
  // Two versions of a sentence with removed words struck out and added ones
  // highlighted, like "compare versions" in a document editor.
  import { wordDiff } from '$lib/diff';

  let { before, after }: { before: string; after: string } = $props();
  const parts = $derived(wordDiff(before, after));
</script>

<span class="diff">{#each parts as p, i (i)}{#if p.kind === 'same'}{p.text}{:else if p.kind === 'del'}<del>{p.text}</del>{:else}<ins>{p.text}</ins>{/if}{/each}</span>

<style>
  .diff { white-space: pre-wrap; }
  del { color: var(--danger); text-decoration: line-through; margin-right: 3px; }
  ins { text-decoration: none; background: var(--accent-soft); color: var(--accent-strong); border-radius: 3px; padding: 0 1px; }
</style>
