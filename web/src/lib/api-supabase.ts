// The real data layer: Supabase, called with the teacher's own session, so
// the database's security rules decide what she can read and change.
import { supabase } from './supabase';
import type { Api, Bucket, NewAnnotation, NewComment, NewPractice, PresentTeacher } from './api';
import { EditConflict, scoreFormatOf } from './api';
import type {
  FullLessonPass, PracticeSession, SharedTake, StudentQuestion,
  Annotation, BarPosition, CardFields, CardVersion, Checkpoint, Comment, DiagramSuggestion, Exercise, Feedback, Lesson, PhotoMark,
  LessonViewRequest, PracticeItem, Profile, Score, Share, Song, StorageRow, StudentSummary, Take, Teacher
} from './types';

const ME_COLUMNS = 'id, role, display_name, alias, color, name_public';

function check<T>(res: { data: T | null; error: { message: string } | null }): T {
  if (res.error) throw new Error(res.error.message);
  return res.data as T;
}

async function uid(): Promise<string> {
  const { data } = await supabase.auth.getSession();
  const id = data.session?.user.id;
  if (!id) throw new Error('Not signed in');
  return id;
}

function safeName(name: string): string {
  return name.toLowerCase().replace(/[^a-z0-9.]+/g, '-').replace(/^-+|-+$/g, '') || 'file';
}


const SUGGESTION_COLUMNS = 'id, feedback_id, diagram_id, confidence, source, teacher_status, student_status, created_at';
const PHOTO_COLUMNS = 'id, lesson_id, taken_at_ms, feedback_id, caption, confidence, image_path, cleaned_path, auto_feedback_id, link_source';
/**
 * The debug view: one stand-in share per student who has a lesson or a
 * teacher, so the dashboard shows them as a teacher with full access would
 * see them. Read-only: saving anything is refused by the database.
 */
async function debugShares(): Promise<Share[]> {
  const [lessons, shares] = await Promise.all([
    supabase.from('lesson').select('student_id'),
    supabase.from('teacher_student_share').select('student_id').eq('status', 'active')
  ]);
  const ids = [...new Set([...(check(lessons) as { student_id: string }[]), ...(check(shares) as { student_id: string }[])]
    .map((r) => r.student_id))];
  return ids.map((id) => ({
    id: `debug-${id}`, student_id: id, invite_email: '', teacher_id: null, status: 'active',
    next_lesson_at: null, created_at: new Date(0).toISOString(), show_only_taught: false, restricted_to_taught: false
  }));
}

/** Uploads a teacher's voice note to clips/<student>/teacher-notes/ and returns its path. */
async function uploadVoiceNote(studentId: string, voice: Blob): Promise<string> {
  const ext = voice.type.includes('mp4') ? 'm4a' : voice.type.includes('ogg') ? 'ogg' : 'webm';
  const path = `${studentId}/teacher-notes/${crypto.randomUUID()}.${ext}`;
  check(await supabase.storage.from('clips').upload(path, voice, { contentType: voice.type }));
  return path;
}

export const supabaseApi: Api = {
  demo: false,

  async me() {
    const { data } = await supabase.auth.getSession();
    if (!data.session) return null;
    const [res, debug] = await Promise.all([
      supabase.from('profile').select(ME_COLUMNS).eq('id', data.session.user.id).single(),
      supabase.rpc('am_debug')
    ]);
    const me = res.data as Profile | null;
    return me && debug.data === true ? { ...me, debug: true } : me;
  },

  async listStudents() {
    const me = await uid();
    const debug = (await supabase.rpc('am_debug')).data === true;
    const shares = debug ? await debugShares() : check(await supabase.from('teacher_student_share').select('*')
      .eq('teacher_id', me).eq('status', 'active')) as Share[];
    if (!shares.length) return [];
    const ids = shares.map((s) => s.student_id);
    const [profiles, lessons, takes, questions] = await Promise.all([
      supabase.from('profile').select('id, role, display_name').in('id', ids),
      supabase.from('lesson').select('student_id, recorded_at').in('student_id', ids),
      supabase.from('practice_take').select('student_id').in('student_id', ids).is('heard_at', null),
      supabase.from('student_question').select('student_id').in('student_id', ids).eq('status', 'open')
    ]);
    const people = check(profiles) as Profile[];
    const rows = check(lessons) as { student_id: string; recorded_at: string }[];
    // Counts are a nicety: a failure here shouldn't hide the students.
    const waiting = (takes.data ?? []) as { student_id: string }[];
    const open = (questions.data ?? []) as { student_id: string }[];
    const list: StudentSummary[] = shares.map((share) => {
      const mine = rows.filter((l) => l.student_id === share.student_id);
      return {
        share,
        student: people.find((p) => p.id === share.student_id) ?? { id: share.student_id, role: 'student', display_name: '' },
        lessonCount: mine.length,
        lastLessonAt: mine.map((l) => l.recorded_at).sort().at(-1) ?? null,
        waitingTakes: waiting.filter((t) => t.student_id === share.student_id).length,
        openQuestions: open.filter((q) => q.student_id === share.student_id).length
      };
    });
    return list.sort((a, b) => (b.lastLessonAt ?? '').localeCompare(a.lastLessonAt ?? ''));
  },

  async listInvites() {
    // Only invites sent to this account's email. The security rules also let a
    // student see the invites she sent (and a debug account see all of them),
    // which aren't hers to accept.
    const { data: { user } } = await supabase.auth.getUser();
    if (!user?.email) return [];
    const mine = user.email.toLowerCase();
    const shares = (check(await supabase.from('teacher_student_share').select('*')
      .eq('status', 'pending').neq('student_id', user.id)) as Share[])
      .filter((s) => s.invite_email?.toLowerCase() === mine);
    // A student's name is private until she accepts, so the invite shows only its date.
    return shares.map((s) => ({ ...s, student_name: '' }));
  },

  async acceptInvite(shareId) {
    check(await supabase.rpc('accept_share', { share: shareId }));
  },

  async listLessonViewRequests() {
    return check(await supabase.rpc('my_lesson_view_requests')) as LessonViewRequest[];
  },

  async decideLessonView(requestId, allow) {
    check(await supabase.rpc('decide_lesson_view', { request: requestId, allow }));
  },

  async getStudent(studentId) {
    const me = await uid();
    const debug = (await supabase.rpc('am_debug')).data === true;
    const share = debug ? (await debugShares()).find((s) => s.student_id === studentId) ?? null
      : (await supabase.from('teacher_student_share').select('*')
        .eq('student_id', studentId).eq('teacher_id', me).eq('status', 'active').maybeSingle()).data as Share | null;
    const student = (await supabase.from('profile').select('id, role, display_name').eq('id', studentId).maybeSingle()).data as Profile | null;
    return share && student ? { share, student } : null;
  },

  async setNextLesson(shareId, at) {
    check(await supabase.from('teacher_student_share').update({ next_lesson_at: at }).eq('id', shareId));
  },

  async setShowOnlyTaught(shareId, on) {
    check(await supabase.from('teacher_student_share').update({ show_only_taught: on }).eq('id', shareId));
  },

  async claimLesson(lessonId, mine) {
    check(await supabase.rpc('claim_lesson', { lesson: lessonId, mine }));
  },

  async listTeachers(studentId) {
    return check(await supabase.rpc('list_teachers', { student: studentId })) as Teacher[];
  },

  async updateMe(patch) {
    const me = await uid();
    return check(await supabase.from('profile').update(patch).eq('id', me).select(ME_COLUMNS).single()) as Profile;
  },

  async listLessons(studentId) {
    return check(await supabase.from('lesson').select('id, student_id, recorded_at, duration_ms, pipeline_state, taught_by, teacher_name')
      .eq('student_id', studentId).order('recorded_at', { ascending: false })) as Lesson[];
  },

  async getLesson(lessonId) {
    return (await supabase.from('lesson').select('id, student_id, recorded_at, duration_ms, pipeline_state, taught_by, teacher_name')
      .eq('id', lessonId).maybeSingle()).data as Lesson | null;
  },

  async listTakes(lessonId) {
    return check(await supabase.from('take').select('*').eq('lesson_id', lessonId).order('start_ms')) as Take[];
  },

  async listExercises(lessonId) {
    return check(await supabase.from('exercise').select('*').eq('lesson_id', lessonId).order('start_ms')) as Exercise[];
  },

  async listFeedback(lessonId) {
    return check(await supabase.from('feedback').select('*').eq('lesson_id', lessonId).order('span_start_ms')) as Feedback[];
  },

  async listPhotos(lessonId) {
    return check(await supabase.from('photo_mark').select(PHOTO_COLUMNS)
      .eq('lesson_id', lessonId).order('taken_at_ms')) as PhotoMark[];
  },

  async movePhoto(photoId, feedbackId) {
    // The database marks the move as hers (link_source = 'teacher').
    return check(await supabase.from('photo_mark').update({ feedback_id: feedbackId })
      .eq('id', photoId).select(PHOTO_COLUMNS).single()) as PhotoMark;
  },

  async listDiagramSuggestions(lessonId) {
    const rows = check(await supabase.from('diagram_suggestion').select(`${SUGGESTION_COLUMNS}, feedback!inner(lesson_id)`)
      .eq('feedback.lesson_id', lessonId).order('created_at')) as (DiagramSuggestion & { feedback: unknown })[];
    return rows.map(({ feedback: _, ...s }) => s);
  },

  async decideDiagram(suggestionId, decision) {
    // The database stamps who decided, and allows only the teacher's side.
    return check(await supabase.from('diagram_suggestion').update({ teacher_status: decision })
      .eq('id', suggestionId).select(SUGGESTION_COLUMNS).single()) as DiagramSuggestion;
  },

  async listStudentFeedback(studentId) {
    const rows = check(await supabase.from('feedback').select('*, lesson!inner(student_id, recorded_at)')
      .eq('lesson.student_id', studentId)) as (Feedback & { lesson: { recorded_at: string } })[];
    return rows
      .map(({ lesson, ...f }) => ({ ...f, recorded_at: lesson.recorded_at }))
      .sort((a, b) => b.recorded_at.localeCompare(a.recorded_at) || a.span_start_ms - b.span_start_ms);
  },

  async listSongTakes(songId) {
    return check(await supabase.from('take').select('*').eq('song_id', songId).order('bar_start')) as Take[];
  },

  async listVersions(feedbackId) {
    return check(await supabase.from('card_version').select('*').eq('feedback_id', feedbackId)
      .order('created_at', { ascending: false })) as CardVersion[];
  },

  async listLessonVersions(lessonId) {
    const rows = check(await supabase.from('card_version').select('*, feedback!inner(lesson_id)')
      .eq('feedback.lesson_id', lessonId).order('created_at', { ascending: false })) as (CardVersion & { feedback: unknown })[];
    return rows.map(({ feedback: _f, ...v }) => v);
  },

  async saveCard(feedbackId, changes: Partial<CardFields>, opts = {}) {
    const res = await supabase.rpc('save_card', {
      fb: feedbackId, changes, expected: opts.expected ?? null, restored_from: opts.restoredFrom ?? null
    });
    if (res.error?.code === 'P0409') {
      let d: { fields?: (keyof CardFields)[]; theirs?: Partial<CardFields>; by?: string | null; at?: string | null } = {};
      try { d = JSON.parse(res.error.details ?? '{}'); } catch { /* keep the defaults */ }
      throw new EditConflict(d.fields ?? [], d.theirs ?? {}, d.by ?? null, d.at ?? null);
    }
    check(res);
    return check(await supabase.from('feedback').select('*').eq('id', feedbackId).single()) as Feedback;
  },

  async removeCard(card) {
    // The file first: the storage rule checks that a card still names it.
    if (card.clip_path) check(await supabase.storage.from('clips').remove([card.clip_path]));
    check(await supabase.rpc('remove_card', { card: card.id }));
  },

  async removeCardAudio(card) {
    if (card.clip_path) check(await supabase.storage.from('clips').remove([card.clip_path]));
    return check(await supabase.from('feedback')
      .update({ clip_path: null, audio_removed_at: new Date().toISOString() })
      .eq('id', card.id).select('*').single()) as Feedback;
  },

  async checkSummary(feedbackId, said) {
    return check(await supabase.rpc('check_summary', { fb: feedbackId, said })) as Feedback;
  },

  async answerSummary(card, studentId, verdict, explain = {}) {
    const note_clip = explain.voice ? await uploadVoiceNote(studentId, explain.voice) : explain.clip ?? null;
    return check(await supabase.rpc('answer_summary', {
      fb: card.id, verdict, note: explain.note?.trim() || null, note_clip
    })) as Feedback;
  },

  async summaryTally() {
    const rows = check(await supabase.rpc('summary_check_tally')) as Record<string, number | string>[];
    const r = rows?.[0] ?? {};
    return { said: Number(r.said ?? 0), close: Number(r.close ?? 0), not_said: Number(r.not_said ?? 0), unchecked: Number(r.unchecked ?? 0), lessons: Number(r.lessons ?? 0) };
  },

  async listCheckpoints(lessonId) {
    return check(await supabase.from('lesson_checkpoint').select('*').eq('lesson_id', lessonId)
      .order('created_at', { ascending: false })) as Checkpoint[];
  },

  async addCheckpoint(lessonId, label) {
    const me = await uid();
    return check(await supabase.from('lesson_checkpoint').insert({ lesson_id: lessonId, label, created_by: me })
      .select().single()) as Checkpoint;
  },

  async renameCheckpoint(id, label) {
    check(await supabase.from('lesson_checkpoint').update({ label }).eq('id', id));
  },

  async deleteCheckpoint(id) {
    check(await supabase.from('lesson_checkpoint').delete().eq('id', id));
  },

  async restoreCheckpoint(id) {
    return check(await supabase.rpc('restore_checkpoint', { checkpoint: id })) as number;
  },

  watchLesson(lessonId, events) {
    // Row changes come over an ordinary channel; the database's security
    // rules still decide which rows this teacher hears about.
    const rows = supabase.channel(`lesson-rows:${lessonId}`)
      .on('postgres_changes', { event: 'UPDATE', schema: 'public', table: 'feedback', filter: `lesson_id=eq.${lessonId}` },
        (p) => events.onCard(p.new as Feedback))
      .on('postgres_changes', { event: 'INSERT', schema: 'public', table: 'card_version' },
        (p) => events.onVersion(p.new as CardVersion))
      .on('postgres_changes', { event: '*', schema: 'public', table: 'lesson_checkpoint', filter: `lesson_id=eq.${lessonId}` },
        () => events.onCheckpoints())
      .subscribe();

    // Presence is private: only this student's teachers may join it.
    let editing: string | null = null;
    let joined = false;
    let stopped = false;
    let presence: ReturnType<typeof supabase.channel> | null = null;
    (async () => {
      const me = await uid().catch(() => null);
      if (!me || stopped) return;
      await supabase.realtime.setAuth();
      presence = supabase.channel(`lesson:${lessonId}`, { config: { private: true, presence: { key: me } } });
      presence.on('presence', { event: 'sync' }, () => {
        const state = presence!.presenceState<{ editing: string | null }>();
        const list: PresentTeacher[] = Object.entries(state).map(([id, metas]) => ({
          id, editing: metas.find((m) => m.editing)?.editing ?? null
        }));
        events.onPresence(list);
      });
      presence.subscribe((status) => {
        if (status !== 'SUBSCRIBED') return;
        joined = true;
        presence!.track({ editing });
      });
    })();

    return {
      setEditing(cardId) {
        editing = cardId;
        if (joined) presence?.track({ editing });
      },
      stop() {
        stopped = true;
        supabase.removeChannel(rows);
        if (presence) supabase.removeChannel(presence);
      }
    };
  },

  async listComments(studentId) {
    return check(await supabase.from('comment').select('*').eq('student_id', studentId)
      .order('created_at', { ascending: false })) as Comment[];
  },

  async addComment(c: NewComment) {
    const me = await uid();
    const clip_path = c.voice ? await uploadVoiceNote(c.student_id, c.voice) : null;
    return check(await supabase.from('comment').insert({
      student_id: c.student_id, lesson_id: c.lesson_id, author_id: me, body: c.body, clip_path
    }).select().single()) as Comment;
  },

  async deleteComment(c) {
    check(await supabase.from('comment').delete().eq('id', c.id));
    if (c.clip_path) await supabase.storage.from('clips').remove([c.clip_path]);
  },

  async listPractice(studentId) {
    return check(await supabase.from('practice_item').select('*').eq('student_id', studentId)
      .order('created_at', { ascending: false })) as PracticeItem[];
  },

  async addPractice(p: NewPractice) {
    const me = await uid();
    return check(await supabase.from('practice_item').insert({ ...p, author_id: me }).select().single()) as PracticeItem;
  },

  async updatePractice(id, patch) {
    check(await supabase.from('practice_item').update(patch).eq('id', id));
  },

  async deletePractice(id) {
    check(await supabase.from('practice_item').delete().eq('id', id));
  },

  async getAgenda(studentId) {
    const since = new Date(Date.now() - 28 * 864e5).toISOString();
    const [sessions, takes, questions] = await Promise.all([
      supabase.from('practice_session').select('*').eq('student_id', studentId).gte('started_at', since)
        .order('started_at', { ascending: false }),
      supabase.from('practice_take').select('*').eq('student_id', studentId).order('shared_at', { ascending: false }),
      supabase.from('student_question').select('*').eq('student_id', studentId).neq('status', 'dropped')
        .order('created_at', { ascending: false })
    ]);
    return {
      sessions: check(sessions) as PracticeSession[],
      takes: check(takes) as SharedTake[],
      questions: check(questions) as StudentQuestion[]
    };
  },

  async markTakeHeard(takeId, heard) {
    check(await supabase.from('practice_take').update({ heard_at: heard ? new Date().toISOString() : null }).eq('id', takeId));
  },

  async answerQuestion(questionId, answer, voice) {
    let answer_clip_path: string | null = null;
    if (voice) {
      const q = check(await supabase.from('student_question').select('student_id').eq('id', questionId).single()) as { student_id: string };
      answer_clip_path = await uploadVoiceNote(q.student_id, voice);
    }
    check(await supabase.from('student_question').update({ status: 'answered', answer, answer_clip_path }).eq('id', questionId));
  },

  async reopenQuestion(questionId) {
    check(await supabase.from('student_question').update({ status: 'open', answer: null, answer_clip_path: null }).eq('id', questionId));
  },

  async replyToTake(take, words, voice) {
    const reply_clip_path = voice ? await uploadVoiceNote(take.student_id, voice) : null;
    check(await supabase.from('practice_take').update({ reply: words?.trim() || null, reply_clip_path }).eq('id', take.id));
  },

  async listFullLessonPasses() {
    const me = await uid();
    return check(await supabase.from('full_lesson_pass').select('*').eq('teacher_id', me)
      .order('created_at', { ascending: false })) as FullLessonPass[];
  },

  async askFullLesson(lessonId, note) {
    check(await supabase.rpc('ask_full_lesson', { lesson: lessonId, note: note?.trim() || null }));
  },

  async answerFullLesson(passId, yes) {
    check(await supabase.rpc('answer_full_lesson', { pass: passId, yes }));
  },

  async endFullLesson(passId) {
    check(await supabase.rpc('end_full_lesson', { pass: passId }));
  },

  async listSongs(studentId) {
    return check(await supabase.from('song').select('id, student_id, title, composer, created_at')
      .eq('student_id', studentId).order('created_at', { ascending: false })) as Song[];
  },

  async createSong(studentId, title, composer) {
    const me = await uid();
    return check(await supabase.from('song').insert({ student_id: studentId, title, composer, created_by: me })
      .select('id, student_id, title, composer, created_at').single()) as Song;
  },

  async getSong(songId) {
    return (await supabase.from('song').select('id, student_id, title, composer, created_at')
      .eq('id', songId).maybeSingle()).data as Song | null;
  },

  async getScore(songId) {
    return (await supabase.from('score').select('id, song_id, file_path, format, page_count, bar_map')
      .eq('song_id', songId).maybeSingle()).data as Score | null;
  },

  async uploadScore(song, file, pageCount) {
    const me = await uid();
    const format = scoreFormatOf(file);
    if (!format) throw new Error('Use a PDF, a photo, or a MusicXML file.');
    const path = `${song.student_id}/${song.id}/${Date.now()}-${safeName(file.name)}`;
    check(await supabase.storage.from('scores').upload(path, file, { contentType: file.type || undefined }));
    // One score per song: replacing it removes the old file and its marks.
    const old = await this.getScore(song.id);
    if (old) {
      check(await supabase.from('score').delete().eq('id', old.id));
      await supabase.storage.from('scores').remove([old.file_path]);
    }
    return check(await supabase.from('score').insert({
      song_id: song.id, file_path: path, format, page_count: pageCount, created_by: me
    }).select('id, song_id, file_path, format, page_count, bar_map').single()) as Score;
  },

  async saveBarMap(scoreId, map) {
    check(await supabase.from('score').update({ bar_map: map }).eq('id', scoreId));
  },

  async listAnnotations(scoreId) {
    return check(await supabase.from('score_annotation').select('*').eq('score_id', scoreId).order('bar')) as Annotation[];
  },

  async addAnnotation(a: NewAnnotation) {
    const me = await uid();
    return check(await supabase.from('score_annotation').insert({ ...a, author_id: me }).select().single()) as Annotation;
  },

  async deleteAnnotation(id) {
    check(await supabase.from('score_annotation').delete().eq('id', id));
  },

  async fileUrl(bucket: Bucket, path: string) {
    const { data } = await supabase.storage.from(bucket).createSignedUrl(path, 60 * 60);
    return data?.signedUrl ?? null;
  },

  async storageUsed(studentId) {
    return check(await supabase.rpc('storage_used', { student: studentId })) as StorageRow[];
  }
};
