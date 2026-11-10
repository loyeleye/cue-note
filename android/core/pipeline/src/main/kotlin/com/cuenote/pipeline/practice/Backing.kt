package com.cuenote.pipeline.practice

import com.cuenote.pipeline.Pipeline
import com.cuenote.pipeline.Take
import com.cuenote.pipeline.audio.LessonFolder
import com.cuenote.signal.Channel
import com.cuenote.signal.LessonAudio
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * M5: backing tracks for practice, cut from the teacher's channel of the
 * lesson recording (right = teacher; the Hollyland transmitters keep the two
 * people on separate channels). Pure Kotlin, so it is tested on the JVM.
 *
 *   BackingPlan    which stretches of a card become tracks
 *   BackingAudio   cuts one stretch as 48 kHz mono
 *   StartingNote   the first sung note of the lesson take, and a tone for it
 *   TimeStretch    slower practice without changing the pitch (WSOLA)
 */

/** One playable track for a card. */
data class BackingSpan(
    val kind: Kind,
    val startMs: Long,
    val endMs: Long,
    /** Demos are numbered from 1 within the card. */
    val number: Int = 1,
) {
    enum class Kind {
        /**
         * The teacher's channel while the student sang the lesson take: the
         * piano (and anything the teacher sang along), to sing along with.
         */
        ACCOMPANIMENT,
        /** The teacher singing the phrase for the student to copy. */
        DEMO,
    }

    val durationMs: Long get() = endMs - startMs
    val fileName: String get() = "${kind.name.lowercase()}_$number.wav"
}

object BackingPlan {
    /** A count-in's worth of the piano before the take starts. */
    const val LEAD_IN_MS = 1_500L
    const val TAIL_MS = 500L
    const val DEMO_PAD_MS = 200L
    /** Longer tracks are cut short; a practice loop rarely needs more. */
    const val MAX_MS = 3 * 60_000L

    /**
     * The tracks for the feedback block [feedbackLocalId] (the pipeline's id,
     * "<lesson>-fb-001"): the accompaniment of the take it is about, when the
     * piano played under it, then each sung demo.
     */
    fun forCard(out: Pipeline.Output, feedbackLocalId: String): List<BackingSpan> {
        val fb = out.feedback.firstOrNull { it.id == feedbackLocalId } ?: return emptyList()
        val take = fb.takeId?.let { id -> out.takes.firstOrNull { it.id == id } }
        val spans = mutableListOf<BackingSpan>()
        if (take != null && take.withPiano) {
            spans += span(BackingSpan.Kind.ACCOMPANIMENT, take.startMs - LEAD_IN_MS, take.endMs + TAIL_MS, out.durationMs, 1)
        }
        fb.demos.sortedBy { it.startMs }.forEachIndexed { i, d ->
            spans += span(BackingSpan.Kind.DEMO, d.startMs - DEMO_PAD_MS, d.endMs + DEMO_PAD_MS, out.durationMs, i + 1)
        }
        return spans.filter { it.durationMs > 0 }
    }

    private fun span(kind: BackingSpan.Kind, a: Long, b: Long, lessonMs: Long, n: Int): BackingSpan {
        val start = a.coerceAtLeast(0)
        val end = minOf(b, if (lessonMs > 0) lessonMs else Long.MAX_VALUE, start + MAX_MS)
        return BackingSpan(kind, start, end, n)
    }
}

object BackingAudio {
    /** Fades at each end, so a track doesn't start or stop with a click. */
    const val FADE_MS = 40

    /** The teacher's channel over [span], as 48 kHz mono, faded and evened out in level. */
    fun cut(folder: LessonFolder, span: BackingSpan): ShortArray {
        val first = LessonAudio.msToFrames(span.startMs)
        val count = LessonAudio.msToFrames(span.endMs) - first
        val stereo = folder.readFrames(first, count.toInt())
        return fromStereo(stereo, Channel.TEACHER)
    }

    /** One channel of interleaved stereo, faded and normalised. */
    fun fromStereo(stereo: ShortArray, channel: Channel): ShortArray {
        val n = stereo.size / 2
        val mono = FloatArray(n) { stereo[2 * it + channel.index].toFloat() }
        var peak = 1f
        for (v in mono) peak = maxOf(peak, abs(v))
        // To about -3 dBFS, at most 4x so a quiet track isn't turned into hiss.
        val gain = minOf(4f, 0.7f * Short.MAX_VALUE / peak)
        val fade = minOf(n / 2, LessonAudio.SAMPLE_RATE * FADE_MS / 1000)
        return ShortArray(n) { i ->
            val f = when {
                i < fade -> i.toFloat() / fade
                i >= n - fade -> (n - 1 - i).toFloat() / fade
                else -> 1f
            }
            (mono[i] * gain * f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }
}

/** The note the lesson take starts on, for the "starting note" button. */
object StartingNote {
    private val names = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")

    data class Note(val midi: Int) {
        val hz: Double get() = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val name: String get() = names[midi % 12] + (midi / 12 - 1)
    }

    /**
     * The first steady sung note of [take]: the first run of at least
     * [minFrames] confident frames within half a semitone of each other,
     * rounded to the nearest note. Null when nothing is clear enough.
     */
    fun of(take: Take, minConfidence: Float = 0.6f, minFrames: Int = 5): Note? {
        val hz = take.pitchHz
        val conf = take.pitchConfidence
        var run = mutableListOf<Double>()
        for (i in hz.indices) {
            val ok = hz[i] > 0 && (conf.getOrNull(i) ?: 0f) >= minConfidence
            val semi = if (ok) 69 + 12 * ln(hz[i] / 440.0) / ln(2.0) else Double.NaN
            if (ok && (run.isEmpty() || abs(semi - run.sorted()[run.size / 2]) <= 0.5)) {
                run.add(semi)
                if (run.size >= minFrames) return Note(run.sorted()[run.size / 2].roundToInt())
            } else {
                run = if (ok) mutableListOf(semi) else mutableListOf()
            }
        }
        return null
    }

    /** A soft, piano-like tone for [note]: a few harmonics that die away, 48 kHz mono. */
    fun tone(note: Note, ms: Int = 1_500, sampleRate: Int = LessonAudio.SAMPLE_RATE): ShortArray {
        val n = ms * sampleRate / 1000
        val f = note.hz
        val attack = sampleRate * 0.008
        return ShortArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val env = minOf(1.0, i / attack) * exp(-t * 2.2) * minOf(1.0, (n - i) / (sampleRate * 0.05))
            val v = 0.55 * sin(2 * PI * f * t) + 0.25 * sin(4 * PI * f * t) * exp(-t * 2) + 0.12 * sin(6 * PI * f * t) * exp(-t * 3)
            (0.6 * env * v * Short.MAX_VALUE).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }
}

/**
 * Slows a track down without changing its pitch, by WSOLA: overlapping
 * windows are laid down at the new pace, each one nudged (within a small
 * search range) to where it lines up best with the last, so the waveform
 * stays continuous and the voice doesn't warble.
 */
object TimeStretch {
    /** [rate] < 1 is slower (0.75 = three quarters of the speed). */
    fun stretch(x: ShortArray, rate: Double, sampleRate: Int = LessonAudio.SAMPLE_RATE): ShortArray {
        require(rate in 0.25..4.0) { "rate out of range" }
        if (rate == 1.0 || x.isEmpty()) return x.copyOf()
        val win = sampleRate * 40 / 1000 // 40 ms windows
        val hopOut = win / 2
        val hopIn = hopOut * rate
        val search = sampleRate * 10 / 1000 // look 10 ms either way
        val outLen = (x.size / rate).toInt()
        val out = FloatArray(outLen + win)
        val norm = FloatArray(outLen + win)
        val hann = FloatArray(win) { (0.5 - 0.5 * kotlin.math.cos(2 * PI * it / win)).toFloat() }
        var prevEnd = 0 // where the natural continuation of the last window starts in x
        var outPos = 0
        var first = true
        while (outPos < outLen) {
            val target = (outPos / hopOut * hopIn).toInt()
            val start = if (first) 0 else bestMatch(x, prevEnd, target, search, win)
            if (start + win > x.size) break
            for (k in 0 until win) {
                out[outPos + k] += x[start + k] * hann[k]
                norm[outPos + k] += hann[k]
            }
            prevEnd = start + hopOut
            outPos += hopOut
            first = false
        }
        return ShortArray(outLen) { i ->
            val v = if (norm[i] > 1e-3f) out[i] / norm[i] else 0f
            v.roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** The start near [target] whose first half-window best matches x from [natural]. */
    private fun bestMatch(x: ShortArray, natural: Int, target: Int, search: Int, win: Int): Int {
        val len = win / 2
        if (natural + len > x.size) return target.coerceIn(0, maxOf(0, x.size - win))
        var best = target
        var bestScore = Double.NEGATIVE_INFINITY
        val from = (target - search).coerceAtLeast(0)
        val to = (target + search).coerceAtMost(x.size - win)
        var s = from
        while (s <= to) {
            var dot = 0.0
            var k = 0
            while (k < len) { dot += x[s + k].toDouble() * x[natural + k]; k += 2 }
            if (dot > bestScore) { bestScore = dot; best = s }
            s++
        }
        return best
    }
}
