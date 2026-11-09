package com.cuenote.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Keeps the lesson recording alive with the screen off. Android only lets an
 * app use the microphone in the background from a foreground service, which
 * must show a notification while it runs.
 */
class RecordingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (removed.any { it.isSource && it.type in USB_TYPES }) {
                RecordingSession.micChanged("USB mic unplugged")
            }
        }
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            added.firstOrNull { it.isSource && it.type in USB_TYPES }?.let {
                RecordingSession.micChanged("USB mic plugged in: ${UsbMic.describeAny(it)}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_LESSON_ID) ?: return START_NOT_STICKY
                val dir = intent.getStringExtra(EXTRA_DIR)?.let(::File) ?: return START_NOT_STICKY
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cuenote:recording")
                    .apply { acquire(3 * 60 * 60 * 1000L) } // a 3-hour ceiling, released on stop
                getSystemService(AudioManager::class.java).registerAudioDeviceCallback(deviceCallback, null)
                RecordingSession.begin(this, id, dir)
                if (RecordingSession.state.value is RecordingState.Failed) shutDown()
            }
            ACTION_STOP -> {
                RecordingSession.end()
                shutDown()
            }
        }
        // If Android kills the process, do not restart silently: the lesson
        // folder already holds everything up to the last chunk.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        RecordingSession.end()
        releaseResources()
        super.onDestroy()
    }

    private fun shutDown() {
        releaseResources()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseResources() {
        runCatching { getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(deviceCallback) }
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Lesson recording", NotificationManager.IMPORTANCE_LOW),
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Recording lesson")
            .setContentText("Both mics are being recorded. Tap to open.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val ACTION_START = "com.cuenote.audio.START"
        private const val ACTION_STOP = "com.cuenote.audio.STOP"
        private const val EXTRA_LESSON_ID = "lesson_id"
        private const val EXTRA_DIR = "dir"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private val USB_TYPES = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET)

        fun start(context: Context, lessonId: String, lessonDir: File) {
            val i = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LESSON_ID, lessonId)
                .putExtra(EXTRA_DIR, lessonDir.absolutePath)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
