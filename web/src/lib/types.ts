// Shapes of the rows the dashboard reads and writes. They mirror the tables in
// supabase/migrations; keep the two in step when a migration changes a column.

export type Role = 'student' | 'teacher';
export type PracticeFlag = 'practice_this' | 'just_listen';
export type ScoreFormat = 'pdf' | 'image' | 'musicxml';
export type AnnotationKind = 'breath' | 'vowel' | 'dynamic' | 'text';

export interface Profile {
  id: string;
  role: Role;
  display_name: string;
}

export interface Share {
  id: string;
  student_id: string;
  invite_email: string;
  teacher_id: string | null;
  status: 'pending' | 'active' | 'revoked';
  next_lesson_at: string | null;
  created_at: string;
}

/** A student she teaches, with what the list needs to show. */
export interface StudentSummary {
  share: Share;
  student: Profile;
  lessonCount: number;
  lastLessonAt: string | null;
}

export interface Lesson {
  id: string;
  student_id: string;
  recorded_at: string;
  duration_ms: number | null;
  pipeline_state: string;
}

/**
 * Pitch for a take, written by the phone pipeline: one value per frame,
 * `hop_ms` apart, in Hz (null where no pitch was found). Confidence is 0..1
 * per frame; frames below the threshold show as "unclear", never as wrong.
 */
export interface PitchContour {
  hop_ms: number;
  hz: (number | null)[];
}

export interface Take {
  id: string;
  lesson_id: string;
  start_ms: number;
  end_ms: number;
  clip_path: string | null;
  pitch_contour: PitchContour | null;
  pitch_confidence: number[] | null;
  song_id: string | null;
  bar_start: number | null;
  bar_end: number | null;
}

export interface Feedback {
  id: string;
  lesson_id: string;
  take_id: string | null;
  span_start_ms: number;
  span_end_ms: number;
  clip_path: string | null;
  cue: string;
  category: string | null;
  her_words: string;
  practice_flag: PracticeFlag | null;
  song_id: string | null;
  bar_start: number | null;
  bar_end: number | null;
  resolved_at: string | null;
  updated_at: string;
}

/** The fields a teacher may change on a card; also what a version stores. */
export interface CardFields {
  cue: string;
  category: string | null;
  her_words: string;
  practice_flag: PracticeFlag | null;
  take_id: string | null;
  song_id: string | null;
  bar_start: number | null;
  bar_end: number | null;
}
export const CARD_FIELDS: (keyof CardFields)[] = [
  'cue', 'her_words', 'category', 'practice_flag', 'take_id', 'song_id', 'bar_start', 'bar_end'
];

export interface CardVersion {
  id: string;
  feedback_id: string;
  author: 'ai' | 'teacher';
  author_id: string | null;
  payload: Partial<CardFields>;
  created_at: string;
}

export interface PhotoMark {
  id: string;
  lesson_id: string;
  taken_at_ms: number;
  feedback_id: string | null;
  caption: string | null;
  image_path: string | null;
}

export interface Comment {
  id: string;
  student_id: string;
  lesson_id: string | null;
  author_id: string;
  body: string;
  clip_path: string | null;
  created_at: string;
}

export interface PracticeItem {
  id: string;
  student_id: string;
  lesson_id: string | null;
  feedback_id: string | null;
  author_id: string;
  body: string;
  due_week: string | null;
  created_at: string;
}

export interface Song {
  id: string;
  student_id: string;
  title: string;
  composer: string;
  created_at: string;
}

/** Where bar `bar` starts on page `page`; x and y are fractions of the page. */
export interface BarPosition {
  bar: number;
  page: number;
  x: number;
  y: number;
}

export interface Score {
  id: string;
  song_id: string;
  file_path: string;
  format: ScoreFormat;
  page_count: number | null;
  bar_map: BarPosition[];
}

export interface Annotation {
  id: string;
  score_id: string;
  bar: number;
  beat: number | null;
  kind: AnnotationKind;
  text: string;
  page: number;
  x: number;
  y: number;
  author_id: string;
}

export interface StorageRow {
  category: 'clips' | 'photos' | 'scores' | 'voice_notes';
  files: number;
  bytes: number;
}
