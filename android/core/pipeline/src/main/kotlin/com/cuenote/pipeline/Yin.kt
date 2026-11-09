package com.cuenote.pipeline

/**
 * YIN pitch detection (de Cheveigné and Kawahara, 2002) on 16 kHz audio.
 * Gives a pitch and a confidence for each frame. The confidence is what lets
 * the app say "unclear" instead of "wrong" when the piano masks a voice.
 *
 * Written out here (about 60 lines) rather than pulled in from TarsosDSP:
 * TarsosDSP is not on Maven Central, and this is the whole algorithm.
 */
class Yin(
    private val sampleRate: Int = 16_000,
    private val window: Int = 512,
    minHz: Double = 60.0,
    maxHz: Double = 1_100.0,
    private val threshold: Double = 0.15,
) {
    private val tauMin = (sampleRate / maxHz).toInt().coerceAtLeast(2)
    private val tauMax = (sampleRate / minHz).toInt()
    private val diff = DoubleArray(tauMax + 2)

    /** Samples needed from [start] for one estimate. */
    val span get() = window + tauMax + 1

    data class Estimate(val hz: Double, val confidence: Double)

    /** Estimates the pitch of the [window] samples starting at [start] of [x]. */
    fun estimate(x: FloatArray, start: Int): Estimate {
        if (start + span > x.size) return Estimate(0.0, 0.0)
        // 1. difference function
        for (tau in 1..tauMax) {
            var sum = 0.0
            var i = start
            val end = start + window
            while (i < end) {
                val d = (x[i] - x[i + tau]).toDouble()
                sum += d * d
                i++
            }
            diff[tau] = sum
        }
        // 2. cumulative mean normalised difference
        diff[0] = 1.0
        var running = 0.0
        for (tau in 1..tauMax) {
            running += diff[tau]
            diff[tau] = if (running == 0.0) 1.0 else diff[tau] * tau / running
        }
        // 3. first dip under the threshold, else the global minimum
        var best = -1
        var tau = tauMin
        while (tau < tauMax) {
            if (diff[tau] < threshold) {
                while (tau + 1 < tauMax && diff[tau + 1] < diff[tau]) tau++
                best = tau
                break
            }
            tau++
        }
        if (best < 0) {
            best = tauMin
            for (t in tauMin..tauMax) if (diff[t] < diff[best]) best = t
        }
        val confidence = (1.0 - diff[best]).coerceIn(0.0, 1.0)
        // 4. parabolic interpolation for a finer period
        val refined = if (best in 1 until tauMax) {
            val a = diff[best - 1]; val b = diff[best]; val c = diff[best + 1]
            val den = a - 2 * b + c
            if (den != 0.0) best + 0.5 * (a - c) / den else best.toDouble()
        } else best.toDouble()
        return Estimate(sampleRate / refined, confidence)
    }
}
