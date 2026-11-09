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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cuenote.audio.RecordingService
import com.cuenote.audio.RecordingSession
import com.cuenote.audio.RecordingState
import com.cuenote.lesson.LessonScreen
import com.cuenote.lesson.TimelineScreen
import com.cuenote.record.ConsentScreen
import com.cuenote.record.HomeScreen
import com.cuenote.record.RecordingScreen
import com.cuenote.record.SetupCheckScreen
import com.cuenote.record.SignInScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as CueNoteApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.auth.handleDeepLink(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface { AppNav(app) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        app.auth.handleDeepLink(intent)
    }
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
    val recState by RecordingSession.state.collectAsStateWithLifecycle()

    // Ask for the microphone (and notification) permission, then go on.
    val afterPermission = remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) afterPermission.value?.invoke()
        else Toast.makeText(app, "Cue Note needs the microphone to record lessons.", Toast.LENGTH_LONG).show()
    }
    fun withPermission(then: () -> Unit) { afterPermission.value = then; permissions.launch(runtimePermissions) }

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
                onExport = { l ->
                    scope.launch {
                        val msg = runCatching { app.lessons.exportToDownloads(l.id) }
                            .fold({ "Copied $it files to Downloads/CueNote" }, { "Export failed: ${it.message}" })
                        Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                    }
                },
                onDelete = { l -> scope.launch { app.lessons.delete(l.id) } },
                onOpen = { l -> nav.navigate("lesson/${l.id}") },
            )
        }
        composable("lesson/{id}") { entry ->
            val id = entry.arguments?.getString("id")!!
            lessons.firstOrNull { it.id == id }?.let { lesson ->
                LessonScreen(
                    lesson = lesson,
                    repo = app.lessons,
                    onOpenTimeline = { nav.navigate("timeline/$id") },
                    onBack = { nav.popBackStack() },
                )
            }
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
            ConsentScreen(
                store = app.consent,
                onCancel = { nav.popBackStack() },
                onConsented = { consentAt ->
                    scope.launch {
                        val lesson = app.lessons.create(consentAt)
                        RecordingService.start(app, lesson.id, app.lessons.lessonDir(lesson.id))
                        nav.navigate("recording/${lesson.id}") { popUpTo("home") }
                    }
                },
            )
        }
        composable("recording/{id}") { entry ->
            val id = entry.arguments?.getString("id")!!
            RecordingScreen(lessonDir = app.lessons.lessonDir(id), onFinished = { nav.popBackStack("home", false) })
        }
        composable("signin") { SignInScreen(app.auth, email, onBack = { nav.popBackStack() }) }
    }
}
