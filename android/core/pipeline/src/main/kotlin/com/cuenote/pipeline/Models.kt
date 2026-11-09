package com.cuenote.pipeline

import com.cuenote.ml.EnergyVad
import com.cuenote.ml.SileroVad
import com.cuenote.ml.Transcriber
import com.cuenote.ml.VoiceActivityDetector
import com.cuenote.ml.WhisperNative
import com.cuenote.ml.WhisperTranscriber
import java.io.File

/**
 * Picks the models to run with. With the native library and the model files
 * present it uses Silero and whisper; without them it falls back to the
 * loudness-only detector and no transcript, and says so in the timeline.
 */
class Models(val vad: VoiceActivityDetector, val transcriber: Transcriber?) : AutoCloseable {
    override fun close() {
        (vad as? AutoCloseable)?.close()
        (transcriber as? AutoCloseable)?.close()
    }

    companion object {
        const val SILERO = "ggml-silero-v5.1.2.bin"
        val WHISPER_PREFERENCE = listOf("ggml-base.en.bin", "ggml-small.en.bin", "ggml-tiny.en.bin")

        /**
         * [nativeDir] null means "load from the default library path", which is
         * what the phone does. [whisperModel] picks a specific file by name.
         */
        fun load(modelsDir: File?, nativeDir: File?, whisperModel: String? = null, threads: Int = 4): Models {
            val native = modelsDir != null && WhisperNative.available(nativeDir)
            val silero = modelsDir?.let { File(it, SILERO) }?.takeIf { native && it.isFile }
            val whisper = modelsDir?.let { dir ->
                (whisperModel?.let { listOf(it) } ?: WHISPER_PREFERENCE).map { File(dir, it) }.firstOrNull { it.isFile }
            }?.takeIf { native }
            return Models(
                vad = silero?.let { SileroVad(it) } ?: EnergyVad(),
                transcriber = whisper?.let { WhisperTranscriber(it, threads) },
            )
        }

        /** For the laptop: system properties set by Gradle, or the CUENOTE_MODELS variable. */
        fun fromEnvironment(whisperModel: String? = null): Models = load(
            modelsDir = (System.getProperty("cuenote.models") ?: System.getenv("CUENOTE_MODELS"))?.let(::File),
            nativeDir = System.getProperty("cuenote.nativeDir")?.let(::File),
            whisperModel = whisperModel,
        )
    }
}
