package com.cuenote.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.cuenote.signal.GapDetector
import com.cuenote.signal.LessonAudio
import com.cuenote.signal.StereoLevelMeter
import com.cuenote.signal.StereoLevels
import com.cuenote.signal.swapStereoInPlace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads 48 kHz stereo from the USB mic on a dedicated high-priority thread
 * and hands each block to a sink. Used by the setup check (no sink, levels
 * only) and by the recording service (sink = WAV chunks).
 */
class LessonRecorder(private val context: Context) {

    /** Levels of the last ~100 ms, for the live "is each mic alive" dots. */
    private val _levels = MutableStateFlow<StereoLevels?>(null)
    val levels: StateFlow<StereoLevels?> = _levels.asStateFlow()

    private val windowMeter = StereoLevelMeter()
    private val windowLock = Any()

    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    var framesRead = 0L
        private set

    /**
     * Switch left and right as audio comes in, so the student is always on
     * the left in levels and saved files. Starts from the saved setting.
     */
    @Volatile var switchChannels: Boolean = ChannelSwap.isOn(context)

    /** Starts recording. Throws if the microphone permission is missing. */
    @SuppressLint("MissingPermission") // checked just below
    fun start(
        sink: ((ShortArray, Int) -> Unit)? = null,
        onGap: (GapDetector.Gap) -> Unit = {},
    ): String {
        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        ) { "microphone permission not granted" }
        check(!running) { "already recording" }

        val format = AudioFormat.Builder()
            .setSampleRate(LessonAudio.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            LessonAudio.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        )
        // Two seconds of buffer, so a slow disk write never drops audio.
        val bufferBytes = maxOf(minBuf * 4, LessonAudio.SAMPLE_RATE * 2 * 2 * 2)
        val rec = AudioRecord.Builder()
            .setAudioSource(chooseSource())
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "could not open the microphone" }
        UsbMic.find(context)?.let { rec.setPreferredDevice(it) }
        rec.startRecording()
        record = rec
        running = true
        framesRead = 0
        thread = Thread({ loop(rec, sink, onGap) }, "cuenote-recorder").also { it.start() }
        return UsbMic.describeAny(rec.routedDevice ?: rec.preferredDevice)
    }

    fun stop() {
        running = false
        thread?.join(3_000)
        thread = null
        record?.run {
            runCatching { stop() }
            release()
        }
        record = null
        _levels.value = null
    }

    /** Name of the input Android actually routed to (should be the Lark receiver). */
    fun routedDeviceName(): String = UsbMic.describeAny(record?.routedDevice)

    /** Starts a fresh measurement window for one setup-check step. */
    fun resetWindow() = synchronized(windowLock) { windowMeter.reset() }

    /** Levels accumulated since [resetWindow]. */
    fun windowLevels(): StereoLevels = synchronized(windowLock) { windowMeter.result() }

    private fun loop(rec: AudioRecord, sink: ((ShortArray, Int) -> Unit)?, onGap: (GapDetector.Gap) -> Unit) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val block = ShortArray(LessonAudio.SAMPLE_RATE / 10 * LessonAudio.CHANNELS) // 100 ms
        val blockMeter = StereoLevelMeter()
        val gaps = GapDetector()
        while (running) {
            val n = rec.read(block, 0, block.size, AudioRecord.READ_BLOCKING)
            if (n <= 0) {
                if (n == AudioRecord.ERROR_DEAD_OBJECT) break
                continue
            }
            val whole = n - n % LessonAudio.CHANNELS
            framesRead += whole / LessonAudio.CHANNELS
            if (switchChannels) swapStereoInPlace(block, whole)
            sink?.invoke(block, whole)
            gaps.onFrames(whole / LessonAudio.CHANNELS, SystemClock.elapsedRealtimeNanos())?.let(onGap)
            synchronized(windowLock) { windowMeter.add(block, whole) }
            blockMeter.reset()
            blockMeter.add(block, whole)
            _levels.value = blockMeter.result()
        }
    }

    /** UNPROCESSED skips noise suppression and gain control when the phone supports it. */
    private fun chooseSource(): Int {
        val am = context.getSystemService(AudioManager::class.java)
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }
}
