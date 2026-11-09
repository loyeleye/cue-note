package com.cuenote.audio

import android.content.Context

/**
 * Whether the student's transmitter arrives on the right input instead of the
 * left. The "who's on which mic" step sets it before each lesson (the
 * transmitters are handed out at random), and the recording that follows
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
}
