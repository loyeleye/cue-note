package com.cuenote.signal

/**
 * Says whether a mic is "hearing" someone, steadily. It turns on above
 * [onDb] (the same -55 dBFS the recording screen's dot uses), turns off only
 * below [offDb], and once switched holds for [holdMs] so the label doesn't
 * flicker on consonants and breaths.
 */
class VoiceGate(
    private val onDb: Double = -55.0,
    private val offDb: Double = -60.0,
    private val holdMs: Long = 400,
) {
    var hearing = false
        private set
    private var changedAt = Long.MIN_VALUE / 2

    /** Feeds one level reading at [nowMs] and returns the steady state. */
    fun update(db: Double?, nowMs: Long): Boolean {
        val level = db ?: Double.NEGATIVE_INFINITY
        if (nowMs - changedAt < holdMs) return hearing
        val next = if (hearing) level > offDb else level > onDb
        if (next != hearing) { hearing = next; changedAt = nowMs }
        return hearing
    }
}

/**
 * Smooths a level for drawing: rises fast ([attackMs]) so a note shows at
 * once, falls slowly ([releaseMs]) so the bar doesn't jitter. Works on 0..1.
 */
class LevelFollower(private val attackMs: Double = 50.0, private val releaseMs: Double = 300.0) {
    var value = 0.0
        private set

    fun step(target: Double, dtMs: Double): Double {
        val tau = if (target > value) attackMs else releaseMs
        val k = 1 - kotlin.math.exp(-dtMs / tau)
        value += (target - value) * k
        return value
    }

    companion object {
        /** -60..0 dBFS onto 0..1, the scale every level bar in the app uses. */
        fun fraction(db: Double?): Double = (((db ?: -60.0) + 60.0) / 60.0).coerceIn(0.0, 1.0)
    }
}
