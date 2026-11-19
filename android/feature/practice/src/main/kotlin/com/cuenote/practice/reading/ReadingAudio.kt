package com.cuenote.practice.reading

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.cuenote.audio.LiveMic
import com.cuenote.pipeline.practice.StartingNote
import com.cuenote.pipeline.reading.LivePitch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** The voice, live: pitch frames from the mic while an exercise listens. */
class LiveVoice(context: Context) {
    private val mic = LiveMic(context)
    private val pitch = LivePitch()
    private val _frames = MutableSharedFlow<LivePitch.Frame>(extraBufferCapacity = 64)
    val frames: SharedFlow<LivePitch.Frame> = _frames.asSharedFlow()
    var error by mutableStateOf<String?>(null)
        private set
    var listening by mutableStateOf(false)
        private set

    fun start() {
        if (listening) return
        pitch.reset()
        runCatching { mic.start { block, n -> for (f in pitch.push(block, n)) _frames.tryEmit(f) } }
            .onSuccess { listening = true; error = null }
            .onFailure { error = "The mic didn't start: ${it.message}" }
    }

    fun stop() {
        mic.stop()
        listening = false
    }
}

@Composable
fun rememberLiveVoice(): LiveVoice {
    val context = LocalContext.current
    val v = remember { LiveVoice(context) }
    DisposableEffect(v) { onDispose { v.stop() } }
    return v
}

/** Plays a note with the app's soft piano tone. */
class TonePlayer {
    private var track: AudioTrack? = null

    /** Plays [midi] for [ms]; returns how long it lasts, so the caller can wait for the speaker to go quiet. */
    fun play(midi: Int, ms: Int = NOTE_MS): Int {
        playPcm(StartingNote.tone(StartingNote.Note(midi), ms))
        return ms
    }

    /** Plays a ready-made 48 kHz mono buffer (a melody, a click track); returns its length in ms. */
    fun playPcm(pcm: ShortArray): Int {
        stop()
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(LiveMic.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(pcm, 0, pcm.size)
        t.play()
        track = t
        return (pcm.size * 1000L / LiveMic.SAMPLE_RATE).toInt()
    }

    /**
     * When the first sample of the buffer playing now left the speaker, on
     * the [System.nanoTime] clock, so taps can be timed against what was
     * heard rather than when play was called. Null until the output reports it.
     */
    fun startedAtNanos(): Long? {
        val t = track ?: return null
        val ts = AudioTimestamp()
        if (!runCatching { t.getTimestamp(ts) }.getOrDefault(false) || ts.framePosition <= 0) return null
        return ts.nanoTime - ts.framePosition * 1_000_000_000L / LiveMic.SAMPLE_RATE
    }

    fun stop() {
        track?.run { runCatching { stop() }; release() }
        track = null
    }

    companion object {
        const val NOTE_MS = 1_500
    }
}

@Composable
fun rememberTonePlayer(): TonePlayer {
    val p = remember { TonePlayer() }
    DisposableEffect(p) { onDispose { p.stop() } }
    return p
}
