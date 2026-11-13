package com.cuenote.pipeline

import com.cuenote.pipeline.audio.CompressedAudio
import com.cuenote.pipeline.audio.LessonFolder
import com.cuenote.signal.ChunkedWavSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class CompressedAudioTest {
    /** A minute of stereo "music" with a quiet start. */
    private fun music(frames: Int, seed: Int = 1): ShortArray {
        val r = Random(seed)
        return ShortArray(frames * 2) { i ->
            val k = i / 2
            val env = if (k < 10_000) 0.0 else 1.0
            (env * (6000 * sin(2 * PI * (220 + 30 * (i % 2)) * k / 48_000.0) + 2000 * sin(2 * PI * 3.1 * k / 48_000.0) * r.nextDouble())).toInt().toShort()
        }
    }

    /** What a lossy encoder does: a delay at the start, a little noise, padding at the end. */
    private fun encodeLike(orig: ShortArray, lag: Int, noise: Int): ShortArray {
        val r = Random(7)
        val out = ShortArray(orig.size + (lag + 1500) * 2)
        for (i in orig.indices) out[i + lag * 2] = (orig[i] + r.nextInt(-noise, noise + 1)).toShort()
        return out
    }

    @Test fun `the encoder delay is found and a good copy passes`() {
        val orig = music(96_000)
        val c = CompressedAudio.check(orig, encodeLike(orig, 2112, 200))
        assertEquals(2112, c.lag)
        assertTrue("snr ${c.snrDb}", c.faithful)
        val back = CompressedAudio.trim(encodeLike(orig, 2112, 0), 96_000, c.lag)
        assertArrayEquals(orig, back)
    }

    @Test fun `a broken copy fails the check`() {
        val orig = music(96_000)
        val garbage = music(96_000, seed = 9).copyOfRange(0, 96_000)   // half the length, different audio
        assertTrue(!CompressedAudio.check(orig, garbage).faithful)
    }

    @Test fun `a near-silent chunk passes when the difference is inaudible`() {
        val r = Random(3)
        val hiss = ShortArray(96_000) { r.nextInt(-30, 31).toShort() }
        val c = CompressedAudio.check(hiss, ShortArray(hiss.size))   // the encoder dropped the hiss
        assertTrue(c.snrDb < CompressedAudio.MIN_SNR_DB)
        assertTrue(c.faithful)
    }

    @Test fun `a lesson reads the same after its chunks are compressed`() {
        val dir = Files.createTempDirectory("lesson").toFile()
        val audio = File(dir, "audio")
        val sink = ChunkedWavSink(audio)
        val all = music(48_000 * 70)    // 70 s: three chunks
        sink.write(all, all.size); sink.close()
        val before = LessonFolder(dir)
        val wantFrames = before.chunkFrames
        val want = before.readFrames(48_000 * 29L, 48_000 * 2)   // across a chunk edge
        // "compress" every chunk: here the stand-in codec is a delayed copy kept in memory
        val fake = mutableMapOf<String, ShortArray>()
        for (chunk in before.chunks) {
            val pcm = before.samplesOf(chunk)
            val packed = File(audio, chunk.nameWithoutExtension + "." + CompressedAudio.EXTENSION)
            packed.writeText("stand-in")
            fake[packed.path] = encodeLike(pcm, 2112, 0)
            val c = CompressedAudio.check(pcm, fake.getValue(packed.path))
            CompressedAudio.writeEntry(audio, CompressedAudio.Entry(packed.name, pcm.size / 2L, c.lag))
            chunk.delete()
        }
        LessonFolder.decoder = com.cuenote.pipeline.audio.ChunkDecoder { fake.getValue(it.path) }
        try {
            val after = LessonFolder(dir)
            assertTrue(after.chunks.all { after.isCompressed(it) })
            assertEquals(wantFrames, after.chunkFrames)
            assertArrayEquals(want, after.readFrames(48_000 * 29L, 48_000 * 2))
        } finally {
            LessonFolder.decoder = null
            dir.deleteRecursively()
        }
    }
}
