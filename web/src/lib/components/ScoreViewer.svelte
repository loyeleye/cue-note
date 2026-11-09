<script lang="ts">
  // The score with everything tied to its bars. Four modes:
  //  Read      tap a bar to see every cue, take and mark about it
  //  Mark bars tap the start of each bar once, so cues can point at bars
  //  Annotate  drop a breath, vowel, dynamic or text mark on the page
  //  Pin cue   pick a cue, then tap its first and last bar
  import { api } from '$lib/app.svelte';
  import { bars as barsLabel, date } from '$lib/format';
  import { pdfPages } from '$lib/pdf';
  import type { Annotation, AnnotationKind, BarPosition, Feedback, Score, Take } from '$lib/types';
  import ClipPlayer from './ClipPlayer.svelte';

  type Cue = Feedback & { recorded_at: string };
  let { score, cards, takes, oncardsaved }: {
    score: Score;
    cards: Cue[];
    takes: Take[];
    oncardsaved: (f: Feedback) => void;
  } = $props();

  type Mode = 'read' | 'bars' | 'annotate' | 'pin';
  let mode = $state<Mode>('read');
  let pages = $state<string[]>([]);
  let loadError = $state('');
  let barMap = $state<BarPosition[]>([]);
  let barsDirty = $state(false);
  let nextBar = $state(1);
  let annotations = $state<Annotation[]>([]);
  let selectedBar = $state<number | null>(null);
  let annKind = $state<AnnotationKind>('breath');
  let annText = $state('');
  let pinCue = $state<string | null>(null);
  let pinStart = $state<number | null>(null);
  let status = $state('');

  // Load the pages whenever the score file changes.
  $effect(() => {
    const s = score;
    barMap = s.bar_map.map((b) => ({ ...b }));
    nextBar = (Math.max(0, ...s.bar_map.map((b) => b.bar)) || 0) + 1;
    barsDirty = false;
    pages = [];
    loadError = '';
    api().listAnnotations(s.id).then((a) => (annotations = a));
    api().fileUrl('scores', s.file_path).then(async (url) => {
      if (!url) { loadError = "Couldn't open the score file."; return; }
      if (s.format === 'image') pages = [url];
      else if (s.format === 'pdf') {
        try { pages = await pdfPages(url); } catch (e) { loadError = `Couldn't read the PDF: ${(e as Error).message}`; }
      } else loadError = 'MusicXML is saved with the song. Showing it on the page comes in a later update; attach a PDF or photo to mark bars now.';
    });
  });

  const sortedBars = $derived([...barMap].sort((a, b) => a.bar - b.bar));
  const songCues = $derived(cards.filter((c) => c.song_id === score.song_id && c.bar_start != null));
  const cuesOnBar = (bar: number) =>
    songCues.filter((c) => bar >= (c.bar_start ?? 0) && bar <= (c.bar_end ?? c.bar_start ?? 0));
  const takesOnBar = (bar: number) =>
    takes.filter((t) => t.bar_start != null && bar >= t.bar_start && bar <= (t.bar_end ?? t.bar_start));

  /** The bar a point on the page falls in: the nearest bar start to its left on the same line. */
  function barAt(page: number, x: number, y: number): number | null {
    const same = barMap.filter((b) => b.page === page);
    if (!same.length) return null;
    const row = same.filter((b) => Math.abs(b.y - y) < 0.07 && b.x <= x + 0.01);
    if (row.length) return row.reduce((a, b) => (b.x > a.x ? b : a)).bar;
    return same.reduce((a, b) => (Math.hypot(b.x - x, b.y - y) < Math.hypot(a.x - x, a.y - y) ? b : a)).bar;
  }

  function point(e: MouseEvent, page: number) {
    const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
    return { page, x: (e.clientX - r.left) / r.width, y: (e.clientY - r.top) / r.height };
  }

  async function tapPage(e: MouseEvent, page: number) {
    const p = point(e, page);
    if (mode === 'bars') {
      barMap = [...barMap.filter((b) => b.bar !== nextBar), { bar: nextBar, ...p }];
      nextBar += 1;
      barsDirty = true;
    } else if (mode === 'annotate') {
      const bar = barAt(page, p.x, p.y);
      if (bar == null) { status = 'Mark the bars first, so each mark knows its bar.'; return; }
      if (annKind !== 'breath' && !annText.trim()) { status = 'Type the mark first, then tap the page.'; return; }
      const a = await api().addAnnotation({ score_id: score.id, bar, kind: annKind, text: annKind === 'breath' ? '' : annText.trim(), ...p });
      annotations = [...annotations, a];
      status = `Added to bar ${bar}.`;
    } else {
      const bar = barAt(page, p.x, p.y);
      if (bar != null) tapBar(bar);
    }
  }

  async function tapBar(bar: number) {
    if (mode === 'bars') return;
    if (mode === 'pin') {
      if (!pinCue) { status = 'Choose a cue first.'; return; }
      if (pinStart == null) { pinStart = bar; status = `From bar ${bar}. Now tap the last bar (or the same bar again).`; return; }
      const [start, end] = pinStart <= bar ? [pinStart, bar] : [bar, pinStart];
      const saved = await api().saveCard(pinCue, { song_id: score.song_id, bar_start: start, bar_end: end });
      oncardsaved(saved);
      status = `Pinned to ${barsLabel(start, end)}.`;
      pinStart = null;
      return;
    }
    selectedBar = bar;
  }

  function removeBar(bar: number) {
    barMap = barMap.filter((b) => b.bar !== bar);
    barsDirty = true;
  }

  async function saveBars() {
    await api().saveBarMap(score.id, barMap);
    barsDirty = false;
    status = `Saved ${barMap.length} bars.`;
  }

  async function removeAnnotation(a: Annotation) {
    await api().deleteAnnotation(a.id);
    annotations = annotations.filter((x) => x.id !== a.id);
  }

  function setMode(m: Mode) {
    mode = m;
    status = '';
    pinStart = null;
    if (m !== 'read') selectedBar = null;
  }

  const MODES: [Mode, string][] = [['read', 'Read'], ['bars', 'Mark bars'], ['annotate', 'Annotate'], ['pin', 'Pin a cue']];
  const KINDS: [AnnotationKind, string, string][] = [
    ['breath', 'Breath', ''], ['vowel', 'Vowel', 'ah, oh, ee…'], ['dynamic', 'Dynamic', 'p, mf, cresc.'], ['text', 'Text', 'a short note']
  ];
</script>

<div class="toolbar" role="toolbar" aria-label="Score tools">
  <div class="seg">
    {#each MODES as [m, label] (m)}
      <button class:on={mode === m} aria-pressed={mode === m} onclick={() => setMode(m)}>{label}</button>
    {/each}
  </div>
</div>

<div class="help">
  {#if mode === 'read'}
    {#if !barMap.length}
      <p>Start with <strong>Mark bars</strong>: tap the start of each bar once. After that, cues and marks can point at bars.</p>
    {:else}<p>Tap a bar to see every cue and take about it.</p>{/if}
  {:else if mode === 'bars'}
    <div class="row">
      <p>Tap where each bar starts, in order. Next: <strong>bar {nextBar}</strong>.</p>
      <label class="inline">Next bar number <input type="number" min="1" bind:value={nextBar} /></label>
      <button class="primary small" disabled={!barsDirty} onclick={saveBars}>Save bars</button>
    </div>
    <p class="muted small">Tap a numbered marker to remove it. Change the number for pickups or repeats.</p>
  {:else if mode === 'annotate'}
    <div class="row">
      <div class="seg small">
        {#each KINDS as [k, label] (k)}
          <button class:on={annKind === k} onclick={() => (annKind = k)}>{label}</button>
        {/each}
      </div>
      {#if annKind !== 'breath'}
        <input class="ann-text" bind:value={annText} placeholder={KINDS.find((k) => k[0] === annKind)?.[2]} aria-label="Mark text" />
      {/if}
    </div>
    <p class="muted small">Then tap the page where the mark goes.</p>
  {:else}
    <div class="row">
      <select bind:value={pinCue} aria-label="Cue to pin">
        <option value={null}>Choose a cue…</option>
        {#each cards as c (c.id)}
          <option value={c.id}>{c.cue}{c.bar_start != null && c.song_id === score.song_id ? ` (${barsLabel(c.bar_start, c.bar_end)})` : ''}</option>
        {/each}
      </select>
    </div>
    <p class="muted small">Then tap its first bar and its last bar.</p>
  {/if}
  {#if status}<p class="status" role="status">{status}</p>{/if}
</div>

<div class="layout">
  <div class="pages">
    {#if loadError}<p class="muted">{loadError}</p>{/if}
    {#if !pages.length && !loadError}<p class="muted">Opening the score…</p>{/if}
    {#each pages as src, i (src)}
      {@const page = i + 1}
      <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
      <div class="page" class:crosshair={mode !== 'read'} onclick={(e) => tapPage(e, page)}>
        <img {src} alt="Score page {page}" draggable="false" />
        {#each barMap.filter((b) => b.page === page) as b (b.bar)}
          {@const n = cuesOnBar(b.bar).length}
          <button
            class="bar-pin"
            class:selected={selectedBar === b.bar || pinStart === b.bar}
            class:has-cues={n > 0}
            style="left:{b.x * 100}%; top:{b.y * 100}%"
            title={n ? `Bar ${b.bar}: ${n} cue${n > 1 ? 's' : ''}` : `Bar ${b.bar}`}
            onclick={(e) => { e.stopPropagation(); if (mode === 'bars') removeBar(b.bar); else tapBar(b.bar); }}
          >{b.bar}{#if n}<span class="count">{n}</span>{/if}</button>
        {/each}
        {#each annotations.filter((a) => a.page === page) as a (a.id)}
          <span class="ann {a.kind}" style="left:{a.x * 100}%; top:{a.y * 100}%">
            {a.kind === 'breath' ? '’' : a.kind === 'vowel' ? `[${a.text}]` : a.text}
          </span>
        {/each}
      </div>
    {/each}
  </div>

  {#if mode === 'read' && selectedBar != null}
    {@const cues = cuesOnBar(selectedBar)}
    {@const tk = takesOnBar(selectedBar)}
    {@const marks = annotations.filter((a) => a.bar === selectedBar)}
    <aside class="panel card">
      <div class="panel-head">
        <h3>Bar {selectedBar}</h3>
        <button class="link" onclick={() => (selectedBar = null)}>Close</button>
      </div>
      <h4>Cues</h4>
      {#each cues as c (c.id)}
        <div class="cue">
          <p><strong>{c.cue}</strong></p>
          {#if c.her_words}<p class="quote">“{c.her_words}”</p>{/if}
          <p class="muted small"><a href="/lessons/{c.lesson_id}#card-{c.id}">Lesson {date(c.recorded_at)}</a> · {barsLabel(c.bar_start, c.bar_end)}</p>
        </div>
      {:else}<p class="muted small">No cues on this bar yet. Use <strong>Pin a cue</strong> to add one.</p>{/each}
      <h4>Takes</h4>
      {#each tk as t (t.id)}
        <div class="take">
          <p class="muted small">{barsLabel(t.bar_start, t.bar_end)}</p>
          {#if t.clip_path}<ClipPlayer path={t.clip_path} compact />{/if}
        </div>
      {:else}<p class="muted small">No takes of this bar yet.</p>{/each}
      <h4>Marks</h4>
      {#each marks as a (a.id)}
        <p class="mark">{a.kind}{a.text ? `: ${a.text}` : ''} <button class="link danger" onclick={() => removeAnnotation(a)}>Remove</button></p>
      {:else}<p class="muted small">No marks.</p>{/each}
    </aside>
  {/if}
</div>

{#if mode === 'read' && sortedBars.length && songCues.length}
  <section class="by-bar">
    <h3>Cues by bar</h3>
    <ul>
      {#each songCues.toSorted((a, b) => (a.bar_start ?? 0) - (b.bar_start ?? 0)) as c (c.id)}
        <li><button class="link" onclick={() => (selectedBar = c.bar_start)}>{barsLabel(c.bar_start, c.bar_end)}</button> {c.cue}</li>
      {/each}
    </ul>
  </section>
{/if}

<style>
  .toolbar { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
  .seg { display: inline-flex; border: 1px solid var(--border); border-radius: 8px; overflow: hidden; flex-wrap: wrap; }
  .seg button { border: none; border-radius: 0; background: transparent; color: var(--fg); padding: 8px 12px; font-size: 14px; }
  .seg button + button { border-left: 1px solid var(--border); }
  .seg button.on { background: var(--accent-soft); color: var(--accent-strong); font-weight: 600; }
  .seg.small button { padding: 6px 10px; font-size: 13px; }
  .help { min-height: 44px; margin-bottom: 8px; }
  .help p { margin: 4px 0; }
  .row { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
  .inline { display: flex; gap: 6px; align-items: center; font-size: 13px; color: var(--muted); }
  .inline input { width: 64px; }
  .ann-text { width: 160px; }
  .status { color: var(--accent-strong); font-size: 13px; }
  .layout { display: flex; gap: 16px; align-items: flex-start; }
  .pages { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 12px; }
  .page { position: relative; border: 1px solid var(--border); border-radius: 6px; overflow: hidden; background: white; user-select: none; }
  .page.crosshair { cursor: crosshair; }
  .page img { display: block; width: 100%; height: auto; }
  .bar-pin {
    position: absolute; transform: translate(-50%, -100%); padding: 1px 5px; min-width: 20px;
    font-size: 11px; font-weight: 700; line-height: 16px; border-radius: 9px; cursor: pointer;
    background: color-mix(in srgb, var(--accent) 14%, transparent); color: var(--accent-strong); border: 1px solid color-mix(in srgb, var(--accent) 50%, transparent);
  }
  .bar-pin.has-cues { background: var(--accent); color: var(--on-accent); }
  .bar-pin.selected { outline: 3px solid var(--brass); }
  .count { margin-left: 3px; font-weight: 600; opacity: 0.85; }
  .count::before { content: '·'; margin-right: 2px; }
  .ann { position: absolute; transform: translate(-50%, -50%); pointer-events: none; color: #b0352b; white-space: nowrap; }
  .ann.breath { font-size: 34px; font-weight: 700; font-family: Georgia, serif; }
  .ann.vowel { font-size: 16px; font-weight: 600; }
  .ann.dynamic { font-family: Georgia, serif; font-style: italic; font-weight: 700; font-size: 20px; }
  .ann.text { font-style: italic; font-size: 14px; background: rgba(255, 250, 230, 0.85); padding: 0 3px; border-radius: 3px; }
  .panel { width: 300px; flex: none; position: sticky; top: 72px; max-height: calc(100vh - 90px); overflow: auto; }
  .panel-head { display: flex; justify-content: space-between; align-items: center; }
  .panel h3 { margin: 0; }
  .panel h4 { margin: 14px 0 6px; font-size: 13px; color: var(--muted); }
  .cue p { margin: 2px 0; }
  .cue + .cue { border-top: 1px solid var(--border); margin-top: 8px; padding-top: 8px; }
  .quote { font-size: 14px; color: var(--muted); }
  .take { margin-bottom: 8px; }
  .mark { margin: 2px 0; font-size: 14px; display: flex; gap: 8px; }
  .by-bar ul { list-style: none; padding: 0; display: flex; flex-direction: column; gap: 4px; }
  .by-bar .link { min-width: 76px; text-align: left; display: inline-block; }
  .muted { color: var(--muted); }
  .small { font-size: 12px; }
  @media (max-width: 760px) {
    .layout { flex-direction: column; }
    .panel { width: auto; position: static; max-height: none; align-self: stretch; }
  }
</style>
