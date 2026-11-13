// The demo data layer: same interface as the real one, backed by sample data
// held in this browser. Edits are kept in localStorage so they survive a
// reload; "Reset demo" puts everything back.
import type { Api, Bucket, NewAnnotation, NewComment, NewPractice, PresentTeacher } from './api';
import { EditConflict, scoreFormatOf } from './api';
import type { CardFields, CardVersion, DiagramSuggestion, Feedback, Profile, StorageRow, Teacher } from './types';
import { CARD_FIELDS } from './types';
import {
  DEMO_COTEACHER, DEMO_TEACHER, MELODIES, demoAgenda, demoScoreSvg, demoSuggestions, demoSketchSvg, demoSketchPhotoSvg, demoExercises, initialDemoState, type DemoState
} from './demo-data';
import { wavFor } from './tone';

const KEY = 'cuenote-demo-state-v3';
let state: DemoState | null = null;
const memoryFiles = new Map<string, string>();     // big uploads, this tab only
const toneUrls = new Map<string, string>();

function load(): DemoState {
  if (state) return state;
  try {
    const saved = localStorage.getItem(KEY);
    if (saved) state = JSON.parse(saved);
  } catch { /* private mode or blocked storage: start fresh */ }
  state ??= initialDemoState();
  return state;
}

// Another tab of the demo saved: read the new state on next use.
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (e) => { if (e.key === KEY) state = null; });
}

function save() {
  try { localStorage.setItem(KEY, JSON.stringify(state)); } catch { /* full or blocked: keep in memory */ }
}

export function resetDemo() {
  state = initialDemoState();
  save();
}

const id = () => `demo-${crypto.randomUUID()}`;
const now = () => new Date().toISOString();
const wait = () => new Promise((r) => setTimeout(r, 120));   // feel like a network call

function blobToDataUrl(b: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(r.result as string);
    r.onerror = () => reject(r.error);
    r.readAsDataURL(b);
  });
}

function svgUrl(svg: string) {
  return URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml' }));
}

/** The demo state, with the agenda's sample data added to one saved before it existed. */
function withAgenda(): DemoState {
  const s = load();
  if (!s.practiceSessions || !s.sharedTakes || !s.questions) Object.assign(s, demoAgenda());
  return s;
}

function lessonStudent(s: DemoState, lessonId: string) {
  return s.lessons.find((l) => l.id === lessonId)?.student_id;
}

function meIn(s: DemoState): Profile {
  return s.profiles.find((p) => p.id === DEMO_TEACHER.id) ?? DEMO_TEACHER;
}

const mine = (s: DemoState) => s.shares.filter((sh) => sh.teacher_id === DEMO_TEACHER.id);

function snapshot(f: Feedback): Partial<CardFields> {
  return {
    cue: f.cue, category: f.category, her_words: f.her_words, practice_flag: f.practice_flag,
    take_id: f.take_id, song_id: f.song_id, bar_start: f.bar_start, bar_end: f.bar_end
  };
}

export const demoApi: Api = {
  demo: true,

  async me() { return { ...meIn(load()) }; },

  async listStudents() {
    const s = load();
    return mine(s).filter((sh) => sh.status === 'active').map((share) => {
      const lessons = s.lessons.filter((l) => l.student_id === share.student_id);
      return {
        share,
        student: s.profiles.find((p) => p.id === share.student_id)!,
        lessonCount: lessons.length,
        lastLessonAt: lessons.map((l) => l.recorded_at).sort().at(-1) ?? null
      };
    }).sort((a, b) => (b.lastLessonAt ?? '').localeCompare(a.lastLessonAt ?? ''));
  },

  async listInvites() {
    return load().shares.filter((sh) => sh.status === 'pending').map((sh) => ({ ...sh, student_name: '' }));
  },

  async acceptInvite(shareId) {
    await wait();
    const s = load();
    const sh = s.shares.find((x) => x.id === shareId);
    if (!sh) return;
    sh.status = 'active';
    sh.teacher_id = DEMO_TEACHER.id;
    if (!s.profiles.some((p) => p.id === sh.student_id)) {
      s.profiles.push({ id: sh.student_id, role: 'student', display_name: 'Jordan Lee' });
    }
    save();
  },

  async listLessonViewRequests() {
    return [];
  },

  async decideLessonView() {},

  async getStudent(studentId) {
    const s = load();
    const share = mine(s).find((x) => x.student_id === studentId && x.status === 'active');
    const student = s.profiles.find((p) => p.id === studentId);
    return share && student ? { share, student } : null;
  },

  async setNextLesson(shareId, at) {
    const sh = load().shares.find((x) => x.id === shareId);
    if (sh) sh.next_lesson_at = at;
    save();
  },

  async setShowOnlyTaught(shareId, on) {
    const sh = mine(load()).find((x) => x.id === shareId);
    if (sh) sh.show_only_taught = on;
    save();
  },

  async claimLesson(lessonId, isMine) {
    await wait();
    const l = load().lessons.find((x) => x.id === lessonId);
    if (!l) throw new Error('lesson not found');
    if (isMine) {
      if (l.taught_by && l.taught_by !== DEMO_TEACHER.id) throw new Error('another teacher is already marked on this lesson');
      l.taught_by = DEMO_TEACHER.id;
      l.teacher_name = null;
    } else if (l.taught_by === DEMO_TEACHER.id) {
      l.taught_by = null;
    }
    save();
  },

  async listTeachers(studentId) {
    const s = load();
    return s.shares.filter((sh) => sh.student_id === studentId && sh.status === 'active' && sh.teacher_id)
      .map((sh) => s.profiles.find((p) => p.id === sh.teacher_id)!)
      .filter(Boolean)
      .map((p): Teacher => ({
        id: p.id, alias: p.alias ?? 'Teacher', color: p.color ?? 'blue', name_public: !!p.name_public,
        display_name: p.id === DEMO_TEACHER.id || p.name_public ? p.display_name : null
      }));
  },

  async updateMe(patch) {
    await wait();
    const s = load();
    const me = meIn(s);
    if (patch.alias !== undefined && !patch.alias.trim()) throw new Error('Your alias can’t be empty.');
    Object.assign(me, patch);
    if (!s.profiles.includes(me)) s.profiles.push(me);
    save();
    return { ...me };
  },

  async listLessons(studentId) {
    return load().lessons.filter((l) => l.student_id === studentId)
      .sort((a, b) => b.recorded_at.localeCompare(a.recorded_at));
  },
  async getLesson(lessonId) { return load().lessons.find((l) => l.id === lessonId) ?? null; },
  async listTakes(lessonId) { return load().takes.filter((t) => t.lesson_id === lessonId).sort((a, b) => a.start_ms - b.start_ms); },
  async listExercises(lessonId) {
    const s = load();
    return (s.exercises ??= demoExercises()).filter((e) => e.lesson_id === lessonId).sort((a, b) => a.start_ms - b.start_ms);
  },
  async listFeedback(lessonId) { return load().feedback.filter((f) => f.lesson_id === lessonId).sort((a, b) => a.span_start_ms - b.span_start_ms); },
  async listDiagramSuggestions(lessonId) {
    const s = load();
    const cards = new Set(s.feedback.filter((f) => f.lesson_id === lessonId).map((f) => f.id));
    return (s.suggestions ??= demoSuggestions()).filter((x) => cards.has(x.feedback_id)).map((x) => ({ ...x }));
  },
  async decideDiagram(suggestionId, decision) {
    const s = load();
    const x: DiagramSuggestion | undefined = (s.suggestions ??= demoSuggestions()).find((y) => y.id === suggestionId);
    if (!x) throw new Error('No such suggestion');
    x.teacher_status = decision;
    save();
    return { ...x };
  },
  async listPhotos(lessonId) { return load().photos.filter((p) => p.lesson_id === lessonId); },
  async movePhoto(photoId, feedbackId) {
    const s = load();
    const p = s.photos.find((x) => x.id === photoId);
    if (!p) throw new Error('No such photo');
    if (feedbackId && !s.feedback.some((f) => f.id === feedbackId && f.lesson_id === p.lesson_id)) {
      throw new Error('A photo can only go on a card from its own lesson');
    }
    p.feedback_id = feedbackId;
    p.link_source = 'teacher';
    save();
    return { ...p };
  },

  async listStudentFeedback(studentId) {
    const s = load();
    return s.feedback
      .filter((f) => lessonStudent(s, f.lesson_id) === studentId)
      .map((f) => ({ ...f, recorded_at: s.lessons.find((l) => l.id === f.lesson_id)!.recorded_at }))
      .sort((a, b) => b.recorded_at.localeCompare(a.recorded_at) || a.span_start_ms - b.span_start_ms);
  },

  async listSongTakes(songId) { return load().takes.filter((t) => t.song_id === songId); },

  async listVersions(feedbackId) {
    return load().versions.filter((v) => v.feedback_id === feedbackId)
      .sort((a, b) => b.created_at.localeCompare(a.created_at));
  },

  async listLessonVersions(lessonId) {
    const s = load();
    const ids = new Set(s.feedback.filter((f) => f.lesson_id === lessonId).map((f) => f.id));
    return s.versions.filter((v) => ids.has(v.feedback_id)).sort((a, b) => b.created_at.localeCompare(a.created_at));
  },

  async saveCard(feedbackId, changes: Partial<CardFields>, opts = {}) {
    await wait();
    const s = load();
    const f = s.feedback.find((x) => x.id === feedbackId);
    if (!f) throw new Error('card not found');
    // The same clash rule as save_card in the database.
    if (opts.expected) {
      const cur = f as unknown as Record<string, unknown>;
      const exp = opts.expected as Record<string, unknown>;
      const chg = changes as Record<string, unknown>;
      const clash = CARD_FIELDS.filter((k) => k in chg && k in exp
        && (cur[k] ?? null) !== (exp[k] ?? null) && (cur[k] ?? null) !== (chg[k] ?? null));
      if (clash.length) {
        const last = s.versions.filter((v) => v.feedback_id === f.id).sort((a, b) => b.created_at.localeCompare(a.created_at))[0];
        throw new EditConflict(clash, Object.fromEntries(clash.map((k) => [k, cur[k]])), last?.author_id ?? null, f.updated_at);
      }
    }
    if (changes.take_id && s.takes.find((t) => t.id === changes.take_id)?.lesson_id !== f.lesson_id) {
      throw new Error('a card can only link to a take from the same lesson');
    }
    Object.assign(f, changes, { updated_at: now() });
    s.versions.push({
      id: id(), feedback_id: f.id, author: 'teacher', author_id: DEMO_TEACHER.id, created_at: now(),
      payload: snapshot(f), restored_from: opts.restoredFrom ?? null
    });
    save();
    return { ...f } as Feedback;
  },

  async listCheckpoints(lessonId) {
    return load().checkpoints.filter((c) => c.lesson_id === lessonId)
      .sort((a, b) => b.created_at.localeCompare(a.created_at));
  },

  async addCheckpoint(lessonId, label) {
    await wait();
    const row = { id: id(), lesson_id: lessonId, label, created_by: DEMO_TEACHER.id, created_at: now() };
    load().checkpoints.push(row);
    save();
    return row;
  },

  async renameCheckpoint(cid, label) {
    const c = load().checkpoints.find((x) => x.id === cid && x.created_by === DEMO_TEACHER.id);
    if (c) c.label = label;
    save();
  },

  async deleteCheckpoint(cid) {
    const s = load();
    s.checkpoints = s.checkpoints.filter((x) => !(x.id === cid && x.created_by === DEMO_TEACHER.id));
    save();
  },

  async restoreCheckpoint(cid) {
    await wait();
    const s = load();
    const cp = s.checkpoints.find((x) => x.id === cid);
    if (!cp) throw new Error('named version not found');
    let n = 0;
    for (const f of s.feedback.filter((x) => x.lesson_id === cp.lesson_id)) {
      const then: CardVersion | undefined = s.versions
        .filter((v) => v.feedback_id === f.id && v.created_at <= cp.created_at)
        .sort((a, b) => b.created_at.localeCompare(a.created_at))[0];
      if (!then) continue;
      const cur = f as unknown as Record<string, unknown>;
      const differs = Object.entries(then.payload).some(([k, v]) => (cur[k] ?? null) !== (v ?? null));
      if (!differs) continue;
      await this.saveCard(f.id, then.payload, { restoredFrom: then.id });
      n++;
    }
    return n;
  },

  watchLesson(lessonId, events) {
    // The demo has no server, so the other teacher's presence is staged:
    // on Sam's first lesson, Lark is looking at the high F card.
    const t = setTimeout(() => {
      const present: PresentTeacher[] = [{ id: DEMO_TEACHER.id, editing: null }];
      if (lessonId === 'demo-lesson-1') present.push({ id: DEMO_COTEACHER.id, editing: 'demo-fb-3' });
      events.onPresence(present);
    }, 300);
    // Saves made in another tab of the demo arrive like live updates.
    const onStorage = (e: StorageEvent) => {
      if (e.key !== KEY) return;
      const s = load();
      for (const f of s.feedback.filter((x) => x.lesson_id === lessonId)) {
        events.onCard({ ...f });
        for (const v of s.versions.filter((x) => x.feedback_id === f.id)) events.onVersion(v);
      }
      events.onCheckpoints();
    };
    window.addEventListener('storage', onStorage);
    return {
      setEditing() { /* nobody to tell */ },
      stop() { clearTimeout(t); window.removeEventListener('storage', onStorage); }
    };
  },

  async listComments(studentId) {
    return load().comments.filter((c) => c.student_id === studentId)
      .sort((a, b) => b.created_at.localeCompare(a.created_at));
  },

  async addComment(c: NewComment) {
    await wait();
    const s = load();
    let clip_path: string | null = null;
    if (c.voice) {
      clip_path = `demo-file:${id()}`;
      s.files[clip_path] = await blobToDataUrl(c.voice);
    }
    const row = { id: id(), student_id: c.student_id, lesson_id: c.lesson_id, author_id: DEMO_TEACHER.id,
      body: c.body, clip_path, created_at: now() };
    s.comments.push(row);
    save();
    return row;
  },

  async removeCard(card) {
    await wait();
    const s = load();
    s.feedback = s.feedback.filter((x) => x.id !== card.id);
    s.versions = s.versions.filter((v) => v.feedback_id !== card.id);
    if (card.clip_path) delete s.files[card.clip_path];
    save();
  },

  async removeCardAudio(card) {
    await wait();
    const s = load();
    const f = s.feedback.find((x) => x.id === card.id);
    if (!f) throw new Error('card not found');
    if (f.clip_path) delete s.files[f.clip_path];
    Object.assign(f, { clip_path: null, audio_removed_at: now(), updated_at: now() });
    save();
    return f;
  },

  async deleteComment(c) {
    const s = load();
    s.comments = s.comments.filter((x) => x.id !== c.id);
    if (c.clip_path) delete s.files[c.clip_path];
    save();
  },

  async listPractice(studentId) {
    return load().practice.filter((p) => p.student_id === studentId)
      .sort((a, b) => b.created_at.localeCompare(a.created_at));
  },

  async addPractice(p: NewPractice) {
    const row = { id: id(), lesson_id: null, feedback_id: null, due_week: null, ...p,
      author_id: DEMO_TEACHER.id, created_at: now() };
    load().practice.push(row);
    save();
    return row;
  },

  async updatePractice(pid, patch) {
    const p = load().practice.find((x) => x.id === pid);
    if (p) Object.assign(p, patch);
    save();
  },

  async deletePractice(pid) {
    const s = load();
    s.practice = s.practice.filter((x) => x.id !== pid);
    save();
  },

  async listSongs(studentId) { return load().songs.filter((g) => g.student_id === studentId); },

  async createSong(studentId, title, composer) {
    const row = { id: id(), student_id: studentId, title, composer, created_at: now() };
    load().songs.push(row);
    save();
    return row;
  },

  async getSong(songId) { return load().songs.find((g) => g.id === songId) ?? null; },
  async getScore(songId) { return load().scores.find((c) => c.song_id === songId) ?? null; },

  async uploadScore(song, file, pageCount) {
    await wait();
    const format = scoreFormatOf(file);
    if (!format) throw new Error('Use a PDF, a photo, or a MusicXML file.');
    const s = load();
    const path = `demo-file:${id()}`;
    if (file.size < 3_000_000) s.files[path] = await blobToDataUrl(file);
    else memoryFiles.set(path, URL.createObjectURL(file));
    const old = s.scores.find((c) => c.song_id === song.id);
    if (old) {
      s.scores = s.scores.filter((c) => c.id !== old.id);
      s.annotations = s.annotations.filter((a) => a.score_id !== old.id);
      delete s.files[old.file_path];
    }
    const row = { id: id(), song_id: song.id, file_path: path, format, page_count: pageCount, bar_map: [] };
    s.scores.push(row);
    save();
    return row;
  },

  async saveBarMap(scoreId, map) {
    const c = load().scores.find((x) => x.id === scoreId);
    if (c) c.bar_map = map;
    save();
  },

  async listAnnotations(scoreId) { return load().annotations.filter((a) => a.score_id === scoreId); },

  async addAnnotation(a: NewAnnotation) {
    const row = { id: id(), beat: null, ...a, author_id: DEMO_TEACHER.id };
    load().annotations.push(row);
    save();
    return row;
  },

  async deleteAnnotation(aid) {
    const s = load();
    s.annotations = s.annotations.filter((x) => x.id !== aid);
    save();
  },

  async getAgenda(studentId) {
    await wait();
    const s = withAgenda();
    const since = new Date(Date.now() - 28 * 864e5).toISOString();
    return {
      sessions: s.practiceSessions!.filter((p) => p.student_id === studentId && p.started_at >= since)
        .sort((a, b) => b.started_at.localeCompare(a.started_at)),
      takes: s.sharedTakes!.filter((t) => t.student_id === studentId).sort((a, b) => b.shared_at.localeCompare(a.shared_at)),
      questions: s.questions!.filter((q) => q.student_id === studentId && q.status !== 'dropped')
        .sort((a, b) => b.created_at.localeCompare(a.created_at))
    };
  },

  async markTakeHeard(takeId, heard) {
    const t = withAgenda().sharedTakes!.find((x) => x.id === takeId);
    if (!t) throw new Error('That take is no longer shared.');
    t.heard_at = heard ? now() : null;
    t.heard_by = heard ? DEMO_TEACHER.id : null;
    save();
  },

  async answerQuestion(questionId, answer) {
    const q = withAgenda().questions!.find((x) => x.id === questionId);
    if (!q) throw new Error('That question was taken back.');
    Object.assign(q, { status: 'answered', answer, answered_by: DEMO_TEACHER.id, answered_at: q.answered_at ?? now() });
    save();
  },

  async reopenQuestion(questionId) {
    const q = withAgenda().questions!.find((x) => x.id === questionId);
    if (!q) return;
    Object.assign(q, { status: 'open', answer: null, answered_by: null, answered_at: null });
    save();
  },

  async fileUrl(_bucket: Bucket, path: string) {
    if (path.startsWith('demo-tone:')) {
      const cached = toneUrls.get(path);
      if (cached) return cached;
      const [, takeId, who] = path.split(':');
      const notes = MELODIES[takeId];
      if (!notes) return null;
      // Her clip is the same phrase sung an octave lower, standing in for her demo.
      const url = URL.createObjectURL(wavFor(notes, { octave: who === 'her' ? -1 : 0 }));
      toneUrls.set(path, url);
      return url;
    }
    if (path === 'demo-score:caro' || path.startsWith('demo-sketch:')) {
      if (!toneUrls.has(path)) {
        const svg = path === 'demo-score:caro' ? demoScoreSvg() : path.endsWith(':photo') ? demoSketchPhotoSvg() : demoSketchSvg();
        toneUrls.set(path, svgUrl(svg));
      }
      return toneUrls.get(path)!;
    }
    return load().files[path] ?? memoryFiles.get(path) ?? null;
  },

  async storageUsed(studentId) {
    const s = load();
    const lessons = s.lessons.filter((l) => l.student_id === studentId).map((l) => l.id);
    const takes = s.takes.filter((t) => lessons.includes(t.lesson_id));
    const cards = s.feedback.filter((f) => lessons.includes(f.lesson_id) && f.clip_path);
    // Clip sizes estimated at 64 kbps Opus, the rate the phone uploads at.
    const opus = (ms: number) => Math.round((ms / 1000) * 8000);
    const clipBytes = takes.reduce((n, t) => n + opus(t.end_ms - t.start_ms), 0)
      + cards.reduce((n, f) => n + opus(f.span_end_ms - f.span_start_ms), 0);
    const notes = s.comments.filter((c) => c.student_id === studentId && c.clip_path);
    const songs = s.songs.filter((g) => g.student_id === studentId).map((g) => g.id);
    const scores = s.scores.filter((c) => songs.includes(c.song_id));
    const sizeOf = (p: string) => Math.round(((s.files[p]?.length ?? 0) * 3) / 4) || 180_000;
    const rows: StorageRow[] = [
      { category: 'clips', files: takes.length + cards.length, bytes: clipBytes },
      { category: 'photos', files: s.photos.filter((p) => lessons.includes(p.lesson_id)).length, bytes: 0 },
      { category: 'scores', files: scores.length, bytes: scores.reduce((n, c) => n + sizeOf(c.file_path), 0) },
      { category: 'voice_notes', files: notes.length, bytes: notes.reduce((n, c) => n + (c.clip_path!.startsWith('demo-tone') ? 40_000 : sizeOf(c.clip_path!)), 0) }
    ];
    rows[1].bytes = rows[1].files * 420_000;
    return rows.filter((r) => r.files > 0);
  }
};
