<script lang="ts">
  // The take's pitch line over time. Frames the phone wasn't sure about (often
  // where the piano covered the voice) are drawn grey and dashed as "unclear",
  // never as wrong notes.
  import type { PitchContour } from '$lib/types';

  let { contour, confidence, threshold = 0.6 }:
    { contour: PitchContour; confidence: number[] | null; threshold?: number } = $props();

  const W = 600;
  const H = 110;
  const PAD_L = 34;
  const NAMES = ['C', 'C♯', 'D', 'E♭', 'E', 'F', 'F♯', 'G', 'A♭', 'A', 'B♭', 'B'];
  const toMidi = (hz: number) => 69 + 12 * Math.log2(hz / 440);

  const plot = $derived.by(() => {
    const midis = contour.hz.map((h) => (h ? toMidi(h) : null));
    const voiced = midis.filter((m): m is number => m != null);
    if (!voiced.length) return null;
    const lo = Math.floor(Math.min(...voiced)) - 1;
    const hi = Math.ceil(Math.max(...voiced)) + 1;
    const n = midis.length;
    const x = (i: number) => PAD_L + (i / Math.max(1, n - 1)) * (W - PAD_L - 4);
    const y = (m: number) => 6 + (1 - (m - lo) / (hi - lo)) * (H - 22);
    const clear: string[] = [];
    const unclear: string[] = [];
    let cur: string[] = [];
    let curClear = true;
    const flush = () => { if (cur.length > 1) (curClear ? clear : unclear).push(cur.join(' ')); cur = []; };
    midis.forEach((m, i) => {
      if (m == null) { flush(); return; }
      const ok = (confidence?.[i] ?? 1) >= threshold;
      if (cur.length && ok !== curClear) {
        const last = cur[cur.length - 1];
        flush();
        cur.push(last.replace('L', 'M'));
      }
      curClear = ok;
      cur.push(`${cur.length ? 'L' : 'M'}${x(i).toFixed(1)},${y(m).toFixed(1)}`);
    });
    flush();
    const grid = [];
    for (let m = lo; m <= hi; m++) {
      const name = NAMES[((m % 12) + 12) % 12];
      if (name.length === 1) grid.push({ y: y(m), label: 'CEGA'.includes(name) ? `${name}${Math.floor(m / 12) - 1}` : '' });
    }
    const unclearShare = confidence ? confidence.filter((c, i) => midis[i] != null && c < threshold).length / voiced.length : 0;
    const seconds = (n * contour.hop_ms) / 1000;
    return { clear, unclear, grid, unclearShare, seconds };
  });
</script>

{#if plot}
  <figure>
    <svg viewBox="0 0 {W} {H}" role="img" aria-label="Pitch line of the take over {plot.seconds.toFixed(1)} seconds">
      {#each plot.grid as g (g.y)}
        <line x1={PAD_L} x2={W - 4} y1={g.y} y2={g.y} class="grid" />
        <text x={PAD_L - 6} y={g.y + 3.5} class="label">{g.label}</text>
      {/each}
      {#each plot.unclear as d, i (i)}<path {d} class="unclear" />{/each}
      {#each plot.clear as d, i (i)}<path {d} class="clear" />{/each}
      <text x={PAD_L} y={H - 3} class="label start">0 s</text>
      <text x={W - 4} y={H - 3} class="label end">{plot.seconds.toFixed(1)} s</text>
    </svg>
    {#if plot.unclearShare > 0.02}
      <figcaption><span class="key"></span> Unclear: {Math.round(plot.unclearShare * 100)}% of the take was too masked to judge.</figcaption>
    {/if}
  </figure>
{/if}

<style>
  figure { margin: 0; }
  svg { width: 100%; height: auto; display: block; }
  .grid { stroke: var(--border); stroke-width: 1; }
  .label { font-size: 10px; fill: var(--muted); text-anchor: end; }
  .label.start { text-anchor: start; }
  .clear { fill: none; stroke: var(--accent); stroke-width: 2.2; stroke-linejoin: round; stroke-linecap: round; }
  .unclear { fill: none; stroke: var(--muted); stroke-width: 2; stroke-dasharray: 3 3; opacity: 0.7; }
  figcaption { font-size: 12px; color: var(--muted); display: flex; align-items: center; gap: 6px; }
  .key { width: 18px; border-top: 2px dashed var(--muted); display: inline-block; }
</style>
