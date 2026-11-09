<script lang="ts">
  // One student: lessons, songs, notes and practice, one tab each.
  import { goto } from '$app/navigation';
  import { page } from '$app/state';
  import { api, app } from '$lib/app.svelte';
  import { date, minutes } from '$lib/format';
  import type { Feedback, Lesson, Profile, Share, Song } from '$lib/types';
  import NotesPanel from '$lib/components/NotesPanel.svelte';
  import PracticePanel from '$lib/components/PracticePanel.svelte';

  const id = $derived(page.params.id!);
  const TABS = ['lessons', 'songs', 'notes', 'practice'] as const;
  type Tab = (typeof TABS)[number];
  const tab = $derived<Tab>((TABS as readonly string[]).includes(page.url.hash.slice(1)) ? (page.url.hash.slice(1) as Tab) : 'lessons');

  let student = $state<{ student: Profile; share: Share } | null | undefined>(undefined);
  let lessons = $state<Lesson[]>([]);
  let songs = $state<Song[]>([]);
  let cards = $state<(Feedback & { recorded_at: string })[]>([]);
  let cardCounts = $state<Record<string, { total: number; edited: number }>>({});
  let newTitle = $state('');
  let newComposer = $state('');
  let nextLesson = $state('');

  $effect(() => {
    if (!app.me) return;
    const sid = id;
    api().getStudent(sid).then((s) => {
      student = s;
      nextLesson = s?.share.next_lesson_at ? toLocalInput(s.share.next_lesson_at) : '';
    });
    api().listLessons(sid).then((l) => (lessons = l));
    api().listSongs(sid).then((s) => (songs = s));
    api().listStudentFeedback(sid).then((c) => {
      cards = c;
      const counts: Record<string, { total: number; edited: number }> = {};
      for (const f of c) {
        const k = (counts[f.lesson_id] ??= { total: 0, edited: 0 });
        k.total += 1;
      }
      cardCounts = counts;
    });
  });

  function toLocalInput(iso: string) {
    const d = new Date(iso);
    return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  }

  async function saveNextLesson() {
    if (!student) return;
    await api().setNextLesson(student.share.id, nextLesson ? new Date(nextLesson).toISOString() : null);
  }

  async function addSong(e: SubmitEvent) {
    e.preventDefault();
    if (!newTitle.trim()) return;
    const s = await api().createSong(id, newTitle.trim(), newComposer.trim());
    goto(`/songs/${s.id}`);
  }

  const STATE_LABEL: Record<string, string> = {
    recorded: 'Recorded, not processed yet',
    processing: 'Processing on the phone',
    cards_ready: 'Cards ready'
  };
</script>

{#if student === null}
  <p>This student isn't shared with you. <a href="/">Back to students</a></p>
{:else if student}
  <a class="back" href="/">← Students</a>
  <div class="head">
    <h1>{student.student.display_name || 'Unnamed student'}</h1>
    <label class="next">
      <span>Next lesson</span>
      <input type="datetime-local" bind:value={nextLesson} onchange={saveNextLesson} />
    </label>
  </div>

  <nav class="tabs" aria-label="Student sections">
    {#each TABS as t (t)}
      <a href="#{t}" aria-current={tab === t ? 'page' : undefined}>{t[0].toUpperCase() + t.slice(1)}</a>
    {/each}
  </nav>

  {#if tab === 'lessons'}
    {#if !lessons.length}
      <p class="muted">No lessons yet. They appear here once the phone has processed a recording.</p>
    {:else}
      <ul class="list">
        {#each lessons as l (l.id)}
          <li>
            <a class="card row" href="/lessons/{l.id}">
              <span>
                <strong>{date(l.recorded_at, { weekday: 'long', month: 'long', day: 'numeric' })}</strong>
                <span class="muted"> · {minutes(l.duration_ms)}</span>
              </span>
              <span class="muted small">
                {cardCounts[l.id]?.total ?? 0} cards · {STATE_LABEL[l.pipeline_state] ?? l.pipeline_state}
              </span>
            </a>
          </li>
        {/each}
      </ul>
    {/if}
  {:else if tab === 'songs'}
    <ul class="list">
      {#each songs as s (s.id)}
        <li>
          <a class="card row" href="/songs/{s.id}">
            <span><strong>{s.title}</strong>{#if s.composer}<span class="muted"> · {s.composer}</span>{/if}</span>
            <span class="muted small">{cards.filter((c) => c.song_id === s.id).length} cues</span>
          </a>
        </li>
      {:else}
        <li class="muted">No songs yet.</li>
      {/each}
    </ul>
    <form class="card add-song" onsubmit={addSong}>
      <h3>Add a song</h3>
      <div class="fields">
        <input bind:value={newTitle} placeholder="Title" aria-label="Song title" required />
        <input bind:value={newComposer} placeholder="Composer (optional)" aria-label="Composer" />
        <button class="primary" type="submit">Add and attach a score</button>
      </div>
    </form>
  {:else if tab === 'notes'}
    <NotesPanel studentId={id} {lessons} />
  {:else}
    <PracticePanel studentId={id} {cards} />
  {/if}
{:else}
  <p class="muted">Loading…</p>
{/if}

<style>
  .head { display: flex; justify-content: space-between; align-items: flex-end; gap: 12px; flex-wrap: wrap; margin-top: 8px; }
  .next { display: flex; flex-direction: column; gap: 4px; font-size: 12px; font-weight: 600; color: var(--muted); }
  .tabs { display: flex; gap: 4px; border-bottom: 1px solid var(--border); margin: 18px 0 16px; overflow-x: auto; }
  .tabs a { padding: 8px 14px; text-decoration: none; color: var(--muted); border-bottom: 2px solid transparent; margin-bottom: -1px; white-space: nowrap; }
  .tabs a[aria-current='page'] { color: var(--fg); border-bottom-color: var(--accent); font-weight: 600; }
  .list { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 8px; }
  .row { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; text-decoration: none; color: inherit; }
  .row:hover { border-color: var(--accent); }
  .small { font-size: 13px; }
  .add-song { margin-top: 16px; }
  .fields { display: flex; gap: 8px; flex-wrap: wrap; }
  .fields input { flex: 1 1 180px; }
</style>
