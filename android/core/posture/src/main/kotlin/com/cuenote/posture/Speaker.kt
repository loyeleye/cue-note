package com.cuenote.posture

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Spoken prompts through Android's text-to-speech, so the student doesn't
 * have to come back to the phone to read them. Used by the framing check and
 * by the mid-lesson "Recalibrate posture".
 */
class Speaker(context: Context) {
    private var ready = false
    private var pending: String? = null
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts.language = Locale.US
                pending?.let { say(it) }
                pending = null
            }
        }
    }

    fun say(text: String) {
        if (!ready) { pending = text; return }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text)
    }

    fun close() { tts.stop(); tts.shutdown() }
}
