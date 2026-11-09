package com.cuenote.pipeline.synthetic

import com.cuenote.pipeline.Label
import com.cuenote.pipeline.Segment
import com.cuenote.pipeline.golden.GoldenLabels
import com.cuenote.signal.ChunkedWavSink
import com.cuenote.signal.LessonAudio
import com.cuenote.signal.LessonEvent
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Builds a fake lesson, laid out exactly like a real recording, with the
 * right answers written alongside it. The voices are computer-made: a buzzing
 * source shaped by vowel resonances, gliding and stopping like speech or
 * holding notes with vibrato like singing. The piano is struck, decaying
 * notes and chords. Each person's mic hears them loudly, the other person
 * quietly, and the piano mostly on the teacher's side.
 *
 * Synthetic lessons check that the pipeline's logic does what it should; the
 * real golden set is what proves it works on real voices.
 */
class SyntheticLesson(private val seed: Int, private val rounds: Int = 6) {
    private val rate = LessonAudio.SAMPLE_RATE
    private val rnd = Random(seed)

    /** One scripted happening. */
    data class Part(val label: Label, val startMs: Long, val endMs: Long, val take: Int? = null, val withPiano: Boolean = false)

    private val parts = mutableListOf<Part>()
    private val takeSpans = mutableListOf<LongArray>()
    private val feedback = mutableListOf<GoldenLabels.FeedbackLabel>()
    private val demos = mutableListOf<GoldenLabels.Span>()
    private val events = mutableListOf<LessonEvent>()
    private val takeMelodies = mutableListOf<List<Double>>()   // semitones relative to A3

    // voices
    private val student = Voice(speakHz = 125.0, singBase = 130.81, gain = 0.22)   // a low voice
    private val teacher = Voice(speakHz = 205.0, singBase = 261.63, gain = 0.2)

    private var cursor = 0L
    private lateinit var studentCh: FloatArray
    private lateinit var teacherCh: FloatArray
    private lateinit var piano: FloatArray

    fun write(dir: File): GoldenLabels {
        script()
        val totalMs = cursor + 1_500
        val n = (totalMs * rate / 1000).toInt()
        studentCh = FloatArray(n); teacherCh = FloatArray(n); piano = FloatArray(n)
        render()
        // the two mics: each hears its person loudly and the other quietly
        val noise = Random(seed + 1)
        val sink = ChunkedWavSink(File(dir, "audio"))
        val block = rate
        val buf = ShortArray(block * 2)
        var i = 0
        while (i < n) {
            val len = minOf(block, n - i)
            for (k in 0 until len) {
                val s = studentCh[i + k]; val t = teacherCh[i + k]; val p = piano[i + k]
                val left = s + 0.11f * t + 0.22f * p + 0.0004f * noise.nextFloat()
                val right = t + 0.11f * s + 0.75f * p + 0.0004f * noise.nextFloat()
                buf[2 * k] = toShort(left); buf[2 * k + 1] = toShort(right)
            }
            sink.write(buf, len * 2)
            i += len
        }
        sink.close()
        File(dir, "events.tsv").writeText(events.joinToString("") { it.toLine() + "\n" })
        File(dir, "summary.tsv").writeText("durationMs\t$totalMs\nchunks\t${(n + LessonAudio.FRAMES_PER_CHUNK - 1) / LessonAudio.FRAMES_PER_CHUNK}\ngaps\t0\n")
        val labels = GoldenLabels(
            lessonId = dir.name,
            takes = takeSpans.map { GoldenLabels.Span(it[0], it[1]) },
            feedback = feedback,
            demos = demos,
            segments = parts.filter { it.label != Label.SILENCE }.map { Segment(it.label, it.startMs, it.endMs) },
            notes = "synthetic lesson, seed $seed",
        )
        GoldenLabels.write(File(dir, "labels.json"), labels)
        return labels
    }

    // ---- the script ---------------------------------------------------------

    private fun script() {
        pause(1_500)
        // opening chat: no take before it, so it links to nothing
        teacherTalk(rnd.nextLong(4_000, 7_000), take = null)
        say(Label.STUDENT_SPEAK, rnd.nextLong(1_200, 2_200))
        teacherTalk(rnd.nextLong(2_000, 3_500), take = null, joinPrevious = true)
        for (r in 0 until rounds) {
            pause(rnd.nextLong(400, 1_200))
            val accompanied = rnd.nextInt(3) != 0
            if (accompanied && rnd.nextBoolean()) say(Label.PIANO, rnd.nextLong(2_000, 4_000))   // piano intro
            pause(rnd.nextLong(100, 400))
            take(accompanied)
            pause(rnd.nextLong(600, 1_500))
            val t = takeSpans.size - 1
            // feedback: talk, maybe a demo, maybe a student question, more talk
            teacherTalk(rnd.nextLong(2_500, 6_000), take = t)
            if (rnd.nextInt(3) != 0) {
                pause(rnd.nextLong(300, 700))
                demo(t)
                pause(rnd.nextLong(300, 700))
                teacherTalk(rnd.nextLong(1_500, 3_000), take = t, joinPrevious = true)
            }
            if (rnd.nextBoolean()) {
                pause(rnd.nextLong(400, 900))
                say(Label.STUDENT_SPEAK, rnd.nextLong(1_000, 2_200))       // "was that flat?"
                if (rnd.nextBoolean()) events += LessonEvent.Mark(cursor - 500)
                pause(rnd.nextLong(300, 800))
                teacherTalk(rnd.nextLong(2_000, 4_000), take = t, joinPrevious = true)
            }
            if (rnd.nextInt(4) == 0) {
                // she sketches something: a photo during or just after her talk
                events += LessonEvent.Photo(cursor + rnd.nextLong(2_000, 15_000), "photo_${r}.jpg")
            }
            if (rnd.nextInt(4) == 0) { pause(rnd.nextLong(1_500, 2_500)); say(Label.PIANO, rnd.nextLong(3_000, 6_000)) }
        }
    }

    private fun pause(ms: Long) { parts += Part(Label.SILENCE, cursor, cursor + ms); cursor += ms }

    private fun say(label: Label, ms: Long) { parts += Part(label, cursor, cursor + ms); cursor += ms }

    private fun teacherTalk(ms: Long, take: Int?, joinPrevious: Boolean = false) {
        val start = cursor
        say(Label.TEACHER_SPEAK, ms)
        val last = feedback.lastOrNull()
        if (joinPrevious && last != null) feedback[feedback.size - 1] = last.copy(endMs = cursor)
        else feedback += GoldenLabels.FeedbackLabel(start, cursor, take)
    }

    private fun take(withPiano: Boolean) {
        val start = cursor
        val melody = melody()
        takeMelodies += melody
        val phrases = if (rnd.nextBoolean()) 2 else 1
        val perPhrase = melody.size / phrases
        for (p in 0 until phrases) {
            val notes = melody.subList(p * perPhrase, if (p == phrases - 1) melody.size else (p + 1) * perPhrase)
            val ms = notes.size * NOTE_MS
            parts += Part(Label.STUDENT_SING, cursor, cursor + ms, takeSpans.size, withPiano)
            noteBank += NoteRun(true, cursor, notes, withPiano)
            cursor += ms
            if (p < phrases - 1) pause(rnd.nextLong(350, 650))   // a breath
        }
        takeSpans += longArrayOf(start, cursor)
    }

    private fun demo(take: Int) {
        // she sings a piece of the take's melody, an octave up from the student
        val m = takeMelodies[take]
        val from = rnd.nextInt(0, maxOf(1, m.size - 4))
        val notes = m.subList(from, minOf(m.size, from + rnd.nextInt(4, 7)))
        val ms = notes.size * NOTE_MS
        parts += Part(Label.TEACHER_SING, cursor, cursor + ms)
        noteBank += NoteRun(false, cursor, notes, false)
        demos += GoldenLabels.Span(cursor, cursor + ms, take)
        cursor += ms
        val last = feedback.last()
        feedback[feedback.size - 1] = last.copy(endMs = cursor)
    }

    private fun melody(): List<Double> {
        val n = rnd.nextInt(8, 14)
        val scale = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12)
        var deg = rnd.nextInt(0, 4)
        return List(n) {
            deg = (deg + rnd.nextInt(-2, 3)).coerceIn(0, scale.size - 1)
            scale[deg].toDouble()
        }
    }

    // ---- rendering ----------------------------------------------------------

    private data class NoteRun(val student: Boolean, val startMs: Long, val notes: List<Double>, val piano: Boolean)
    private val noteBank = mutableListOf<NoteRun>()

    private fun render() {
        for (p in parts) when (p.label) {
            Label.STUDENT_SPEAK -> speech(studentCh, student, p.startMs, p.endMs)
            Label.TEACHER_SPEAK -> speech(teacherCh, teacher, p.startMs, p.endMs)
            Label.PIANO -> pianoPassage(p.startMs, p.endMs)
            else -> Unit
        }
        for (run in noteBank) {
            val v = if (run.student) student else teacher
            val ch = if (run.student) studentCh else teacherCh
            sing(ch, v, run.startMs, run.notes)
            if (run.piano) for ((i, semis) in run.notes.withIndex()) {
                if (i % 2 == 0) chord(run.startMs + i * NOTE_MS, v.singBase * 2.0.pow(semis / 12))
            }
        }
    }

    private class Voice(val speakHz: Double, val singBase: Double, val gain: Double)

    private val vowels = arrayOf(
        doubleArrayOf(730.0, 1090.0, 2440.0), doubleArrayOf(530.0, 1840.0, 2480.0),
        doubleArrayOf(270.0, 2290.0, 3010.0), doubleArrayOf(570.0, 840.0, 2410.0),
        doubleArrayOf(300.0, 870.0, 2240.0),
    )

    /** Harmonic amplitude through three vowel resonances. */
    private fun envelope(freq: Double, formants: DoubleArray): Double {
        var a = 0.0
        val bw = doubleArrayOf(90.0, 110.0, 170.0)
        for (k in 0..2) {
            val d = (freq - formants[k]) / bw[k]
            a += (1.0 / (1 + d * d)) * (if (k == 0) 1.0 else 0.6 / k)
        }
        return a + 0.02
    }

    /** Additive voice: f0 track and vowel per sample range, into [out]. */
    private fun voiced(out: FloatArray, from: Int, to: Int, gain: Double, f0: (Int) -> Double, formants: DoubleArray, attack: Int = 400) {
        val maxH = 40
        val phase = DoubleArray(maxH + 1)
        for (i in from until minOf(to, out.size)) {
            val f = f0(i)
            val env = minOf(1.0, (i - from).toDouble() / attack, (to - i).toDouble() / attack)
            var s = 0.0
            for (h in 1..maxH) {
                val hf = f * h
                if (hf > 7_000) break
                phase[h] += 2 * PI * hf / rate
                s += sin(phase[h]) * envelope(hf, formants) / h
            }
            out[i] += (gain * env * s * 0.6).toFloat()
        }
    }

    private fun speech(out: FloatArray, v: Voice, startMs: Long, endMs: Long) {
        var t = startMs
        while (t < endMs - 120) {
            val syll = rnd.nextLong(110, 260)
            val end = minOf(endMs, t + syll)
            val a = (t * rate / 1000).toInt(); val b = (end * rate / 1000).toInt()
            val startHz = v.speakHz * 2.0.pow(rnd.nextDouble(-3.0, 4.0) / 12)
            val slope = rnd.nextDouble(-9.0, 6.0) / 12          // octaves per second, a glide
            val formants = vowels[rnd.nextInt(vowels.size)]
            voiced(out, a, b, v.gain * rnd.nextDouble(0.7, 1.1), { i -> startHz * 2.0.pow(slope * (i - a) / rate) }, formants, attack = 240)
            // a consonant: a little noise, then a short gap
            val gap = rnd.nextLong(25, 90) + if (rnd.nextInt(5) == 0) rnd.nextLong(120, 300) else 0
            val c0 = b; val c1 = minOf(out.size, b + (gap * rate / 1000 / 2).toInt())
            for (i in c0 until c1) out[i] += (v.gain * 0.08 * (rnd.nextDouble() * 2 - 1)).toFloat()
            t = end + gap
        }
    }

    private fun sing(out: FloatArray, v: Voice, startMs: Long, notes: List<Double>) {
        val a = (startMs * rate / 1000).toInt()
        val noteSamples = (NOTE_MS * rate / 1000).toInt()
        val b = a + notes.size * noteSamples
        val vib = rnd.nextDouble(5.0, 6.2)
        val formants = vowels[rnd.nextInt(vowels.size)]
        val jitter = Random(seed * 31 + a)
        var drift = 0.0
        voiced(out, a, b, v.gain, { i ->
            val k = ((i - a) / noteSamples).coerceAtMost(notes.size - 1)
            val inNote = (i - a) - k * noteSamples
            var semis = notes[k]
            // a short glide from the previous note
            if (k > 0 && inNote < 1_800) semis = notes[k - 1] + (notes[k] - notes[k - 1]) * inNote / 1_800.0
            val vibDepth = if (inNote < 6_000) 0.0 else 0.35
            if (i % 480 == 0) drift = (drift * 0.9 + jitter.nextDouble(-0.02, 0.02))
            v.singBase * 2.0.pow((semis + vibDepth * sin(2 * PI * vib * i / rate) + drift) / 12)
        }, formants, attack = 900)
    }

    private fun pianoNote(atMs: Long, hz: Double, gain: Double, decay: Double) {
        val a = (atMs * rate / 1000).toInt()
        val len = (rate * minOf(3.0, decay * 4)).toInt()
        for (h in 1..8) {
            val f = hz * h * (1 + 0.0004 * h * h)       // slight stiffness, like real strings
            if (f > 7_000) break
            val amp = gain / h.toDouble().pow(1.3)
            for (i in 0 until len) {
                val idx = a + i
                if (idx >= piano.size) break
                val env = (if (i < 200) i / 200.0 else 1.0) * exp(-i / (rate * decay / (1 + 0.3 * h)))
                piano[idx] += (amp * env * sin(2 * PI * f * i / rate)).toFloat()
            }
        }
    }

    private fun chord(atMs: Long, rootHz: Double) {
        val root = rootHz / 2
        for (semi in intArrayOf(0, 4, 7)) pianoNote(atMs, root * 2.0.pow(semi / 12.0), 0.07, rnd.nextDouble(0.5, 1.0))
    }

    private fun pianoPassage(startMs: Long, endMs: Long) {
        var t = startMs
        val base = 220.0 * 2.0.pow(rnd.nextInt(-5, 5) / 12.0)
        while (t < endMs - 300) {
            if (rnd.nextInt(3) == 0) chord(t, base * 2.0.pow(rnd.nextInt(0, 8) / 12.0))
            else pianoNote(t, base * 2.0.pow(rnd.nextInt(0, 12) / 12.0), 0.12, rnd.nextDouble(0.4, 0.9))
            t += rnd.nextLong(250, 600)
        }
    }

    private fun toShort(x: Float): Short = (x * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()

    companion object {
        const val NOTE_MS = 600L
    }
}
