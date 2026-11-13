<script lang="ts">
  // One photo from the lesson. A sketch: the cleaned copy the phone made (straightened,
  // paper whitened), the original a click away, the AI caption, and where it
  // sits. Drag it onto another card, or pick the card from the list, which
  // also works on a phone. A photo the student took of themself (kind 'me',
  // e.g. the mouth shape asked for) shows only once they've shared it: as
  // taken, labeled as theirs, with their own note and no AI caption.
  import { api } from '$lib/app.svelte';
  import { clock } from '$lib/format';
  import type { Feedback, PhotoMark } from '$lib/types';

  let { photo, cards, onmove, studentName = '' }:
    { photo: PhotoMark; cards: Feedback[]; onmove: (p: PhotoMark) => void; studentName?: string } = $props();

  const me = $derived(photo.kind === 'me');
  const whose = $derived(studentName ? `${studentName}'s photo` : "The student's photo");

  let original = $state(false);
  let moving = $state(false);
  let error = $state('');
  const path = $derived(original || !photo.cleaned_path ? photo.image_path : photo.cleaned_path);

  const placedBy = $derived(
    photo.link_source === 'teacher' ? 'Moved by a teacher'
      : photo.link_source === 'student' ? 'Moved by the student'
      : photo.feedback_id ? 'Placed by time' : 'Not on a card'
  );

  function label(c: Feedback) {
    const cue = c.cue || c.her_words || 'Untitled cue';
    return `${clock(c.span_start_ms)} · ${cue.length > 50 ? cue.slice(0, 50) + '…' : cue}`;
  }

  async function move(feedbackId: string | null) {
    if (feedbackId === photo.feedback_id) return;
    moving = true;
    error = '';
    try {
      onmove(await api().movePhoto(photo.id, feedbackId));
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      moving = false;
    }
  }

  function dragstart(e: DragEvent) {
    e.dataTransfer?.setData('application/x-cuenote-photo', photo.id);
    if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move';
  }
</script>

<figure class="sketch" class:me draggable="true" ondragstart={dragstart} aria-busy={moving}>
  {#await api().fileUrl('photos', path ?? '') then src}
    {#if src}<img {src} alt={me ? `${whose} of themself${photo.note ? `: ${photo.note}` : ''}` : (photo.caption ?? 'Sketch from the lesson')} loading="lazy" draggable="false" />{/if}
  {/await}
  <figcaption>
    <div class="row">
      {#if me}
        <span class="chip me-chip">{whose} · {clock(photo.taken_at_ms)}</span>
        <span class="muted tiny">Shared with you</span>
      {:else}
        <span class="chip">Sketch · {clock(photo.taken_at_ms)}</span>
      {/if}
      {#if !me && photo.cleaned_path}
        <button class="link" onclick={() => (original = !original)}>{original ? 'Show cleaned' : 'Show original'}</button>
      {/if}
    </div>
    {#if me && photo.note}
      <span class="caption">“{photo.note}”</span>
      <span class="muted tiny">What they want to notice</span>
    {/if}
    {#if !me && photo.caption}
      <span class="caption">{photo.caption}</span>
      <span class="muted tiny">AI caption from the sketch and what the teacher said</span>
    {/if}
    <label class="move">
      <span class="muted tiny">{placedBy}. Move to</span>
      <select value={photo.feedback_id ?? ''} disabled={moving}
              onchange={(e) => move((e.currentTarget as HTMLSelectElement).value || null)}>
        <option value="">No card</option>
        {#each cards as c (c.id)}<option value={c.id}>{label(c)}</option>{/each}
      </select>
    </label>
    {#if error}<span class="error tiny" role="alert">{error}</span>{/if}
  </figcaption>
</figure>

<style>
  .sketch { margin: 0; display: flex; flex-direction: column; gap: 6px; max-width: 360px; cursor: grab; }
  .sketch[aria-busy='true'] { opacity: 0.6; }
  img { width: 100%; border-radius: 8px; border: 1px solid var(--border); background: #fff; }
  /* a photo of the student: kept as taken, framed a little low so the mouth sits mid-frame */
  .me img { aspect-ratio: 4 / 3; object-fit: cover; object-position: 50% 62%; border: 2px solid var(--brass, #b8862b); }
  .me-chip { font-weight: 600; }
  figcaption { font-size: 13px; display: flex; flex-direction: column; gap: 4px; }
  .row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
  .caption { color: var(--fg); }
  .move { display: flex; flex-direction: column; gap: 2px; }
  .move select { font-size: 13px; padding: 4px 6px; max-width: 100%; }
  .tiny { font-size: 11.5px; }
  .error { color: var(--danger); }
</style>
