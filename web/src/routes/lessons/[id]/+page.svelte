<script lang="ts">
  // The lesson timeline: every take in order, with its clip, pitch line and
  // the cards she gave about it. Any card opens in the editor in place.
  // Several teachers can have the lesson open: each card shows who edited it
  // last, others' saves arrive live, and their faces show at the top.
  import { tick } from 'svelte';
  import { page } from '$app/state';
  import { api, app } from '$lib/app.svelte';
  import type { LessonWatch, PresentTeacher } from '$lib/api';
  import { ago, bars, clock, date, FLAG_LABEL, minutes } from '$lib/format';
  import { colorVar, findTeacher, initials, softVar, teacherLabel } from '$lib/people';
  import type {
    CardFields, CardVersion, Checkpoint, DiagramSuggestion, Feedback, Lesson, PhotoMark, Profile, Song, Take, Teacher
  } from '$lib/types';
  import CardEditor from '$lib/components/CardEditor.svelte';
  import LessonHistory from '$lib/components/LessonHistory.svelte';
  import Who from '$lib/components/Who.svelte';
  import ClipPlayer from '$lib/components/ClipPlayer.svelte';
  import NotesPanel from '$lib/components/NotesPanel.svelte';
  import PitchPlot from '$lib/components/PitchPlot.svelte';
  import PracticePanel from '$lib/components/PracticePanel.svelte';
  import VersionHistory from '$lib/components/VersionHistory.svelte';
  import SketchFigure from '$lib/components/SketchFigure.svelte';
  import DiagramSuggestionView from '$lib/components/DiagramSuggestion.svelte';

  const id = $derived(page.params.id!);
  let lesson = $state<Lesson | null | undefined>(undefined);
  let student = $state<Profile | null>(null);
  let takes = $state<Take[]>([]);
  let cards = $state<Feedback[]>([]);
  let photos = $state<PhotoMark[]>([]);
  let suggestions = $state<DiagramSuggestion[]>([]);
  let songs = $state<Song[]>([]);
  let editing = $state<string | null>(null);
  let refresh = $state(0);
  let flash = $state<string | null>(null);
  let flashColor = $state<string | null>(null);
  let editor: CardEditor | undefined = $state();
  let teachers = $state<Teacher[]>([]);
  let versions = $state<CardVersion[]>([]);
  let checkpoints = $state<Checkpoint[]>([]);
  let present = $state<PresentTeacher[]>([]);
  let watch: LessonWatch | null = null;

  const me = $derived(app.me?.id ?? null);
  const others = $derived(present.filter((p) => p.id !== me));

  $effect(() => {
    if (!app.me) return;
    load(id);
  });

  async function load(lessonId: string) {
    const l = await api().getLesson(lessonId);
    lesson = l;
    if (!l) return;
    [takes, cards, photos, songs, suggestions] = await Promise.all([
      api().listTakes(lessonId), api().listFeedback(lessonId), api().listPhotos(lessonId), api().listSongs(l.student_id),
      api().listDiagramSuggestions(lessonId).catch(() => [] as DiagramSuggestion[])
    ]);
    student = (await api().getStudent(l.student_id))?.student ?? null;
    teachers = await api().listTeachers(l.student_id);
    await reloadHistory();
    const hash = location.hash.match(/^#card-(.+)$/)?.[1];
    if (hash) {
      await tick();
      document.getElementById(`card-${hash}`)?.scrollIntoView({ block: 'center' });
      flash = hash;
      setTimeout(() => (flash = null), 2000);
    }
  }

  async function reloadHistory() {
    [versions, checkpoints] = await Promise.all([api().listLessonVersions(id), api().listCheckpoints(id)]);
  }

  function flashCard(cardId: string, color: string | null) {
    flash = cardId;
    flashColor = color;
    setTimeout(() => { if (flash === cardId) { flash = null; flashColor = null; } }, 1800);
  }

  // Live: cards others save, their new versions, names, and who's here.
  $effect(() => {
    if (!lesson) return;
    const w = api().watchLesson(lesson.id, {
      onCard(f) {
        if (cards.some((c) => c.id === f.id)) cards = cards.map((c) => (c.id === f.id ? { ...c, ...f } : c));
      },
      onVersion(v) {
        if (!cards.some((c) => c.id === v.feedback_id) || versions.some((x) => x.id === v.id)) return;
        versions = [v, ...versions];
        refresh += 1;
        if (v.author_id !== me) flashCard(v.feedback_id, colorVar(findTeacher(teachers, v.author_id).color));
      },
      onCheckpoints() { api().listCheckpoints(id).then((c) => (checkpoints = c)); },
      onPresence(list) { present = list; }
    });
    watch = w;
    return () => { w.stop(); watch = null; present = []; };
  });
  $effect(() => { watch?.setEditing(editing); });

  /** The newest version of each card. */
  const latest = $derived.by(() => {
    const out = new Map<string, CardVersion>();
    for (const v of versions) {
      const seen = out.get(v.feedback_id);
      if (!seen || seen.created_at < v.created_at) out.set(v.feedback_id, v);
    }
    return out;
  });
  const editorOf = (cardId: string) => others.find((p) => p.editing === cardId);

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
    flashCard(f.id, null);
    reloadHistory();
  }

  async function restore(f: Feedback, payload: Partial<CardFields>, versionId: string) {
    if (editing !== f.id) { editing = f.id; await tick(); }
    editor?.load(payload, versionId);
  }

  let claimError = $state('');
  async function claim(mine: boolean) {
    if (!lesson) return;
    claimError = '';
    try {
      await api().claimLesson(lesson.id, mine);
      lesson = await api().getLesson(lesson.id);
    } catch (e) {
      claimError = e instanceof Error ? e.message : String(e);
    }
  }

  async function changedHistory() {
    if (!lesson) return;
    [cards] = await Promise.all([api().listFeedback(lesson.id), reloadHistory()]);
    refresh += 1;
  }

  // Sketches: drag one onto a card to move it there.
  let dropOn = $state<string | null>(null);
  let moveError = $state('');
  const PHOTO_TYPE = 'application/x-cuenote-photo';

  function dragOver(e: DragEvent, cardId: string) {
    if (!e.dataTransfer?.types.includes(PHOTO_TYPE)) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    dropOn = cardId;
  }

  async function dropPhoto(e: DragEvent, cardId: string) {
    const photoId = e.dataTransfer?.getData(PHOTO_TYPE);
    dropOn = null;
    if (!photoId) return;
    e.preventDefault();
    const p = photos.find((x) => x.id === photoId);
    if (!p || p.feedback_id === cardId) return;
    moveError = '';
    try {
      movedPhoto(await api().movePhoto(photoId, cardId));
    } catch (err) {
      moveError = err instanceof Error ? err.message : String(err);
    }
  }

  function movedPhoto(p: PhotoMark) {
    photos = photos.map((x) => (x.id === p.id ? p : x));
    if (p.feedback_id) flashCard(p.feedback_id, null);
  }

  let removeError = $state<{ card: string; message: string } | null>(null);
  async function removeAudio(c: Feedback) {
    if (!confirm('Remove the audio from this card? The note stays, but the clip of you saying it is deleted for good.')) return;
    removeError = null;
    try {
      const f = await api().removeCardAudio(c);
      cards = cards.map((x) => (x.id === f.id ? f : x));
    } catch (err) {
      removeError = { card: c.id, message: err instanceof Error ? err.message : String(err) };
    }
  }
  async function removeCard(c: Feedback) {
    if (!confirm('Delete this card and its clip for good? It won\'t come back, even if the notes are made again.')) return;
    removeError = null;
    try {
      await api().removeCard(c);
      cards = cards.filter((x) => x.id !== c.id);
    } catch (err) {
      removeError = { card: c.id, message: err instanceof Error ? err.message : String(err) };
    }
  }

  const studentCards = $derived(cards.map((c) => ({ ...c, recorded_at: lesson?.recorded_at ?? '' })));
</script>

{#snippet cardView(c: Feedback)}
  {@const sketches = photos.filter((p) => p.feedback_id === c.id)}
  {@const last = latest.get(c.id)}
  {@const busy = editorOf(c.id)}
  {@const busyT = busy ? findTeacher(teachers, busy.id) : null}
  <article class="card cue-card" id="card-{c.id}" class:flash={flash === c.id} class:open={editing === c.id}
           class:drop={dropOn === c.id}
           ondragover={(e) => dragOver(e, c.id)} ondragleave={() => { if (dropOn === c.id) dropOn = null; }}
           ondrop={(e) => dropPhoto(e, c.id)}
           class:busy={!!busyT} style:--busy={busyT ? colorVar(busyT.color) : null}
           style:--flash={flash === c.id && flashColor ? flashColor : null}>
    {#if busyT}
      <span class="editing-badge" style:background={softVar(busyT.color)} style:color={colorVar(busyT.color)}>
        {teacherLabel(busyT, me)} is editing
      </span>
    {/if}
    {#if editing === c.id}
      <div class="edit-grid">
        <CardEditor bind:this={editor} card={c} {takes} {songs} {teachers} {me}
                    lastEditor={last?.author === 'teacher' ? last.author_id : null}
                    onsaved={saved} oncancel={() => (editing = null)} />
        <VersionHistory feedbackId={c.id} {takes} {songs} {teachers} {checkpoints} {me} {refresh}
                        onrestore={(p, vid) => restore(c, p, vid)} />
      </div>
    {:else}
      <div class="cue-head">
        <h3>{c.cue || 'Untitled cue'}</h3>
        <button class="ghost small" onclick={() => (editing = c.id)}>Edit</button>
      </div>
      {#if last?.author === 'teacher'}
        <p class="edited-by"><span class="muted">Edited by</span> <Who teacher={findTeacher(teachers, last.author_id)} {me} /> <span class="muted">{ago(last.created_at)}</span></p>
      {/if}
      {#if c.her_words}<blockquote class="quote">“{c.her_words}”</blockquote>{/if}
      <div class="chips">
        <span class="chip">{clock(c.span_start_ms)}</span>
        {#if c.category}<span class="chip">{c.category}</span>{/if}
        {#if c.practice_flag}<span class="chip accent">{FLAG_LABEL[c.practice_flag]}</span>{/if}
        {#if c.bar_start != null}<span class="chip">{bars(c.bar_start, c.bar_end)}{songTitle(c.song_id) ? ` · ${songTitle(c.song_id)}` : ''}</span>{/if}
      </div>
      {#if c.clip_path}
        <div class="her-clip"><span class="muted small">Feedback clip</span><ClipPlayer path={c.clip_path} compact /></div>
      {:else if c.audio_removed_at}
        <p class="muted small">Audio removed {ago(c.audio_removed_at)}. The note stays.</p>
      {/if}
      <div class="card-remove">
        {#if c.clip_path}<button class="link small" onclick={() => removeAudio(c)}>Remove audio</button>{/if}
        <button class="link small danger" onclick={() => removeCard(c)}>Delete card</button>
      </div>
      {#if removeError?.card === c.id}<p class="error small" role="alert">{removeError.message}</p>{/if}
      {#each sketches as p (p.id)}{@render photoView(p)}{/each}
      {#each suggestions.filter((s) => s.feedback_id === c.id) as s (s.id)}
        <DiagramSuggestionView suggestion={s} ondecide={(u) => (suggestions = suggestions.map((x) => (x.id === u.id ? u : x)))} />
      {/each}
    {/if}
  </article>
{/snippet}

{#snippet photoView(p: PhotoMark)}
  <SketchFigure photo={p} {cards} onmove={movedPhoto} />
{/snippet}

{#if lesson === null}
  <p>This lesson isn't shared with you. <a href="/">Back to students</a></p>
{:else if lesson}
  <a class="back" href="/students/{lesson.student_id}">← {student?.display_name || 'Student'}</a>
  <div class="title-row">
    <h1>Lesson, {date(lesson.recorded_at, { weekday: 'long', month: 'long', day: 'numeric' })}</h1>
    {#if others.length}
      <div class="present" aria-label="Also viewing this lesson">
        {#each others as p (p.id)}
          {@const t = findTeacher(teachers, p.id)}
          <span class="face" style:background={colorVar(t.color)}
                title="{teacherLabel(t, me)}{p.editing ? ', editing a card' : ', viewing'}">{initials(t.alias)}</span>
        {/each}
        <span class="muted small">{others.length === 1 ? `${findTeacher(teachers, others[0].id).alias} is here` : `${others.length} others here`}</span>
      </div>
    {/if}
  </div>
  <p class="muted">{minutes(lesson.duration_ms)} · {takes.length} takes · {cards.length} cards. Every save keeps the earlier version.</p>
  {#if teachers.length > 1 || (!lesson.taught_by && lesson.teacher_name)}
    <p class="taught">
      {#if lesson.taught_by}
        <span class="muted">Taught by</span> <Who teacher={findTeacher(teachers, lesson.taught_by)} {me} />
        {#if lesson.taught_by === me}<button class="link" onclick={() => claim(false)}>Not my lesson</button>{/if}
      {:else}
        {#if lesson.teacher_name}
          <span class="muted">Taught by</span> {lesson.teacher_name} <span class="muted small">(not on Cue Note)</span>
        {:else}
          <span class="muted">Teacher not set.</span>
        {/if}
        <button class="link" onclick={() => claim(true)}>This was my lesson</button>
      {/if}
      {#if claimError}<span class="error small" role="alert">{claimError}</span>{/if}
    </p>
  {/if}

  {#if photos.length}
    <p class="muted small">Sketches sit on the card they were taken during. Drag one onto another card, or use its "Move to" list.</p>
  {/if}
  {#if moveError}<p class="error small" role="alert">{moveError}</p>{/if}
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

  <LessonHistory lessonId={lesson.id} {cards} {versions} {checkpoints} {teachers} {takes} {songs} {me}
                 onchanged={changedHistory} />

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
  .cue-card { position: relative; }
  .cue-card.drop { box-shadow: 0 0 0 3px var(--accent); }
  .error { color: var(--danger); }
  .cue-card.flash { box-shadow: 0 0 0 3px var(--flash, var(--accent-soft)); }
  .cue-card.busy { border-left-color: var(--busy); outline: 2px solid var(--busy); outline-offset: -1px; }
  .editing-badge { position: absolute; top: -10px; right: 12px; font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: 999px; }
  .edited-by { margin: -4px 0 0; font-size: 12px; display: flex; gap: 4px; align-items: center; flex-wrap: wrap; }
  .taught { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; font-size: 13px; margin: 4px 0 0; }
  .taught .error { color: var(--danger); }
  .title-row { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
  .present { display: flex; align-items: center; gap: 4px; }
  .face { width: 28px; height: 28px; border-radius: 50%; color: var(--surface); font-size: 11px; font-weight: 700; display: inline-flex; align-items: center; justify-content: center; border: 2px solid var(--surface); }
  .face + .face { margin-left: -8px; }
  .cue-head { display: flex; justify-content: space-between; gap: 8px; align-items: flex-start; }
  .cue-head h3 { margin: 0; font-size: 16px; }
  blockquote { margin: 0; color: var(--chip-fg); font-size: 15.5px; line-height: 1.45; }
  .chips { display: flex; gap: 6px; flex-wrap: wrap; }
  .her-clip { display: grid; grid-template-columns: auto 1fr; gap: 8px; align-items: center; }
  .edit-grid { display: grid; grid-template-columns: minmax(0, 3fr) minmax(0, 2fr); gap: 20px; }
  .after { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; margin-top: 12px; }
  .small { font-size: 13px; }
  @media (max-width: 760px) {
    .edit-grid, .after { grid-template-columns: 1fr; }
    .timeline > li { grid-template-columns: 40px 1fr; gap: 8px; }
    .timeline > li::before { left: 19px; }
    .cue-card { margin-left: 0; }
  }
  .card-remove { display: flex; gap: 14px; justify-content: flex-end; margin-top: 6px; }
</style>
