// Every read and write the dashboard makes goes through this one interface.
// Two versions sit behind it: the real one talks to Supabase with her session,
// and the demo one keeps made-up sample data in the browser. Screens never know
// which one they have, so the demo exercises exactly the same screens.
import type {
  Annotation, BarPosition, CardFields, CardVersion, Comment, Feedback, Lesson, PhotoMark,
  PracticeItem, Profile, Score, Share, Song, StorageRow, StudentSummary, Take
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

export interface Api {
  readonly demo: boolean;
  me(): Promise<Profile | null>;

  listStudents(): Promise<StudentSummary[]>;
  listInvites(): Promise<(Share & { student_name: string })[]>;
  acceptInvite(shareId: string): Promise<void>;
  getStudent(studentId: string): Promise<{ student: Profile; share: Share } | null>;
  setNextLesson(shareId: string, at: string | null): Promise<void>;

  listLessons(studentId: string): Promise<Lesson[]>;
  getLesson(lessonId: string): Promise<Lesson | null>;
  listTakes(lessonId: string): Promise<Take[]>;
  listFeedback(lessonId: string): Promise<Feedback[]>;
  listPhotos(lessonId: string): Promise<PhotoMark[]>;
  /** Every card for a student, across lessons, newest lesson first. */
  listStudentFeedback(studentId: string): Promise<(Feedback & { recorded_at: string })[]>;
  listSongTakes(songId: string): Promise<Take[]>;

  listVersions(feedbackId: string): Promise<CardVersion[]>;
  /** Updates the card and appends a new version in one step. */
  saveCard(feedbackId: string, changes: Partial<CardFields>): Promise<Feedback>;

  listComments(studentId: string): Promise<Comment[]>;
  addComment(c: NewComment): Promise<Comment>;
  deleteComment(c: Comment): Promise<void>;

  listPractice(studentId: string): Promise<PracticeItem[]>;
  addPractice(p: NewPractice): Promise<PracticeItem>;
  updatePractice(id: string, patch: Partial<Pick<PracticeItem, 'body' | 'due_week'>>): Promise<void>;
  deletePractice(id: string): Promise<void>;

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
