package com.cuenote.signal

import com.cuenote.signal.practice.LatencyCheck
import com.cuenote.signal.practice.LatencyCheck.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class LatencyCheckTest {
    private val sr = LatencyCheck.SAMPLE_RATE

    /**
     * A pretend recording: room noise, and a sung "ta" (a 200 ms vowel at
     * 220 Hz with a quick attack) [delayMs] after each sung click, give or
     * take [jitterMs]. [skip] leaves some clicks unsung.
     */
    private fun recording(
        delayMs: Int,
        jitterMs: Int = 0,
        startFrame: Int = 0,
        roomDb: Double = -60.0,
        voiceDb: Double = -18.0,
        skip: Set<Int> = emptySet(),
        bleedDb: Double? = null,
        seed: Int = 1,
    ): ShortArray {
        val rnd = Random(seed)
        val total = startFrame + LatencyCheck.trackMs * sr / 1000 + sr
        val x = DoubleArray(total) { amp(roomDb) * rnd.nextDouble(-1.0, 1.0) * 1.7 }
        val clicks = LatencyCheck.clickTrack(sr)
        if (bleedDb != null) {
            val g = Math.pow(10.0, bleedDb / 20)
            for (i in clicks.indices) x[startFrame + i] += clicks[i] * g
        }
        for (i in LatencyCheck.LISTEN_CLICKS until LatencyCheck.CLICKS) {
            if (i in skip) continue
            val jitter = if (jitterMs == 0) 0 else rnd.nextInt(-jitterMs, jitterMs + 1)
            val at = startFrame + (LatencyCheck.clickTimesMs[i] + delayMs + jitter) * sr / 1000
            val len = sr / 5
            for (k in 0 until len) {
                if (at + k !in x.indices) break
                val attack = minOf(1.0, k / (sr * 0.004))
                val release = minOf(1.0, (len - k) / (sr * 0.03))
                x[at + k] += amp(voiceDb) * attack * release * sin(2 * PI * 220.0 * k / sr)
            }
        }
        return ShortArray(total) { x[it].coerceIn(-32768.0, 32767.0).toInt().toShort() }
    }

    private fun amp(db: Double) = 32768.0 * Math.pow(10.0, db / 20) * Math.sqrt(2.0)

    @Test fun `finds the delay of wired headphones`() {
        val r = LatencyCheck.measure(recording(delayMs = 60, jitterMs = 10), trackStartFrame = 0)
        r as Result.Measured
        assertTrue("got ${r.latencyMs}", abs(r.latencyMs - 60) <= 12)
        assertEquals(false, r.tooSlow)
    }

    @Test fun `counts from where the track started`() {
        val start = sr / 3
        val r = LatencyCheck.measure(recording(delayMs = 120, jitterMs = 8, startFrame = start), trackStartFrame = start.toLong())
        r as Result.Measured
        assertTrue("got ${r.latencyMs}", abs(r.latencyMs - 120) <= 12)
    }

    @Test fun `flags Bluetooth-like delays as too slow`() {
        val r = LatencyCheck.measure(recording(delayMs = 300, jitterMs = 10), trackStartFrame = 0)
        r as Result.Measured
        assertTrue("got ${r.latencyMs}", abs(r.latencyMs - 300) <= 12)
        assertTrue(r.tooSlow)
    }

    @Test fun `a few missed clicks are fine`() {
        val r = LatencyCheck.measure(recording(delayMs = 80, skip = setOf(3, 7)), trackStartFrame = 0)
        r as Result.Measured
        assertEquals(6, r.hits)
    }

    @Test fun `too many missed clicks ask to try again`() {
        val r = LatencyCheck.measure(recording(delayMs = 80, skip = setOf(2, 3, 4, 5, 6)), trackStartFrame = 0)
        assertTrue("got $r", r is Result.TooFewHits)
    }

    @Test fun `silence is too quiet`() {
        val r = LatencyCheck.measure(recording(delayMs = 80, skip = (0 until LatencyCheck.CLICKS).toSet()), trackStartFrame = 0)
        assertEquals(Result.TooQuiet, r)
    }

    @Test fun `wandering timing is uneven`() {
        val r = LatencyCheck.measure(recording(delayMs = 150, jitterMs = 140, seed = 3), trackStartFrame = 0)
        assertTrue("got $r", r is Result.Uneven || r is Result.TooFewHits)
    }

    @Test fun `quiet click bleed from the headphones doesn't fool it`() {
        val r = LatencyCheck.measure(recording(delayMs = 90, jitterMs = 6, bleedDb = -40.0), trackStartFrame = 0)
        r as Result.Measured
        assertTrue("got ${r.latencyMs}", abs(r.latencyMs - 90) <= 12)
    }

    @Test fun `the click track has every click`() {
        val t = LatencyCheck.clickTrack(sr)
        assertEquals(LatencyCheck.trackMs * sr / 1000, t.size)
        for (ms in LatencyCheck.clickTimesMs) {
            val at = ms * sr / 1000
            val peak = (at until at + sr / 100).maxOf { abs(t[it].toInt()) }
            assertTrue(peak > 8000)
        }
    }
}
