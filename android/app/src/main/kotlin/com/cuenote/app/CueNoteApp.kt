package com.cuenote.app

import android.app.Application
import com.cuenote.audio.RecordingSession
import com.cuenote.audio.RecordingState
import com.cuenote.data.AuthRepository
import com.cuenote.data.AgeGate
import com.cuenote.data.ConsentStore
import com.cuenote.data.DeviceTokens
import com.cuenote.data.LessonDatabase
import com.cuenote.data.LessonRepository
import com.cuenote.data.LessonTeacherStore
import com.cuenote.data.PracticeRepository
import com.cuenote.data.ShareRepository
import com.cuenote.data.sketch.SketchRepository
import com.cuenote.data.diagrams.DiagramRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import com.google.firebase.messaging.FirebaseMessaging
import android.util.Log

/**
 * Builds the app's few shared objects once (a hand-written "container"
 * instead of a dependency-injection library), and saves each finished
 * recording to the database even if no screen is open.
 */
class CueNoteApp : Application() {
    lateinit var lessons: LessonRepository private set
    lateinit var consent: ConsentStore private set
    lateinit var ageGate: AgeGate private set
    lateinit var auth: AuthRepository private set
    lateinit var shares: ShareRepository private set
    lateinit var lessonTeachers: LessonTeacherStore private set
    lateinit var sketches: SketchRepository private set
    lateinit var diagrams: DiagramRepository private set
    lateinit var posture: PostureLink private set
    lateinit var practicePosture: PracticePosture private set
    lateinit var practice: PracticeRepository private set
    lateinit var deviceTokens: DeviceTokens private set

    /** A lesson to open, from a tapped "card updated" notification; the nav clears it once shown. */
    val openLesson = MutableStateFlow<String?>(null)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // The posture camera runs in its own process; it needs none of this.
        if (getProcessName().endsWith(":posture")) return
        val db = LessonDatabase.get(this)
        lessons = LessonRepository(this, db.lessons(), db.pipeline(), db.cards(), db.photos(), db.practice())
        practice = PracticeRepository(this, db.practice(), db.cards())
        sketches = SketchRepository(this, db.photos())
        diagrams = DiagramRepository(AuthRepository.get())
        consent = ConsentStore(this)
        ageGate = AgeGate(this)
        auth = AuthRepository.get()
        shares = ShareRepository(auth)
        lessonTeachers = LessonTeacherStore(this)
        posture = PostureLink(this, scope)
        practicePosture = PracticePosture(this, scope)
        deviceTokens = DeviceTokens(this, auth)
        auth.beforeSignOut = { deviceTokens.unregister() }
        CardPushService.createChannel(this)

        // Lessons processed while signed out get their cards once someone signs in.
        scope.launch {
            auth.signedInEmail.distinctUntilChanged().collect { if (it != null) lessons.makeMissingCards() }
        }
        // Once signed in, register this phone for "card updated" pushes.
        scope.launch {
            auth.signedInEmail.distinctUntilChanged().collect { email ->
                if (email == null) return@collect
                runCatching { FirebaseMessaging.getInstance().token.await() }
                    .onSuccess { deviceTokens.register(it) }
                    .onFailure { Log.w("CueNotePush", "No Firebase token yet", it) }
            }
        }
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
