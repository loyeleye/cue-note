<script lang="ts">
  // Settings: what's stored for each student and how much space it takes,
  // plus the notes and practice she can remove.
  import { api, app } from '$lib/app.svelte';
  import { bytes } from '$lib/format';
  import type { StorageRow, StudentSummary } from '$lib/types';

  // Supabase's free plan includes 1 GB of file storage.
  const FREE_PLAN_BYTES = 1024 ** 3;
  const LABEL: Record<StorageRow['category'], string> = {
    clips: 'Lesson clips (takes and her feedback)',
    photos: 'Sketch photos',
    scores: 'Scores',
    voice_notes: 'Your voice notes'
  };

  let students = $state<StudentSummary[]>([]);
  let usage = $state<Record<string, StorageRow[]>>({});
  let counts = $state<Record<string, { notes: number; practice: number }>>({});
  let error = $state('');

  async function reload() {
    try {
      students = await api().listStudents();
      const entries = await Promise.all(students.map(async (s) => {
        const [rows, notes, practice] = await Promise.all([
          api().storageUsed(s.student.id), api().listComments(s.student.id), api().listPractice(s.student.id)
        ]);
        return [s.student.id, rows, { notes: notes.filter((n) => n.author_id === app.me?.id).length, practice: practice.filter((p) => p.author_id === app.me?.id).length }] as const;
      }));
      usage = Object.fromEntries(entries.map(([id, rows]) => [id, rows]));
      counts = Object.fromEntries(entries.map(([id, , c]) => [id, c]));
    } catch (e) {
      error = (e as Error).message;
    }
  }

  $effect(() => { if (app.me) reload(); });

  const total = $derived(Object.values(usage).flat().reduce((n, r) => n + r.bytes, 0));
  const studentTotal = (id: string) => (usage[id] ?? []).reduce((n, r) => n + r.bytes, 0);

  async function deleteMine(studentId: string, name: string) {
    if (!confirm(`Delete every note, voice note and practice item you've written for ${name}? This can't be undone.`)) return;
    const [notes, practice] = await Promise.all([api().listComments(studentId), api().listPractice(studentId)]);
    for (const n of notes.filter((x) => x.author_id === app.me?.id)) await api().deleteComment(n);
    for (const p of practice.filter((x) => x.author_id === app.me?.id)) await api().deletePractice(p.id);
    await reload();
  }
</script>

<h1>Settings</h1>
{#if error}<p class="error" role="alert">{error}</p>{/if}

<section>
  <h2>Storage used</h2>
  <div class="card total">
    <div class="total-head">
      <strong>{bytes(total)}</strong>
      <span class="muted">of {bytes(FREE_PLAN_BYTES)} on the free plan</span>
    </div>
    <div class="meter" role="meter" aria-valuemin="0" aria-valuemax={FREE_PLAN_BYTES} aria-valuenow={total} aria-label="Storage used">
      <span style="width:{Math.min(100, Math.max(total > 0 ? 0.5 : 0, (total / FREE_PLAN_BYTES) * 100))}%"></span>
    </div>
    <p class="muted small">Full lesson recordings stay on the student's phone. Only short clips, sketch photos, scores and your voice notes are stored online.</p>
  </div>

  {#each students as s (s.student.id)}
    <div class="card student">
      <div class="student-head">
        <h3>{s.student.display_name || 'Unnamed student'}</h3>
        <span class="muted">{bytes(studentTotal(s.student.id))}</span>
      </div>
      {#if usage[s.student.id]?.length}
        <table>
          <thead><tr><th scope="col">What</th><th scope="col">Files</th><th scope="col">Size</th></tr></thead>
          <tbody>
            {#each usage[s.student.id] as r (r.category)}
              <tr><td>{LABEL[r.category] ?? r.category}</td><td>{r.files}</td><td>{bytes(r.bytes)}</td></tr>
            {/each}
          </tbody>
        </table>
      {:else}
        <p class="muted small">Nothing stored yet.</p>
      {/if}
      <div class="mine">
        <span class="muted small">
          You've written {counts[s.student.id]?.notes ?? 0} notes and {counts[s.student.id]?.practice ?? 0} practice items for {s.student.display_name || 'this student'}.
        </span>
        <button class="ghost small danger-btn" onclick={() => deleteMine(s.student.id, s.student.display_name || 'this student')}
                disabled={!counts[s.student.id]?.notes && !counts[s.student.id]?.practice}>Delete what I wrote</button>
      </div>
    </div>
  {:else}
    <p class="muted">No students yet.</p>
  {/each}
</section>

<section>
  <h2>Account</h2>
  <p>Signed in as <strong>{app.demo ? 'the demo teacher' : app.email}</strong>.</p>
  <p class="muted small">Deleting your whole account, and choosing which pitch metrics each student sees, arrive with the practice features (M5).</p>
</section>

<style>
  .total, .student { margin-bottom: 12px; }
  .total-head { display: flex; gap: 8px; align-items: baseline; font-size: 18px; }
  .total-head .muted { font-size: 14px; }
  .meter { height: 10px; background: var(--chip); border-radius: 5px; overflow: hidden; margin: 10px 0; }
  .meter span { display: block; height: 100%; background: var(--accent); }
  .student-head { display: flex; justify-content: space-between; align-items: baseline; }
  table { width: 100%; border-collapse: collapse; font-size: 14px; }
  th { text-align: left; font-size: 12px; color: var(--muted); font-weight: 600; padding: 4px 0; border-bottom: 1px solid var(--border); }
  td { padding: 6px 0; border-bottom: 1px solid var(--border); font-variant-numeric: tabular-nums; }
  th:not(:first-child), td:not(:first-child) { text-align: right; width: 80px; }
  .mine { display: flex; justify-content: space-between; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 10px; }
  .danger-btn { color: var(--danger); }
  .small { font-size: 13px; }
  .error { color: var(--danger); }
</style>
