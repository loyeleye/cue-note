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
  /** What other teachers call this teacher unless name_public is on. */
  alias?: string;
  color?: TeacherColor;
  name_public?: boolean;
}

export const TEACHER_COLORS = ['blue', 'teal', 'green', 'amber', 'coral', 'rose', 'violet', 'indigo'] as const;
export type TeacherColor = (typeof TEACHER_COLORS)[number];

/**
 * One of a student's teachers, as list_teachers() shows them to the viewer.
 * display_name is null when the viewer may not see it: another teacher who
 * keeps their real name private.
 */
export interface Teacher {
  id: string;
  alias: string;
  color: TeacherColor;
  name_public: boolean;
  display_name: string | null;
}

/** A named version of a whole lesson: a moment in its history. */
export interface Checkpoint {
  id: string;
  lesson_id: string;
  label: string;
  created_by: string | null;
  created_at: string;
}

export interface Share {
  id: string;
  student_id: string;
  invite_email: string;
  teacher_id: string | null;
  status: 'pending' | 'active' | 'revoked';
  next_lesson_at: string | null;
  created_at: string;
  /** The teacher's own choice: list only the lessons they taught. */
  show_only_taught?: boolean;
  /** Set by the student: this teacher can open only the lessons they taught. */
  restricted_to_taught?: boolean;
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
  /** The teacher who taught it, picked on the phone or claimed later. */
  taught_by?: string | null;
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
  /** Everything she said in the block, as the phone transcribed it (M3). */
  transcript?: string;
  /** Set once a teacher removed the card's audio; the note stays. */
  audio_removed_at?: string | null;
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
  /** Set when this version was made by restoring an older one. */
  restored_from?: string | null;
}

export interface PhotoMark {
  id: string;
  lesson_id: string;
  taken_at_ms: number;
  feedback_id: string | null;
  /** caption-sketch's one line, and how well the sketch matches her words, 0..1. */
  caption: string | null;
  confidence?: number | null;
  /** The photo as taken. */
  image_path: string | null;
  /** The straightened, whitened copy the phone made; null until then. */
  cleaned_path?: string | null;
  /** The card the phone guessed by time, kept after any move. */
  auto_feedback_id?: string | null;
  /** Who put it on its current card. */
  link_source?: 'auto' | 'student' | 'teacher';
}

/** Where a side stands on a suggested diagram. */
export type DiagramDecision = 'open' | 'accepted' | 'dismissed';

/**
 * "Does this diagram help?": a diagram from the library (static/diagrams)
 * the AI matched to a card. A teacher's dismissal hides it for everyone; a
 * student's only for the student.
 */
export interface DiagramSuggestion {
  id: string;
  feedback_id: string;
  diagram_id: string;
  /** How sure the AI was, 0..1. Only 0.7 and up are saved, to start. */
  confidence: number;
  /** Picked from her words when the cards were made, or from her sketch. */
  source: 'cues' | 'sketch';
  teacher_status: DiagramDecision;
  student_status: DiagramDecision;
  created_at: string;
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
