package com.cuenote.ml

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * A stand-in voice detector that only looks at loudness. It cannot tell a
 * voice from a piano, so the pipeline uses it only in tests and when the
 * Silero model is missing; the debug timeline says which one ran.
 */
class EnergyVad(private val onDb: Double = -45.0, private val fullDb: Double = -30.0) : VoiceActivityDetector {
    override val name = "energy"

    override fun speechProbabilities(samples: FloatArray): FloatArray {
        val frames = samples.size / VAD_FRAME_SAMPLES
        return FloatArray(frames) { f ->
            var sum = 0.0
            val base = f * VAD_FRAME_SAMPLES
            for (i in base until base + VAD_FRAME_SAMPLES) sum += samples[i] * samples[i]
            val rms = sqrt(sum / VAD_FRAME_SAMPLES)
            val db = if (rms <= 0) -120.0 else 20 * log10(rms)
            ((db - onDb) / (fullDb - onDb)).coerceIn(0.0, 1.0).toFloat()
        }
    }
}
