// The real data layer: Supabase, called with the teacher's own session, so
// the database's security rules decide what she can read and change.
import { supabase } from './supabase';
import type { Api, Bucket, NewAnnotation, NewComment, NewPractice, PresentTeacher } from './api';
import { EditConflict, scoreFormatOf } from './api';
import type {
  Annotation, BarPosition, CardFields, CardVersion, Checkpoint, Comment, Feedback, Lesson, PhotoMark,
  PracticeItem, Profile, Score, Share, Song, StorageRow, StudentSummary, Take, Teacher
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

export const supabaseApi: Api = {
  demo: false,

  async me() {
    const { data } = await supabase.auth.getSession();
    if (!data.session) return null;
    const res = await supabase.from('profile').select(ME_COLUMNS).eq('id', data.session.user.id).single();
    return res.data as Profile | null;
  },

  async listStudents() {
    const me = await uid();
    const shares = check(await supabase.from('teacher_student_share').select('*')
      .eq('teacher_id', me).eq('status', 'active')) as Share[];
    if (!shares.length) return [];
    const ids = shares.map((s) => s.student_id);
    const [profiles, lessons] = await Promise.all([
      supabase.from('profile').select('id, role, display_name').in('id', ids),
      supabase.from('lesson').select('student_id, recorded_at').in('student_id', ids)
    ]);
    const people = check(profiles) as Profile[];
    const rows = check(lessons) as { student_id: string; recorded_at: string }[];
    const list: StudentSummary[] = shares.map((share) => {
      const mine = rows.filter((l) => l.student_id === share.student_id);
      return {
        share,
        student: people.find((p) => p.id === share.student_id) ?? { id: share.student_id, role: 'student', display_name: '' },
        lessonCount: mine.length,
        lastLessonAt: mine.map((l) => l.recorded_at).sort().at(-1) ?? null
      };
    });
    return list.sort((a, b) => (b.lastLessonAt ?? '').localeCompare(a.lastLessonAt ?? ''));
  },

  async listInvites() {
    // Security rules show her only the pending invites sent to her own email.
    const shares = check(await supabase.from('teacher_student_share').select('*')
      .eq('status', 'pending')) as Share[];
    // A student's name is private until she accepts, so the invite shows only its date.
    return shares.map((s) => ({ ...s, student_name: '' }));
  },

  async acceptInvite(shareId) {
    check(await supabase.rpc('accept_share', { share: shareId }));
  },

  async getStudent(studentId) {
    const me = await uid();
    const share = (await supabase.from('teacher_student_share').select('*')
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
    return check(await supabase.from('lesson').select('id, student_id, recorded_at, duration_ms, pipeline_state, taught_by')
      .eq('student_id', studentId).order('recorded_at', { ascending: false })) as Lesson[];
  },

  async getLesson(lessonId) {
    return (await supabase.from('lesson').select('id, student_id, recorded_at, duration_ms, pipeline_state, taught_by')
      .eq('id', lessonId).maybeSingle()).data as Lesson | null;
  },

  async listTakes(lessonId) {
    return check(await supabase.from('take').select('*').eq('lesson_id', lessonId).order('start_ms')) as Take[];
  },

  async listFeedback(lessonId) {
    return check(await supabase.from('feedback').select('*').eq('lesson_id', lessonId).order('span_start_ms')) as Feedback[];
  },

  async listPhotos(lessonId) {
    return check(await supabase.from('photo_mark').select('id, lesson_id, taken_at_ms, feedback_id, caption, image_path')
      .eq('lesson_id', lessonId).order('taken_at_ms')) as PhotoMark[];
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
    let clip_path: string | null = null;
    if (c.voice) {
      const ext = c.voice.type.includes('mp4') ? 'm4a' : c.voice.type.includes('ogg') ? 'ogg' : 'webm';
      clip_path = `${c.student_id}/teacher-notes/${crypto.randomUUID()}.${ext}`;
      check(await supabase.storage.from('clips').upload(clip_path, c.voice, { contentType: c.voice.type }));
    }
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
