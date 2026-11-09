package com.cuenote.signal

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Builds interleaved stereo test audio: a tone at a given level per channel. */
object TestSignals {
    fun stereo(
        seconds: Double,
        leftDb: Double?,
        rightDb: Double?,
        leftHz: Double = 220.0,
        rightHz: Double = 330.0,
        sameSignal: Boolean = false,
        noiseDb: Double = -70.0,
    ): ShortArray {
        val frames = (seconds * LessonAudio.SAMPLE_RATE).toInt()
        val rnd = Random(42)
        val noise = amp(noiseDb)
        return ShortArray(frames * 2) { i ->
            val f = i / 2
            val t = f.toDouble() / LessonAudio.SAMPLE_RATE
            val left = (leftDb?.let { amp(it) * sin(2 * PI * leftHz * t) } ?: 0.0)
            val v = if (i % 2 == 0) {
                left + noise * rnd.nextDouble(-1.0, 1.0)
            } else if (sameSignal) {
                left
            } else {
                (rightDb?.let { amp(it) * sin(2 * PI * rightHz * t) } ?: 0.0) + noise * rnd.nextDouble(-1.0, 1.0)
            }
            v.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }

    /** Peak amplitude for a sine whose RMS is [db] dBFS. */
    private fun amp(db: Double) = 32768.0 * Math.pow(10.0, db / 20) * Math.sqrt(2.0)

    fun levels(samples: ShortArray) = StereoLevelMeter().apply { add(samples) }.result()
}
