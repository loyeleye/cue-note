<script lang="ts">
  // Plays a private clip with its waveform. The file is fetched through a
  // short-lived signed link, so nothing in storage is ever public.
  import { onDestroy } from 'svelte';
  import WaveSurfer from 'wavesurfer.js';
  import { api } from '$lib/app.svelte';
  import type { Bucket } from '$lib/api';
  import { clock } from '$lib/format';

  let { path, bucket = 'clips', label = 'Play', compact = false }:
    { path: string; bucket?: Bucket; label?: string; compact?: boolean } = $props();

  let el: HTMLDivElement;
  let ws: WaveSurfer | null = null;
  let playing = $state(false);
  let time = $state(0);
  let duration = $state(0);
  let failed = $state(false);

  $effect(() => {
    const p = path;
    let cancelled = false;
    failed = false;
    api().fileUrl(bucket, p).then((url) => {
      if (cancelled) return;
      if (!url) { failed = true; return; }
      ws?.destroy();
      const css = getComputedStyle(document.documentElement);
      ws = WaveSurfer.create({
        container: el,
        url,
        height: compact ? 28 : 40,
        barWidth: 2,
        barGap: 1,
        barRadius: 2,
        waveColor: css.getPropertyValue('--wave').trim() || '#d6c8ea',
        progressColor: css.getPropertyValue('--accent').trim() || '#5b3a8c',
        cursorWidth: 0
      });
      ws.on('ready', (d) => (duration = d));
      ws.on('timeupdate', (t) => (time = t));
      ws.on('play', () => (playing = true));
      ws.on('pause', () => (playing = false));
      ws.on('finish', () => (playing = false));
      ws.on('error', () => (failed = true));
    });
    return () => { cancelled = true; };
  });

  onDestroy(() => ws?.destroy());
</script>

<div class="player" class:compact>
  <button class="play" onclick={() => ws?.playPause()} disabled={failed} aria-label={playing ? 'Pause' : label}>
    {#if playing}
      <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><rect x="3" y="2" width="3.5" height="12" rx="1"/><rect x="9.5" y="2" width="3.5" height="12" rx="1"/></svg>
    {:else}
      <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><path d="M4 2.5v11l9.5-5.5z"/></svg>
    {/if}
  </button>
  <div class="wave" bind:this={el}></div>
  <span class="time">{failed ? 'Unavailable' : `${clock(time * 1000)} / ${clock(duration * 1000)}`}</span>
</div>

<style>
  .player { display: flex; align-items: center; gap: 10px; min-width: 0; }
  .wave { flex: 1; min-width: 60px; }
  .play {
    flex: none; width: 34px; height: 34px; border-radius: 50%; border: none;
    background: var(--accent); color: var(--on-accent); display: grid; place-items: center; cursor: pointer;
  }
  .compact .play { width: 28px; height: 28px; }
  .play svg { fill: currentColor; }
  .play:disabled { opacity: 0.4; cursor: default; }
  .time { flex: none; font-size: 12px; color: var(--muted); font-variant-numeric: tabular-nums; }
</style>
