<script lang="ts">
  // The lesson timeline: every take in order, with its clip, pitch line and
  // the cards she gave about it. Any card opens in the editor in place.
  import { tick } from 'svelte';
  import { page } from '$app/state';
  import { api, app } from '$lib/app.svelte';
  import { bars, clock, date, FLAG_LABEL, minutes } from '$lib/format';
  import type { CardFields, Feedback, Lesson, PhotoMark, Profile, Song, Take } from '$lib/types';
  import CardEditor from '$lib/components/CardEditor.svelte';
  import ClipPlayer from '$lib/components/ClipPlayer.svelte';
  import NotesPanel from '$lib/components/NotesPanel.svelte';
  import PitchPlot from '$lib/components/PitchPlot.svelte';
  import PracticePanel from '$lib/components/PracticePanel.svelte';
  import VersionHistory from '$lib/components/VersionHistory.svelte';

  const id = $derived(page.params.id!);
  let lesson = $state<Lesson | null | undefined>(undefined);
  let student = $state<Profile | null>(null);
  let takes = $state<Take[]>([]);
  let cards = $state<Feedback[]>([]);
  let photos = $state<PhotoMark[]>([]);
  let songs = $state<Song[]>([]);
  let editing = $state<string | null>(null);
  let refresh = $state(0);
  let flash = $state<string | null>(null);
  let editor: CardEditor | undefined = $state();

  $effect(() => {
    if (!app.me) return;
    load(id);
  });

  async function load(lessonId: string) {
    const l = await api().getLesson(lessonId);
    lesson = l;
    if (!l) return;
    [takes, cards, photos, songs] = await Promise.all([
      api().listTakes(lessonId), api().listFeedback(lessonId), api().listPhotos(lessonId), api().listSongs(l.student_id)
    ]);
    student = (await api().getStudent(l.student_id))?.student ?? null;
    const hash = location.hash.match(/^#card-(.+)$/)?.[1];
    if (hash) {
      await tick();
      document.getElementById(`card-${hash}`)?.scrollIntoView({ block: 'center' });
      flash = hash;
      setTimeout(() => (flash = null), 2000);
    }
  }

  type Item =
    | { kind: 'take'; at: number; take: Take; index: number; cards: Feedback[] }
    | { kind: 'card'; at: number; card: Feedback }
    | { kind: 'photo'; at: number; photo: PhotoMark };

  const timeline = $derived.by<Item[]>(() => {
    const items: Item[] = takes.map((t, index) => ({
      kind: 'take', at: t.start_ms, take: t, index, cards: cards.filter((c) => c.take_id === t.id)
    }));
    for (const c of cards) if (!c.take_id || !takes.some((t) => t.id === c.take_id)) items.push({ kind: 'card', at: c.span_start_ms, card: c });
    for (const p of photos) if (!p.feedback_id) items.push({ kind: 'photo', at: p.taken_at_ms, photo: p });
    return items.sort((a, b) => a.at - b.at);
  });

  const songTitle = (sid: string | null) => songs.find((s) => s.id === sid)?.title;

  function saved(f: Feedback) {
    cards = cards.map((c) => (c.id === f.id ? f : c));
    refresh += 1;
    editing = null;
    flash = f.id;
    setTimeout(() => (flash = null), 1600);
  }

  async function restore(f: Feedback, payload: Partial<CardFields>) {
    if (editing !== f.id) { editing = f.id; await tick(); }
    editor?.load(payload);
  }

  const studentCards = $derived(cards.map((c) => ({ ...c, recorded_at: lesson?.recorded_at ?? '' })));
</script>

{#snippet cardView(c: Feedback)}
  {@const photo = photos.find((p) => p.feedback_id === c.id)}
  <article class="card cue-card" id="card-{c.id}" class:flash={flash === c.id} class:open={editing === c.id}>
    {#if editing === c.id}
      <div class="edit-grid">
        <CardEditor bind:this={editor} card={c} {takes} {songs} onsaved={saved} oncancel={() => (editing = null)} />
        <VersionHistory feedbackId={c.id} {takes} {songs} {refresh} onrestore={(p) => restore(c, p)} />
      </div>
    {:else}
      <div class="cue-head">
        <h3>{c.cue || 'Untitled cue'}</h3>
        <button class="ghost small" onclick={() => (editing = c.id)}>Edit</button>
      </div>
      {#if c.her_words}<blockquote>“{c.her_words}”</blockquote>{/if}
      <div class="chips">
        <span class="chip">{clock(c.span_start_ms)}</span>
        {#if c.category}<span class="chip">{c.category}</span>{/if}
        {#if c.practice_flag}<span class="chip accent">{FLAG_LABEL[c.practice_flag]}</span>{/if}
        {#if c.bar_start != null}<span class="chip">{bars(c.bar_start, c.bar_end)}{songTitle(c.song_id) ? ` · ${songTitle(c.song_id)}` : ''}</span>{/if}
      </div>
      {#if c.clip_path}
        <div class="her-clip"><span class="muted small">Her clip</span><ClipPlayer path={c.clip_path} compact /></div>
      {/if}
      {#if photo}{@render photoView(photo)}{/if}
    {/if}
  </article>
{/snippet}

{#snippet photoView(p: PhotoMark)}
  <figure class="photo">
    {#await api().fileUrl('photos', p.image_path ?? '') then src}
      {#if src}<img {src} alt={p.caption ?? 'Sketch from the lesson'} loading="lazy" />{/if}
    {/await}
    <figcaption><span class="chip">Sketch · {clock(p.taken_at_ms)}</span> {p.caption ?? ''}</figcaption>
  </figure>
{/snippet}

{#if lesson === null}
  <p>This lesson isn't shared with you. <a href="/">Back to students</a></p>
{:else if lesson}
  <a class="back" href="/students/{lesson.student_id}">← {student?.display_name || 'Student'}</a>
  <h1>Lesson, {date(lesson.recorded_at, { weekday: 'long', month: 'long', day: 'numeric' })}</h1>
  <p class="muted">{minutes(lesson.duration_ms)} · {takes.length} takes · {cards.length} cards. Every save keeps the earlier version.</p>

  <ol class="timeline">
    {#each timeline as item (item.kind + (item.kind === 'take' ? item.take.id : item.kind === 'card' ? item.card.id : item.photo.id))}
      <li>
        <span class="time">{clock(item.at)}</span>
        <div class="body">
          {#if item.kind === 'take'}
            {@const t = item.take}
            <section class="take">
              <div class="take-head">
                <strong>Take {item.index + 1}</strong>
                <span class="muted small">{Math.round((t.end_ms - t.start_ms) / 1000)} s</span>
                {#if t.bar_start != null}<span class="chip">{bars(t.bar_start, t.bar_end)}{songTitle(t.song_id) ? ` · ${songTitle(t.song_id)}` : ''}</span>{/if}
              </div>
              {#if t.clip_path}<ClipPlayer path={t.clip_path} label="Play take" />{/if}
              {#if t.pitch_contour}<PitchPlot contour={t.pitch_contour} confidence={t.pitch_confidence} />{/if}
            </section>
            {#each item.cards as c (c.id)}{@render cardView(c)}{/each}
          {:else if item.kind === 'card'}
            {@render cardView(item.card)}
          {:else}
            {@render photoView(item.photo)}
          {/if}
        </div>
      </li>
    {:else}
      <li class="muted">No takes or cards in this lesson yet.</li>
    {/each}
  </ol>

  <div class="after">
    <section>
      <h2>Notes on this lesson</h2>
      <NotesPanel studentId={lesson.student_id} lessonId={lesson.id} />
    </section>
    <section>
      <h2>Practice</h2>
      <PracticePanel studentId={lesson.student_id} cards={studentCards} lessonId={lesson.id} />
    </section>
  </div>
{:else}
  <p class="muted">Loading…</p>
{/if}

<style>
  .timeline { list-style: none; margin: 20px 0 0; padding: 0; }
  .timeline > li { display: grid; grid-template-columns: 56px 1fr; gap: 12px; position: relative; padding-bottom: 18px; }
  .timeline > li::before {
    content: ''; position: absolute; left: 27px; top: 22px; bottom: 0; border-left: 2px solid var(--border);
  }
  .time { font-size: 12px; color: var(--muted); font-variant-numeric: tabular-nums; padding-top: 2px; background: var(--bg); position: relative; text-align: center; height: fit-content; }
  .body { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
  .take { display: flex; flex-direction: column; gap: 6px; }
  .take-head { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
  .cue-card { display: flex; flex-direction: column; gap: 8px; transition: box-shadow 0.4s; margin-left: 12px; border-left: 3px solid var(--accent); }
  .cue-card.flash { box-shadow: 0 0 0 3px var(--accent-soft); }
  .cue-head { display: flex; justify-content: space-between; gap: 8px; align-items: flex-start; }
  .cue-head h3 { margin: 0; font-size: 16px; }
  blockquote { margin: 0; color: var(--muted); font-size: 14px; }
  .chips { display: flex; gap: 6px; flex-wrap: wrap; }
  .her-clip { display: grid; grid-template-columns: auto 1fr; gap: 8px; align-items: center; }
  .edit-grid { display: grid; grid-template-columns: minmax(0, 3fr) minmax(0, 2fr); gap: 20px; }
  .photo { margin: 0; display: flex; flex-direction: column; gap: 4px; max-width: 360px; }
  .photo img { width: 100%; border-radius: 8px; border: 1px solid var(--border); }
  .photo figcaption { font-size: 13px; color: var(--muted); }
  .after { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; margin-top: 12px; }
  .small { font-size: 13px; }
  @media (max-width: 760px) {
    .edit-grid, .after { grid-template-columns: 1fr; }
    .timeline > li { grid-template-columns: 40px 1fr; gap: 8px; }
    .timeline > li::before { left: 19px; }
    .cue-card { margin-left: 0; }
  }
</style>
