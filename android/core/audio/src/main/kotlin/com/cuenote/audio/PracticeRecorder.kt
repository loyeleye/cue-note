package com.cuenote.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import androidx.core.content.ContextCompat
import com.cuenote.signal.WavWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Records one practice take: 48 kHz mono, from the phone's own mic, or from
 * the student's transmitter when the lesson mic receiver is plugged in. It
 * can play something at the same time (the timing check's clicks now, a
 * backing track later) and reports where in the take that playback started,
 * so the take and the track can be lined up afterwards.
 */
class PracticeRecorder(private val context: Context) {

    /** Where the take came from: the phone's mic, or the student's lesson mic. */
    enum class Input { PHONE, LESSON_MIC }

    data class Take(
        val file: File,
        val frames: Long,
        val input: Input,
        /**
         * The frame of the take at which the first sample of the playback
         * reached the speaker, as best Android can tell; null with no playback.
         */
        val playbackStartFrame: Long?,
        /** True when the playback ran to its end before the take was stopped. */
        val playbackFinished: Boolean,
    ) {
        val durationMs: Long get() = frames * 1000 / SAMPLE_RATE
    }

    /** Loudness of the last ~50 ms in dBFS, for the level bar; null when stopped. */
    private val _level = MutableStateFlow<Double?>(null)
    val level: StateFlow<Double?> = _level.asStateFlow()

    /** True once the playback has played to its end. */
    private val _playbackDone = MutableStateFlow(false)
    val playbackDone: StateFlow<Boolean> = _playbackDone.asStateFlow()

    @Volatile private var running = false
    private var recordThread: Thread? = null
    private var playThread: Thread? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var writer: WavWriter? = null
    private var input = Input.PHONE
    @Volatile private var framesRead = 0L
    @Volatile private var playbackStart: Long? = null
    @Volatile private var fallbackStart: Long? = null

    val isRecording: Boolean get() = running

    /**
     * How far the playback has got in ms, from what the speaker has actually
     * played (not what was handed to it), so on-screen cues match what is heard.
     */
    fun playbackHeardMs(): Long? {
        val t = track ?: return null
        val ts = AudioTimestamp()
        return if (t.getTimestamp(ts)) {
            val since = (System.nanoTime() - ts.nanoTime).coerceAtLeast(0)
            ts.framePosition * 1000L / SAMPLE_RATE + since / 1_000_000
        } else {
            runCatching { t.playbackHeadPosition.toLong() * 1000L / SAMPLE_RATE }.getOrNull()
        }
    }

    /**
     * Starts a take into [out]. [playback], if given, is 48 kHz mono played
     * from the start of the take.
     */
    @SuppressLint("MissingPermission") // checked just below
    fun start(out: File, playback: ShortArray? = null): Input {
        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        ) { "microphone permission not granted" }
        check(!running) { "already recording" }

        val usb = UsbMic.find(context)
        input = if (usb != null) Input.LESSON_MIC else Input.PHONE
        val channels = if (usb != null) 2 else 1
        val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioSource(chooseSource())
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf * 4, SAMPLE_RATE * 2 * channels))
            .build()
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "could not open the microphone" }
        if (usb != null) rec.setPreferredDevice(usb) else builtInMic()?.let { rec.setPreferredDevice(it) }

        out.parentFile?.mkdirs()
        writer = WavWriter(out, SAMPLE_RATE, 1)
        framesRead = 0
        playbackStart = null
        fallbackStart = null
        _playbackDone.value = playback == null
        playedToEnd = false
        rec.startRecording()
        record = rec
        running = true
        val swap = usb != null && ChannelSwap.isOn(context)
        val t = playback?.let { openTrack() }
        track = t
        recordThread = Thread({ recordLoop(rec, channels, swap, t) }, "cuenote-practice-rec").also { it.start() }
        if (t != null && playback != null) {
            playThread = Thread({ playLoop(t, playback) }, "cuenote-practice-play").also { it.start() }
        }
        currentFile = out
        return input
    }

    private var currentFile: File? = null

    /** Stops the take and closes its file. */
    fun stop(): Take? {
        if (!running && writer == null) return null
        running = false
        playThread?.join(1_000)
        recordThread?.join(2_000)
        playThread = null
        recordThread = null
        track?.run { runCatching { stop() }; release() }
        track = null
        record?.run { runCatching { stop() }; release() }
        record = null
        val w = writer ?: return null
        writer = null
        w.close()
        _level.value = null
        return Take(
            file = currentFile ?: w.file,
            frames = w.framesWritten,
            input = input,
            playbackStartFrame = playbackStart ?: fallbackStart,
            playbackFinished = playedToEnd,
        )
    }

    private fun recordLoop(rec: AudioRecord, channels: Int, swap: Boolean, t: AudioTrack?) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val block = ShortArray(SAMPLE_RATE / 20 * channels) // 50 ms
        val mono = ShortArray(SAMPLE_RATE / 20)
        val recTs = AudioTimestamp()
        val trackTs = AudioTimestamp()
        while (running) {
            val n = rec.read(block, 0, block.size, AudioRecord.READ_BLOCKING)
            if (n <= 0) {
                if (n == AudioRecord.ERROR_DEAD_OBJECT) break
                continue
            }
            val frames = n / channels
            if (channels == 2) {
                // The student is on the left, unless today's transmitters were swapped.
                val pick = if (swap) 1 else 0
                for (i in 0 until frames) mono[i] = block[i * 2 + pick]
            } else {
                System.arraycopy(block, 0, mono, 0, frames)
            }
            writer?.write(mono, 0, frames)
            framesRead += frames
            _level.value = db(mono, frames)
            if (t != null && playbackStart == null) alignPlayback(rec, t, recTs, trackTs)
        }
    }

    /**
     * Works out which recorded frame was being captured when the track's
     * first frame came out of the speaker, from the two streams' timestamps
     * on the same clock. If the phone gives no timestamps, [fallbackStart]
     * (the frame count when playback began) is used instead; the timing check
     * measures with the same method a take uses, so either way they agree.
     */
    private fun alignPlayback(rec: AudioRecord, t: AudioTrack, recTs: AudioTimestamp, trackTs: AudioTimestamp) {
        if (!t.getTimestamp(trackTs) || trackTs.framePosition <= 0) return
        if (rec.getTimestamp(recTs, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) return
        val firstOutNs = trackTs.nanoTime - trackTs.framePosition * 1_000_000_000L / SAMPLE_RATE
        val frame = recTs.framePosition + (firstOutNs - recTs.nanoTime) * SAMPLE_RATE / 1_000_000_000L
        playbackStart = frame.coerceAtLeast(0)
    }

    private fun playLoop(t: AudioTrack, pcm: ShortArray) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        fallbackStart = framesRead
        t.play()
        var pos = 0
        val step = SAMPLE_RATE / 50
        while (running && pos < pcm.size) {
            val n = t.write(pcm, pos, minOf(step, pcm.size - pos), AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            pos += n
        }
        if (pos >= pcm.size) {
            // Let the tail drain before saying it's done.
            val bufferMs = t.bufferSizeInFrames * 1000L / SAMPLE_RATE
            try { Thread.sleep(bufferMs + 100) } catch (_: InterruptedException) {}
        }
        // Done, also when the track failed or the take was stopped, so nobody waits forever.
        playedToEnd = pos >= pcm.size
        _playbackDone.value = true
    }

    @Volatile private var playedToEnd = false

    private fun openTrack(): AudioTrack {
        val format = AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuf * 2)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun builtInMic(): AudioDeviceInfo? =
        context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    /** No noise suppression or automatic gain where the phone allows, so the pitch is clean. */
    private fun chooseSource(): Int {
        val am = context.getSystemService(AudioManager::class.java)
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    private fun db(x: ShortArray, n: Int): Double {
        if (n == 0) return -120.0
        var sum = 0.0
        for (i in 0 until n) { val v = x[i] / 32768.0; sum += v * v }
        val rms = sqrt(sum / n)
        return if (rms <= 1e-6) -120.0 else 20 * log10(rms)
    }

    companion object {
        const val SAMPLE_RATE = 48_000
    }
}
