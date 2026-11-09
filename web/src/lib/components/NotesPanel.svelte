<script lang="ts">
  // Her written notes and voice notes for a student. With a lessonId, new
  // notes are attached to that lesson and only that lesson's notes show.
  import { api, app } from '$lib/app.svelte';
  import { dateTime } from '$lib/format';
  import type { Comment, Lesson } from '$lib/types';
  import ClipPlayer from './ClipPlayer.svelte';
  import VoiceRecorder from './VoiceRecorder.svelte';

  let { studentId, lessonId = null, lessons = [] }:
    { studentId: string; lessonId?: string | null; lessons?: Lesson[] } = $props();

  let notes = $state<Comment[]>([]);
  let body = $state('');
  let voice = $state<Blob | null>(null);
  let saving = $state(false);
  let error = $state('');
  let recorder: VoiceRecorder | undefined = $state();

  const shown = $derived(lessonId ? notes.filter((n) => n.lesson_id === lessonId) : notes);

  async function reload() { notes = await api().listComments(studentId); }
  $effect(() => { void studentId; reload(); });

  async function add(e: SubmitEvent) {
    e.preventDefault();
    if (!body.trim() && !voice) return;
    saving = true;
    error = '';
    try {
      await api().addComment({ student_id: studentId, lesson_id: lessonId, body: body.trim(), voice });
      body = '';
      voice = null;
      recorder?.clear();
      await reload();
    } catch (err) {
      error = err instanceof Error ? err.message : String(err);
    } finally {
      saving = false;
    }
  }

  async function remove(n: Comment) {
    if (!confirm('Delete this note? The student will no longer see it.')) return;
    await api().deleteComment(n);
    await reload();
  }

  const lessonName = (id: string | null) => {
    const l = lessons.find((x) => x.id === id);
    return l ? `Lesson ${new Date(l.recorded_at).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}` : '';
  };
</script>

<form class="compose card" onsubmit={add}>
  <label for="note-{lessonId ?? studentId}" class="visually-hidden">Note</label>
  <textarea id="note-{lessonId ?? studentId}" rows="3" bind:value={body}
            placeholder={lessonId ? 'A note about this lesson…' : 'A note for your student…'}></textarea>
  <div class="bar">
    <VoiceRecorder bind:this={recorder} onrecorded={(b) => (voice = b)} />
    <button type="submit" class="primary" disabled={saving || (!body.trim() && !voice)}>
      {saving ? 'Sending…' : 'Add note'}
    </button>
  </div>
  {#if error}<p class="error" role="alert">Couldn't add the note: {error}</p>{/if}
</form>

{#if shown.length}
  <ul class="notes">
    {#each shown as n (n.id)}
      <li class="card">
        <div class="meta">
          <span>{dateTime(n.created_at)}</span>
          {#if !lessonId && n.lesson_id}<span>· {lessonName(n.lesson_id)}</span>{/if}
          {#if n.author_id === app.me?.id}
            <button class="link danger" onclick={() => remove(n)}>Delete</button>
          {/if}
        </div>
        {#if n.body}<p>{n.body}</p>{/if}
        {#if n.clip_path}<ClipPlayer path={n.clip_path} label="Play voice note" compact />{/if}
      </li>
    {/each}
  </ul>
{:else}
  <p class="muted">No notes yet.</p>
{/if}

<style>
  .compose { display: flex; flex-direction: column; gap: 10px; }
  .bar { display: flex; justify-content: space-between; align-items: center; gap: 10px; flex-wrap: wrap; }
  .notes { list-style: none; padding: 0; margin: 12px 0 0; display: flex; flex-direction: column; gap: 8px; }
  .notes p { margin: 6px 0; white-space: pre-wrap; }
  .meta { display: flex; gap: 6px; font-size: 12px; color: var(--muted); align-items: center; }
  .meta .link { margin-left: auto; }
  .error { color: var(--danger); font-size: 13px; margin: 0; }
  .muted { color: var(--muted); }
</style>
