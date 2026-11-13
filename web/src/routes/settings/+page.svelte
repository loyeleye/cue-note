<script lang="ts">
  // Settings: what's stored for each student and how much space it takes,
  // plus the notes and practice she can remove.
  import { api, app } from '$lib/app.svelte';
  import { bytes } from '$lib/format';
  import { COLOR_LABEL, colorVar, teacherLabel } from '$lib/people';
  import type { StorageRow, StudentSummary, TeacherColor } from '$lib/types';
  import { TEACHER_COLORS } from '$lib/types';
  import Who from '$lib/components/Who.svelte';

  // Supabase's free plan includes 1 GB of file storage.
  const FREE_PLAN_BYTES = 1024 ** 3;
  const LABEL: Record<StorageRow['category'], string> = {
    clips: 'Lesson clips (takes and teacher feedback)',
    photos: 'Photos (sketches and shared photos)',
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

  // How she appears to other teachers and to students.
  let alias = $state('');
  let realName = $state('');
  let color = $state<TeacherColor>('blue');
  let namePublic = $state(false);
  let savingId = $state(false);
  let idMessage = $state('');
  let idError = $state('');
  let loadedFor = '';
  $effect(() => {
    const m = app.me;
    if (!m || loadedFor === m.id) return;
    loadedFor = m.id;
    alias = m.alias ?? '';
    realName = m.display_name ?? '';
    color = m.color ?? 'blue';
    namePublic = !!m.name_public;
  });
  const idDirty = $derived(!!app.me && (alias.trim() !== (app.me.alias ?? '') || realName.trim() !== (app.me.display_name ?? '')
    || color !== (app.me.color ?? 'blue') || namePublic !== !!app.me.name_public));
  const preview = $derived({ id: 'preview', alias: alias.trim() || 'Your alias', color, name_public: namePublic });

  async function saveIdentity(e: SubmitEvent) {
    e.preventDefault();
    if (!alias.trim()) { idError = 'Your alias can’t be empty.'; return; }
    savingId = true;
    idError = '';
    idMessage = '';
    try {
      app.me = await api().updateMe({ alias: alias.trim(), display_name: realName.trim(), color, name_public: namePublic });
      idMessage = 'Saved.';
    } catch (err) {
      idError = err instanceof Error ? err.message : String(err);
    } finally {
      savingId = false;
    }
  }

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
  <h2>How you appear</h2>
  <form class="card identity" onsubmit={saveIdentity}>
    <p class="muted small">When a student shares lessons with more than one teacher, the others see your alias and color on
      your edits and notes, like an anonymous editor in a shared document. Your students always see your real name too.</p>
    <div class="row">
      <label class="grow">
        <span>Alias</span>
        <input maxlength="40" bind:value={alias} />
      </label>
      <label class="grow">
        <span>Your real name</span>
        <input maxlength="80" bind:value={realName} placeholder="For example: Marigold Stewart" />
      </label>
    </div>
    <fieldset>
      <legend>Color</legend>
      <div class="swatches">
        {#each TEACHER_COLORS as c (c)}
          <label class="swatch" class:on={color === c} title={COLOR_LABEL[c]}>
            <input type="radio" name="color" value={c} bind:group={color} class="visually-hidden" />
            <span class="sw" style:background={colorVar(c)} aria-hidden="true"></span>
            <span class="visually-hidden">{COLOR_LABEL[c]}</span>
          </label>
        {/each}
      </div>
    </fieldset>
    <label class="check">
      <input type="checkbox" bind:checked={namePublic} />
      <span>Show my real name to everyone, including other teachers</span>
    </label>
    <dl class="preview">
      <dt>Other teachers see</dt>
      <dd><Who teacher={{ ...preview, display_name: namePublic ? realName.trim() || null : null }} /></dd>
      <dt>Your students see</dt>
      <dd><Who teacher={{ ...preview, display_name: realName.trim() || null }} /></dd>
    </dl>
    {#if idError}<p class="error small" role="alert">{idError}</p>{/if}
    <div class="id-actions">
      {#if idMessage && !idDirty}<span class="muted small" role="status">{idMessage}</span>{/if}
      <button type="submit" class="primary" disabled={savingId || !idDirty}>{savingId ? 'Saving…' : 'Save'}</button>
    </div>
  </form>
</section>

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
    <p class="muted small">Full lesson recordings stay on the student's phone. Only short clips, sketches, photos the student shares, scores and your voice notes are stored online.</p>
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
  <p>Signed in as <strong>{app.demo ? 'the demo teacher' : app.email}</strong>{#if app.me?.alias}, shown to other teachers as <strong>{teacherLabel({ id: '', alias: app.me.alias, display_name: app.me.name_public ? app.me.display_name : null })}</strong>{/if}.</p>
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
  .identity { display: flex; flex-direction: column; gap: 12px; }
  .identity p { margin: 0; }
  .row { display: flex; gap: 12px; flex-wrap: wrap; }
  .grow { flex: 1 1 200px; }
  .identity label:not(.swatch):not(.check), fieldset { display: flex; flex-direction: column; gap: 4px; }
  .identity label > span, legend { font-size: 12px; font-weight: 600; color: var(--muted); }
  .identity .check > span { font-size: 14px; font-weight: 400; color: var(--fg); }
  fieldset { border: none; padding: 0; margin: 0; }
  legend { padding: 0; margin-bottom: 4px; }
  .swatches { display: flex; gap: 8px; flex-wrap: wrap; }
  .swatch { width: 32px; height: 32px; border-radius: 50%; display: inline-flex; align-items: center; justify-content: center; border: 2px solid transparent; cursor: pointer; }
  .swatch.on { border-color: var(--fg); }
  .swatch:focus-within { outline: 2px solid var(--accent); outline-offset: 1px; }
  .sw { width: 22px; height: 22px; border-radius: 50%; }
  .check { display: flex; gap: 8px; align-items: center; }
  .preview { display: grid; grid-template-columns: auto 1fr; gap: 6px 12px; margin: 0; font-size: 13px; background: var(--bg); border-radius: 8px; padding: 10px; }
  .preview dt { color: var(--muted); }
  .preview dd { margin: 0; min-width: 0; }
  .id-actions { display: flex; justify-content: flex-end; align-items: center; gap: 10px; }
</style>
