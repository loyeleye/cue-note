package com.cuenote.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import androidx.core.content.ContextCompat

/**
 * The mic, listened to but not recorded: 48 kHz mono in 50 ms blocks, for
 * pitch shown while the student sings. Nothing is written to a file. Uses
 * the student's lesson mic when its receiver is plugged in, as practice
 * takes do.
 */
class LiveMic(private val context: Context) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    val isListening: Boolean get() = running

    /** Starts listening; [onBlock] runs on the mic's own thread with each block and its length. */
    @SuppressLint("MissingPermission") // checked just below
    fun start(onBlock: (ShortArray, Int) -> Unit) {
        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        ) { "microphone permission not granted" }
        if (running) return
        val usb = UsbMic.find(context)
        val channels = if (usb != null) 2 else 1
        val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioSource(source())
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf * 2, SAMPLE_RATE / 5 * 2 * channels))
            .build()
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "could not open the microphone" }
        if (usb != null) rec.setPreferredDevice(usb) else builtInMic()?.let { rec.setPreferredDevice(it) }
        val swap = usb != null && ChannelSwap.isOn(context)
        rec.startRecording()
        record = rec
        running = true
        thread = Thread({ loop(rec, channels, swap, onBlock) }, "cuenote-live-mic").also { it.start() }
    }

    fun stop() {
        if (!running && record == null) return
        running = false
        thread?.join(1_000)
        thread = null
        record?.run { runCatching { stop() }; release() }
        record = null
    }

    private fun loop(rec: AudioRecord, channels: Int, swap: Boolean, onBlock: (ShortArray, Int) -> Unit) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val block = ShortArray(SAMPLE_RATE / 20 * channels)
        val mono = ShortArray(SAMPLE_RATE / 20)
        while (running) {
            val n = rec.read(block, 0, block.size, AudioRecord.READ_BLOCKING)
            if (n <= 0) { if (n == AudioRecord.ERROR_DEAD_OBJECT) break; continue }
            val frames = n / channels
            if (channels == 2) {
                val pick = if (swap) 1 else 0
                for (i in 0 until frames) mono[i] = block[i * 2 + pick]
            } else {
                System.arraycopy(block, 0, mono, 0, frames)
            }
            onBlock(mono, frames)
        }
    }

    private fun builtInMic(): AudioDeviceInfo? =
        context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    /** No noise suppression or automatic gain where the phone allows, so the pitch is clean. */
    private fun source(): Int {
        val am = context.getSystemService(AudioManager::class.java)
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    companion object {
        const val SAMPLE_RATE = 48_000
    }
}
