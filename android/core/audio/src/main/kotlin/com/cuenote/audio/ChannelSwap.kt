package com.cuenote.audio

import android.content.Context
import com.cuenote.signal.MicVerdict

/**
 * Whether the student's transmitter arrives on the right input instead of the
 * left. The soundcheck or the "who's on which mic" step sets it before each
 * lesson (the transmitters are handed out at random), and the recording that follows
 * switches the channels back, so saved audio always has the student on the left.
 */
object ChannelSwap {
    private const val PREFS = "cuenote_audio"
    private const val KEY = "switch_left_right"

    fun isOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    /** What a soundcheck decided, and when. */
    data class Checked(val verdict: MicVerdict, val atMillis: Long) {
        val minutesAgo: Long get() = ((System.currentTimeMillis() - atMillis) / 60_000).coerceAtLeast(0)
    }

    /**
     * Saves the soundcheck's verdict, and switches the channels to match it, so
     * a lesson started soon after needn't ask who's on which mic.
     */
    fun saveSoundcheck(context: Context, verdict: MicVerdict, atMillis: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY, verdict.studentOnRight)
            .putBoolean(V_RIGHT, verdict.studentOnRight)
            .putString(V_CONFIDENCE, verdict.confidence.name)
            .putFloat(V_SEPARATION, verdict.separationDb?.toFloat() ?: Float.NaN)
            .putLong(V_AT, atMillis)
            .apply()
    }

    /** The last soundcheck's verdict, if it ran in the last [maxAgeMillis] (the same sitting, most likely). */
    fun recentSoundcheck(context: Context, maxAgeMillis: Long = FRESH_MS, now: Long = System.currentTimeMillis()): Checked? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong(V_AT, 0L)
        if (at == 0L || now - at > maxAgeMillis || now < at) return null
        val confidence = p.getString(V_CONFIDENCE, null)
            ?.let { runCatching { MicVerdict.Confidence.valueOf(it) }.getOrNull() } ?: return null
        val sep = p.getFloat(V_SEPARATION, Float.NaN).takeUnless { it.isNaN() }?.toDouble()
        return Checked(MicVerdict(p.getBoolean(V_RIGHT, false), confidence, sep), at)
    }

    /** Forgets the soundcheck's verdict: someone picked the mics by hand since. */
    fun clearSoundcheck(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(V_AT).apply()
    }

    /** Two hours: long enough for a soundcheck before the lesson, short enough that next week asks again. */
    const val FRESH_MS = 2 * 60 * 60 * 1000L

    private const val V_RIGHT = "soundcheck_student_on_right"
    private const val V_CONFIDENCE = "soundcheck_confidence"
    private const val V_SEPARATION = "soundcheck_separation_db"
    private const val V_AT = "soundcheck_at"
}
