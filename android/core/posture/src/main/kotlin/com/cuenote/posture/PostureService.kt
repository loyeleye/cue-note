package com.cuenote.posture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.cuenote.signal.posture.PoseTsv
import com.cuenote.signal.posture.PostureBackOff
import com.cuenote.signal.posture.PostureEvent
import com.cuenote.signal.posture.PostureFlagDetector
import com.cuenote.signal.posture.PostureMetrics
import com.cuenote.signal.posture.StudentPicker
import com.cuenote.signal.posture.decodePostureMetrics
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Posture tracking during a lesson. Runs in its own process, so a camera or
 * model crash can never take the audio recording with it. The camera is on
 * only while the student is singing and nothing is holding posture off;
 * each frame goes through MediaPipe Pose, the student's body points go to
 * posture/pose.tsv, and a still snapshot is saved when a flag starts. No
 * video is ever saved.
 *
 * The main app drives it with intents: who's singing (sent every few seconds
 * as a heartbeat), audio gaps, and holds such as the in-app camera taking a
 * photo. If the heartbeat stops, the lesson is over and the service stops.
 */
class PostureService : LifecycleService() {

    private val main = Handler(Looper.getMainLooper())
    /** Guards the model, the flag detector and the files, shared by the main and pose threads. */
    private val lock = Any()

    private var dir: File? = null
    private var startedAt = 0L
    private var rotation = Surface.ROTATION_0
    private var front = true
    private var engine: PoseEngine? = null
    private var frames: PoseFrames? = null
    private var provider: ProcessCameraProvider? = null
    private var detector: PostureFlagDetector? = null
    private var picker: StudentPicker? = null
    private val backOff = PostureBackOff()
    private var poseOut: BufferedWriter? = null
    private var eventsOut: BufferedWriter? = null
    private var singing = false
    private var cameraOn = false
    private var lastReason: String? = null
    private var lastHeartbeat = 0L
    private var flags = 0
    private var status = "starting"

    private val thermal = PowerManager.OnThermalStatusChangedListener { s ->
        backOff.onThermal(SystemClock.elapsedRealtime(), s)
        reconcile()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() - lastHeartbeat > HEARTBEAT_TIMEOUT_MS) { finish(); return }
            reconcile()
            main.postDelayed(this, 2_000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val now = SystemClock.elapsedRealtime()
        when (intent?.action) {
            ACTION_START -> if (dir == null) begin(intent) else Unit
            ACTION_HEARTBEAT -> { lastHeartbeat = now; singing = intent.getBooleanExtra(EXTRA_SINGING, false) }
            ACTION_GAP -> backOff.onGap(now)
            ACTION_HOLD -> intent.getStringExtra(EXTRA_REASON)?.let(backOff::hold)
            ACTION_RELEASE -> intent.getStringExtra(EXTRA_REASON)?.let(backOff::release)
            ACTION_STOP -> { finish(); return START_NOT_STICKY }
        }
        if (dir == null) { stopSelf(); return START_NOT_STICKY } // a stray command with no lesson running
        reconcile()
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        val lessonDir = intent.getStringExtra(EXTRA_DIR)?.let(::File) ?: return
        val baseline = decodePostureMetrics(intent.getStringExtra(EXTRA_BASELINE)) ?: return
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } catch (e: Exception) {
            Log.w(TAG, "could not start posture tracking", e) // no camera permission, or started from the background
            broadcast("off: could not start the camera")
            stopSelf()
            return
        }
        val out = File(lessonDir, "posture").apply { mkdirs() }
        dir = out
        startedAt = intent.getLongExtra(EXTRA_STARTED_AT, SystemClock.elapsedRealtime())
        rotation = intent.getIntExtra(EXTRA_ROTATION, Surface.ROTATION_0)
        front = intent.getBooleanExtra(EXTRA_FRONT, true)
        detector = PostureFlagDetector(baseline)
        picker = StudentPicker(
            intent.getFloatExtra(EXTRA_CENTER_X, -1f).takeIf { it >= 0 },
            intent.getFloatExtra(EXTRA_CENTER_Y, -1f).takeIf { it >= 0 },
        )
        val newFile = !File(out, "pose.tsv").exists()
        poseOut = BufferedWriter(FileWriter(File(out, "pose.tsv"), true)).apply { if (newFile) { write(PoseTsv.HEADER); newLine() } }
        eventsOut = BufferedWriter(FileWriter(File(out, "events.tsv"), true))
        log(PostureEvent.Started(lessonMs()))
        lastHeartbeat = SystemClock.elapsedRealtime()
        val pm = getSystemService(PowerManager::class.java)
        backOff.onThermal(lastHeartbeat, pm.currentThermalStatus)
        pm.addThermalStatusListener(ContextCompat.getMainExecutor(this), thermal)
        runCatching { engine = PoseEngine(this) }.onFailure {
            Log.w(TAG, "pose model failed to load", it)
            backOff.hold("pose model failed to load")
        }
        ProcessCameraProvider.getInstance(this).addListener({
            provider = runCatching { ProcessCameraProvider.getInstance(this).get() }.getOrNull()
            reconcile()
        }, ContextCompat.getMainExecutor(this))
        main.post(tick)
    }

    /** Turns the camera on or off to match: singing, and nothing holding posture off. */
    private fun reconcile() {
        if (dir == null) return
        val now = SystemClock.elapsedRealtime()
        val reason = backOff.reason(now)
        if (reason != lastReason) {
            if (reason != null) log(PostureEvent.Paused(lessonMs(), reason)) else if (lastReason != null) log(PostureEvent.Resumed(lessonMs()))
            lastReason = reason
        }
        val want = singing && reason == null && engine != null
        if (want && !cameraOn) cameraUp()
        if (!want && cameraOn) cameraDown()
        broadcast(
            when {
                reason != null -> "paused: $reason"
                cameraOn -> "watching"
                else -> "waiting for singing"
            },
        )
    }

    private fun cameraUp() {
        val p = provider ?: return
        val f = frames ?: PoseFrames(onFrame = ::onFrame).also { frames = it }
        try {
            p.bindToLifecycle(this, PoseFrames.selector(front), f.useCase(rotation))
            cameraOn = true
            log(PostureEvent.CameraOn(lessonMs()))
        } catch (e: Exception) {
            Log.w(TAG, "camera would not open", e)
            backOff.hold("camera busy")
            main.postDelayed({ backOff.release("camera busy"); reconcile() }, 30_000)
        }
    }

    private fun cameraDown() {
        provider?.unbindAll()
        cameraOn = false
        synchronized(lock) {
            detector?.endAll()?.forEach { log(PostureEvent.FlagEnded(lessonMs(), it.flag)) }
            poseOut?.flush()
        }
        log(PostureEvent.CameraOff(lessonMs()))
    }

    /** On the pose thread, about five times a second while the camera is on. */
    private fun onFrame(upright: Bitmap, elapsedMs: Long) = synchronized(lock) {
        try {
            val t = elapsedMs - startedAt
            val student = engine?.detect(upright, elapsedMs)?.let { picker?.pick(it, t) }
            val metrics = student?.let(PostureMetrics::of)
            if (student != null) poseOut?.apply { write(PoseTsv.line(t, student)); newLine() }
            detector?.onFrame(t, metrics)?.forEach { e ->
                when (e) {
                    is PostureFlagDetector.Event.Started -> {
                        val name = "flag_%06d_%s.jpg".format(t / 1000, e.flag.key)
                        saveSnapshot(upright, name)
                        flags++
                        log(PostureEvent.Flagged(t, e.flag, name, e.detail))
                    }
                    is PostureFlagDetector.Event.Ended -> log(PostureEvent.FlagEnded(t, e.flag))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "frame failed", e)
        } finally {
            upright.recycle()
        }
    }

    private fun saveSnapshot(frame: Bitmap, name: String) {
        val d = dir ?: return
        val scale = 640f / maxOf(frame.width, frame.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true) else frame
        File(d, name).outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        if (small !== frame) small.recycle()
    }

    private fun finish() {
        main.removeCallbacks(tick)
        if (dir != null) {
            if (cameraOn) cameraDown()
            runCatching { getSystemService(PowerManager::class.java).removeThermalStatusListener(thermal) }
            frames?.close()
            frames = null
            synchronized(lock) {
                log(PostureEvent.Stopped(lessonMs()))
                runCatching { poseOut?.close() }
                runCatching { eventsOut?.close() }
                runCatching { engine?.close() }
                poseOut = null; eventsOut = null; engine = null; detector = null
            }
            dir = null
            broadcast("off")
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (dir != null) finish()
        super.onDestroy()
    }

    private fun lessonMs() = SystemClock.elapsedRealtime() - startedAt

    @Synchronized
    private fun log(e: PostureEvent) {
        eventsOut?.apply { write(e.toLine()); newLine(); flush() }
    }

    private fun broadcast(s: String) {
        if (s == status && !s.startsWith("watching")) return
        status = s
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, s).putExtra(EXTRA_FLAGS, flags))
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Posture tracking", NotificationManager.IMPORTANCE_LOW))
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val stop = PendingIntent.getService(
            this, 2, Intent(this, PostureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Tracking posture")
            .setContentText("The camera is on only while the student sings. No video is saved.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop posture", stop)
            .build()
    }

    companion object {
        private const val TAG = "PostureService"
        private const val ACTION_START = "com.cuenote.posture.START"
        private const val ACTION_HEARTBEAT = "com.cuenote.posture.HEARTBEAT"
        private const val ACTION_GAP = "com.cuenote.posture.GAP"
        private const val ACTION_HOLD = "com.cuenote.posture.HOLD"
        private const val ACTION_RELEASE = "com.cuenote.posture.RELEASE"
        private const val ACTION_STOP = "com.cuenote.posture.STOP"
        /** Broadcast to the app's main process whenever posture's state changes. */
        const val ACTION_STATUS = "com.cuenote.posture.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_FLAGS = "flags"
        private const val EXTRA_DIR = "dir"
        private const val EXTRA_STARTED_AT = "started_at"
        private const val EXTRA_BASELINE = "baseline"
        private const val EXTRA_CENTER_X = "center_x"
        private const val EXTRA_CENTER_Y = "center_y"
        private const val EXTRA_ROTATION = "rotation"
        private const val EXTRA_FRONT = "front"
        private const val EXTRA_SINGING = "singing"
        private const val EXTRA_REASON = "reason"
        private const val CHANNEL_ID = "posture"
        private const val NOTIFICATION_ID = 2
        private const val HEARTBEAT_TIMEOUT_MS = 20_000L

        private val _running = MutableStateFlow(false)
        /** True between [start] and the lesson ending (or posture giving up), in the main process. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /**
         * Starts posture tracking for a lesson that's already recording. Must be
         * called while the app is on screen: Android only lets a camera service
         * start from the foreground.
         */
        fun start(context: Context, setup: PostureSetup, lessonDir: File, recordingStartedAtElapsedMs: Long) {
            _running.value = true
            ContextCompat.startForegroundService(
                context,
                Intent(context, PostureService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_DIR, lessonDir.absolutePath)
                    .putExtra(EXTRA_STARTED_AT, recordingStartedAtElapsedMs)
                    .putExtra(EXTRA_BASELINE, setup.baseline.encode())
                    .putExtra(EXTRA_CENTER_X, setup.centerX)
                    .putExtra(EXTRA_CENTER_Y, setup.centerY)
                    .putExtra(EXTRA_ROTATION, setup.rotation)
                    .putExtra(EXTRA_FRONT, setup.frontCamera),
            )
        }

        fun heartbeat(context: Context, singing: Boolean) =
            send(context, Intent(context, PostureService::class.java).setAction(ACTION_HEARTBEAT).putExtra(EXTRA_SINGING, singing))

        fun audioGap(context: Context) = send(context, Intent(context, PostureService::class.java).setAction(ACTION_GAP))

        /** Pause posture and free the camera, for example while the in-app camera takes a photo. No-op when posture is off. */
        fun pauseFor(context: Context, reason: String) =
            send(context, Intent(context, PostureService::class.java).setAction(ACTION_HOLD).putExtra(EXTRA_REASON, reason))

        fun resumeAfter(context: Context, reason: String) =
            send(context, Intent(context, PostureService::class.java).setAction(ACTION_RELEASE).putExtra(EXTRA_REASON, reason))

        fun stop(context: Context) {
            if (!_running.value) return
            send(context, Intent(context, PostureService::class.java).setAction(ACTION_STOP))
            _running.value = false
        }

        private fun send(context: Context, intent: Intent) {
            if (!_running.value) return
            runCatching { context.startService(intent) }.onFailure { Log.w(TAG, "posture command not delivered", it) }
        }
    }
}

/** What the framing check hands the camera service. */
data class PostureSetup(
    val baseline: PostureMetrics,
    val centerX: Float,
    val centerY: Float,
    /** The display rotation the phone is mounted at (a Surface.ROTATION_ value). */
    val rotation: Int,
    val frontCamera: Boolean,
)
