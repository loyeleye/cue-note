package com.cuenote.signal

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Loudness of one channel over a stretch of audio, in dB relative to full scale. */
data class ChannelLevel(
    val rmsDb: Double,
    val peakDb: Double,
    /** Fraction of samples at or near full scale, a sign of clipping. */
    val clipFraction: Double,
)

data class StereoLevels(
    val student: ChannelLevel,
    val teacher: ChannelLevel,
    /** Correlation between the channels, -1..1. Close to 1 means the phone mixed to mono. */
    val correlation: Double,
    val frames: Long,
) {
    operator fun get(channel: Channel) = if (channel == Channel.STUDENT) student else teacher

    /** The same audio with left and right exchanged. */
    fun swapped() = copy(student = teacher, teacher = student)
}

/**
 * Exchanges left and right in interleaved stereo, in place. Used when the
 * student's transmitter turns out to be on the right input, so everything
 * downstream still sees the student on the left.
 */
fun swapStereoInPlace(samples: ShortArray, sampleCount: Int = samples.size) {
    var i = 0
    while (i + 1 < sampleCount) {
        val l = samples[i]
        samples[i] = samples[i + 1]
        samples[i + 1] = l
        i += 2
    }
}

/**
 * Accumulates levels for interleaved stereo audio. Feed it blocks with [add],
 * read the result with [result], and [reset] between setup-check steps.
 */
class StereoLevelMeter(private val clipThreshold: Int = 32_000) {
    private val sumSq = DoubleArray(2)
    private val peak = IntArray(2)
    private val clips = LongArray(2)
    private var sumLR = 0.0
    private var sumL = 0.0
    private var sumR = 0.0
    private var frames = 0L

    fun add(samples: ShortArray, sampleCount: Int = samples.size) {
        var i = 0
        while (i + 1 < sampleCount) {
            val l = samples[i].toInt()
            val r = samples[i + 1].toInt()
            accumulate(0, l); accumulate(1, r)
            sumLR += l.toDouble() * r
            sumL += l; sumR += r
            frames++
            i += 2
        }
    }

    private fun accumulate(ch: Int, s: Int) {
        sumSq[ch] += s.toDouble() * s
        val a = abs(s)
        peak[ch] = max(peak[ch], a)
        if (a >= clipThreshold) clips[ch]++
    }

    fun reset() {
        sumSq.fill(0.0); peak.fill(0); clips.fill(0)
        sumLR = 0.0; sumL = 0.0; sumR = 0.0; frames = 0
    }

    fun result(): StereoLevels {
        val n = max(frames, 1L).toDouble()
        fun level(ch: Int) = ChannelLevel(
            rmsDb = toDb(sqrt(sumSq[ch] / n)),
            peakDb = toDb(peak[ch].toDouble()),
            clipFraction = clips[ch] / n,
        )
        val meanL = sumL / n
        val meanR = sumR / n
        val cov = sumLR / n - meanL * meanR
        val varL = sumSq[0] / n - meanL * meanL
        val varR = sumSq[1] / n - meanR * meanR
        val corr = if (varL <= 0 || varR <= 0) 0.0 else cov / sqrt(varL * varR)
        return StereoLevels(level(0), level(1), corr, frames)
    }

    companion object {
        const val SILENCE_DB = -120.0
        fun toDb(amplitude: Double): Double =
            if (amplitude <= 0) SILENCE_DB else max(SILENCE_DB, 20 * log10(amplitude / 32768.0))
    }
}
