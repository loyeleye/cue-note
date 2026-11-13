// Made-up sample data for demo mode: two students, three lessons, cards with
// their AI originals, notes, practice and a song with a score. Nothing here
// touches the real database. Dates are relative to today so it looks fresh.
import type { FullLessonPass,
  Annotation, CardVersion, Checkpoint, Comment, DiagramSuggestion, Exercise, Feedback, Lesson, PhotoMark, PracticeItem,
  PracticeSession, Profile, Score, Share, SharedTake, Song, StudentQuestion, Take
} from './types';
import { contourFor, type Note } from './tone';
import { weekOf as mondayOf } from './format';

export const DEMO_TEACHER: Profile = {
  id: 'demo-teacher', role: 'teacher', display_name: 'Demo Teacher', alias: 'Tiger', color: 'violet', name_public: false
};
/** A second teacher who also reviews Sam's lessons. Her real name is private. */
export const DEMO_COTEACHER: Profile = {
  id: 'demo-ola', role: 'teacher', display_name: 'Ola Mensah', alias: 'Lark', color: 'teal', name_public: false
};
const SAM = 'demo-sam';
const PRIYA = 'demo-priya';
const L1 = 'demo-lesson-1';
const L2 = 'demo-lesson-2';
const L3 = 'demo-lesson-3';
const SONG = 'demo-song-caro';

const day = 24 * 3600 * 1000;
const ago = (days: number, hour = 17) => {
  const d = new Date(Date.now() - days * day);
  d.setHours(hour, 0, 0, 0);
  return d.toISOString();
};
const min = (m: number, s = 0) => (m * 60 + s) * 1000;

/** The notes each take "sings"; the demo synthesises audio and pitch from them. */
export const MELODIES: Record<string, Note[]> = {
  'demo-take-1': [[62, 400], [64, 400], [66, 400], [67, 400], [69, 700], [67, 400], [66, 400], [64, 400], [62, 900]].map(([midi, ms]) => ({ midi, ms })),
  'demo-take-2': [[66, 700], [64, 350], [62, 1000], [0, 300], [69, 700], [67, 350], [66, 1000]].map(([midi, ms]) => ({ midi, ms })),
  'demo-take-3': [[71, 500], [72, 500], [74, 400], [77, 1100], [76, 400], [74, 900]].map(([midi, ms]) => ({ midi, ms })),
  'demo-take-4': [[69, 500], [71, 500], [69, 500], [67, 500], [66, 1200], [0, 200], [64, 500], [62, 1200]].map(([midi, ms]) => ({ midi, ms })),
  'demo-take-5': [[66, 700], [64, 350], [62, 1000], [0, 300], [69, 700], [67, 350], [66, 1000]].map(([midi, ms]) => ({ midi, ms })),
  'demo-take-6': [[60, 500], [64, 500], [67, 500], [72, 900], [67, 500], [64, 500], [60, 900]].map(([midi, ms]) => ({ midi, ms })),
  'demo-take-7': [[66, 700], [64, 350], [62, 1100], [0, 300], [69, 700], [67, 350], [66, 1100]].map(([midi, ms]) => ({ midi, ms })),
  'demo-take-8': [[67, 600], [69, 600], [71, 600], [72, 1200], [71, 600], [69, 1200]].map(([midi, ms]) => ({ midi, ms })),
  'demo-take-9': [[57, 500], [59, 500], [61, 500], [62, 500], [64, 1000], [62, 500], [61, 500], [59, 500], [57, 1000]].map(([midi, ms]) => ({ midi, ms }))
};
/** Where the piano drowned out the student, so the pitch is marked unclear. */
const UNCLEAR: Record<string, [number, number][]> = {
  'demo-take-3': [[1300, 2200]],
  'demo-take-7': [[600, 1300]]
};

function take(id: string, lesson_id: string, start_ms: number, bars?: [number, number]): Take {
  const notes = MELODIES[id];
  const len = notes.reduce((s, n) => s + n.ms, 0);
  const { contour, confidence } = contourFor(notes, { unclear: UNCLEAR[id], seed: id.length * 7 + start_ms });
  return {
    id, lesson_id, start_ms, end_ms: start_ms + len, clip_path: `demo-tone:${id}`,
    pitch_contour: contour, pitch_confidence: confidence,
    song_id: bars ? SONG : null, bar_start: bars?.[0] ?? null, bar_end: bars?.[1] ?? null
  };
}

function card(
  id: string, lesson_id: string, take_id: string | null, start: number, f: Partial<Feedback>
): Feedback {
  return {
    id, lesson_id, take_id, span_start_ms: start, span_end_ms: start + 12000,
    clip_path: take_id ? `demo-tone:${take_id}:her` : null,
    cue: '', category: null, her_words: '', practice_flag: null, song_id: null,
    bar_start: null, bar_end: null, resolved_at: null, updated_at: ago(4), ...f
  };
}

export interface DemoState {
  profiles: Profile[];
  shares: Share[];
  lessons: Lesson[];
  takes: Take[];
  feedback: Feedback[];
  versions: CardVersion[];
  checkpoints: Checkpoint[];
  photos: PhotoMark[];
  /** Missing from demo states saved before suggested diagrams. */
  suggestions?: DiagramSuggestion[];
  /** Missing from demo states saved before exercises. */
  exercises?: Exercise[];
  comments: Comment[];
  practice: PracticeItem[];
  /** The next lesson's agenda; missing from demo states saved before it. */
  practiceSessions?: PracticeSession[];
  sharedTakes?: SharedTake[];
  questions?: StudentQuestion[];
  songs: Song[];
  scores: Score[];
  annotations: Annotation[];
  /** Data URLs for voice notes recorded in the demo, so they survive a reload. */
  files: Record<string, string>;
  /** Full-lesson asks and passes; missing from demo states saved before them. */
  passes?: FullLessonPass[];
}

export function initialDemoState(): DemoState {
  const takes = [
    take('demo-take-1', L1, min(2, 10)),
    take('demo-take-2', L1, min(6, 40), [1, 4]),
    take('demo-take-3', L1, min(9, 15), [5, 8]),
    take('demo-take-4', L1, min(14, 0), [9, 12]),
    take('demo-take-5', L1, min(21, 5), [1, 4]),
    take('demo-take-6', L2, min(3, 0)),
    take('demo-take-7', L2, min(11, 30), [1, 4]),
    take('demo-take-8', L2, min(18, 45)),
    take('demo-take-9', L3, min(4, 20))
  ];

  const feedback = [
    card('demo-fb-1', L1, 'demo-take-1', min(2, 30), {
      cue: 'Keep the hum buzzing forward', category: 'resonance', practice_flag: 'practice_this',
      her_words: 'Feel it buzz on your lips, right behind your nose. Keep that buzz when you open to the vowel.'
    }),
    card('demo-fb-2', L1, 'demo-take-2', min(7, 8), {
      cue: 'Breathe low before the first phrase, shoulders still', category: 'breath', practice_flag: 'practice_this',
      her_words: "Don't lift the shoulders. Let the belly drop out, then sing.", song_id: SONG, bar_start: 1, bar_end: 2,
      updated_at: ago(3)
    }),
    card('demo-fb-3', L1, 'demo-take-3', min(9, 45), {
      cue: "Don't reach up for the high F", category: 'registration', practice_flag: 'practice_this',
      her_words: "You're reaching up for it. Think of it as down and over, like going over a hill.",
      song_id: SONG, bar_start: 7, bar_end: 7
    }),
    card('demo-fb-4', L1, 'demo-take-4', min(14, 32), {
      cue: 'Pure "ah" on "ben"', category: 'vowel', practice_flag: 'practice_this',
      her_words: "Drop the jaw, keep the tongue forward. No 'aw'.", song_id: SONG, bar_start: 11, bar_end: 12
    }),
    card('demo-fb-5', L1, 'demo-take-5', min(21, 32), {
      cue: 'Better: that low breath worked', category: 'encouragement', practice_flag: 'just_listen',
      her_words: 'Yes! Hear how much easier that was? Same breath every time.'
    }),
    card('demo-fb-6', L2, 'demo-take-6', min(3, 20), {
      cue: 'Lip trill through the arpeggio', category: 'warm-up', practice_flag: 'practice_this',
      her_words: 'Keep the air moving even when it gets high. Let the lips do the work.'
    }),
    card('demo-fb-7', L2, 'demo-take-7', min(12, 0), {
      cue: 'Sing through the phrase end, no drop-off', category: 'phrasing', practice_flag: 'just_listen',
      her_words: "Don't let 'ben' die. Carry it to the very end of the bar.", song_id: SONG, bar_start: 4, bar_end: 4
    }),
    card('demo-fb-8', L2, 'demo-take-8', min(19, 10), {
      cue: 'Open the throat on the top note', category: 'resonance', practice_flag: null,
      her_words: 'Like the start of a yawn. Keep that space.'
    }),
    card('demo-fb-9', L3, 'demo-take-9', min(4, 45), {
      cue: 'Start the scale lighter', category: 'registration', practice_flag: 'practice_this',
      her_words: "Less weight on the bottom notes and the top won't feel so far away."
    })
  ];

  const aiPayload = (f: Feedback) => ({
    cue: f.cue, category: f.category, her_words: f.her_words, practice_flag: f.practice_flag,
    take_id: f.take_id, song_id: f.song_id, bar_start: f.bar_start, bar_end: f.bar_end
  });
  const versions: CardVersion[] = feedback.map((f) => ({
    id: `${f.id}-v1`, feedback_id: f.id, author: 'ai', author_id: null, created_at: ago(4, 18),
    payload: aiPayload(f)
  }));
  // One card she already corrected: the AI heard "shoulders" as the cue.
  versions[1].payload = { ...versions[1].payload, cue: 'Lift your shoulders before the first phrase', category: 'posture' };
  versions.push({
    id: 'demo-fb-2-v2', feedback_id: 'demo-fb-2', author: 'teacher', author_id: DEMO_TEACHER.id,
    created_at: ago(3, 9), payload: aiPayload(feedback[1])
  });
  // And one the other teacher fixed: the AI had it backwards.
  versions[2].payload = { ...versions[2].payload, cue: 'Reach up for the high F' };
  versions.push({
    id: 'demo-fb-3-v2', feedback_id: 'demo-fb-3', author: 'teacher', author_id: DEMO_COTEACHER.id,
    created_at: ago(2, 19), payload: aiPayload(feedback[2])
  });
  feedback[2].updated_at = ago(2, 19);

  const thisWeek = mondayOf(new Date());
  return {
    profiles: [
      DEMO_TEACHER,
      DEMO_COTEACHER,
      { id: SAM, role: 'student', display_name: 'Sam Rivera' },
      { id: PRIYA, role: 'student', display_name: 'Priya Nair' }
    ],
    shares: [
      { id: 'demo-share-sam', student_id: SAM, invite_email: 'you@example.com', teacher_id: DEMO_TEACHER.id,
        status: 'active', next_lesson_at: ago(-3, 17), created_at: ago(30) },
      { id: 'demo-share-sam-ola', student_id: SAM, invite_email: 'ola@example.com', teacher_id: DEMO_COTEACHER.id,
        status: 'active', next_lesson_at: null, created_at: ago(25) },
      { id: 'demo-share-priya', student_id: PRIYA, invite_email: 'you@example.com', teacher_id: DEMO_TEACHER.id,
        status: 'active', next_lesson_at: null, created_at: ago(20) },
      { id: 'demo-share-new', student_id: 'demo-new', invite_email: 'you@example.com', teacher_id: null,
        status: 'pending', next_lesson_at: null, created_at: ago(1) }
    ],
    lessons: [
      { id: L1, student_id: SAM, recorded_at: ago(4), duration_ms: min(52), pipeline_state: 'cards_ready', taught_by: DEMO_TEACHER.id },
      // Lark taught this one; "Only my lessons" hides it.
      { id: L2, student_id: SAM, recorded_at: ago(11), duration_ms: min(48), pipeline_state: 'cards_ready', taught_by: DEMO_COTEACHER.id },
      { id: L3, student_id: PRIYA, recorded_at: ago(6, 10), duration_ms: min(30), pipeline_state: 'cards_ready', taught_by: null, teacher_name: 'Ana' }
    ],
    takes,
    feedback,
    versions,
    checkpoints: [
      { id: 'demo-cp-1', lesson_id: L1, label: "After Lark's review", created_by: DEMO_COTEACHER.id, created_at: ago(2, 20) }
    ],
    photos: [
      { id: 'demo-photo-1', lesson_id: L1, taken_at_ms: min(14, 40), feedback_id: 'demo-fb-4',
        caption: 'Jaw drops, tongue tip stays behind the bottom teeth', confidence: 0.9,
        image_path: 'demo-sketch:jaw:photo', cleaned_path: 'demo-sketch:jaw',
        auto_feedback_id: 'demo-fb-4', link_source: 'auto' }
    ],
    suggestions: demoSuggestions(),
    comments: [
      { id: 'demo-comment-1', student_id: SAM, lesson_id: L1, author_id: DEMO_TEACHER.id, created_at: ago(3, 9),
        body: 'Lovely progress on the breath this week. Bars 1 to 4 are nearly there; bar 7 is the one to live with.',
        clip_path: null },
      { id: 'demo-comment-2', student_id: SAM, lesson_id: null, author_id: DEMO_TEACHER.id, created_at: ago(2, 20),
        body: 'Voice note: how the "ah" on "ben" should sound.', clip_path: 'demo-tone:demo-take-4:her' },
      { id: 'demo-comment-3', student_id: SAM, lesson_id: L1, author_id: DEMO_COTEACHER.id, created_at: ago(2, 19),
        body: 'I fixed the high F card: the idea is the opposite of reaching. Think down and over.', clip_path: null }
    ],
    practice: [
      { id: 'demo-practice-1', student_id: SAM, lesson_id: L1, feedback_id: 'demo-fb-1', author_id: DEMO_TEACHER.id,
        body: 'Five-note scale on "ng", then open to "ah" without losing the buzz. 5 minutes a day.',
        due_week: thisWeek, created_at: ago(3, 9) },
      { id: 'demo-practice-2', student_id: SAM, lesson_id: L1, feedback_id: 'demo-fb-3', author_id: DEMO_TEACHER.id,
        body: 'Bar 7 slowly, "down and over" the F. Record one take and send it.', due_week: thisWeek, created_at: ago(3, 9) },
      { id: 'demo-practice-3', student_id: SAM, lesson_id: L2, feedback_id: null, author_id: DEMO_TEACHER.id,
        body: 'Lip trills through two octaves before singing anything.', due_week: mondayOf(new Date(Date.now() - 7 * day)),
        created_at: ago(10, 9) }
    ],
    ...demoAgenda(),
    songs: [{ id: SONG, student_id: SAM, title: 'Caro mio ben', composer: 'Tommaso Giordani', created_at: ago(11) }],
    scores: [{
      id: 'demo-score-1', song_id: SONG, file_path: 'demo-score:caro', format: 'image', page_count: 1,
      bar_map: DEMO_SCORE_BARS.map((b) => ({ ...b }))
    }],
    annotations: [
      { id: 'demo-ann-1', score_id: 'demo-score-1', bar: 1, beat: null, kind: 'dynamic', text: 'mp', page: 1, x: 0.13, y: 0.32, author_id: DEMO_TEACHER.id },
      { id: 'demo-ann-2', score_id: 'demo-score-1', bar: 2, beat: null, kind: 'breath', text: '', page: 1, x: 0.43, y: 0.155, author_id: DEMO_TEACHER.id },
      { id: 'demo-ann-3', score_id: 'demo-score-1', bar: 7, beat: null, kind: 'text', text: 'down and over', page: 1, x: 0.56, y: 0.39, author_id: DEMO_TEACHER.id },
      { id: 'demo-ann-4', score_id: 'demo-score-1', bar: 11, beat: null, kind: 'vowel', text: 'ah', page: 1, x: 0.53, y: 0.627, author_id: DEMO_TEACHER.id }
    ],
    files: {}
  };
}

// ---------------------------------------------------------------------------
// The demo score: four systems of four bars, drawn as an SVG "scan".
// ---------------------------------------------------------------------------
const PAGE_W = 1000;
const PAGE_H = 1100;
const SYSTEM_Y = [170, 430, 690, 950];
const BAR_X = [110, 330, 550, 770, 990];

export const DEMO_SCORE_BARS = SYSTEM_Y.flatMap((y, s) =>
  [0, 1, 2, 3].map((b) => ({ bar: s * 4 + b + 1, page: 1, x: (BAR_X[b] + 8) / PAGE_W, y: (y - 30) / PAGE_H }))
);

/** Suggested diagrams on the demo's cards, as extract-cues and caption-sketch would save them. */
export function demoSuggestions(): DiagramSuggestion[] {
  const s = (id: string, feedback_id: string, diagram_id: string, confidence: number, source: 'cues' | 'sketch',
             student_status: DiagramSuggestion['student_status'] = 'open'): DiagramSuggestion => ({
    id, feedback_id, diagram_id, confidence, source, teacher_status: 'open', student_status, created_at: ago(4, 18)
  });
  return [
    s('demo-sug-1', 'demo-fb-2', 'breath-low-high', 0.91, 'cues', 'accepted'),
    s('demo-sug-2', 'demo-fb-4', 'vowels-jaw', 0.82, 'sketch'),
    s('demo-sug-3', 'demo-fb-1', 'resonance-mask', 0.77, 'cues'),
    s('demo-sug-4', 'demo-fb-8', 'resonance-soft-palate', 0.73, 'cues')
  ];
}

export function demoScoreSvg(): string {
  const parts: string[] = [];
  parts.push(`<rect width="${PAGE_W}" height="${PAGE_H}" fill="#fffdf8"/>`);
  parts.push(`<text x="500" y="80" font-family="Georgia, serif" font-size="40" text-anchor="middle" fill="#222">Caro mio ben</text>`);
  parts.push(`<text x="940" y="120" font-family="Georgia, serif" font-size="20" text-anchor="end" fill="#444">T. Giordani</text>`);
  let r = 7;
  const rand = () => ((r = (r * 16807) % 2147483647) / 2147483647);
  SYSTEM_Y.forEach((y, s) => {
    for (let l = 0; l < 5; l++) parts.push(`<line x1="40" x2="990" y1="${y + l * 14}" y2="${y + l * 14}" stroke="#333" stroke-width="1.4"/>`);
    parts.push(`<text x="44" y="${y + 52}" font-size="74" font-family="serif" fill="#222">𝄞</text>`);
    BAR_X.forEach((x) => parts.push(`<line x1="${x}" x2="${x}" y1="${y}" y2="${y + 56}" stroke="#333" stroke-width="1.6"/>`));
    parts.push(`<text x="${BAR_X[0] + 2}" y="${y - 10}" font-size="16" font-family="Georgia, serif" fill="#555">${s * 4 + 1}</text>`);
    for (let b = 0; b < 4; b++) {
      const notes = 3 + Math.floor(rand() * 2);
      for (let n = 0; n < notes; n++) {
        const nx = BAR_X[b] + 40 + n * ((BAR_X[b + 1] - BAR_X[b] - 60) / notes);
        const ny = y + 7 * Math.floor(rand() * 9) - 7;
        const half = rand() > 0.7;
        parts.push(`<ellipse cx="${nx}" cy="${ny}" rx="9" ry="6.5" transform="rotate(-20 ${nx} ${ny})" fill="${half ? 'none' : '#222'}" stroke="#222" stroke-width="2"/>`);
        parts.push(`<line x1="${nx + 8}" x2="${nx + 8}" y1="${ny}" y2="${ny - 48}" stroke="#222" stroke-width="1.6"/>`);
      }
      const words = ['Ca-ro', 'mio', 'ben,', 'cre-di-mi', 'al-men,', 'sen-za', 'di', 'te', 'lan-gui-sce', 'il', 'cor.', 'Il', 'tuo', 'fe-del', 'so-spi-ra', 'ognor.'];
      parts.push(`<text x="${BAR_X[b] + 34}" y="${y + 100}" font-size="19" font-family="Georgia, serif" font-style="italic" fill="#333">${words[s * 4 + b]}</text>`);
    }
  });
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${PAGE_W} ${PAGE_H}">${parts.join('')}</svg>`;
}

/** The same sketch as the phone photographed it: a tilted, greyish page on a table. */
export function demoSketchPhotoSvg(): string {
  const inner = demoSketchSvg().replace(/^<svg[^>]*>/, '').replace(/<\/svg>$/, '').replace('fill="#f4f4ef"', 'fill="#c9c6bc"');
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 300">
  <rect width="400" height="300" fill="#4a3f36"/>
  <g transform="matrix(0.78 -0.06 0.04 0.74 52 58)">${inner}</g>
</svg>`;
}

/** A rough marker sketch, standing in for a photo of her whiteboard. */
export function demoSketchSvg(): string {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 300">
  <rect width="400" height="300" fill="#f4f4ef"/>
  <path d="M110 120 Q200 60 290 120" fill="none" stroke="#1b4f9c" stroke-width="5" stroke-linecap="round"/>
  <path d="M110 120 Q200 250 290 120" fill="none" stroke="#1b4f9c" stroke-width="5" stroke-linecap="round"/>
  <path d="M150 175 Q200 200 250 175" fill="none" stroke="#c0392b" stroke-width="5" stroke-linecap="round"/>
  <path d="M320 110 L320 200 M305 185 L320 205 L335 185" fill="none" stroke="#222" stroke-width="4" stroke-linecap="round"/>
  <text x="300" y="235" font-family="Comic Sans MS, cursive" font-size="20" fill="#222">jaw</text>
  <path d="M120 230 L175 190 M160 186 L178 188 L172 205" fill="none" stroke="#222" stroke-width="4" stroke-linecap="round"/>
  <text x="40" y="262" font-family="Comic Sans MS, cursive" font-size="20" fill="#222">tongue fwd</text>
  <text x="150" y="45" font-family="Comic Sans MS, cursive" font-size="26" fill="#222">"ah" not "aw"</text>
</svg>`;
}

/** Priya has invited the demo teacher to hear her whole lesson before an audition. */
export function demoPasses(): FullLessonPass[] {
  return [{
    id: 'demo-pass-1', lesson_id: L3, student_id: PRIYA, teacher_id: DEMO_TEACHER.id, asked_by: 'student',
    status: 'pending', note: 'Audition next Friday. Can you hear the whole thing?', created_at: ago(0, 9),
    decided_at: null, ends_at: null, ended_at: null
  }];
}

/** Sam's week since the last lesson: three sittings, two shared takes, three questions. */
export function demoAgenda(): Pick<DemoState, 'practiceSessions' | 'sharedTakes' | 'questions'> {
  const at = (days: number, hour: number, minute = 0) => {
    const d = new Date(Date.now() - days * day);
    d.setHours(hour, minute, 0, 0);
    return d.toISOString();
  };
  const sitting = (id: string, days: number, hour: number, mins: number, items: { feedback_id: string; takes: number }[]) => ({
    id, student_id: SAM, started_at: at(days, hour), ended_at: at(days, hour, mins), minutes: mins, items
  });
  const shared = (id: string, fb: string, days: number, hour: number, ms: number, extra: Partial<SharedTake>): SharedTake => ({
    id, student_id: SAM, feedback_id: fb, lesson_id: L1, session_id: null, recorded_at: at(days, hour, 5),
    duration_ms: ms, clip_path: null, pitch: null, feeling_note: null, shared_at: at(days, hour, 20),
    heard_at: null, heard_by: null, ...extra
  });
  return {
    practiceSessions: [
      sitting('demo-ps-1', 3, 8, 15, [{ feedback_id: 'demo-fb-1', takes: 3 }, { feedback_id: 'demo-fb-2', takes: 2 }]),
      sitting('demo-ps-2', 2, 19, 22, [{ feedback_id: 'demo-fb-3', takes: 6 }, { feedback_id: 'demo-fb-4', takes: 2 }]),
      sitting('demo-ps-3', 1, 7, 12, [{ feedback_id: 'demo-fb-3', takes: 4 }])
    ],
    sharedTakes: [
      shared('demo-st-1', 'demo-fb-3', 1, 7, 9400, {
        clip_path: 'demo-tone:demo-take-3:student', feeling_note: 'Thought "down and over" and it felt easier, but still tight on the F.',
        pitch: [
          { sung: 65.02, target: 65, verdict: 'IN_TUNE' }, { sung: 66.9, target: 67, verdict: 'IN_TUNE' },
          { sung: 68.55, target: 69, verdict: 'FLAT' }, { sung: 66.97, target: 67, verdict: 'IN_TUNE' }
        ]
      }),
      shared('demo-st-2', 'demo-fb-1', 3, 8, 7100, {
        clip_path: 'demo-tone:demo-take-1:student',
        pitch: [{ sung: 60.1, target: 60, verdict: 'IN_TUNE' }, { sung: 61.6, target: null, verdict: 'UNCLEAR' }]
      })
    ],
    questions: [
      { id: 'demo-q-1', student_id: SAM, feedback_id: 'demo-fb-3', lesson_id: L1, song_id: null, bar: 7,
        body: 'Bar 7 still cracks when I go for the F. Is it the breath or the vowel?', status: 'open',
        answer: null, answered_by: null, answered_at: null, created_at: at(1, 7, 30) },
      { id: 'demo-q-2', student_id: SAM, feedback_id: null, lesson_id: null, song_id: null, bar: null,
        body: 'How long should I warm up before practicing?', status: 'open',
        answer: null, answered_by: null, answered_at: null, created_at: at(2, 19, 40) },
      { id: 'demo-q-3', student_id: SAM, feedback_id: 'demo-fb-4', lesson_id: L1, song_id: null, bar: 11,
        body: 'Should the "ah" on "ben" feel this wide?', status: 'answered',
        answer: 'Yes, tall and wide is right. Keep the tongue tip down.', answered_by: DEMO_TEACHER.id,
        answered_at: at(2, 9), created_at: at(3, 8, 20) }
    ]
  };
}

/** The demo lessons as the phone groups them: a warmup, then the song. */
export function demoExercises(): Exercise[] {
  const ex = (id: string, lesson_id: string, start: number, end: number, kind: Exercise['kind'], title: string | null): Exercise =>
    ({ id, lesson_id, start_ms: start, end_ms: end, kind, title, reason: '' });
  return [
    ex('demo-ex-1', L1, min(1, 40), min(5, 30), 'warmup', 'Hum into "ah", five-note scale'),
    ex('demo-ex-2', L1, min(6, 20), min(23, 0), 'song', null),
    ex('demo-ex-3', L2, min(2, 30), min(9, 0), 'warmup', null),
    ex('demo-ex-4', L2, min(11, 0), min(22, 0), 'song', null),
    ex('demo-ex-5', L3, min(4, 0), min(8, 0), 'warmup', null)
  ];
}
