package com.cuenote.pipeline

import com.cuenote.pipeline.practice.Contour
import com.cuenote.pipeline.practice.NoteResult.Verdict
import com.cuenote.pipeline.practice.PitchCompare
import com.cuenote.pipeline.practice.PitchSummary
import com.cuenote.pipeline.practice.PitchTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class PitchCompareTest {
    /**
     * A sung phrase at 48 kHz: each (midi, seconds) note as a voice-like tone
     * (a few harmonics, gentle vibrato), with short gaps, and [noiseDb] of hiss.
     */
    private fun sing(notes: List<Pair<Double, Double>>, gap: Double = 0.12, noiseDb: Double = -70.0, seed: Int = 1): ShortArray {
        val sr = 48_000
        val rnd = Random(seed)
        val out = ArrayList<Short>()
        var phase = 0.0
        fun push(v: Double) { out += v.coerceIn(-32768.0, 32767.0).toInt().toShort() }
        val noise = 32768 * 10.0.pow(noiseDb / 20)
        repeat((0.3 * sr).toInt()) { push(noise * rnd.nextDouble(-1.0, 1.0)) }
        for ((midi, secs) in notes) {
            val n = (secs * sr).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / sr
                val hz = 440 * 2.0.pow((midi - 69) / 12) * (1 + 0.006 * sin(2 * PI * 5.5 * t))
                phase += 2 * PI * hz / sr
                val env = minOf(1.0, i / (0.03 * sr), (n - i) / (0.03 * sr))
                val v = 9000 * env * (sin(phase) + 0.4 * sin(2 * phase) + 0.2 * sin(3 * phase))
                push(v + noise * rnd.nextDouble(-1.0, 1.0))
            }
            repeat((gap * sr).toInt()) { push(noise * rnd.nextDouble(-1.0, 1.0)) }
        }
        return out.toShortArray()
    }

    private val tune = listOf(60.0, 62.0, 64.0, 65.0, 67.0) // C D E F G

    @Test fun `tracks a sung phrase note by note`() {
        val c = PitchTrack.of(sing(tune.map { it to 0.5 }))
        val notes = PitchCompare.compare(c, null).notes
        assertEquals(5, notes.size)
        notes.zip(tune).forEach { (n, midi) -> assertEquals(midi, n.sung.toDouble(), 0.25) }
        assertTrue(notes.all { it.verdict == Verdict.NO_TARGET })
    }

    @Test fun `in tune with a demo an octave lower and a bit slower`() {
        val take = PitchTrack.of(sing(tune.map { it to 0.45 }))
        val demo = PitchTrack.of(sing(tune.map { it - 12 to 0.6 }, seed = 2))
        val p = PitchCompare.compare(take, demo)
        assertEquals(5, p.notes.size)
        assertTrue(p.notes.map { it.verdict }.toString(), p.notes.all { it.verdict == Verdict.IN_TUNE })
        assertEquals("Right with their demo all the way through.", PitchSummary.of(p))
    }

    @Test fun `a flat note is marked flat`() {
        val sung = tune.mapIndexed { i, m -> (if (i == 3) m - 0.6 else m) to 0.5 }
        val p = PitchCompare.compare(PitchTrack.of(sing(sung)), PitchTrack.of(sing(tune.map { it to 0.5 }, seed = 3)))
        assertEquals(Verdict.FLAT, p.notes[3].verdict)
        assertEquals(4, p.notes.count { it.verdict == Verdict.IN_TUNE })
        assertEquals("Mostly with their demo. One note ran a little flat.", PitchSummary.of(p))
    }

    @Test fun `low-confidence notes are unclear, not wrong`() {
        val c = Contour(List(20) { 60f }, List(20) { 0.3f })
        val p = PitchCompare.compare(c, Contour(List(20) { 60f }, List(20) { 0.9f }))
        assertEquals(listOf(Verdict.UNCLEAR), p.notes.map { it.verdict })
        assertFalse(PitchSummary.of(p).contains("%"))
    }

    @Test fun `silence gives no notes`() {
        val p = PitchCompare.compare(PitchTrack.of(ShortArray(48_000)), null)
        assertTrue(p.notes.isEmpty())
        assertEquals("No clear notes were heard in this take.", PitchSummary.of(p))
    }
}
