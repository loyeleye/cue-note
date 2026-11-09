package com.cuenote.ml

/**
 * All audio handed to these models is 16 kHz mono, as floats in -1..1.
 * The pipeline downsamples each lesson channel to this before calling them.
 */
const val MODEL_SAMPLE_RATE = 16_000

/** Silero works on 512-sample frames, 32 ms at 16 kHz. The pipeline uses the same frame. */
const val VAD_FRAME_SAMPLES = 512

/** Says, frame by frame, how likely it is that someone is speaking or singing. */
interface VoiceActivityDetector {
    /** One probability (0..1) per [VAD_FRAME_SAMPLES] samples of [samples]. */
    fun speechProbabilities(samples: FloatArray): FloatArray

    /** A short name for reports, such as "silero" or "energy". */
    val name: String
}

/** One stretch of recognised speech, times relative to the start of the audio handed in. */
data class TranscriptPiece(val startMs: Long, val endMs: Long, val text: String)

/** Turns speech into text. */
interface Transcriber {
    /**
     * Transcribes [samples]. [prompt] is optional context (for example the
     * vocabulary of a singing lesson) that nudges the model's spelling.
     */
    fun transcribe(samples: FloatArray, prompt: String? = null): List<TranscriptPiece>

    val name: String
}
