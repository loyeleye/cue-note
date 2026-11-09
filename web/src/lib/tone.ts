// Tiny synthesiser for the demo: turns a list of notes into a WAV file and a
// matching pitch line, so the sample lessons have something to play and plot.
import type { PitchContour } from './types';

export interface Note {
  midi: number;   // 60 = middle C; 0 = a rest
  ms: number;
}

const RATE = 16000;
const HOP_MS = 20;

export const midiToHz = (m: number) => 440 * Math.pow(2, (m - 69) / 12);

/** Pitch per frame with gentle vibrato; `unclear` frame ranges get low confidence. */
export function contourFor(notes: Note[], opts: { unclear?: [number, number][]; seed?: number } = {}) {
  const hz: (number | null)[] = [];
  const confidence: number[] = [];
  let t = 0;
  let r = opts.seed ?? 1;
  const rand = () => ((r = (r * 16807) % 2147483647) / 2147483647);
  for (const n of notes) {
    const frames = Math.round(n.ms / HOP_MS);
    for (let i = 0; i < frames; i++, t += HOP_MS) {
      if (!n.midi) { hz.push(null); confidence.push(0); continue; }
      const scoop = i < 4 ? -0.35 * (1 - i / 4) : 0;            // a small slide into the note
      const vib = i > 10 ? 0.25 * Math.sin((t / 1000) * 2 * Math.PI * 5.5) : 0;
      const drift = (rand() - 0.5) * 0.08;
      hz.push(midiToHz(n.midi + scoop + vib + drift));
      confidence.push(0.85 + rand() * 0.15);
    }
  }
  for (const [a, b] of opts.unclear ?? []) {
    for (let i = Math.floor(a / HOP_MS); i < Math.min(hz.length, b / HOP_MS); i++) confidence[i] = 0.25 + rand() * 0.2;
  }
  const contour: PitchContour = { hop_ms: HOP_MS, hz };
  return { contour, confidence };
}

/** A mono 16 kHz WAV of the notes, sung by a soft "ah". */
export function wavFor(notes: Note[], opts: { octave?: number } = {}): Blob {
  const total = notes.reduce((s, n) => s + n.ms, 0);
  const n = Math.round((total / 1000) * RATE);
  const pcm = new Int16Array(n);
  let i = 0;
  let phase = 0;
  for (const note of notes) {
    const len = Math.round((note.ms / 1000) * RATE);
    const base = note.midi ? midiToHz(note.midi + 12 * (opts.octave ?? 0)) : 0;
    for (let k = 0; k < len && i < n; k++, i++) {
      if (!base) { pcm[i] = 0; continue; }
      const t = k / RATE;
      const f = base * Math.pow(2, (t > 0.2 ? 0.25 * Math.sin(2 * Math.PI * 5.5 * t) : 0) / 12);
      phase += (2 * Math.PI * f) / RATE;
      const env = Math.min(1, k / 600) * Math.min(1, (len - k) / 800);
      const s = Math.sin(phase) + 0.45 * Math.sin(2 * phase) + 0.2 * Math.sin(3 * phase) + 0.08 * Math.sin(5 * phase);
      pcm[i] = Math.round(s * env * 6000);
    }
  }
  const buf = new ArrayBuffer(44 + pcm.byteLength);
  const v = new DataView(buf);
  const str = (o: number, s: string) => [...s].forEach((c, j) => v.setUint8(o + j, c.charCodeAt(0)));
  str(0, 'RIFF'); v.setUint32(4, 36 + pcm.byteLength, true); str(8, 'WAVE');
  str(12, 'fmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, RATE, true); v.setUint32(28, RATE * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
  str(36, 'data'); v.setUint32(40, pcm.byteLength, true);
  new Int16Array(buf, 44).set(pcm);
  return new Blob([buf], { type: 'audio/wav' });
}
