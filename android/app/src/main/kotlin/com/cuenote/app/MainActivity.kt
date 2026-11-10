package com.cuenote.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.work.WorkInfo
import com.cuenote.audio.RecordingService
import com.cuenote.audio.RecordingSession
import com.cuenote.app.ui.CueNoteTheme
import com.cuenote.audio.RecordingState
import com.cuenote.data.InvitedTeacher
import com.cuenote.lesson.CornerEditorScreen
import com.cuenote.lesson.LessonScreen
import com.cuenote.lesson.SketchScreen
import com.cuenote.lesson.TimelineScreen
import com.cuenote.posture.PostureSetup
import com.cuenote.record.ConsentScreen
import com.cuenote.record.FramingCheckScreen
import com.cuenote.record.HomeScreen
import com.cuenote.record.InviteTeachersScreen
import com.cuenote.record.RecordingScreen
import com.cuenote.record.SetupCheckScreen
import com.cuenote.record.SignInScreen
import com.cuenote.record.WhoIsWhoScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as CueNoteApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // On a rotation the old link is redelivered; its code is already spent.
        if (savedInstanceState == null) app.auth.handleDeepLink(intent)
        setContent {
            CueNoteTheme {
                Surface { AppNav(app) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        app.auth.handleDeepLink(intent)
    }
}

private fun modelStatus(ready: Boolean, download: Pair<WorkInfo.State, Int>?): String = when {
    ready -> "Speech models are on the phone. Lessons can be processed offline."
    download?.first == WorkInfo.State.RUNNING -> "Downloading speech models… ${download.second}%"
    download?.first == WorkInfo.State.ENQUEUED -> "Speech models will download when the phone is online."
    download?.first == WorkInfo.State.FAILED -> "The download failed. Try again on Wi-Fi."
    else -> "Download the speech models on Wi-Fi before your first lesson, or they download when it is first processed."
}

private val runtimePermissions = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
private fun AppNav(app: CueNoteApp) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val lessons by app.lessons.lessons.collectAsStateWithLifecycle(emptyList())
    val email by app.auth.signedInEmail.collectAsStateWithLifecycle(null)
    val linkStatus by app.auth.linkStatus.collectAsStateWithLifecycle()
    val recState by RecordingSession.state.collectAsStateWithLifecycle()
    val download by app.lessons.modelDownload().collectAsStateWithLifecycle(null)

    // Ask for the microphone (and notification) permission, then go on.
    val afterPermission = remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) afterPermission.value?.invoke()
        else Toast.makeText(app, "Cue Note needs the microphone to record lessons.", Toast.LENGTH_LONG).show()
    }
    fun withPermission(then: () -> Unit) { afterPermission.value = then; permissions.launch(runtimePermissions) }

    // The camera is asked for only when a lesson tracks posture. Without it, the lesson records audio only.
    val afterCamera = remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(app, "No camera, so this lesson records audio only.", Toast.LENGTH_LONG).show()
        afterCamera.value?.invoke(granted)
    }

    // A sign-in link that fails or is still exchanging shows on the sign-in screen.
    LaunchedEffect(linkStatus) {
        if (linkStatus != null && nav.currentDestination?.route != "signin") nav.navigate("signin")
    }

    // Consent given, waiting on the who's-on-which-mic step (and the framing check, with posture).
    val pendingLesson = remember { mutableStateOf<PendingLesson?>(null) }

    fun startLesson(posture: PostureSetup?) {
        val p = pendingLesson.value ?: return
        pendingLesson.value = null
        scope.launch {
            val lesson = app.lessons.create(p.consentAt)
            app.lessonTeachers.set(lesson.id, p.teacherId)
            if (p.teacherId != null) app.lessonTeachers.lastPicked = p.teacherId
            val dir = app.lessons.lessonDir(lesson.id)
            RecordingService.start(app, lesson.id, dir)
            if (posture != null) app.posture.begin(lesson.id, dir, posture)
            nav.navigate("recording/${lesson.id}") { popUpTo("home") }
        }
    }

    NavHost(nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                lessons = lessons,
                recordingInProgress = recState is RecordingState.Recording,
                signedInEmail = email,
                onStartLesson = { withPermission { nav.navigate("consent") } },
                onReturnToRecording = {
                    (recState as? RecordingState.Recording)?.let { nav.navigate("recording/${it.lessonId}") }
                },
                onSetupCheck = { withPermission { nav.navigate("setup") } },
                onSignIn = { nav.navigate("signin") },
                onTeachers = { nav.navigate("teachers") },
                onExport = { l ->
                    scope.launch {
                        val msg = runCatching { app.lessons.exportToDownloads(l.id) }
                            .fold({ "Copied $it files to Downloads/CueNote" }, { "Export failed: ${it.message}" })
                        Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                    }
                },
                onDelete = { l -> scope.launch { app.lessons.delete(l.id) } },
                onOpen = { l -> nav.navigate("lesson/${l.id}") },
                modelStatus = modelStatus(app.lessons.modelsReady(), download),
                onDownloadModels = if (app.lessons.modelsReady() || download?.first == WorkInfo.State.RUNNING ||
                    download?.first == WorkInfo.State.ENQUEUED
                ) null else ({ app.lessons.downloadModels() }),
            )
        }
        composable("lesson/{id}") { entry ->
            val id = entry.arguments?.getString("id")!!
            lessons.firstOrNull { it.id == id }?.let { lesson ->
                LessonScreen(
                    lesson = lesson,
                    repo = app.lessons,
                    sketches = app.sketches,
                    diagrams = app.diagrams,
                    onOpenTimeline = { nav.navigate("timeline/$id") },
                    onOpenSketch = { photoId -> nav.navigate("sketch/$id/$photoId") },
                    onBack = { nav.popBackStack() },
                )
            }
        }
        composable("sketch/{lesson}/{photo}") { entry ->
            val id = entry.arguments?.getString("lesson")!!
            val photoId = entry.arguments?.getString("photo")!!
            val feedback by app.lessons.pipelineDao!!.observeFeedback(id).collectAsStateWithLifecycle(emptyList())
            SketchScreen(
                photoId = photoId,
                lessonDir = app.lessons.lessonDir(id),
                feedback = feedback,
                repo = app.sketches,
                lessonId = id,
                onFixCorners = { nav.navigate("corners/$id/$photoId") },
                onBack = { nav.popBackStack() },
            )
        }
        composable("corners/{lesson}/{photo}") { entry ->
            val id = entry.arguments?.getString("lesson")!!
            CornerEditorScreen(
                photoId = entry.arguments?.getString("photo")!!,
                lessonId = id,
                lessonDir = app.lessons.lessonDir(id),
                repo = app.sketches,
                onDone = { nav.popBackStack() },
            )
        }
        composable("timeline/{id}") { entry ->
            val id = entry.arguments?.getString("id")!!
            TimelineScreen(
                lessonDir = app.lessons.lessonDir(id),
                // re-running only redraws the timeline (finished stages are kept), now scored against the labels
                onLabelsSaved = { app.lessons.process(id) },
                onBack = { nav.popBackStack() },
            )
        }
        composable("setup") { SetupCheckScreen(onDone = { nav.popBackStack() }) }
        composable("consent") {
            // The student's teachers, for "Who's teaching today?". Offline or
            // signed out, the question is skipped and no teacher is set.
            var teachers by remember { mutableStateOf<List<InvitedTeacher>>(emptyList()) }
            LaunchedEffect(email) {
                if (email != null) teachers = runCatching { app.shares.list() }.getOrDefault(emptyList())
            }
            ConsentScreen(
                store = app.consent,
                teachers = teachers,
                lastPicked = app.lessonTeachers.lastPicked,
                onCancel = { nav.popBackStack() },
                onConsented = { consentAt, teacherId, posture ->
                    if (!posture) {
                        pendingLesson.value = PendingLesson(consentAt, teacherId, posture = false)
                        nav.navigate("whoiswho")
                    } else {
                        afterCamera.value = { granted ->
                            pendingLesson.value = PendingLesson(consentAt, teacherId, posture = granted)
                            nav.navigate("whoiswho")
                        }
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                },
            )
        }
        // Before each lesson: find out which mic the student has today.
        composable("whoiswho") {
            WhoIsWhoScreen(
                onCancel = { nav.popBackStack("home", false) },
                onReady = {
                    if (pendingLesson.value?.posture == true) nav.navigate("framing") { popUpTo("consent") }
                    else startLesson(null)
                },
            )
        }
        // With posture: make sure the camera can see the student at their singing spot.
        composable("framing") {
            FramingCheckScreen(
                onReady = { setup -> startLesson(setup) },
                onSkip = { startLesson(null) },
                onCancel = { pendingLesson.value = null; nav.popBackStack("home", false) },
            )
        }
        composable("recording/{id}") { entry ->
            val id = entry.arguments?.getString("id")!!
            RecordingScreen(lessonDir = app.lessons.lessonDir(id), onFinished = { nav.popBackStack("home", false) })
        }
        composable("teachers") {
            InviteTeachersScreen(
                shares = app.shares,
                signedIn = email != null,
                onSignIn = { nav.navigate("signin") },
                onBack = { nav.popBackStack() },
            )
        }
        composable("signin") { SignInScreen(app.auth, email, linkStatus, onBack = { nav.popBackStack() }) }
    }
}

private data class PendingLesson(val consentAt: Long, val teacherId: String?, val posture: Boolean)
