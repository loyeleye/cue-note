package com.cuenote.signal

/**
 * Notices audio that never arrived: compares the frames actually received
 * with what the elapsed (monotonic) time says should have arrived. A gap means
 * the recorder fell behind or the system dropped audio. The M1 gate is a
 * 60-minute screen-off lesson with zero gaps.
 */
class GapDetector(
    private val toleranceMs: Long = 250,
    private val sampleRate: Int = LessonAudio.SAMPLE_RATE,
) {
    data class Gap(val atMs: Long, val missingMs: Long)

    private var startNanos = -1L
    private var frames = 0L
    private var reportedMissingMs = 0L

    val gaps = mutableListOf<Gap>()

    /** Call after each read with the frames received and the current monotonic time. */
    fun onFrames(count: Int, nowNanos: Long): Gap? {
        if (startNanos < 0) {
            startNanos = nowNanos
            frames += count
            return null
        }
        frames += count
        val elapsedMs = (nowNanos - startNanos) / 1_000_000
        val receivedMs = frames * 1000 / sampleRate
        val missing = elapsedMs - receivedMs - reportedMissingMs
        if (missing > toleranceMs) {
            reportedMissingMs += missing
            return Gap(receivedMs, missing).also { gaps += it }
        }
        return null
    }
}
