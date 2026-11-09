<script lang="ts">
  // A teacher's colored dot and name, or the AI's grey one.
  import { colorVar, teacherLabel } from '$lib/people';
  import type { Teacher } from '$lib/types';

  let { teacher = null, me = null, ai = false }:
    { teacher?: Teacher | null; me?: string | null; ai?: boolean } = $props();
</script>

<span class="who" class:ai>
  <span class="dot" style:background={ai ? 'var(--muted)' : colorVar(teacher?.color)} aria-hidden="true"></span>
  <span class="name">{ai ? 'AI original' : teacher ? teacherLabel(teacher, me) : 'Teacher'}</span>
</span>

<style>
  .who { display: inline-flex; align-items: center; gap: 5px; font-weight: 600; font-size: 12px; min-width: 0; }
  .who.ai { color: var(--muted); }
  .dot { width: 9px; height: 9px; border-radius: 50%; flex: none; }
  .name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
