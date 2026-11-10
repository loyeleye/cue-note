// Tests for the model-free half of extract-cues.
// Run: node --experimental-strip-types --test supabase/functions/extract-cues/cues.test.ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  ANSWER_SCHEMA, batches, CATEGORIES, check, costUsd, examplesText, invalidRequest, labelBlocks, lessonText,
  samePayload, supportsFallback, verbatim, type ModelCard, type Request, type TakeRow,
} from './cues.ts';

// A short test lesson in the shape the phone sends: an opening chat, two
// takes with feedback, and a block with bar numbers.
const sample = JSON.parse(readFileSync(new URL('./fixtures/sample-lesson.json', import.meta.url), 'utf8')) as {
  request: Request;
  takes: TakeRow[];
  answer: { cards: ModelCard[] };
};

test('the sample request is valid', () => {
  assert.equal(invalidRequest(sample.request), null);
});

test('bad requests say why', () => {
  assert.match(invalidRequest(null)!, /object/);
  assert.match(invalidRequest({ ...sample.request, lesson_id: 'x' })!, /lesson_id/);
  const twice = { ...sample.request, blocks: [sample.request.blocks[0], sample.request.blocks[0]] };
  assert.match(invalidRequest(twice)!, /twice/);
  const backwards = { ...sample.request, blocks: [{ ...sample.request.blocks[0], span_end_ms: 0 }] };
  assert.match(invalidRequest(backwards)!, /span/);
  const who = { ...sample.request, transcript: [{ speaker: 'piano', start_ms: 0, end_ms: 1, text: '' }] };
  assert.match(invalidRequest(who)!, /transcript/);
});

test('blocks are labeled in time order', () => {
  const shuffled = [...sample.request.blocks].reverse();
  const labels = labelBlocks(shuffled);
  assert.deepEqual([...labels.keys()], ['B1', 'B2', 'B3', 'B4']);
  assert.equal(labels.get('B1')!.local_id.endsWith('fb-001'), true);
});

test('the lesson text shows takes, both speakers and demos, and no uuids', () => {
  const labels = labelBlocks(sample.request.blocks);
  const text = lessonText(labels, sample.takes, sample.request.transcript);
  assert.match(text, /B1 at 0:02, not after a take/);
  assert.match(text, /B2 at 1:10, after take T1 \(the student sang 0:40–1:08\), she sings 1 demo/);
  assert.match(text, /Student: was that flat\?/);
  assert.match(text, /Teacher: A little\./);
  assert.doesNotMatch(text, /[0-9a-f]{8}-[0-9a-f]{4}-/);
  const some = lessonText(labels, sample.takes, sample.request.transcript, new Set(['B3']));
  assert.match(some, /^B3 /);
  assert.doesNotMatch(some, /B2/);
});

test('her words are kept when they are hers, and replaced with her sentence when not', () => {
  const said = 'Lovely. But you pushed the top. Let the jaw drop and think of a yawn, okay?';
  assert.deepEqual(verbatim('let the jaw drop and think of a yawn', said), { text: 'let the jaw drop and think of a yawn', repaired: false });
  // Different punctuation and case still count as her words.
  assert.equal(verbatim('Let the jaw drop, and think of a yawn!', said).repaired, false);
  const fixed = verbatim('Relax your jaw like yawning', said);
  assert.equal(fixed.repaired, true);
  assert.equal(fixed.text, 'Let the jaw drop and think of a yawn, okay?');
  assert.deepEqual(verbatim('anything', ''), { text: '', repaired: true });
});

test('the model answer becomes one card per block', () => {
  const labels = labelBlocks(sample.request.blocks);
  const out = check(labels, sample.answer.cards);
  assert.equal(out.length, 4);
  const [hello, jaw, flat, bars] = out;
  assert.equal(hello.payload.category, 'chat');
  assert.equal(hello.payload.cue, '', 'chat cards have no cue');
  assert.equal(jaw.payload.cue, 'Drop the jaw on the top note');
  assert.equal(jaw.payload.take_id, sample.request.blocks[1].take_id);
  assert.equal(jaw.repaired, false);
  assert.equal(flat.repaired, true, 'a paraphrased quote is replaced with her sentence');
  assert.equal(flat.payload.her_words, 'Keep the breath moving through the end of the phrase so it doesn\'t sag.');
  assert.deepEqual([bars.payload.bar_start, bars.payload.bar_end], [9, 12], 'bars put in order');
  assert.equal(bars.payload.practice_flag, 'practice_this');
});

test('skipped, doubled and unknown blocks are handled', () => {
  const labels = labelBlocks(sample.request.blocks);
  const cards: ModelCard[] = [
    { ...sample.answer.cards[1], block: 'B2' },
    { ...sample.answer.cards[1], block: 'B2', cue: 'second answer' },
    { ...sample.answer.cards[1], block: 'B9' },
    { ...sample.answer.cards[3], block: 'B4', bar_start: null, bar_end: 0, category: 'nonsense' as never },
  ];
  const out = check(labels, cards);
  assert.equal(out.length, 4);
  assert.equal(out[0].missing, true);
  assert.equal(out[0].payload.her_words, sample.request.blocks[0].transcript);
  assert.equal(out[1].payload.cue, 'Drop the jaw on the top note', 'the first answer wins');
  assert.equal(out[2].missing, true);
  assert.deepEqual([out[3].payload.bar_start, out[3].payload.bar_end], [null, null], 'bar 0 is not a bar');
  assert.equal(out[3].payload.category, null, 'unknown categories are dropped');
});

test('a rerun with the same answer adds no version', () => {
  const labels = labelBlocks(sample.request.blocks);
  const [, jaw] = check(labels, sample.answer.cards);
  assert.equal(samePayload({ ...jaw.payload }, jaw.payload), true);
  assert.equal(samePayload({ ...jaw.payload, cue: 'other' }, jaw.payload), false);
  assert.equal(samePayload(null, jaw.payload), false);
});

test('past corrections become short examples', () => {
  assert.equal(examplesText([]), '');
  const t = examplesText([{ transcript: 'think of a yawn', ai: { cue: 'Relax' }, teacher: { cue: 'Yawn into it', category: 'resonance' } }]);
  assert.match(t, /Example 1\. She said: "think of a yawn"/);
  assert.match(t, /Her correction: \{"cue":"Yawn into it","category":"resonance"/);
});

test('long lessons are split into batches', () => {
  const n = Array.from({ length: 40 }, (_, i) => i);
  assert.deepEqual(batches(n).map((b) => b.length), [15, 15, 10]);
});

test('cost and fallbacks follow the model', () => {
  assert.equal(costUsd('claude-opus-5-5', 15_000, 3_000), 0.12);
  assert.equal(costUsd('claude-haiku-5-5', 15_000, 3_000), 0.003);
  assert.equal(costUsd('some-other-model', 1, 1), null);
  assert.equal(supportsFallback('claude-opus-5-5'), true);
  assert.equal(supportsFallback('claude-haiku-5-5'), false);
});

test('the answer schema follows the structured-output rules', () => {
  // Every object closes its properties and requires all of them.
  const walk = (s: any) => {
    if (s && typeof s === 'object') {
      if (s.type === 'object') {
        assert.equal(s.additionalProperties, false);
        assert.deepEqual([...s.required].sort(), Object.keys(s.properties).sort());
      }
      Object.values(s).forEach(walk);
    }
  };
  walk(ANSWER_SCHEMA);
  assert.deepEqual(ANSWER_SCHEMA.properties.cards.items.properties.category.enum, [...CATEGORIES]);
});
