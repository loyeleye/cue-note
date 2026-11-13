// The part of extract-cues that doesn't touch the network: what the phone
// sends, how a lesson becomes a prompt, the JSON schema the model must
// answer in, and the checks on its answer. Plain TypeScript with no Deno or
// npm imports, so the same file runs in the edge function and in the tests
// (node --experimental-strip-types --test).
import { DIAGRAMS } from '../_shared/diagrams.ts';

// ----------------------------------------------------------------- Request

/** One feedback block as the phone's pipeline found it. */
export interface Block {
  id: string;               // cloud uuid, derived from local_id on the phone
  local_id: string;         // "<lesson>-fb-001"
  take_id: string | null;   // the take this block is about (cloud uuid), or null
  span_start_ms: number;
  span_end_ms: number;
  clip_path: string | null; // clips/<student>/<lesson>/<id>.ogg once uploaded
  transcript: string;       // the teacher's words in this block, from whisper
  link_confidence: number | null;
  demos: number;            // how many sung demos the block contains
  exercise_id?: string | null; // the warmup or song section it is part of (cloud uuid), or null
}

export interface Line {
  speaker: 'student' | 'teacher';
  start_ms: number;
  end_ms: number;
  text: string;
}

export interface Request {
  lesson_id: string;
  blocks: Block[];
  transcript: Line[];
  /** Compare these models on the lesson instead of writing cards. */
  trial_models?: string[];
}

/** The models a trial may compare. */
export const TRIAL_MODELS = ['claude-haiku-5-5', 'claude-sonnet-5-5', 'claude-opus-5-5', 'claude-fable-5-2'];

export interface TakeRow {
  id: string;
  start_ms: number;
  end_ms: number;
}

/** A warmup or a section of a song: a run of tries and her feedback. */
export interface ExerciseRow {
  id: string;
  start_ms: number;
  kind: 'warmup' | 'song';
}

/** A card the teacher corrected before, used as a worked example. */
export interface Correction {
  transcript: string;
  ai: Partial<CardPayload>;
  teacher: Partial<CardPayload>;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_BLOCKS = 400;
const MAX_TEXT = 20_000;

/** Checks the phone's request; returns a reason it can't be used, or null. */
export function invalidRequest(r: unknown): string | null {
  if (!r || typeof r !== 'object') return 'body must be a JSON object';
  const q = r as Partial<Request>;
  if (typeof q.lesson_id !== 'string' || !UUID.test(q.lesson_id)) return 'lesson_id must be a uuid';
  if (!Array.isArray(q.blocks) || q.blocks.length > MAX_BLOCKS) return `blocks must be a list of at most ${MAX_BLOCKS}`;
  if (!Array.isArray(q.transcript)) return 'transcript must be a list';
  if (q.trial_models != null && (!Array.isArray(q.trial_models) || q.trial_models.length > TRIAL_MODELS.length
      || q.trial_models.some((m) => !TRIAL_MODELS.includes(m)))) {
    return `trial_models must be some of ${TRIAL_MODELS.join(', ')}`;
  }
  const ids = new Set<string>();
  for (const b of q.blocks) {
    if (!b || typeof b.id !== 'string' || !UUID.test(b.id)) return 'each block needs a uuid id';
    if (ids.has(b.id)) return `block ${b.id} appears twice`;
    ids.add(b.id);
    if (b.take_id != null && (typeof b.take_id !== 'string' || !UUID.test(b.take_id))) return `block ${b.id}: take_id must be a uuid`;
    if (b.exercise_id != null && (typeof b.exercise_id !== 'string' || !UUID.test(b.exercise_id))) return `block ${b.id}: exercise_id must be a uuid`;
    if (!Number.isFinite(b.span_start_ms) || !Number.isFinite(b.span_end_ms) || b.span_end_ms < b.span_start_ms) {
      return `block ${b.id}: bad span`;
    }
    if (typeof b.transcript !== 'string' || b.transcript.length > MAX_TEXT) return `block ${b.id}: transcript missing or too long`;
    if (typeof b.local_id !== 'string') return `block ${b.id}: local_id missing`;
  }
  for (const l of q.transcript) {
    if (!l || (l.speaker !== 'student' && l.speaker !== 'teacher') || typeof l.text !== 'string'
        || !Number.isFinite(l.start_ms) || !Number.isFinite(l.end_ms)) return 'bad transcript line';
  }
  return null;
}

// ----------------------------------------------------------------- Answer

// The same list the dashboard's card editor offers. "chat" is talk that
// isn't about the singing (hello, scheduling, small talk).
export const CATEGORIES = [
  'breath', 'resonance', 'vowel', 'registration', 'phrasing', 'pitch', 'diction',
  'posture', 'warm-up', 'encouragement', 'chat',
] as const;
export type Category = typeof CATEGORIES[number];
export type PracticeFlag = 'practice_this' | 'just_listen';

/** What the model returns for one block. "B3" is the third block of the lesson. */
export interface ModelCard {
  block: string;
  cue: string;
  category: Category;
  her_words: string;
  practice_flag: PracticeFlag | null;
  bar_start: number | null;
  bar_end: number | null;
  /** One diagram from the library that shows what she explains, or null. */
  diagram: string | null;
  /** 0..1: how sure the model is that the diagram shows what she explained. */
  diagram_confidence: number;
}

/** What a card_version stores; the same keys save_card snapshots. */
export interface CardPayload {
  cue: string;
  category: string | null;
  her_words: string;
  practice_flag: PracticeFlag | null;
  take_id: string | null;
  song_id: string | null;
  bar_start: number | null;
  bar_end: number | null;
}

const nullable = (s: object) => ({ anyOf: [s, { type: 'null' }] });

/** The JSON schema for output_config.format. */
export const ANSWER_SCHEMA = {
  type: 'object',
  properties: {
    cards: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          block: { type: 'string', description: 'The block label, such as "B3".' },
          cue: { type: 'string', description: 'Her instruction as a short imperative, at most about 12 words. Empty for chat.' },
          category: { type: 'string', enum: [...CATEGORIES] },
          her_words: { type: 'string', description: 'Her exact words that carry the cue, copied from the block, one or two sentences.' },
          practice_flag: nullable({ type: 'string', enum: ['practice_this', 'just_listen'] }),
          bar_start: nullable({ type: 'integer' }),
          bar_end: nullable({ type: 'integer' }),
          diagram: nullable({ type: 'string', enum: DIAGRAMS.map((d) => d.id) }),
          diagram_confidence: { type: 'number', description: '0 to 1. Use 0 when diagram is null.' },
        },
        required: ['block', 'cue', 'category', 'her_words', 'practice_flag', 'bar_start', 'bar_end', 'diagram', 'diagram_confidence'],
        additionalProperties: false,
      },
    },
  },
  required: ['cards'],
  additionalProperties: false,
} as const;

// ----------------------------------------------------------------- Prompt

export const SYSTEM_PROMPT = `You read the transcript of a one-to-one singing lesson and write one cue card per feedback block.

The lesson was recorded on two microphones and split by software into takes (the student singing) and feedback blocks (the teacher talking, and sometimes demonstrating, after a take). Each block is labeled B1, B2, ... and names the take it follows. The transcript comes from speech recognition, so expect misheard words; music terms and Italian lyrics are often garbled.

For each block, write:
- cue: the teacher's main instruction as a short imperative in plain English, at most about 12 words, such as "Drop the jaw on the high G" or "Breathe low before the phrase". Use her idea and her images; never add advice she didn't give. If she gives several instructions, keep the one she spends the most time on. If the block has no instruction (greetings, scheduling, small talk, or only praise with nothing to change), leave cue empty and set category to "chat", unless it is clear praise of the singing, which is "encouragement" with a cue like "Keep that open sound".
- her_words: her exact words that carry the cue, copied verbatim from the block's teacher lines, one or two sentences. Fix nothing, not even recognition errors. Never paraphrase here.
- category: the one that fits best.
- practice_flag: "practice_this" when she asks the student to work on it at home or keeps returning to it; "just_listen" when it is something to hear or notice rather than drill; null when neither is clear.
- bar_start and bar_end: only when she names bar or measure numbers ("from bar 17", "measures 9 to 12"); otherwise null. A single bar has the same start and end.

- diagram: the one diagram from the library below that shows exactly what she is explaining in this block, or null. Most blocks get null. Pick one only when the block is about that very thing (she talks about the soft palate lifting, not just "more space"). Never pick one for chat or encouragement.
- diagram_confidence: from 0 to 1, how sure you are that the diagram shows what she explained. 0 when diagram is null.

Write exactly one card for every block, in order, and use the block labels exactly as given. The student's lines are context only; never take a cue from them.

The diagram library (id: what it shows):
${DIAGRAMS.map((d) => `${d.id}: ${d.title}. ${d.shows}.`).join('\n')}`;

const clock = (ms: number) => {
  const s = Math.max(0, Math.floor(ms / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
};

/** Labels "B1"... in lesson order, so the model never handles uuids. */
export function labelBlocks(blocks: Block[]): Map<string, Block> {
  const sorted = [...blocks].sort((a, b) => a.span_start_ms - b.span_start_ms);
  return new Map(sorted.map((b, i) => [`B${i + 1}`, b]));
}

/**
 * The lesson as the model reads it: takes and blocks in time order, each
 * block with every line said during it, both speakers, under a heading for
 * each warmup or song section, since her feedback often runs across tries.
 */
export function lessonText(labels: Map<string, Block>, takes: TakeRow[], lines: Line[], only?: Set<string>,
                           exercises: ExerciseRow[] = []): string {
  const takeNo = new Map([...takes].sort((a, b) => a.start_ms - b.start_ms).map((t, i) => [t.id, i + 1]));
  const exNo = new Map([...exercises].sort((a, b) => a.start_ms - b.start_ms).map((e, i) => [e.id, i + 1]));
  const out: string[] = [];
  let lastEx: string | null | undefined;
  for (const [label, b] of labels) {
    if (only && !only.has(label)) continue;
    const ex = b.exercise_id && exNo.has(b.exercise_id) ? b.exercise_id : null;
    if (exercises.length && ex !== lastEx) {
      const e = exercises.find((x) => x.id === ex);
      out.push(e ? `== Exercise ${exNo.get(e.id)}: ${e.kind === 'song' ? 'singing the song' : 'a warmup'} ==` : '== Talk between exercises ==');
      lastEx = ex;
    }
    const take = b.take_id ? takes.find((t) => t.id === b.take_id) : undefined;
    const about = take
      ? `after take T${takeNo.get(take.id)} (the student sang ${clock(take.start_ms)}–${clock(take.end_ms)})`
      : 'not after a take (opening or closing talk)';
    out.push(`${label} at ${clock(b.span_start_ms)}, ${about}${b.demos > 0 ? `, she sings ${b.demos} demo${b.demos > 1 ? 's' : ''}` : ''}:`);
    const said = lines.filter((l) => l.start_ms < b.span_end_ms && l.end_ms > b.span_start_ms && l.text.trim());
    if (said.length === 0) out.push(b.transcript.trim() ? `  Teacher: ${b.transcript.trim()}` : '  (nothing transcribed)');
    for (const l of said) out.push(`  ${l.speaker === 'teacher' ? 'Teacher' : 'Student'}: ${l.text.trim()}`);
    out.push('');
  }
  return out.join('\n').trim();
}

/** Past corrections as worked examples, newest first, kept short. */
export function examplesText(corrections: Correction[]): string {
  if (corrections.length === 0) return '';
  const parts = corrections.map((c, i) => {
    const show = (p: Partial<CardPayload>) => JSON.stringify({
      cue: p.cue ?? '', category: p.category ?? null, her_words: p.her_words ?? '', practice_flag: p.practice_flag ?? null,
    });
    return `Example ${i + 1}. She said: "${c.transcript.slice(0, 600)}"\n  First draft: ${show(c.ai)}\n  Her correction: ${show(c.teacher)}`;
  });
  return `This teacher has corrected earlier cards. Follow her preferences:\n\n${parts.join('\n\n')}`;
}

/** Splits a long lesson into calls of at most [size] blocks, so each answers well inside the time limit. */
export function batches<T>(items: T[], size = 15): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

// ----------------------------------------------------------------- Checks

const norm = (s: string) => s.toLowerCase().replace(/[^\p{L}\p{N}']+/gu, ' ').replace(/\s+/g, ' ').trim();

function sentences(text: string): string[] {
  return text.split(/(?<=[.!?])\s+/).map((s) => s.trim()).filter(Boolean);
}

/**
 * her_words must be her words. When the model's quote isn't in the block's
 * transcript, use the sentence of hers that shares the most words with it.
 */
export function verbatim(quote: string, transcript: string): { text: string; repaired: boolean } {
  const q = norm(quote);
  if (!transcript.trim()) return { text: '', repaired: quote.trim() !== '' };
  if (q && norm(transcript).includes(q)) return { text: quote.trim(), repaired: false };
  const want = new Set(q.split(' ').filter((w) => w.length > 2));
  let best = '';
  let bestScore = -1;
  for (const s of sentences(transcript)) {
    const words = norm(s).split(' ');
    const score = words.filter((w) => want.has(w)).length;
    if (score > bestScore) { best = s; bestScore = score; }
  }
  return { text: (best || transcript).slice(0, 400).trim(), repaired: true };
}

/** The model's diagram pick for a card, once checked. */
export interface DiagramPick {
  diagram_id: string;
  confidence: number;
}

/** Suggestions show from this confidence up, to start; accept and dismiss counts will tune it. */
export const DIAGRAM_MIN_CONFIDENCE = 0.7;

const DIAGRAM_IDS = new Set(DIAGRAMS.map((d) => d.id));

/** A known diagram with a confidence in 0..1, never on chat or praise; otherwise null. */
export function diagramPick(c: Pick<ModelCard, 'diagram' | 'diagram_confidence'>, category: string | null): DiagramPick | null {
  if (!c.diagram || !DIAGRAM_IDS.has(c.diagram)) return null;
  if (category === 'chat' || category === 'encouragement') return null;
  const n = Number(c.diagram_confidence);
  if (!Number.isFinite(n)) return null;
  return { diagram_id: c.diagram, confidence: Math.min(1, Math.max(0, n)) };
}

export interface Checked {
  block: Block;
  payload: CardPayload;
  diagram: DiagramPick | null;
  repaired: boolean;   // her_words was replaced with her real words
  missing: boolean;    // the model skipped this block
}

/**
 * Turns the model's cards into one payload per block. Unknown labels are
 * dropped, a block answered twice keeps its first answer, a skipped block
 * gets an empty card with her words, and bars must be positive and in order.
 */
export function check(labels: Map<string, Block>, cards: ModelCard[]): Checked[] {
  const byLabel = new Map<string, ModelCard>();
  for (const c of cards) if (labels.has(c.block) && !byLabel.has(c.block)) byLabel.set(c.block, c);
  const out: Checked[] = [];
  for (const [label, block] of labels) {
    const c = byLabel.get(label);
    if (!c) {
      out.push({
        block, repaired: false, missing: true, diagram: null,
        payload: { cue: '', category: null, her_words: block.transcript.trim().slice(0, 400), practice_flag: null,
                   take_id: block.take_id, song_id: null, bar_start: null, bar_end: null },
      });
      continue;
    }
    const words = verbatim(c.her_words ?? '', block.transcript);
    let bs = Number.isInteger(c.bar_start) && (c.bar_start as number) > 0 ? c.bar_start : null;
    let be = Number.isInteger(c.bar_end) && (c.bar_end as number) > 0 ? c.bar_end : null;
    if (bs == null && be != null) bs = be;
    if (bs != null && be == null) be = bs;
    if (bs != null && be != null && be < bs) [bs, be] = [be, bs];
    const category = (CATEGORIES as readonly string[]).includes(c.category) ? c.category : null;
    out.push({
      block, repaired: words.repaired, missing: false, diagram: diagramPick(c, category),
      payload: {
        cue: category === 'chat' ? '' : (c.cue ?? '').trim().slice(0, 200),
        category,
        her_words: words.text,
        practice_flag: c.practice_flag === 'practice_this' || c.practice_flag === 'just_listen' ? c.practice_flag : null,
        take_id: block.take_id,
        song_id: null,
        bar_start: bs,
        bar_end: be,
      },
    });
  }
  return out;
}

/** True when two card payloads say the same thing (so a rerun adds no new version). */
export function samePayload(a: Partial<CardPayload> | null | undefined, b: CardPayload): boolean {
  if (!a) return false;
  const keys: (keyof CardPayload)[] = ['cue', 'category', 'her_words', 'practice_flag', 'take_id', 'song_id', 'bar_start', 'bar_end'];
  return keys.every((k) => (a[k] ?? null) === (b[k] ?? null));
}

// ----------------------------------------------------------------- Cost

/** US dollars per million tokens (input, output), first-party API prices. */
const PRICES: Record<string, [number, number]> = {
  'claude-fable-5-2': [8, 40],
  'claude-opus-5-5': [4, 20],
  'claude-sonnet-5-5': [2, 10],
  'claude-haiku-5-5': [0.10, 0.50],
};

export function costUsd(model: string, input: number, output: number): number | null {
  const p = PRICES[model];
  if (!p) return null;
  return Math.round(((input * p[0] + output * p[1]) / 1e6) * 1e5) / 1e5;
}

/** Server-side refusal fallbacks exist for these models only; Haiku 5.5 has none. */
export function supportsFallback(model: string): boolean {
  return ['claude-fable-5-2', 'claude-opus-5-5', 'claude-sonnet-5-5'].some((m) => model.startsWith(m));
}
