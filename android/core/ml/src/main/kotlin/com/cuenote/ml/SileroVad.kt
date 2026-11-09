package com.cuenote.ml

import java.io.File

/**
 * Silero voice activity detection, run by whisper.cpp's own implementation
 * (model file ggml-silero-v5.1.2.bin, under 1 MB). Silero is trained to fire on
 * voices and stay quiet for music, which is what lets the pipeline tell the
 * teacher talking apart from the piano on her channel.
 */
class SileroVad(model: File, private val threads: Int = 2) : VoiceActivityDetector, AutoCloseable {
    override val name = "silero"
    private var ctx: Long

    init {
        require(model.isFile) { "Silero model missing: $model" }
        ctx = WhisperNative.initVad(model.absolutePath, threads)
        check(ctx != 0L) { "Silero could not load $model" }
    }

    override fun speechProbabilities(samples: FloatArray): FloatArray {
        check(ctx != 0L) { "VAD is closed" }
        val frames = samples.size / VAD_FRAME_SAMPLES
        if (frames == 0) return FloatArray(0)
        // Feed in ten-minute blocks so memory stays small on an hour-long lesson.
        val block = VAD_FRAME_SAMPLES * 18_750
        val out = FloatArray(frames)
        var pos = 0
        var frame = 0
        while (frame < frames) {
            val len = minOf(block, (frames - frame) * VAD_FRAME_SAMPLES)
            val probs = WhisperNative.vadProbabilities(ctx, samples.copyOfRange(pos, pos + len))
            val n = minOf(probs.size, frames - frame)
            System.arraycopy(probs, 0, out, frame, n)
            frame += len / VAD_FRAME_SAMPLES
            pos += len
        }
        return out
    }

    override fun close() {
        if (ctx != 0L) WhisperNative.freeVad(ctx)
        ctx = 0
    }
}
