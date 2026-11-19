package com.cuenote.pipeline.reading

import com.cuenote.pipeline.Yin
import com.cuenote.pipeline.audio.StereoDownsampler
import com.cuenote.pipeline.practice.Contour
import com.cuenote.pipeline.practice.PitchTrack
import kotlin.math.log10

/*
 * Reading and pitch: the exercises that teach a beginner to read a written
 * note, know how it sounds and match it with their voice. Pure Kotlin, so
 * the rules are tested on the JVM; the phone feeds it the mic.
 */

/**
 * Pitch while the student sings, not after: 48 kHz mono from the mic goes
 * in, a frame comes out every 32 ms, using the same YIN tracker and the same
 * thresholds as the after-take pitch strip.
 */
class LivePitch {
    /** One 32 ms frame. [semis] is NaN when nothing clear is sung. */
    data class Frame(val semis: Float, val confidence: Float, val db: Double) {
        val clear: Boolean get() = !semis.isNaN() && confidence >= PitchTrack.CLEAR
    }

    private val down = StereoDownsampler()
    private val yin = Yin()
    private var pending = FloatArray(0)
    private val recent = ArrayDeque<Float>()

    /** Feeds [n] samples of 48 kHz mono; returns the frames that are now complete. */
    fun push(mono48k: ShortArray, n: Int = mono48k.size): List<Frame> {
        if (n <= 0) return emptyList()
        val stereo = ShortArray(n * 2) { mono48k[it / 2] }
        val x = down.process(stereo, n).first
        pending = pending + x
        val out = ArrayList<Frame>(2)
        var start = 0
        while (pending.size - start >= yin.span) {
            out += frame(pending, start)
            start += HOP
        }
        if (start > 0) pending = pending.copyOfRange(start, pending.size)
        return out
    }

    private fun frame(x: FloatArray, start: Int): Frame {
        var sum = 0.0
        for (i in start until start + HOP) sum += x[i] * x[i].toDouble()
        val db = if (sum <= 0) -120.0 else 10 * log10(sum / HOP)
        if (db < PitchTrack.SILENCE_DB) { recent.clear(); return Frame(Float.NaN, 0f, db) }
        val e = yin.estimate(x, start)
        if (e.hz <= 0 || e.confidence < PitchTrack.CLEAR) {
            return Frame(Float.NaN, e.confidence.toFloat(), db)
        }
        // A median of the last three clear frames steadies the marker
        // without lagging behind a real change of note.
        recent.addLast(Contour.toSemi(e.hz).toFloat())
        while (recent.size > 3) recent.removeFirst()
        val semis = recent.sorted()[recent.size / 2]
        return Frame(semis, e.confidence.toFloat(), db)
    }

    fun reset() { pending = FloatArray(0); recent.clear() }

    companion object {
        /** 512 samples at 16 kHz: 32 ms, as in [Contour.HOP_MS]. */
        const val HOP = 512
        const val FRAME_MS = Contour.HOP_MS
    }
}
