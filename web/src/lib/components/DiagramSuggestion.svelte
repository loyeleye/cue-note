<script lang="ts">
  // "Does this diagram help?": a diagram from the library that the AI matched
  // to this card. It is marked as a suggestion and sits beside her own
  // sketch, never in place of it. Dismissing it hides it for the student too;
  // the student's own dismissal hides it only for them.
  import { api } from '$lib/app.svelte';
  import { diagram, diagramUrl } from '$lib/diagrams';
  import type { DiagramDecision, DiagramSuggestion } from '$lib/types';

  let { suggestion, ondecide }:
    { suggestion: DiagramSuggestion; ondecide: (s: DiagramSuggestion) => void } = $props();

  const d = $derived(diagram(suggestion.diagram_id));
  let busy = $state(false);
  let error = $state('');

  const from = $derived(suggestion.source === 'sketch' ? 'the sketch' : 'what the teacher said');
  const studentSays = $derived(
    suggestion.student_status === 'accepted' ? 'The student found it helpful.'
      : suggestion.student_status === 'dismissed' ? 'The student hid it for themselves.' : ''
  );

  async function decide(decision: DiagramDecision) {
    busy = true;
    error = '';
    try {
      ondecide(await api().decideDiagram(suggestion.id, decision));
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      busy = false;
    }
  }
</script>

{#if d}
  {#if suggestion.teacher_status === 'dismissed'}
    <p class="dismissed muted small">
      Dismissed the suggested diagram “{d.title}”. The student won't see it.
      <button class="link" disabled={busy} onclick={() => decide('open')}>Undo</button>
    </p>
  {:else}
    <div class="suggestion" role="group" aria-label="Suggested diagram" aria-busy={busy}>
      <div class="head">
        <span class="chip accent">Suggested diagram</span>
        <span class="muted small">Matched by AI to {from}</span>
      </div>
      <img src={diagramUrl(d.id)} alt={`${d.title}: ${d.shows}`} width="360" height="240" loading="lazy" />
      <div class="caption">
        <strong>{d.title}</strong>
        <span class="muted small">{d.shows}. A standard drawing, not the teacher's sketch.</span>
        {#if studentSays}<span class="small">{studentSays}</span>{/if}
      </div>
      {#if suggestion.teacher_status === 'open'}
        <div class="ask">
          <span>Does this diagram help?</span>
          <button class="primary small" disabled={busy} onclick={() => decide('accepted')}>Yes, keep it</button>
          <button class="ghost small" disabled={busy} onclick={() => decide('dismissed')}>Dismiss</button>
        </div>
        <span class="muted tiny">Dismissing hides it for the student too.</span>
      {:else}
        <div class="ask">
          <span class="muted small">You kept this diagram on the card.</span>
          <button class="link small" disabled={busy} onclick={() => decide('dismissed')}>Remove it</button>
        </div>
      {/if}
      {#if error}<span class="error tiny" role="alert">{error}</span>{/if}
    </div>
  {/if}
{/if}

<style>
  .suggestion { margin: 0; display: flex; flex-direction: column; gap: 6px; max-width: 360px;
    border: 1px dashed var(--border); border-radius: 10px; padding: 10px; }
  .suggestion[aria-busy='true'] { opacity: 0.6; }
  .head { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
  img { width: 100%; height: auto; border-radius: 8px; border: 1px solid var(--border); background: #fff; }
  .caption { display: flex; flex-direction: column; gap: 2px; font-size: 13px; }
  .ask { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; font-size: 13.5px; }
  .dismissed { margin: 0; }
  .small { font-size: 12.5px; }
  .tiny { font-size: 11.5px; }
  .error { color: var(--danger); }
</style>
