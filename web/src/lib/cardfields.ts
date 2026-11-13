// How each card field is named and shown, shared by the history, the
// compare view and the clash dialog.
import { bars, clock, FLAG_LABEL } from './format';
import type { CardFields, Song, Take } from './types';
import { CARD_FIELDS } from './types';

export const FIELD_LABEL: Record<keyof CardFields, string> = {
  cue: 'Cue', summary: 'Summary', her_words: "Teacher's words", category: 'Category', practice_flag: 'Practice',
  take_id: 'Take', song_id: 'Song', bar_start: 'Bars', bar_end: 'Bars'
};

/** Fields shown as running text, where a word-by-word comparison helps. */
export const TEXT_FIELDS: (keyof CardFields)[] = ['cue', 'summary', 'her_words'];

export function showField(k: keyof CardFields, p: Partial<CardFields>, takes: Take[], songs: Song[]): string {
  switch (k) {
    case 'practice_flag': return p.practice_flag ? FLAG_LABEL[p.practice_flag] : 'Neither';
    case 'take_id': {
      const i = takes.findIndex((t) => t.id === p.take_id);
      return i < 0 ? 'No take' : `Take ${i + 1} at ${clock(takes[i].start_ms)}`;
    }
    case 'song_id': return songs.find((s) => s.id === p.song_id)?.title ?? 'None';
    case 'bar_start': case 'bar_end': return bars(p.bar_start ?? null, p.bar_end ?? null) || 'None';
    default: return String(p[k] ?? '') || '(empty)';
  }
}

export interface FieldChange {
  key: keyof CardFields;
  label: string;
  before: string;
  after: string;
}

/** The fields that differ between two versions, bars counted once. */
export function changedFields(before: Partial<CardFields>, after: Partial<CardFields>, takes: Take[], songs: Song[]): FieldChange[] {
  const out: FieldChange[] = [];
  const seen = new Set<string>();
  for (const k of CARD_FIELDS) {
    if (!(k in after) && !(k in before)) continue;
    if ((before[k] ?? null) === (after[k] ?? null)) continue;
    const label = FIELD_LABEL[k];
    if (seen.has(label)) continue;
    seen.add(label);
    out.push({ key: k, label, before: showField(k, before, takes, songs), after: showField(k, after, takes, songs) });
  }
  return out;
}
