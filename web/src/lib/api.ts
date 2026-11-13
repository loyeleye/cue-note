// Every read and write the dashboard makes goes through this one interface.
// Two versions sit behind it: the real one talks to Supabase with her session,
// and the demo one keeps made-up sample data in the browser. Screens never know
// which one they have, so the demo exercises exactly the same screens.
import type { FullLessonPass, LessonViewRequest,
  Agenda, Annotation, PlanSetting, BarPosition, CardFields, CardVersion, Checkpoint, Comment, DiagramDecision, DiagramSuggestion, Exercise, Feedback,
  Lesson, PhotoMark,
  PracticeItem, Profile, Score, Share, Song, StorageRow, StudentSummary, SummaryCheck, SummaryTally, Take, Teacher
} from './types';

export type Bucket = 'clips' | 'photos' | 'scores';

export interface NewComment {
  student_id: string;
  lesson_id: string | null;
  body: string;
  voice?: Blob | null;
}

export interface NewPractice {
  student_id: string;
  lesson_id?: string | null;
  feedback_id?: string | null;
  body: string;
  due_week?: string | null;
}

export interface NewAnnotation {
  score_id: string;
  bar: number;
  kind: Annotation['kind'];
  text: string;
  page: number;
  x: number;
  y: number;
}

/** What a teacher can change about how they appear to others. */
export type IdentityPatch = Partial<Pick<Profile, 'display_name' | 'alias' | 'color' | 'name_public'>>;

export interface SaveOptions {
  /** The values the editor started from, for the fields being changed. */
  expected?: Partial<CardFields>;
  /** The version being restored, so the history can say so. */
  restoredFrom?: string;
}

/**
 * Someone else changed one of the same fields while this teacher was editing.
 * `theirs` holds the card's current values for those fields.
 */
export class EditConflict extends Error {
  constructor(
    readonly fields: (keyof CardFields)[],
    readonly theirs: Partial<CardFields>,
    readonly by: string | null,
    readonly at: string | null
  ) {
    super('Someone else changed this card while you were editing.');
  }
}

/** Another teacher with the same lesson open, and the card they're editing. */
export interface PresentTeacher {
  id: string;
  editing: string | null;
}

export interface LessonEvents {
  /** A card changed, by anyone, including this teacher in another tab. */
  onCard(f: Feedback): void;
  onVersion(v: CardVersion): void;
  onCheckpoints(): void;
  onPresence(present: PresentTeacher[]): void;
}

export interface LessonWatch {
  /** Tell the others which card this teacher is editing (null for none). */
  setEditing(cardId: string | null): void;
  stop(): void;
}

export interface Api {
  readonly demo: boolean;
  me(): Promise<Profile | null>;

  listStudents(): Promise<StudentSummary[]>;
  listInvites(): Promise<(Share & { student_name: string })[]>;
  acceptInvite(shareId: string): Promise<void>;
  /** Other teachers asking to see the lessons this teacher taught, newest first, pending ones on top. */
  listLessonViewRequests(): Promise<LessonViewRequest[]>;
  /** Yes or no to one of those; can be changed later. */
  decideLessonView(requestId: string, allow: boolean): Promise<void>;
  getStudent(studentId: string): Promise<{ student: Profile; share: Share } | null>;
  setNextLesson(shareId: string, at: string | null): Promise<void>;
  /** The teacher's "only my lessons" view setting for one student. */
  setShowOnlyTaught(shareId: string, on: boolean): Promise<void>;
  /** "This was my lesson" (mine = true), or take that mark off. */
  claimLesson(lessonId: string, mine: boolean): Promise<void>;
  /** Every teacher of this student, named as this viewer may see them. */
  listTeachers(studentId: string): Promise<Teacher[]>;
  updateMe(patch: IdentityPatch): Promise<Profile>;

  listLessons(studentId: string): Promise<Lesson[]>;
  getLesson(lessonId: string): Promise<Lesson | null>;
  listTakes(lessonId: string): Promise<Take[]>;
  /** The lesson's warmups and song sections, in order; empty for lessons processed before exercises. */
  listExercises(lessonId: string): Promise<Exercise[]>;
  listFeedback(lessonId: string): Promise<Feedback[]>;
  listPhotos(lessonId: string): Promise<PhotoMark[]>;
  /** Puts a sketch on another card of its lesson, or on none (null). */
  movePhoto(photoId: string, feedbackId: string | null): Promise<PhotoMark>;
  /** Suggested diagrams on a lesson's cards, dismissed ones included. */
  listDiagramSuggestions(lessonId: string): Promise<DiagramSuggestion[]>;
  /** The teacher's answer to "Does this diagram help?". */
  decideDiagram(suggestionId: string, decision: DiagramDecision): Promise<DiagramSuggestion>;
  /** Every card for a student, across lessons, newest lesson first. */
  listStudentFeedback(studentId: string): Promise<(Feedback & { recorded_at: string })[]>;
  listSongTakes(songId: string): Promise<Take[]>;

  listVersions(feedbackId: string): Promise<CardVersion[]>;
  /** Every version of every card in a lesson, newest first. */
  listLessonVersions(lessonId: string): Promise<CardVersion[]>;
  /**
   * Updates the card and appends a new version in one step. With
   * opts.expected, throws EditConflict instead of saving over someone else.
   */
  saveCard(feedbackId: string, changes: Partial<CardFields>, opts?: SaveOptions): Promise<Feedback>;
  /** Deletes a card and its clip for good; making the notes again won't bring it back. */
  removeCard(card: Feedback): Promise<void>;
  /** Deletes a card's clip but keeps the note. */
  removeCardAudio(card: Feedback): Promise<Feedback>;
  /**
   * The teacher's answer to "Did you say this?" about a card's summary. No
   * takes the summary off the student's card, so she sees the teacher's own words.
   */
  checkSummary(feedbackId: string, said: boolean): Promise<Feedback>;
  /**
   * Yes, close or wrong about a card's summary, with what she meant in words
   * and/or a voice note. A new voice Blob replaces the clip; clip keeps or
   * (null) drops the one there. Wrong takes the summary off the student's card;
   * moving back from wrong puts it back.
   */
  answerSummary(card: Feedback, studentId: string, verdict: SummaryCheck,
    explain?: { note?: string | null; clip?: string | null; voice?: Blob | null }): Promise<Feedback>;
  /** How the summaries this teacher can see have been answered, across lessons. */
  summaryTally(): Promise<SummaryTally>;

  listCheckpoints(lessonId: string): Promise<Checkpoint[]>;
  addCheckpoint(lessonId: string, label: string): Promise<Checkpoint>;
  renameCheckpoint(id: string, label: string): Promise<void>;
  deleteCheckpoint(id: string): Promise<void>;
  /** Puts every card back to how it was at the named version; returns how many changed. */
  restoreCheckpoint(id: string): Promise<number>;

  /** Live changes and who else is here, until stop() is called. */
  watchLesson(lessonId: string, events: LessonEvents): LessonWatch;

  listComments(studentId: string): Promise<Comment[]>;
  addComment(c: NewComment): Promise<Comment>;
  deleteComment(c: Comment): Promise<void>;

  listPractice(studentId: string): Promise<PracticeItem[]>;
  addPractice(p: NewPractice): Promise<PracticeItem>;
  updatePractice(id: string, patch: Partial<Pick<PracticeItem, 'body' | 'due_week'>>): Promise<void>;
  deletePractice(id: string): Promise<void>;

  /** The next lesson's agenda: the practice log, shared takes and questions, newest first. */
  getAgenda(studentId: string): Promise<Agenda>;
  /** This teacher's settings for the student's suggested practice, or null before any are saved. */
  getPlanSetting(studentId: string): Promise<PlanSetting | null>;
  savePlanSetting(studentId: string, changes: Partial<Omit<PlanSetting, 'student_id' | 'teacher_id'>>): Promise<PlanSetting>;
  /** Marks a shared take as listened to (or not), which frees the student to share another. */
  markTakeHeard(takeId: string, heard: boolean): Promise<void>;
  /**
   * Answers a question, in words, a voice note, or (both null) as covered in
   * the lesson; reopen puts it back.
   */
  answerQuestion(questionId: string, answer: string | null, voice?: Blob | null): Promise<void>;
  /** Replies to a shared take in words and/or a voice note, which also marks it heard. */
  replyToTake(take: { id: string; student_id: string }, words: string | null, voice: Blob | null): Promise<void>;

  /** Every full-lesson ask, invite and pass this teacher is part of, newest first. */
  listFullLessonPasses(): Promise<FullLessonPass[]>;
  /** Asks the student to open this whole lesson for 14 days; their yes starts it. */
  askFullLesson(lessonId: string, note: string | null): Promise<void>;
  /** Yes, or "not this time", to a student's invite. */
  answerFullLesson(passId: string, yes: boolean): Promise<void>;
  /** Ends a pass early, or takes back an ask. */
  endFullLesson(passId: string): Promise<void>;
  reopenQuestion(questionId: string): Promise<void>;

  listSongs(studentId: string): Promise<Song[]>;
  createSong(studentId: string, title: string, composer: string): Promise<Song>;
  getSong(songId: string): Promise<Song | null>;
  getScore(songId: string): Promise<Score | null>;
  uploadScore(song: Song, file: File, pageCount: number | null): Promise<Score>;
  saveBarMap(scoreId: string, map: BarPosition[]): Promise<void>;
  listAnnotations(scoreId: string): Promise<Annotation[]>;
  addAnnotation(a: NewAnnotation): Promise<Annotation>;
  deleteAnnotation(id: string): Promise<void>;

  /** A short-lived link to a private file, or null if it can't be read. */
  fileUrl(bucket: Bucket, path: string): Promise<string | null>;
  storageUsed(studentId: string): Promise<StorageRow[]>;
}

export function scoreFormatOf(file: File): Score['format'] | null {
  const name = file.name.toLowerCase();
  if (file.type === 'application/pdf' || name.endsWith('.pdf')) return 'pdf';
  if (file.type.startsWith('image/')) return 'image';
  if (name.endsWith('.musicxml') || name.endsWith('.xml') || name.endsWith('.mxl')) return 'musicxml';
  return null;
}
