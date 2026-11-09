package com.cuenote.signal

/**
 * The lesson recording format: 48 kHz, 16-bit, two channels.
 * Left is the student's transmitter, right is the teacher's.
 */
object LessonAudio {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    const val BITS_PER_SAMPLE = 16
    const val CHUNK_SECONDS = 30
    const val FRAMES_PER_CHUNK = SAMPLE_RATE * CHUNK_SECONDS

    fun framesToMs(frames: Long): Long = frames * 1000 / SAMPLE_RATE
    fun msToFrames(ms: Long): Long = ms * SAMPLE_RATE / 1000
}

enum class Channel(val index: Int) {
    STUDENT(0),
    TEACHER(1);

    val other: Channel get() = if (this == STUDENT) TEACHER else STUDENT
}
