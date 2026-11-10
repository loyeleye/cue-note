// Runs the extract-cues handler end to end against in-memory fakes of the
// database and the model, with the sample lesson the phone would send.
// Run: node --experimental-strip-types --import ./supabase/functions/extract-cues/test/loader.mjs \
//        --test supabase/functions/extract-cues/test/index.test.ts
import { beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { answers, behavior, calls } from './fake-anthropic.ts';
import { reset, rpcs, tables } from './fake-supabase.ts';

const sample = JSON.parse(readFileSync(new URL('../fixtures/sample-lesson.json', import.meta.url), 'utf8'));
const STUDENT = '6f1c2a3b-0000-4000-8000-00000000000a';
const env: Record<string, string> = { SUPABASE_URL: 'http://x', SUPABASE_ANON_KEY: 'a', SUPABASE_SERVICE_ROLE_KEY: 's', ANTHROPIC_API_KEY: 'k' };
let handler: (req: Request) => Promise<Response>;
(globalThis as any).Deno = { env: { get: (k: string) => env[k] }, serve: (h: typeof handler) => { handler = h; } };
await import('../index.ts');

const post = (body: unknown, user: string | null = STUDENT) => handler(new Request('http://x/extract-cues', {
  method: 'POST',
  headers: user ? { Authorization: `Bearer ${user}`, 'content-type': 'application/json' } : {},
  body: typeof body === 'string' ? body : JSON.stringify(body),
}));
const B = (n: number) => sample.request.blocks[n - 1].id as string;
const feedback = (id: string) => tables.feedback.find((f) => f.id === id)!;
const versions = (id: string) => tables.card_version.filter((v) => v.feedback_id === id);

beforeEach(() => {
  reset();
  calls.length = 0;
  behavior.stop = 'end_turn';
  answers.cards = structuredClone(sample.answer.cards);
  tables.lesson = [{ id: sample.request.lesson_id, student_id: STUDENT, pipeline_state: 'uploading' }];
  tables.take = structuredClone(sample.takes).map((t: any) => ({ ...t, lesson_id: sample.request.lesson_id }));
  env.ANTHROPIC_API_KEY = 'k';
});

test('a lesson becomes one card per block, with the AI version kept', async () => {
  const res = await post(sample.request);
  assert.equal(res.status, 200);
  const out = await res.json();
  assert.equal(out.cards, 4);
  assert.equal(out.repaired_quotes, 1);
  assert.equal(out.model, 'claude-opus-5-5');
  assert.equal(tables.feedback.length, 4);
  assert.equal(feedback(B(2)).cue, 'Drop the jaw on the top note');
  assert.equal(feedback(B(2)).take_id, sample.request.blocks[1].take_id);
  assert.equal(feedback(B(2)).transcript, sample.request.blocks[1].transcript);
  assert.equal(feedback(B(2)).local_id, sample.request.blocks[1].local_id);
  assert.equal(feedback(B(1)).cue, '');
  assert.equal(feedback(B(4)).bar_start, 9);
  assert.equal(tables.card_version.length, 4);
  assert.ok(tables.card_version.every((v) => v.author === 'ai'));
  assert.equal(tables.lesson[0].pipeline_state, 'cards');
  const [log] = tables.cue_extraction;
  assert.equal(log.cards, 4);
  assert.equal(log.cost_usd, 0.008);
  assert.equal(log.error, undefined);
  // One call, text only, with the refusal fallback on.
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0].betas, ['server-side-fallback-2026-07-01']);
  assert.equal(calls[0].fallbacks, 'default');
  assert.equal(calls[0].effort, 'low');
  assert.doesNotMatch(calls[0].content, /[0-9a-f]{8}-[0-9a-f]{4}-/);
});

test('her corrections go into the prompt as examples', async () => {
  rpcs.cue_corrections_for = () => [{ transcript: 'think of a yawn', ai: { cue: 'Relax' }, teacher: { cue: 'Yawn into it' } }];
  await post(sample.request);
  assert.match(calls[0].content, /Her correction: \{"cue":"Yawn into it"/);
});

test('running it again adds no versions', async () => {
  await post(sample.request);
  await post(sample.request);
  assert.equal(tables.card_version.length, 4);
  assert.equal(tables.feedback.length, 4);
});

test('a card she edited keeps her version; only the measurements change', async () => {
  await post(sample.request);
  Object.assign(feedback(B(2)), { cue: 'Yawn into the top note', category: 'resonance' });
  tables.card_version.push({ feedback_id: B(2), author: 'teacher', payload: { cue: 'Yawn into the top note' }, created_at: '999999' });
  answers.cards[1].cue = 'Something new from the model';
  const again = structuredClone(sample.request);
  again.blocks[1].transcript += ' Good.';
  again.blocks[1].span_end_ms += 1000;
  const out = await (await post(again)).json();
  assert.equal(out.kept_teacher, 1);
  assert.equal(feedback(B(2)).cue, 'Yawn into the top note');
  assert.equal(feedback(B(2)).category, 'resonance');
  assert.match(feedback(B(2)).transcript, /Good\.$/);
  assert.equal(feedback(B(2)).span_end_ms, 111000);
  assert.equal(versions(B(2)).length, 2, 'no new AI version on her card');
});

test('blocks the phone no longer finds are removed, unless she worked on them', async () => {
  await post(sample.request);
  tables.card_version.push({ feedback_id: B(3), author: 'teacher', payload: {}, created_at: '999999' });
  const fewer = structuredClone(sample.request);
  fewer.blocks = fewer.blocks.slice(0, 2);
  const out = await (await post(fewer)).json();
  assert.equal(out.removed, 1);
  assert.deepEqual(tables.feedback.map((f) => f.id).sort(), [B(1), B(2), B(3)].sort());
});

test('a link to a take that never uploaded is dropped', async () => {
  tables.take = tables.take.slice(1);
  await post(sample.request);
  assert.equal(feedback(B(2)).take_id, null);
});

test('only the student can make their cards', async () => {
  assert.equal((await post(sample.request, null)).status, 401);
  assert.equal((await post(sample.request, '6f1c2a3b-0000-4000-8000-0000000000ff')).status, 404);
  assert.equal((await post('not json')).status, 400);
  assert.equal((await post({ ...sample.request, lesson_id: 'nope' })).status, 400);
  assert.equal(tables.feedback?.length ?? 0, 0);
});

test('a declined or unconfigured run writes nothing and says why', async () => {
  behavior.stop = 'refusal';
  const res = await post(sample.request);
  assert.equal(res.status, 502);
  assert.match((await res.json()).error, /declined/);
  assert.equal(tables.feedback?.length ?? 0, 0);
  assert.match(tables.cue_extraction[0].error, /declined/);
  assert.equal(tables.cue_extraction[0].input_tokens, 1000);

  delete env.ANTHROPIC_API_KEY;
  assert.equal((await post(sample.request)).status, 503);
});

test('a trial runs each model on the lesson and writes no cards', async () => {
  const res = await post({ ...sample.request, trial_models: ['claude-haiku-5-5', 'claude-opus-5-5', 'claude-fable-5-2'] });
  assert.equal(res.status, 200);
  const { trials } = await res.json();
  assert.deepEqual(trials.map((t: any) => t.model), ['claude-haiku-5-5', 'claude-opus-5-5', 'claude-fable-5-2']);
  assert.equal(trials[0].cards.length, 4);
  assert.equal(trials[0].cards[1].cue, 'Drop the jaw on the top note');
  assert.equal(trials[0].cards[1].id, B(2));
  assert.equal(trials[0].cost_usd, 0.0002);
  assert.equal(trials[2].cost_usd, 0.016);
  assert.equal(tables.feedback?.length ?? 0, 0, 'a trial writes no cards');
  assert.equal(tables.lesson[0].pipeline_state, 'uploading');
  assert.equal(tables.cue_extraction.length, 3);
  assert.ok(tables.cue_extraction.every((r) => r.trial === true));
  // Haiku has no server-side fallback; the others do.
  const haiku = calls.find((c) => c.model === 'claude-haiku-5-5')!;
  assert.equal(haiku.fallbacks, undefined);
  assert.equal(calls.find((c) => c.model === 'claude-fable-5-2')!.fallbacks, 'default');
});

test('a trial can only name known models', async () => {
  assert.equal((await post({ ...sample.request, trial_models: ['gpt-9'] })).status, 400);
});

test('confident diagram picks become suggestions, once, keeping her decision', async () => {
  const out = await (await post(sample.request)).json();
  assert.equal(out.diagrams_suggested, 2, 'the 0.62 pick is under the bar');
  const rows = tables.diagram_suggestion;
  assert.deepEqual(rows.map((r) => [r.feedback_id, r.diagram_id, r.source]).sort(),
    [[B(2), 'vowels-jaw', 'cues'], [B(4), 'line-legato', 'cues']].sort());
  rows[0].teacher_status = 'dismissed';
  await post(sample.request);
  assert.equal(tables.diagram_suggestion.length, 2, 'a rerun adds no copies');
  assert.equal(tables.diagram_suggestion[0].teacher_status, 'dismissed', 'her dismissal stands');
  assert.match(calls[0].system, /vowels-jaw: Jaw drop/);
});
