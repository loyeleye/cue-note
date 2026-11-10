package com.cuenote.signal.posture

/**
 * Decides from the student's mic whether they're singing, so the camera is
 * only on while they are. Voice on the student's channel, clearly louder
 * than the teacher's, turns it on quickly; it stays on through short
 * breaths and only turns off after [offAfterMs] of quiet, so the camera
 * isn't opened and closed between every phrase.
 */
class SingingGate(
    private val minStudentDb: Double = -42.0,
    private val louderThanTeacherDb: Double = 6.0,
    private val onAfterMs: Long = 300,
    private val offAfterMs: Long = 8_000,
) {
    var singing = false
        private set
    private var voiceSinceMs = -1L
    private var lastVoiceMs = -1L

    /** Call with each live level reading; returns true when [singing] changed. */
    fun onLevels(tMs: Long, studentDb: Double, teacherDb: Double): Boolean {
        val voice = studentDb >= minStudentDb && studentDb - teacherDb >= louderThanTeacherDb
        if (voice) {
            if (voiceSinceMs < 0) voiceSinceMs = tMs
            lastVoiceMs = tMs
        } else {
            voiceSinceMs = -1
        }
        val was = singing
        if (!singing && voice && tMs - voiceSinceMs >= onAfterMs) singing = true
        if (singing && lastVoiceMs >= 0 && tMs - lastVoiceMs >= offAfterMs) singing = false
        return was != singing
    }
}

/**
 * Audio always wins. Posture backs off when the phone warms up or the
 * recorder reports a gap, and it can be paused for other reasons (the
 * in-app camera taking a photo). [allowed] is true only when nothing
 * is holding it off.
 */
class PostureBackOff(
    /** Android's thermal status at or above which posture pauses: 2 is "moderate". */
    private val warmStatus: Int = 2,
    /** And it resumes once the status has been at or below 1 ("light") this long. */
    private val coolForMs: Long = 60_000,
    /** Each audio gap pauses posture this long. */
    private val gapPauseMs: Long = 3 * 60_000,
    /** After this many gaps, posture stays off for the rest of the lesson. */
    private val maxGaps: Int = 2,
) {
    private var warm = false
    private var coolSinceMs = -1L
    private var gapUntilMs = -1L
    private var gaps = 0
    private val holds = mutableSetOf<String>()

    fun onThermal(tMs: Long, status: Int) {
        if (status >= warmStatus) { warm = true; coolSinceMs = -1 }
        else if (warm && status <= warmStatus - 1) {
            if (coolSinceMs < 0) coolSinceMs = tMs
        }
    }

    fun onGap(tMs: Long) {
        gaps++
        gapUntilMs = tMs + gapPauseMs
    }

    fun hold(reason: String) { holds += reason }
    fun release(reason: String) { holds -= reason }

    /** Null when posture may run at [tMs], otherwise a few words on why not. */
    fun reason(tMs: Long): String? {
        if (warm && coolSinceMs >= 0 && tMs - coolSinceMs >= coolForMs) warm = false
        return when {
            gaps >= maxGaps -> "off after $gaps audio gaps"
            warm -> "phone warm"
            tMs < gapUntilMs -> "audio gap"
            holds.isNotEmpty() -> holds.first()
            else -> null
        }
    }

    fun allowed(tMs: Long) = reason(tMs) == null
}
