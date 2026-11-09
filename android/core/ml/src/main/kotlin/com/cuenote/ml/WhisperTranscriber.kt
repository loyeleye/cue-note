package com.cuenote.ml

import java.io.File

/**
 * Speech to text with whisper.cpp. [model] is a ggml file such as
 * ggml-base.en.bin. Not thread-safe: use one per worker.
 */
class WhisperTranscriber(
    model: File,
    private val threads: Int = 4,
    private val language: String = "en",
) : Transcriber, AutoCloseable {
    override val name = "whisper:${model.nameWithoutExtension.removePrefix("ggml-")}"
    private var ctx: Long

    init {
        require(model.isFile) { "whisper model missing: $model" }
        ctx = WhisperNative.initContext(model.absolutePath)
        check(ctx != 0L) { "whisper could not load $model" }
    }

    override fun transcribe(samples: FloatArray, prompt: String?): List<TranscriptPiece> {
        check(ctx != 0L) { "transcriber is closed" }
        // whisper.cpp pads to at least one second; very short clips come back empty.
        if (samples.size < MODEL_SAMPLE_RATE / 4) return emptyList()
        val padded = if (samples.size < MODEL_SAMPLE_RATE) samples.copyOf(MODEL_SAMPLE_RATE + 1600) else samples
        return parse(WhisperNative.transcribe(ctx, padded, threads, language, prompt))
    }

    override fun close() {
        if (ctx != 0L) WhisperNative.freeContext(ctx)
        ctx = 0
    }

    companion object {
        /** Parses the "start\tend\ttext" lines the native side returns. */
        fun parse(raw: String): List<TranscriptPiece> = raw.lineSequence()
            .mapNotNull { line ->
                val p = line.split('\t', limit = 3)
                if (p.size < 3) return@mapNotNull null
                val text = p[2].trim()
                if (text.isEmpty() || isNoise(text)) null
                else TranscriptPiece(p[0].toLong(), p[1].toLong(), text)
            }
            .toList()

        // whisper writes things like "[MUSIC]", "(piano playing)" or "♪" for non-speech.
        private val noise = Regex("""^[\[(].*[\])]$|^[♪♫\s.]*$""")
        fun isNoise(text: String) = noise.matches(text)
    }
}
