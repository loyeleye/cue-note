<script lang="ts">
  // A song: attach its score (PDF, photo or MusicXML), mark the bars, and
  // tie cues and annotations to them.
  import { page } from '$app/state';
  import { api, app } from '$lib/app.svelte';
  import { scoreFormatOf } from '$lib/api';
  import { pdfPageCount } from '$lib/pdf';
  import type { Feedback, Profile, Score, Song, Take } from '$lib/types';
  import ScoreViewer from '$lib/components/ScoreViewer.svelte';

  const id = $derived(page.params.id!);
  let song = $state<Song | null | undefined>(undefined);
  let student = $state<Profile | null>(null);
  let score = $state<Score | null>(null);
  let cards = $state<(Feedback & { recorded_at: string })[]>([]);
  let takes = $state<Take[]>([]);
  let uploading = $state(false);
  let error = $state('');

  $effect(() => {
    if (!app.me) return;
    load(id);
  });

  async function load(songId: string) {
    const s = await api().getSong(songId);
    song = s;
    if (!s) return;
    [score, cards, takes] = await Promise.all([
      api().getScore(songId), api().listStudentFeedback(s.student_id), api().listSongTakes(songId)
    ]);
    student = (await api().getStudent(s.student_id))?.student ?? null;
  }

  async function upload(e: Event) {
    const input = e.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || !song) return;
    const format = scoreFormatOf(file);
    if (!format) { error = 'Use a PDF, a photo of the page, or a MusicXML file.'; return; }
    if (score && !confirm('Replace the current score? Its bar marks and annotations will be removed.')) return;
    uploading = true;
    error = '';
    try {
      const pages = format === 'pdf' ? await pdfPageCount(file) : format === 'image' ? 1 : null;
      score = await api().uploadScore(song, file, pages);
    } catch (err) {
      error = err instanceof Error ? err.message : String(err);
    } finally {
      uploading = false;
    }
  }

  function cardSaved(f: Feedback) {
    cards = cards.map((c) => (c.id === f.id ? { ...c, ...f } : c));
  }
</script>

{#if song === null}
  <p>This song isn't shared with you. <a href="/">Back to students</a></p>
{:else if song}
  <a class="back" href="/students/{song.student_id}#songs">← {student?.display_name || 'Student'}</a>
  <div class="head">
    <div>
      <h1>{song.title}</h1>
      {#if song.composer}<p class="muted">{song.composer}</p>{/if}
    </div>
    <label class="ghost upload">
      {uploading ? 'Uploading…' : score ? 'Replace score' : 'Attach score'}
      <input type="file" accept="application/pdf,image/*,.musicxml,.xml,.mxl" onchange={upload} disabled={uploading} />
    </label>
  </div>
  {#if error}<p class="error" role="alert">{error}</p>{/if}

  {#if score}
    <ScoreViewer {score} {cards} {takes} oncardsaved={cardSaved} />
  {:else}
    <div class="card empty">
      <p><strong>No score yet.</strong></p>
      <p class="muted">Attach a PDF or a photo of the paper copy. MusicXML is accepted too. Only use scores you own; public-domain ones are on IMSLP.</p>
    </div>
  {/if}
{:else}
  <p class="muted">Loading…</p>
{/if}

<style>
  .head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; margin: 8px 0 12px; }
  .head p { margin: 0; }
  .upload { cursor: pointer; position: relative; }
  .upload input { position: absolute; inset: 0; opacity: 0; cursor: pointer; }
  .empty p { margin: 4px 0; }
  .error { color: var(--danger); }
</style>
