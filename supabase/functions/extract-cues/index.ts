// extract-cues: turns a lesson's feedback blocks into cue cards.
//
// The phone calls this after it has uploaded the lesson, its takes and the
// clips. The body carries each feedback block (span, take link, the teacher's
// transcribed words) and the lesson's transcript; see Request in cues.ts.
// Only text goes to the model, never audio.
//
// For each block the function writes the feedback row and, when the card
// changed, an "ai" card_version, all with the service role. A card a teacher
// has already edited keeps her version: only the phone's measurements on it
// (span, transcript, clip) are refreshed. Each run is logged in
// public.cue_extraction with its token use and cost.
//
// Secrets (supabase secrets set ...):
//   ANTHROPIC_API_KEY  the Claude API key
//   CUE_MODEL          optional, default claude-opus-5-5
//   CUE_EFFORT         optional, low | medium | high, default low
//   DIAGRAM_MIN_CONFIDENCE  optional, 0..1, default 0.7: the bar for
//                      suggesting a diagram on a card (diagram_suggestion)
// With "trial_models" in the body (any of TRIAL_MODELS), it runs the lesson
// through each of those models and returns their cards side by side without
// writing any; each run is still logged, marked trial, with its cost.
//
// SUPABASE_URL, SUPABASE_ANON_KEY and SUPABASE_SERVICE_ROLE_KEY come from Supabase.
import Anthropic from 'npm:@anthropic-ai/sdk@1.2.0';
import { createClient } from 'npm:@supabase/supabase-js@2';
import {
  ANSWER_SCHEMA, batchesByExercise, check, costUsd, DIAGRAM_MIN_CONFIDENCE, examplesText, exerciseTitles, invalidRequest, labelBlocks,
  labelExercises, lessonText, samePayload, supportsFallback, SYSTEM_PROMPT,
  type Block, type CardPayload, type Checked, type Correction, type ModelCard, type ModelExercise, type Request, type TakeRow,
  type ExerciseRow,
} from './cues.ts';

const MODEL = Deno.env.get('CUE_MODEL') || 'claude-opus-5-5';
const EFFORT = (Deno.env.get('CUE_EFFORT') || 'low') as 'low' | 'medium' | 'high';
/** Blocks per model call. Each card now carries a summary too, so calls are kept short. */
const BATCH = Number(Deno.env.get('CUE_BATCH') || 8);
const DIAGRAM_BAR = Number(Deno.env.get('DIAGRAM_MIN_CONFIDENCE') || DIAGRAM_MIN_CONFIDENCE);

const json = (body: unknown, status = 200) => Response.json(body, { status });

interface Usage { input: number; output: number }

/** One model call for a batch of blocks. Returns its cards and token use. */
async function ask(client: Anthropic, model: string, lesson: string, examples: string):
    Promise<{ cards: ModelCard[]; exercises: ModelExercise[]; usage: Usage }> {
  const content = [examples, `The lesson:\n\n${lesson}`].filter(Boolean).join('\n\n---\n\n');
  const params = {
    model,
    max_tokens: 32000,
    system: SYSTEM_PROMPT,
    messages: [{ role: 'user' as const, content }],
    output_config: { effort: EFFORT, format: { type: 'json_schema' as const, schema: ANSWER_SCHEMA } },
  };
  // Fable 5.2, Opus 5.5 and Sonnet 5.5 can hand a declined request to another model on the
  // server; Haiku 5.5 has no such fallback.
  const msg = supportsFallback(model)
    ? await client.beta.messages.stream({ ...params, betas: ['server-side-fallback-2026-07-01'], fallbacks: 'default' }).finalMessage()
    : await client.messages.stream(params).finalMessage();
  const usage = {
    input: (msg.usage.input_tokens ?? 0) + (msg.usage.cache_read_input_tokens ?? 0) + (msg.usage.cache_creation_input_tokens ?? 0),
    output: msg.usage.output_tokens ?? 0,
  };
  if (msg.stop_reason === 'refusal') throw Object.assign(new Error('the model declined this lesson'), { usage });
  if (msg.stop_reason === 'max_tokens') throw Object.assign(new Error('the answer was cut off (max_tokens)'), { usage });
  const blocks = msg.content as Array<{ type: string; text?: string }>;
  const text = blocks.map((b) => (b.type === 'text' ? b.text ?? '' : '')).join('');
  let answer: { cards?: ModelCard[]; exercises?: ModelExercise[] };
  try {
    answer = JSON.parse(text);
  } catch {
    throw Object.assign(new Error('the answer was not valid JSON'), { usage });
  }
  return { cards: answer.cards ?? [], exercises: answer.exercises ?? [], usage };
}

Deno.serve(async (req) => {
  if (req.method !== 'POST') return json({ error: 'POST only' }, 405);
  const url = Deno.env.get('SUPABASE_URL')!;
  const auth = req.headers.get('Authorization') ?? '';

  // Who is calling: the student whose lesson this is.
  const asUser = createClient(url, Deno.env.get('SUPABASE_ANON_KEY')!, { global: { headers: { Authorization: auth } } });
  const { data: who, error: whoErr } = await asUser.auth.getUser();
  if (whoErr || !who?.user) return json({ error: 'sign in first' }, 401);

  let body: Request;
  try {
    body = await req.json();
  } catch {
    return json({ error: 'body must be JSON' }, 400);
  }
  const bad = invalidRequest(body);
  if (bad) return json({ error: bad }, 400);

  const db = createClient(url, Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!);
  const { data: lesson } = await db.from('lesson').select('id, student_id').eq('id', body.lesson_id).maybeSingle();
  if (!lesson || lesson.student_id !== who.user.id) return json({ error: 'lesson not found' }, 404);

  const { data: takeRows, error: takeErr } = await db.from('take').select('id, start_ms, end_ms').eq('lesson_id', lesson.id);
  if (takeErr) return json({ error: takeErr.message }, 500);
  const takes = (takeRows ?? []) as TakeRow[];
  const takeIds = new Set(takes.map((t) => t.id));
  // A card a teacher deleted stays deleted when the notes are made again.
  const { data: removedRows } = await db.from('feedback_removed').select('feedback_id').eq('lesson_id', lesson.id);
  const removed = new Set((removedRows ?? []).map((r) => r.feedback_id as string));
  const { data: exRows, error: exErr } = await db.from('exercise').select('id, start_ms, kind, title, title_by_model').eq('lesson_id', lesson.id);
  if (exErr) return json({ error: exErr.message }, 500);
  const exercises = (exRows ?? []) as ExerciseRow[];
  const exIds = new Set(exercises.map((e) => e.id));
  const exLabels = labelExercises(exercises);
  // A link to a take or exercise that never uploaded would break the foreign key.
  const blocks: Block[] = body.blocks.filter((b) => !removed.has(b.id)).map((b) => ({
    ...b,
    take_id: b.take_id && takeIds.has(b.take_id) ? b.take_id : null,
    exercise_id: b.exercise_id && exIds.has(b.exercise_id) ? b.exercise_id : null,
  }));

  const log = async (row: Record<string, unknown>, model = MODEL) => {
    await db.from('cue_extraction').insert({ lesson_id: lesson.id, model, ...row });
  };

  // ---------------------------------------------------------------- ask the model
  const labels = labelBlocks(blocks);
  const { data: past } = await db.rpc('cue_corrections_for', { student: lesson.student_id, max_rows: 8 });
  const examples = examplesText((past ?? []) as Correction[]);

  const apiKey = Deno.env.get('ANTHROPIC_API_KEY');
  if (!apiKey) {
    await log({ error: 'ANTHROPIC_API_KEY is not set' });
    return json({ error: 'cue extraction is not set up yet (ANTHROPIC_API_KEY)' }, 503);
  }
  // The phone gives up at 150 s and so does the edge runtime: answer (or fail
  // with a reason) well before that. Smaller batches run side by side keep
  // each call short; the phone retries the whole lesson if one fails.
  const client = new Anthropic({ apiKey, timeout: 110_000, maxRetries: 0 });

  // Blocks with nothing said need no model call.
  const spoken = [...labels].filter(([, b]) => b.transcript.trim()).map(([l]) => l);

  /** The whole lesson through one model: its cards and token use, or why it failed. */
  const run = async (model: string): Promise<{ cards: ModelCard[]; titles: Map<string, string>; usage: Usage; error?: string }> => {
    const usage: Usage = { input: 0, output: 0 };
    let cards: ModelCard[] = [];
    let named: ModelExercise[] = [];
    try {
      // One exercise per call where it fits, so a point she keeps coming back to gets one cue.
      const answers = await Promise.all(batchesByExercise(labels, spoken, BATCH).map((group) =>
        ask(client, model, lessonText(labels, takes, body.transcript, new Set(group), exercises), examples)));
      for (const a of answers) {
        cards = cards.concat(a.cards);
        named = named.concat(a.exercises);
        usage.input += a.usage.input;
        usage.output += a.usage.output;
      }
      return { cards, titles: exerciseTitles(exLabels, named), usage };
    } catch (e) {
      const u = (e as { usage?: Usage }).usage;
      if (u) { usage.input += u.input; usage.output += u.output; }
      const error = e instanceof Anthropic.APIError ? `${e.status ?? ''} ${e.message}`.trim() : String((e as Error).message ?? e);
      return { cards: [], titles: new Map(), usage, error };
    }
  };

  // ---------------------------------------------------------------- trial: compare models, write no cards
  if (body.trial_models?.length) {
    const trials = await Promise.all(body.trial_models.map(async (model) => {
      const r = await run(model);
      const cost = costUsd(model, r.usage.input, r.usage.output);
      await log({ input_tokens: r.usage.input, output_tokens: r.usage.output, cost_usd: cost, trial: true, error: r.error ?? null }, model);
      const checked = r.error ? [] : check(labels, r.cards);
      return {
        model, cost_usd: cost, input_tokens: r.usage.input, output_tokens: r.usage.output, error: r.error ?? null,
        exercises: [...r.titles].map(([id, title]) => ({ id, title })),
        cards: checked.map((c) => ({
          id: c.block.id, cue: c.payload.cue, her_words: c.payload.her_words, summary: c.summary, weight: c.weight,
          category: c.payload.category,
          practice_flag: c.payload.practice_flag, bar_start: c.payload.bar_start, bar_end: c.payload.bar_end,
          repaired: c.repaired, missing: c.missing,
        })),
      };
    }));
    return json({ trials });
  }

  const { cards, titles, usage, error } = await run(MODEL);
  if (error) {
    await log({ input_tokens: usage.input, output_tokens: usage.output, cost_usd: costUsd(MODEL, usage.input, usage.output), error });
    return json({ error: `cue extraction failed: ${error}` }, 502);
  }
  const checked = check(labels, cards);

  // ---------------------------------------------------------------- write the cards
  const ids = blocks.map((b) => b.id);
  const { data: existing } = await db.from('feedback').select('id, audio_removed_at, summary_check').eq('lesson_id', lesson.id);
  // Audio a teacher removed isn't linked again; the note stays.
  const muted = new Set((existing ?? []).filter((r) => r.audio_removed_at).map((r) => r.id as string));
  const { data: versions } = await db.from('card_version')
    .select('feedback_id, author, payload, created_at, id')
    .in('feedback_id', (existing ?? []).map((r) => r.id))
    .order('created_at', { ascending: true });
  const edited = new Set((versions ?? []).filter((v) => v.author === 'teacher').map((v) => v.feedback_id as string));
  const latest = new Map<string, Partial<CardPayload>>();
  for (const v of versions ?? []) latest.set(v.feedback_id as string, v.payload as Partial<CardPayload>);

  const measured = (c: Checked) => ({
    id: c.block.id,
    lesson_id: lesson.id,
    local_id: c.block.local_id,
    span_start_ms: c.block.span_start_ms,
    span_end_ms: c.block.span_end_ms,
    clip_path: muted.has(c.block.id) ? null : c.block.clip_path,
    transcript: c.block.transcript,
    link_confidence: c.block.link_confidence,
    exercise_id: c.block.exercise_id ?? null,
    weight: c.weight,
  });
  // The writer's reading of her point. A summary a teacher wrote, or answered
  // "Did you say this?" about, stays as she left it.
  const answered = new Set((existing ?? []).filter((r) => r.summary_check).map((r) => r.id as string));
  const keepsSummary = (id: string) => answered.has(id) || 'summary' in (latest.get(id) ?? {});

  const fresh = checked.filter((c) => !edited.has(c.block.id));
  const kept = checked.filter((c) => edited.has(c.block.id));
  if (fresh.length) {
    const { error } = await db.from('feedback').upsert(fresh.map((c) => ({
      ...measured(c),
      ...(keepsSummary(c.block.id) ? {} : { summary: c.summary }),
      take_id: c.payload.take_id,
      cue: c.payload.cue,
      category: c.payload.category,
      her_words: c.payload.her_words,
      practice_flag: c.payload.practice_flag,
      bar_start: c.payload.bar_start,
      bar_end: c.payload.bar_end,
    })));
    if (error) return json({ error: error.message }, 500);
    const newVersions = fresh.filter((c) => !samePayload(latest.get(c.block.id), c.payload))
      .map((c) => ({ feedback_id: c.block.id, author: 'ai', payload: c.payload }));
    if (newVersions.length) {
      const { error: vErr } = await db.from('card_version').insert(newVersions);
      if (vErr) return json({ error: vErr.message }, 500);
    }
  }
  // Her edits stand; only what the phone measured is refreshed.
  for (const c of kept) {
    const row = keepsSummary(c.block.id) ? measured(c) : { ...measured(c), summary: c.summary };
    const { error } = await db.from('feedback').update(row).eq('id', c.block.id);
    if (error) return json({ error: error.message }, 500);
  }
  // Blocks the phone no longer finds (it processed the lesson again) go,
  // unless a teacher has worked on them.
  const stale = (existing ?? []).map((r) => r.id as string).filter((id) => !ids.includes(id) && !edited.has(id));
  if (stale.length) await db.from('feedback').delete().in('id', stale);

  // "Does this diagram help?": one per card at most, only above the bar. A
  // suggestion already there keeps its decisions; a rerun adds no copy.
  const picks = checked.filter((c) => c.diagram && c.diagram.confidence >= DIAGRAM_BAR).map((c) => ({
    feedback_id: c.block.id, diagram_id: c.diagram!.diagram_id, confidence: c.diagram!.confidence, source: 'cues',
  }));
  if (picks.length) {
    const { error } = await db.from('diagram_suggestion').upsert(picks, { onConflict: 'feedback_id,diagram_id', ignoreDuplicates: true });
    if (error) return json({ error: error.message }, 500);
  }

  // Each exercise gets the model's name, unless a person named it. A name the model gave
  // before is replaced, so making the notes again can fix a song it got wrong.
  let named = 0;
  for (const e of exercises) {
    const title = titles.get(e.id);
    const had = e as ExerciseRow & { title?: string | null; title_by_model?: boolean };
    if (!title || (had.title && !had.title_by_model) || title === had.title) continue;
    const { error } = await db.from('exercise').update({ title, title_by_model: true }).eq('id', e.id);
    if (error) return json({ error: error.message }, 500);
    named++;
  }

  await db.from('lesson').update({ pipeline_state: 'cards' }).eq('id', lesson.id);
  const cost = costUsd(MODEL, usage.input, usage.output);
  await log({ input_tokens: usage.input, output_tokens: usage.output, cost_usd: cost, cards: fresh.length, kept_teacher: kept.length });

  return json({
    cards: checked.length,
    written: fresh.length,
    kept_teacher: kept.length,
    removed: stale.length,
    diagrams_suggested: picks.length,
    exercises_named: named,
    repaired_quotes: checked.filter((c) => c.repaired).length,
    skipped_by_model: checked.filter((c) => c.missing && c.block.transcript.trim()).length,
    model: MODEL,
    input_tokens: usage.input,
    output_tokens: usage.output,
    cost_usd: cost,
  });
});
