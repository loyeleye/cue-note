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
  /** A debug account: sees every student, read-only. For testing; removed before release. */
  debug?: boolean;
}

/**
 * Another teacher would like to see the lessons this teacher taught a
 * student (the student let them "see all"). Only this teacher can answer.
 */
export interface LessonViewRequest {
  id: string;
  student_id: string;
  student_name: string;
  viewer_id: string;
  viewer_alias: string;
  status: 'pending' | 'granted' | 'declined';
  created_at: string;
  decided_at: string | null;
}

/**
 * The whole of one lesson, opened to one teacher for 14 days once both say
 * yes. By default a teacher hears only the takes on "Practice this" cards and
 * what the student shares; the rest of the lesson is the student's.
 */
export interface FullLessonPass {
  id: string;
  lesson_id: string;
  student_id: string;
  teacher_id: string;
  /** Who asked first; the other person answers. */
  asked_by: 'student' | 'teacher';
  status: 'pending' | 'active' | 'declined' | 'ended';
  note: string | null;
  created_at: string;
  decided_at: string | null;
  ends_at: string | null;
  ended_at: string | null;
}

/** Where a pass stands now: an active one past its end date has ended. */
export function passState(p: FullLessonPass, now = Date.now()): FullLessonPass['status'] {
  if (p.status === 'active' && p.ends_at && Date.parse(p.ends_at) <= now) return 'ended';
  return p.status;
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
  /** Shared takes not heard yet and open questions, for the next lesson. */
  waitingTakes?: number;
  openQuestions?: number;
}

export interface Lesson {
  id: string;
  student_id: string;
  recorded_at: string;
  duration_ms: number | null;
  pipeline_state: string;
  /** The teacher who taught it, picked on the phone or claimed later. */
  taught_by?: string | null;
  /** A teacher not on Cue Note, named by the student instead (a guest, say). Set only without taught_by. */
  teacher_name?: string | null;
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
  /** The warmup or song section this take is one try of. */
  exercise_id?: string | null;
}

/**
 * One warmup, or one section of a song: the student's tries and her feedback
 * in between, from setting it up to moving on. Found by the phone.
 */
export interface Exercise {
  id: string;
  lesson_id: string;
  start_ms: number;
  end_ms: number;
  kind: 'warmup' | 'song';
  title: string | null;
  /** Why the phone started a new exercise here. */
  reason: string;
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
  /** The exercise it is part of, when the phone grouped the lesson. */
  exercise_id?: string | null;
  /** One or two sentences to the student: what she meant. Empty until made. */
  summary?: string;
  /** How much the point is worth drilling, 0..1, from the card writer. */
  weight?: number | null;
  /** A teacher's answer to "Did you say this?" about the summary. */
  summary_check?: SummaryCheck | null;
  summary_checked_by?: string | null;
  summary_checked_at?: string | null;
  /** The summary a teacher said she didn't say, kept for the tally. */
  summary_rejected?: string | null;
  /** What she meant, in her words (any length), with her answer. */
  summary_note?: string | null;
  /** A voice note saying what she meant: clips/<student>/teacher-notes/... */
  summary_note_clip?: string | null;
}

/** Yes, close enough (the summary stays, marked close), or wrong (it comes off the student's card). */
export type SummaryCheck = 'said' | 'close' | 'not_said';

/** How the summaries the teacher can see have been checked. */
export interface SummaryTally {
  said: number;
  close: number;
  not_said: number;
  unchecked: number;
  lessons: number;
}

/** The fields a teacher may change on a card; also what a version stores. */
export interface CardFields {
  cue: string;
  summary: string;
  category: string | null;
  her_words: string;
  practice_flag: PracticeFlag | null;
  take_id: string | null;
  song_id: string | null;
  bar_start: number | null;
  bar_end: number | null;
}
export const CARD_FIELDS: (keyof CardFields)[] = [
  'cue', 'summary', 'her_words', 'category', 'practice_flag', 'take_id', 'song_id', 'bar_start', 'bar_end'
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

/** One sitting of practice, as the student's phone logged it. No audio. */
export interface PracticeSession {
  id: string;
  student_id: string;
  started_at: string;
  ended_at: string;
  minutes: number;
  /** Each note practiced in the sitting, and how many goes. */
  items: { feedback_id: string; takes: number }[];
}

export type NoteVerdict = 'IN_TUNE' | 'FLAT' | 'SHARP' | 'UNCLEAR' | 'NO_TARGET';

/** A practice take the student shared before the next lesson. */
export interface SharedTake {
  id: string;
  student_id: string;
  feedback_id: string | null;
  lesson_id: string;
  session_id: string | null;
  recorded_at: string;
  duration_ms: number;
  clip_path: string | null;
  /** One row per note sung, against the demo when there was one. */
  pitch: { sung: number; target?: number | null; verdict: NoteVerdict }[] | null;
  feeling_note: string | null;
  shared_at: string;
  heard_at: string | null;
  heard_by: string | null;
  /** The teacher's reply, in words and/or a voice note (clips/<student>/teacher-notes/...). */
  reply?: string | null;
  reply_clip_path?: string | null;
  replied_by?: string | null;
  replied_at?: string | null;
}

export type QuestionStatus = 'open' | 'answered' | 'dropped';

/** A question the student asked for the next lesson. */
export interface StudentQuestion {
  id: string;
  student_id: string;
  feedback_id: string | null;
  lesson_id: string | null;
  song_id: string | null;
  bar: number | null;
  body: string;
  status: QuestionStatus;
  answer: string | null;
  /** A voice answer: clips/<student>/teacher-notes/... */
  answer_clip_path?: string | null;
  answered_by: string | null;
  answered_at: string | null;
  created_at: string;
}

/** Everything for the next lesson's agenda. */
export interface Agenda {
  sessions: PracticeSession[];
  takes: SharedTake[];
  questions: StudentQuestion[];
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
