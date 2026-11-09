package com.cuenote.audio

import android.content.Context

/**
 * Remembers whether the student's transmitter arrives on the right input
 * instead of the left. The setup check sets it when it hears the student on
 * the right, and every recording after that switches the channels back, so
 * the saved audio always has the student on the left.
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
