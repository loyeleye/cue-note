<script lang="ts">
  // Records a short voice note in the browser with the microphone.
  import { onDestroy } from 'svelte';
  import { clock } from '$lib/format';

  let { onrecorded, maxSeconds = 120 }: { onrecorded: (b: Blob | null) => void; maxSeconds?: number } = $props();

  let recorder: MediaRecorder | null = null;
  let stream: MediaStream | null = null;
  let chunks: Blob[] = [];
  let phase = $state<'idle' | 'recording' | 'done'>('idle');
  let elapsed = $state(0);
  let preview = $state<string | null>(null);
  let error = $state('');
  let timer: ReturnType<typeof setInterval> | undefined;

  const supported = typeof window !== 'undefined' && 'MediaRecorder' in window && !!navigator.mediaDevices?.getUserMedia;

  function pickType() {
    for (const t of ['audio/webm;codecs=opus', 'audio/ogg;codecs=opus', 'audio/mp4']) {
      if (MediaRecorder.isTypeSupported(t)) return t;
    }
    return '';
  }

  async function start() {
    error = '';
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: false } });
    } catch {
      error = 'The browser did not allow the microphone.';
      return;
    }
    const type = pickType();
    recorder = new MediaRecorder(stream, type ? { mimeType: type, audioBitsPerSecond: 48000 } : undefined);
    chunks = [];
    recorder.ondataavailable = (e) => e.data.size && chunks.push(e.data);
    recorder.onstop = () => {
      const blob = new Blob(chunks, { type: recorder?.mimeType || type || 'audio/webm' });
      if (preview) URL.revokeObjectURL(preview);
      preview = URL.createObjectURL(blob);
      phase = 'done';
      onrecorded(blob);
      stream?.getTracks().forEach((t) => t.stop());
    };
    recorder.start();
    phase = 'recording';
    elapsed = 0;
    timer = setInterval(() => {
      elapsed += 1;
      if (elapsed >= maxSeconds) stop();
    }, 1000);
  }

  function stop() {
    clearInterval(timer);
    if (recorder?.state === 'recording') recorder.stop();
  }

  function discard() {
    if (preview) URL.revokeObjectURL(preview);
    preview = null;
    phase = 'idle';
    onrecorded(null);
  }

  export function clear() { discard(); }

  onDestroy(() => { stop(); stream?.getTracks().forEach((t) => t.stop()); });
</script>

{#if !supported}
  <p class="muted">This browser can't record voice notes.</p>
{:else if phase === 'idle'}
  <button type="button" class="ghost" onclick={start}>
    <span class="dot"></span> Record a voice note
  </button>
{:else if phase === 'recording'}
  <div class="rec">
    <span class="dot live"></span> Recording {clock(elapsed * 1000)} / {clock(maxSeconds * 1000)}
    <button type="button" class="primary" onclick={stop}>Stop</button>
  </div>
{:else}
  <div class="rec">
    <audio controls src={preview}></audio>
    <button type="button" class="ghost" onclick={discard}>Discard</button>
  </div>
{/if}
{#if error}<p class="error">{error}</p>{/if}

<style>
  .rec { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 14px; }
  audio { height: 36px; max-width: 100%; }
  .dot { width: 10px; height: 10px; border-radius: 50%; background: var(--danger); display: inline-block; }
  .live { animation: pulse 1s infinite; }
  @keyframes pulse { 50% { opacity: 0.3; } }
  .muted { color: var(--muted); font-size: 13px; }
  .error { color: var(--danger); font-size: 13px; margin: 4px 0 0; }
</style>
