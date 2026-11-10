package com.cuenote.pipeline.practice

import com.cuenote.pipeline.Yin
import com.cuenote.pipeline.audio.StereoDownsampler
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/*
 * M5: pitch after each practice take, compared with the teacher's sung demo.
 * Pure Kotlin, tested on the JVM. The thresholds are starting points, to be
 * tuned on real lessons; nothing here shows the student a number: notes are
 * "in tune", "a little flat", "a little sharp" or "unclear".
 */

/** A pitch line: one value per 32 ms frame, in semitones (MIDI numbers), NaN where unvoiced. */
@Serializable
data class Contour(
    val semis: List<Float>,
    /** YIN confidence per frame, 0..1. */
    val confidence: List<Float>,
) {
    val size: Int get() = semis.size

    companion object {
        const val HOP_MS = 32

        fun fromHz(hz: List<Float>, confidence: List<Float>): Contour =
            Contour(hz.map { if (it > 0) toSemi(it.toDouble()).toFloat() else Float.NaN }, confidence)

        fun toSemi(hz: Double): Double = 69 + 12 * ln(hz / 440.0) / ln(2.0)
    }
}

object PitchTrack {
    /** Frames with less YIN confidence than this are unclear (the piano or a breathy tone masked the voice). */
    const val CLEAR = 0.6f
    /** Quieter frames than this (dBFS) count as silence, not as unclear singing. */
    const val SILENCE_DB = -50.0

    /** The pitch line of 48 kHz mono audio. */
    fun of(mono48k: ShortArray): Contour {
        val stereo = ShortArray(mono48k.size * 2) { mono48k[it / 2] }
        val x = StereoDownsampler().process(stereo).first
        return ofSamples16k(x)
    }

    /** The pitch line of 16 kHz mono audio, one estimate every 32 ms (512 samples). */
    fun ofSamples16k(x: FloatArray): Contour {
        val yin = Yin()
        val hop = 512
        val n = maxOf(0, (x.size - yin.span) / hop + 1)
        val semis = ArrayList<Float>(n)
        val conf = ArrayList<Float>(n)
        for (f in 0 until n) {
            val start = f * hop
            var sum = 0.0
            for (i in start until start + hop) sum += x[i] * x[i].toDouble()
            val db = if (sum <= 0) -120.0 else 10 * kotlin.math.log10(sum / hop)
            if (db < SILENCE_DB) { semis += Float.NaN; conf += 0f; continue }
            val e = yin.estimate(x, start)
            semis += if (e.hz > 0) Contour.toSemi(e.hz).toFloat() else Float.NaN
            conf += e.confidence.toFloat()
        }
        return Contour(semis, conf)
    }
}

/** How one sung note of the take compared. */
@Serializable
data class NoteResult(
    val startFrame: Int,
    val endFrame: Int,
    /** The note sung, in semitones (rounded to the nearest note for naming). */
    val sung: Float,
    /** The demo's note at the same place in the phrase, in the take's octave; null without a demo. */
    val target: Float? = null,
    val verdict: Verdict,
) {
    @Serializable
    enum class Verdict { IN_TUNE, FLAT, SHARP, UNCLEAR, NO_TARGET }
}

/** A take's pitch, and how it went against the demo. */
@Serializable
data class TakePitch(
    val take: Contour,
    val notes: List<NoteResult>,
    /** The demo line, moved to the take's octave and time, for drawing under the take; null without a demo. */
    val demoAligned: List<Float>? = null,
    /** False when the take's notes didn't line up with the demo's (another song, or another part of it), so nothing was judged. */
    val matched: Boolean = true,
)

object PitchCompare {
    /** Off by more than this (cents) and a note is a little flat or sharp. */
    const val TOLERANCE_CENTS = 35.0
    /** A note: frames within this many semitones of their running middle. */
    const val NOTE_SPREAD = 0.7
    const val MIN_NOTE_FRAMES = 5
    /**
     * At least this share of the take's clear notes must line up with a demo
     * note, or the take is treated as not matching the demo. On the Morning
     * Light samples, a demo against itself lined up fully; a different melody
     * or a backing track lined up 55-65%.
     */
    const val MIN_MATCHED = 0.75

    /**
     * Splits [take] into notes and judges each against [demo] (a sung demo's
     * line, any octave). Without a demo, notes are only marked clear or unclear.
     */
    fun compare(take: Contour, demo: Contour?): TakePitch {
        val notes = notes(take)
        if (demo == null || demo.semis.none { !it.isNaN() }) {
            return TakePitch(take, notes.map { (a, b, s, clear) ->
                NoteResult(a, b, s, null, if (clear) NoteResult.Verdict.NO_TARGET else NoteResult.Verdict.UNCLEAR)
            })
        }
        // The teacher may demonstrate an octave away: move the demo by whole octaves to the take's range.
        val octave = 12f * ((median(take.semis) - median(demo.semis)) / 12f).roundToInt()
        val demoNotes = notes(demo).filter { it.clear }.map { it.copy(semis = it.semis + octave) }
        // Line the notes up in order (a sung phrase keeps its order), so a
        // note sung off pitch is still compared with the note it should be.
        val match = alignNotes(notes.map { it.semis }, demoNotes.map { it.semis })
        val clearNotes = notes.indices.filter { notes[it].clear }
        val lined = clearNotes.count { match[it] != null && abs(notes[it].semis - demoNotes[match[it]!!].semis) < 3f }
        if (clearNotes.isNotEmpty() && lined < MIN_MATCHED * clearNotes.size) {
            return TakePitch(take, notes.map { (a, b, s, clear) ->
                NoteResult(a, b, s, null, if (clear) NoteResult.Verdict.NO_TARGET else NoteResult.Verdict.UNCLEAR)
            }, matched = false)
        }
        val aligned = MutableList(take.size) { Float.NaN }
        val results = notes.mapIndexed { k, (a, b, sung, clear) ->
            val target = match[k]?.let { demoNotes[it].semis }
            if (target != null) for (f in a until b) aligned[f] = target
            val verdict = when {
                !clear -> NoteResult.Verdict.UNCLEAR
                target == null -> NoteResult.Verdict.NO_TARGET
                else -> {
                    val cents = (sung - target) * 100
                    when {
                        cents < -TOLERANCE_CENTS -> NoteResult.Verdict.FLAT
                        cents > TOLERANCE_CENTS -> NoteResult.Verdict.SHARP
                        else -> NoteResult.Verdict.IN_TUNE
                    }
                }
            }
            NoteResult(a, b, sung, target, verdict)
        }
        return TakePitch(take, results, aligned)
    }

    /** Skipping a note in either line costs this much (semitones' worth). */
    private const val GAP = 1.5f

    /**
     * Aligns the take's notes with the demo's in order (edit-distance style):
     * matching costs the pitch difference (capped), skipping a note costs
     * [GAP]. Returns, for each take note, the demo note it matches or null.
     */
    internal fun alignNotes(take: List<Float>, demo: List<Float>): List<Int?> {
        val n = take.size; val m = demo.size
        val cost = Array(n + 1) { FloatArray(m + 1) }
        for (i in 1..n) cost[i][0] = i * GAP
        for (j in 1..m) cost[0][j] = j * GAP
        for (i in 1..n) for (j in 1..m) {
            val sub = cost[i - 1][j - 1] + minOf(abs(take[i - 1] - demo[j - 1]), 3f)
            cost[i][j] = minOf(sub, cost[i - 1][j] + GAP, cost[i][j - 1] + GAP)
        }
        val out = MutableList<Int?>(n) { null }
        var i = n; var j = m
        while (i > 0 && j > 0) {
            val sub = cost[i - 1][j - 1] + minOf(abs(take[i - 1] - demo[j - 1]), 3f)
            when {
                cost[i][j] == sub -> { out[i - 1] = j - 1; i--; j-- }
                cost[i][j] == cost[i - 1][j] + GAP -> i--
                else -> j--
            }
        }
        return out
    }

    /** Runs of voiced frames that hold one pitch: (start, end, median, clear). */
    internal fun notes(c: Contour): List<Note> {
        val out = mutableListOf<Note>()
        var start = -1
        val run = mutableListOf<Float>()
        fun close(end: Int) {
            if (start >= 0 && end - start >= MIN_NOTE_FRAMES) {
                val conf = (start until end).map { c.confidence[it] }
                val clear = median(conf) >= PitchTrack.CLEAR
                out += Note(start, end, median(run), clear)
            }
            start = -1; run.clear()
        }
        for (i in 0 until c.size) {
            val s = c.semis[i]
            if (s.isNaN()) { close(i); continue }
            if (start >= 0 && abs(s - median(run)) > NOTE_SPREAD) close(i)
            if (start < 0) start = i
            run += s
        }
        close(c.size)
        return out
    }

    internal data class Note(val start: Int, val end: Int, val semis: Float, val clear: Boolean)

    private fun median(xs: List<Float>): Float {
        val v = xs.filter { !it.isNaN() }.sorted()
        return if (v.isEmpty()) Float.NaN else v[v.size / 2]
    }
}

/** A friendly line about a take, with no numbers. */
object PitchSummary {
    fun of(p: TakePitch): String {
        val judged = p.notes.filter { it.verdict in setOf(NoteResult.Verdict.IN_TUNE, NoteResult.Verdict.FLAT, NoteResult.Verdict.SHARP) }
        val flat = judged.count { it.verdict == NoteResult.Verdict.FLAT }
        val sharp = judged.count { it.verdict == NoteResult.Verdict.SHARP }
        val unclear = p.notes.count { it.verdict == NoteResult.Verdict.UNCLEAR }
        val unclearNote = when {
            unclear == 0 -> ""
            unclear * 3 >= p.notes.size -> " A lot of it was hard to hear clearly, so those notes are marked rather than judged."
            else -> " A few notes weren't clear enough to judge; they're marked."
        }
        if (!p.matched) return "This take didn't line up with their demo, so here's just its pitch.$unclearNote"
        if (p.demoAligned == null || judged.isEmpty()) {
            return if (p.notes.isEmpty()) "No clear notes were heard in this take." else "Here's the pitch of your take.$unclearNote"
        }
        val off = flat + sharp
        val main = when {
            off == 0 -> "Right with their demo all the way through."
            off * 4 <= judged.size -> "Mostly with their demo. " + which(flat, sharp) + "."
            off * 2 > judged.size && (flat == 0 || sharp == 0) ->
                "Most of it sat a little " + (if (flat > 0) "flat" else "sharp") + " of their demo. Try it slower, with their demo in your ear."
            else -> which(flat, sharp) + ". Try it slower, with their demo in your ear."
        }
        return main + unclearNote
    }

    private fun which(flat: Int, sharp: Int): String = when {
        flat > 0 && sharp > 0 -> "A few notes drifted a little off"
        flat == 1 -> "One note ran a little flat"
        flat in 2..3 -> "$flat notes ran a little flat"
        flat > 3 -> "Several notes ran a little flat"
        sharp == 1 -> "One note ran a little sharp"
        sharp in 2..3 -> "$sharp notes ran a little sharp"
        else -> "Several notes ran a little sharp"
    }
}
