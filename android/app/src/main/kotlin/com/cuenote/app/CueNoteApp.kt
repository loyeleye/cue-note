package com.cuenote.app

import android.app.Application
import com.cuenote.audio.RecordingSession
import com.cuenote.audio.RecordingState
import com.cuenote.data.AuthRepository
import com.cuenote.data.ConsentStore
import com.cuenote.data.LessonDatabase
import com.cuenote.data.LessonRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Builds the app's few shared objects once (a hand-written "container"
 * instead of a dependency-injection library), and saves each finished
 * recording to the database even if no screen is open.
 */
class CueNoteApp : Application() {
    lateinit var lessons: LessonRepository private set
    lateinit var consent: ConsentStore private set
    lateinit var auth: AuthRepository private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        lessons = LessonRepository(this, LessonDatabase.create(this).lessons())
        consent = ConsentStore(this)
        auth = AuthRepository.create()

        scope.launch {
            RecordingSession.state.collect { s ->
                when (s) {
                    is RecordingState.Finished -> { lessons.finish(s.summary); RecordingSession.acknowledge() }
                    is RecordingState.Failed -> { lessons.failed(s.lessonId); RecordingSession.acknowledge() }
                    else -> Unit
                }
            }
        }
    }
}
