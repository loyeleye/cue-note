package com.cuenote.pipeline

import com.cuenote.pipeline.audio.LessonFolder
import com.cuenote.pipeline.audio.StereoDownsampler
import com.cuenote.signal.ChunkedWavSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class YinTest {
    private fun tone(hz: Double, n: Int = 2048) = FloatArray(n) { (0.5 * sin(2 * PI * hz * it / 16_000)).toFloat() }

    @Test fun `finds the pitch of a tone with high confidence`() {
        for (hz in listOf(98.0, 220.0, 440.0, 880.0)) {
            val e = Yin().estimate(tone(hz), 0)
            assertEquals("pitch for $hz", hz, e.hz, hz * 0.01)
            assertTrue(e.confidence > 0.9)
        }
    }

    @Test fun `noise has low confidence`() {
        val r = Random(3)
        val noise = FloatArray(2048) { r.nextFloat() - 0.5f }
        assertTrue(Yin().estimate(noise, 0).confidence < 0.5)
    }
}

class DownsamplerTest {
    private fun stereoTone(hz: Double, frames: Int) = ShortArray(frames * 2) {
        (10_000 * sin(2 * PI * hz * (it / 2) / 48_000)).toInt().toShort()
    }

    private fun rms(x: FloatArray, from: Int = 200) = sqrt(x.drop(from).map { it * it }.average())

    @Test fun `keeps voice frequencies and removes what would fold back`() {
        val (low, _) = StereoDownsampler().process(stereoTone(1_000.0, 48_000))
        val (high, _) = StereoDownsampler().process(stereoTone(10_000.0, 48_000))
        assertEquals(16_000, low.size)
        assertEquals(10_000 / 32768.0 / sqrt(2.0), rms(low), 0.01)
        assertTrue("10 kHz should be filtered out, rms=${rms(high)}", rms(high) < 0.003)
    }

    @Test fun `block by block gives the same result as all at once`() {
        val pcm = stereoTone(440.0, 30_001)
        val (whole, _) = StereoDownsampler().process(pcm)
        val ds = StereoDownsampler()
        val parts = listOf(0 to 10_000, 10_000 to 20_001, 20_001 to 30_001).map { (a, b) ->
            ds.process(pcm.copyOfRange(a * 2, b * 2)).first
        }
        val joined = parts.reduce { a, b -> a + b }
        assertEquals(whole.size, joined.size)
        for (i in whole.indices) assertEquals(whole[i], joined[i], 1e-6f)
    }
}

class LessonFolderTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `reads across chunk edges at the right time`() {
        val dir = tmp.newFolder("lesson")
        // 2.5 s of a click every 500 ms on the left, in 1-second chunks
        val sink = ChunkedWavSink(java.io.File(dir, "audio"), framesPerChunk = 48_000)
        val pcm = ShortArray(48_000 * 5 / 2 * 2) { i -> if (i % 2 == 0 && (i / 2) % 24_000 < 48) 20_000 else 0 }
        sink.write(pcm); sink.close()
        val lesson = LessonFolder(dir)
        assertEquals(3, lesson.chunks.size)
        assertEquals(2_500, lesson.durationMs)
        val x = lesson.read16k(0, 900, 1_100)       // spans the first chunk edge, click at 1000 ms
        val peak = x.indices.maxBy { abs(x[it]) }
        assertEquals(100.0, peak / 16.0, 2.0)
        val streamed = lesson.stream16k().map { it.first }.reduce { a, b -> a + b }
        assertEquals(40_000, streamed.size)
    }
}
