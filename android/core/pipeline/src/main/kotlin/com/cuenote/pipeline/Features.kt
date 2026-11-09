package com.cuenote.pipeline

import com.cuenote.ml.VAD_FRAME_SAMPLES
import com.cuenote.ml.VoiceActivityDetector
import com.cuenote.pipeline.audio.LessonFolder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/** Frame length used everywhere in the pipeline: 512 samples at 16 kHz = 32 ms. */
const val FRAME_MS = 32.0
fun frameToMs(frame: Int): Long = (frame * FRAME_MS).toLong()
fun msToFrame(ms: Long): Int = (ms / FRAME_MS).toInt()

/**
 * What the pipeline measures on each 32 ms frame of each channel. Index 0 is
 * the student channel, 1 the teacher's. These few numbers per frame are all
 * the segmenter looks at, so an hour of lesson is about 4 MB of features.
 */
class Features(val frames: Int) {
    val db = Array(2) { FloatArray(frames) }
    val pitchHz = Array(2) { FloatArray(frames) }
    val pitchConf = Array(2) { FloatArray(frames) }
    val vad = Array(2) { FloatArray(frames) }
    var vadName = ""

    fun write(file: File) {
        file.parentFile.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC); out.writeInt(frames); out.writeUTF(vadName)
            for (arr in listOf(db, pitchHz, pitchConf, vad)) for (ch in 0..1) arr[ch].forEach(out::writeFloat)
        }
    }

    companion object {
        private const val MAGIC = 0x43554546 // "CUEF"

        fun read(file: File): Features = DataInputStream(file.inputStream().buffered()).use { inp ->
            require(inp.readInt() == MAGIC) { "not a features file" }
            Features(inp.readInt()).apply {
                vadName = inp.readUTF()
                for (arr in listOf(db, pitchHz, pitchConf, vad)) for (ch in 0..1) {
                    for (i in 0 until frames) arr[ch][i] = inp.readFloat()
                }
            }
        }

        /**
         * Measures a lesson: loudness, YIN pitch and voice probability for both
         * channels, streamed chunk by chunk. [onProgress] gets 0..1.
         */
        fun measure(
            lesson: LessonFolder,
            vad: VoiceActivityDetector,
            silenceFloorDb: Double = -60.0,
            onProgress: (Double) -> Unit = {},
            isStopped: () -> Boolean = { false },
        ): Features {
            val total = (lesson.chunkFrames.sum() / 3 / VAD_FRAME_SAMPLES).toInt()
            val f = Features(total)
            f.vadName = vad.name
            val yin = Yin()
            // carry-over so frames line up across chunk edges
            val carry = arrayOf(FloatArray(0), FloatArray(0))
            var frame = 0
            var chunkNo = 0
            for ((s, t) in lesson.stream16k()) {
                if (isStopped()) throw StoppedException()
                val chans = arrayOf(carry[0] + s, carry[1] + t)
                // YIN looks a little past the frame's end, so keep enough tail for the next block
                val usable = maxOf(0, (chans[0].size - yin.span) / VAD_FRAME_SAMPLES)
                val n = minOf(usable, total - frame)
                for (ch in 0..1) {
                    val x = chans[ch]
                    val probs = vad.speechProbabilities(x.copyOfRange(0, n * VAD_FRAME_SAMPLES))
                    for (i in 0 until n) {
                        val base = i * VAD_FRAME_SAMPLES
                        var sum = 0.0
                        for (k in base until base + VAD_FRAME_SAMPLES) sum += x[k] * x[k]
                        val rms = sqrt(sum / VAD_FRAME_SAMPLES)
                        val level = if (rms <= 1e-9) -120.0 else 20 * log10(rms)
                        f.db[ch][frame + i] = level.toFloat()
                        f.vad[ch][frame + i] = probs.getOrElse(i) { 0f }
                        if (level > silenceFloorDb) {
                            val e = yin.estimate(x, base)
                            f.pitchHz[ch][frame + i] = e.hz.toFloat()
                            f.pitchConf[ch][frame + i] = e.confidence.toFloat()
                        }
                    }
                    carry[ch] = x.copyOfRange(n * VAD_FRAME_SAMPLES, x.size)
                }
                frame += n
                chunkNo++
                onProgress(chunkNo.toDouble() / lesson.chunks.size)
            }
            // the very end: whatever is left, without pitch
            for (ch in 0..1) {
                val x = carry[ch]
                var i = 0
                while (frame + i < total && (i + 1) * VAD_FRAME_SAMPLES <= x.size) {
                    var sum = 0.0
                    for (k in i * VAD_FRAME_SAMPLES until (i + 1) * VAD_FRAME_SAMPLES) sum += x[k] * x[k]
                    val rms = sqrt(sum / VAD_FRAME_SAMPLES)
                    f.db[ch][frame + i] = (if (rms <= 1e-9) -120.0 else 20 * log10(rms)).toFloat()
                    i++
                }
            }
            return f
        }
    }
}

class StoppedException : RuntimeException("pipeline stopped; it resumes from the last finished stage")
