package com.cuenote.app

import android.app.Application
import android.os.SystemClock
import com.cuenote.posture.PostureService
import com.cuenote.posture.PostureSetup
import com.cuenote.practice.TakePosture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Posture during practice, with the lesson's camera service. The framing
 * check's result is kept for this sitting (while the app runs). Each take
 * starts the service with the take's own folder and tells it the student is
 * singing until the take ends, so the camera is on only while they sing.
 */
class PracticePosture(private val app: Application, private val scope: CoroutineScope) : TakePosture {
    private var setup: PostureSetup? = null
    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private var beat: Job? = null

    fun set(s: PostureSetup) { setup = s; _ready.value = true }

    override fun start(dir: File) {
        val s = setup ?: return
        if (PostureService.running.value) return   // a lesson's posture is running
        PostureService.start(app, s, dir, SystemClock.elapsedRealtime())
        beat = scope.launch {
            while (isActive && PostureService.running.value) {
                PostureService.heartbeat(app, singing = true)
                delay(3_000)
            }
        }
    }

    override fun stop() {
        val b = beat ?: return
        beat = null
        b.cancel()
        PostureService.stop(app)
    }
}
