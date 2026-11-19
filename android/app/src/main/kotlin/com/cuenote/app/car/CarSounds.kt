package com.cuenote.app.car

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.cuenote.signal.WavWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The car playlist's own sounds, as files a media player can queue: each
 * spoken instruction (the phone's voice, synthesized once and kept in the
 * cache) and the quiet time of a step done on your own, ending in a soft
 * chime.
 */
class CarSounds(context: Context) {
    private val app = context.applicationContext
    private val speechDir = File(app.cacheDir, "car/speech")
    private val quietDir = File(app.cacheDir, "car/quiet")
    private val ready = CompletableDeferred<TextToSpeech?>()
    private val done = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val one = Mutex()
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(app) { status ->
            if (status != TextToSpeech.SUCCESS) { ready.complete(null); return@TextToSpeech }
            tts.language = Locale.US
            bestVoice()?.let { tts.voice = it }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) {}
                override fun onDone(id: String) { done.remove(id)?.complete(true) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String) { done.remove(id)?.complete(false) }
                override fun onError(id: String, errorCode: Int) { done.remove(id)?.complete(false) }
            })
            ready.complete(tts)
        }
    }

    /** [text] read aloud, as a WAV file; null when the phone has no working voice. */
    suspend fun speech(text: String): File? = withContext(Dispatchers.IO) {
        val t = withTimeoutOrNull(10_000) { ready.await() } ?: return@withContext null
        val f = File(speechDir, hash("${t.voice?.name}|$text") + ".wav")
        if (f.isFile && f.length() > 44) return@withContext f
        one.withLock {
            speechDir.mkdirs()
            val tmp = File(speechDir, f.name + ".part")
            val id = UUID.randomUUID().toString()
            val waiting = CompletableDeferred<Boolean>().also { done[id] = it }
            if (t.synthesizeToFile(text, Bundle(), tmp, id) != TextToSpeech.SUCCESS) {
                done.remove(id); return@withLock null
            }
            val ok = withTimeoutOrNull(30_000) { waiting.await() } == true
            if (ok && tmp.renameTo(f)) f else { tmp.delete(); Log.w(TAG, "Couldn't synthesize a line"); null }
        }
    }

    /** [seconds] of quiet ending in a soft two-note chime: a timer the student can hear. */
    suspend fun quiet(seconds: Int): File = withContext(Dispatchers.IO) {
        val s = seconds.coerceIn(5, 30 * 60)
        val f = File(quietDir, "quiet_$s.wav")
        if (f.isFile) return@withContext f
        quietDir.mkdirs()
        val tmp = File(quietDir, f.name + ".part")
        WavWriter(tmp, RATE, 1).use { w ->
            val chime = chime()
            val silent = s * RATE - chime.size
            val block = ShortArray(RATE)
            var left = silent
            while (left > 0) { val n = minOf(left, block.size); w.write(if (n == block.size) block else ShortArray(n)); left -= n }
            w.write(chime)
        }
        tmp.renameTo(f)
        f
    }

    fun close() = runCatching { tts.shutdown() }

    /** Two soft bell-like notes (G5 then C6), quiet next to a backing track. */
    private fun chime(): ShortArray {
        val note = (RATE * 0.7).toInt()
        val out = ShortArray(note * 2)
        for ((k, hz) in listOf(783.99, 1046.5).withIndex()) {
            for (i in 0 until note) {
                val t = i.toDouble() / RATE
                val v = sin(2 * PI * hz * t) * exp(-t * 5) * minOf(1.0, i / 200.0) * 0.18
                out[k * note + i] = (v * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }

    /** The highest-quality US English voice that works offline, as the rest of the app uses. */
    private fun bestVoice(): Voice? = runCatching {
        tts.voices
            ?.filter { it.locale.language == "en" && it.locale.country == "US" && !it.isNetworkConnectionRequired }
            ?.filterNot { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }
            ?.maxByOrNull { it.quality }
    }.getOrNull()

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    private companion object {
        const val TAG = "CarSounds"
        const val RATE = 16_000
    }
}
