package com.cuenote.posture

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import com.cuenote.signal.posture.VoiceLine
import java.util.Locale

/**
 * The app's spoken prompts, so the student doesn't have to come back to the
 * phone to read them. Each line plays its recorded clip (assets/voice/<key>.mp3)
 * when the app has one; otherwise the phone reads it with the best US English
 * voice it has installed. A line never cuts off the one playing: the newest
 * line waits for it to finish, and anything older that was waiting is dropped.
 */
class Speaker(context: Context) {
    private val app = context.applicationContext
    private var player: MediaPlayer? = null
    private var next: VoiceLine? = null
    private var ready = false
    private var pending: String? = null
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(app) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts.language = Locale.US
                bestVoice()?.let { tts.voice = it }
                pending?.let { speak(it) }
                pending = null
            }
        }
    }

    fun say(line: VoiceLine) {
        if (player != null) { next = line; return }
        if (!playClip(line)) speak(line.text)
    }

    private fun finished(mp: MediaPlayer) {
        mp.release()
        if (player === mp) player = null
        next?.let { next = null; say(it) }
    }

    private fun playClip(line: VoiceLine): Boolean {
        val afd = runCatching { app.assets.openFd("voice/${line.key}.mp3") }.getOrNull() ?: return false
        return try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                setOnCompletionListener(::finished)
                prepare()
                start()
            }
            true
        } catch (e: Exception) {
            Log.w("Speaker", "clip ${line.key} failed", e)
            player?.release(); player = null
            false
        } finally {
            afd.close()
        }
    }

    private fun speak(text: String) {
        if (!ready) { pending = text; return }
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, text)
    }

    /** The highest-quality US English voice that works offline. */
    private fun bestVoice(): Voice? = runCatching {
        tts.voices
            ?.filter { it.locale.language == "en" && it.locale.country == "US" && !it.isNetworkConnectionRequired }
            ?.filterNot { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }
            ?.maxByOrNull { it.quality }
    }.getOrNull()

    private fun stopAll() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        next = null
        if (ready) tts.stop()
        pending = null
    }

    fun close() { stopAll(); tts.shutdown() }
}
