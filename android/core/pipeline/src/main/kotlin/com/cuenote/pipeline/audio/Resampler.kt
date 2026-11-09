package com.cuenote.pipeline.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns 48 kHz interleaved stereo into two 16 kHz mono channels (the rate the
 * voice detector and whisper expect) with a low-pass filter so nothing above
 * 8 kHz folds back into the result. Streaming: it keeps the last few samples
 * of each block so 30-second chunk edges join seamlessly.
 */
class StereoDownsampler(private val factor: Int = 3, taps: Int = 63) {
    private val kernel: FloatArray = run {
        val cutoff = 0.9 / (2.0 * factor)       // just under the new Nyquist, as a fraction of the old rate
        val mid = (taps - 1) / 2.0
        val k = DoubleArray(taps) { i ->
            val x = i - mid
            val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
            val window = 0.54 - 0.46 * cos(2 * PI * i / (taps - 1))   // Hamming
            sinc * window
        }
        val sum = k.sum()
        FloatArray(taps) { (k[it] / sum).toFloat() }
    }
    private val history = Array(2) { FloatArray(kernel.size - 1) }
    private var phase = 0   // input samples to skip before the next output sample

    /** Returns (student, teacher) at the lower rate for [frames] interleaved stereo frames of [pcm]. */
    fun process(pcm: ShortArray, frames: Int = pcm.size / 2): Pair<FloatArray, FloatArray> {
        val h = kernel.size - 1
        val out = Array(2) { FloatArray(0) }
        var nextPhase = 0
        for (ch in 0..1) {
            // history + new samples, as floats
            val buf = FloatArray(h + frames)
            System.arraycopy(history[ch], 0, buf, 0, h)
            for (i in 0 until frames) buf[h + i] = pcm[i * 2 + ch] / 32768f
            val n = if (frames - phase <= 0) 0 else (frames - phase + factor - 1) / factor
            val res = FloatArray(n)
            for (o in 0 until n) {
                val end = h + phase + o * factor          // newest sample used
                var acc = 0f
                var j = end
                for (t in kernel.indices) { acc += kernel[t] * buf[j]; j-- }
                res[o] = acc
            }
            out[ch] = res
            System.arraycopy(buf, buf.size - h, history[ch], 0, h)
            nextPhase = phase + n * factor - frames
        }
        phase = nextPhase
        return out[0] to out[1]
    }
}
