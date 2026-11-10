package com.cuenote.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.cuenote.audio.RecordingSession
import com.cuenote.audio.RecordingState
import com.cuenote.posture.PostureService
import com.cuenote.posture.PostureSetup
import com.cuenote.signal.posture.SingingGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The main process's side of posture tracking. It watches the recording,
 * which is always in charge: it tells the camera service when the student's
 * mic shows singing, passes on every audio gap so posture backs off, and
 * stops posture when the recording ends. Posture never touches the audio.
 */
class PostureLink(private val app: Application, private val scope: CoroutineScope) {

    fun begin(lessonId: String, lessonDir: File, setup: PostureSetup) {
        scope.launch {
            val rec = withTimeoutOrNull(8_000) {
                RecordingSession.state.first { it is RecordingState.Recording && it.lessonId == lessonId }
            } as? RecordingState.Recording ?: return@launch // the recording didn't start, so neither does posture
            withContext(Dispatchers.Main) { PostureService.start(app, setup, lessonDir, rec.startedAtElapsedMs) }

            // If the service gives up (no camera, say), stop sending it anything.
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.getStringExtra(PostureService.EXTRA_STATUS)?.startsWith("off") == true) PostureService.stop(app)
                }
            }
            ContextCompat.registerReceiver(
                app, receiver, IntentFilter(PostureService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            try {
                val gate = SingingGate()
                var gaps = rec.gaps
                var lastBeat = 0L
                while (PostureService.running.value) {
                    val s = RecordingSession.state.value
                    if (s !is RecordingState.Recording || s.lessonId != lessonId) break
                    if (s.gaps > gaps) { gaps = s.gaps; PostureService.audioGap(app) }
                    val now = SystemClock.elapsedRealtime()
                    val changed = RecordingSession.levels.value?.let { gate.onLevels(now, it.student.rmsDb, it.teacher.rmsDb) } == true
                    // A heartbeat every few seconds too: if this process dies, the camera service notices and stops.
                    if (changed || now - lastBeat >= 5_000) { PostureService.heartbeat(app, gate.singing); lastBeat = now }
                    delay(150)
                }
            } finally {
                runCatching { app.unregisterReceiver(receiver) }
                PostureService.stop(app)
            }
        }
    }
}
