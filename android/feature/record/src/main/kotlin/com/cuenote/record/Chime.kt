package com.cuenote.record

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * The soundcheck's short cue after each step: a light double beep for a
 * pass, one low tone for "try again". It plays only after a step's window is
 * captured, so it never ends up in the measurement.
 */
class Chime {
    private val tones: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 40)
    } catch (e: RuntimeException) {
        null // some phones refuse a tone generator; the stamp still shows
    }

    fun play(passed: Boolean) {
        tones?.startTone(if (passed) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_NACK, 180)
    }

    fun release() {
        tones?.release()
    }
}
