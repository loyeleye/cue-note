package com.cuenote.ml

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WhisperParseTest {
    @Test fun `parses segments and drops non-speech markers`() {
        val raw = "0\t1500\t And so my fellow Americans\n1500\t2000\t [MUSIC]\n2000\t2600\t ♪\n"
        assertEquals(listOf(TranscriptPiece(0, 1500, "And so my fellow Americans")), WhisperTranscriber.parse(raw))
    }

    @Test fun `energy vad follows loudness`() {
        val quiet = FloatArray(VAD_FRAME_SAMPLES)
        val loud = FloatArray(VAD_FRAME_SAMPLES) { if (it % 2 == 0) 0.3f else -0.3f }
        val p = EnergyVad().speechProbabilities(quiet + loud)
        assertEquals(0f, p[0]); assertEquals(1f, p[1])
    }
}

/**
 * Runs the real whisper.cpp and Silero models. Needs core/ml/host-build.sh to
 * have been run and CUENOTE_MODELS pointing at a folder with ggml-tiny.en.bin
 * (or ggml-base.en.bin) and ggml-silero-v5.1.2.bin. Skipped otherwise.
 */
class RealModelsTest {
    private val nativeDir = System.getProperty("cuenote.nativeDir")?.let(::File)
    private val models = System.getProperty("cuenote.models")?.let(::File)

    private fun model(vararg names: String): File? =
        names.map { File(models, it) }.firstOrNull { it.isFile }

    private fun jfk(): FloatArray {
        val bytes = javaClass.getResourceAsStream("/jfk.wav")!!.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray((bytes.size - 44) / 2) { buf.getShort(44 + it * 2) / 32768f }
    }

    private fun ready(): Boolean = models != null && WhisperNative.available(nativeDir)

    @Test fun `whisper transcribes the sample speech`() {
        assumeTrue("native library or models missing", ready())
        val m = model("ggml-tiny.en.bin", "ggml-base.en.bin")
        assumeTrue(m != null)
        WhisperTranscriber(m!!).use { w ->
            val text = w.transcribe(jfk()).joinToString(" ") { it.text }.lowercase()
            assertTrue(text, "fellow americans" in text && "your country" in text)
        }
    }

    @Test fun `silero hears speech and not silence`() {
        assumeTrue("native library or models missing", ready())
        val m = model("ggml-silero-v5.1.2.bin")
        assumeTrue(m != null)
        SileroVad(m!!).use { vad ->
            val speech = vad.speechProbabilities(jfk())
            val silence = vad.speechProbabilities(FloatArray(MODEL_SAMPLE_RATE * 3))
            assertEquals(jfk().size / VAD_FRAME_SAMPLES, speech.size)
            assertTrue("speech frames ${speech.count { it > 0.5f }}", speech.count { it > 0.5f } > speech.size / 2)
            assertTrue(silence.all { it < 0.3f })
        }
    }
}
