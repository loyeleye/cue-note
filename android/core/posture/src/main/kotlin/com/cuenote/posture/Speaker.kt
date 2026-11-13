package com.cuenote.posture

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.cuenote.signal.posture.VoiceLine
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import com.cuenote.signal.posture.Voice as RecordedVoice

/**
 * The app's spoken prompts, so the student doesn't have to come back to the
 * phone to read them. Each line plays its recorded clip in the chosen voice
 * (assets/voice/<voice>/<key>.mp3) when the app has one; otherwise the phone
 * reads it with the best US English voice it has installed. A line never cuts
 * off the one playing: the newest line waits for it to finish, and anything
 * older that was waiting is dropped. Call from the main thread.
 */
class Speaker(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var next: Pair<VoiceLine, (() -> Unit)?>? = null
    /** Called when the clip playing now ends (or is stopped). */
    private var playing: (() -> Unit)? = null
    private var ready = false
    private var pending: Pair<String, (() -> Unit)?>? = null
    private val spoken = mutableMapOf<String, () -> Unit>()
    private var utterances = 0
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(app) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts.language = Locale.US
                bestVoice()?.let { tts.voice = it }
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String) {}
                    override fun onDone(id: String) { main.post { spoken.remove(id)?.invoke() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String) { main.post { spoken.remove(id)?.invoke() } }
                    override fun onStop(id: String, interrupted: Boolean) { main.post { spoken.remove(id)?.invoke() } }
                })
            }
            val waiting = pending
            pending = null
            if (ready && waiting != null) speak(waiting.first, waiting.second) else waiting?.second?.invoke()
        }
    }

    /** Says [line]; [onDone] runs once it has been said, or dropped for a newer line. */
    fun say(line: VoiceLine, onDone: (() -> Unit)? = null) {
        if (player != null) {
            next?.second?.invoke() // dropped for this newer line
            next = line to onDone
            return
        }
        if (!playClip(line, onDone)) speak(line.text, onDone)
    }

    /** Says [line] and returns once it has finished, or after [timeoutMs] whatever happens. */
    suspend fun sayAndWait(line: VoiceLine, timeoutMs: Long = 20_000) {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont -> say(line) { if (cont.isActive) cont.resume(Unit) } }
        }
    }

    private fun finished(mp: MediaPlayer) {
        mp.release()
        if (player === mp) {
            player = null
            playing?.let { playing = null; it() }
        }
        next?.let { (line, done) -> next = null; say(line, done) }
    }

    private fun playClip(line: VoiceLine, onDone: (() -> Unit)?): Boolean {
        val voice = RecordedVoice.fromKey(prefs(app).getString(VOICE_KEY, null))
        val afd = runCatching { app.assets.openFd("voice/${voice.key}/${line.key}.mp3") }.getOrNull() ?: return false
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
            playing = onDone
            true
        } catch (e: Exception) {
            Log.w("Speaker", "clip ${line.key} failed", e)
            player?.release(); player = null
            false
        } finally {
            afd.close()
        }
    }

    private fun speak(text: String, onDone: (() -> Unit)?) {
        if (!ready) {
            pending?.second?.invoke()
            pending = text to onDone
            return
        }
        val id = "line${utterances++}"
        if (onDone != null) spoken[id] = onDone
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) spoken.remove(id)?.invoke()
    }

    /** The highest-quality US English voice that works offline. */
    private fun bestVoice(): Voice? = runCatching {
        tts.voices
            ?.filter { it.locale.language == "en" && it.locale.country == "US" && !it.isNetworkConnectionRequired }
            ?.filterNot { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }
            ?.maxByOrNull { it.quality }
    }.getOrNull()

    /** Stops whatever is playing and drops anything waiting. */
    fun stopAll() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        val dropped = listOfNotNull(playing, next?.second, pending?.second) + spoken.values
        playing = null
        next = null
        pending = null
        spoken.clear()
        if (ready) tts.stop()
        dropped.forEach { it() }
    }

    fun close() { stopAll(); tts.shutdown() }

    companion object {
        private const val VOICE_KEY = "voice"
        private fun prefs(context: Context) = context.getSharedPreferences("speaker", Context.MODE_PRIVATE)

        /** The recorded voice every spoken prompt uses: posture and the sound check alike. */
        fun voice(context: Context): RecordedVoice = RecordedVoice.fromKey(prefs(context).getString(VOICE_KEY, null))

        fun setVoice(context: Context, voice: RecordedVoice) {
            prefs(context).edit().putString(VOICE_KEY, voice.key).apply()
        }
    }
}
