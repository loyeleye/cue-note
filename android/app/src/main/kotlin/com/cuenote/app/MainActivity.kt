package com.cuenote.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.cuenote.ui.CueNoteTheme
import com.cuenote.audio.RecordingState
import com.cuenote.data.AgeGate
import com.cuenote.data.InvitedTeacher
import com.cuenote.data.LessonTeacher
import com.cuenote.data.sync.LessonTeacherSyncWorker
import com.cuenote.audio.ChannelSwap
import com.cuenote.lesson.CornerEditorScreen
import com.cuenote.lesson.LessonScreen
import com.cuenote.lesson.SketchScreen
import com.cuenote.lesson.TimelineScreen
import com.cuenote.posture.PostureSetup
import com.cuenote.practice.NextLessonScreen
import com.cuenote.practice.PlanRunScreen
import com.cuenote.practice.PlanScreen
import com.cuenote.practice.PracticeScreen
import com.cuenote.practice.TodayCard
import com.cuenote.practice.TimingCheckScreen
import com.cuenote.record.AgeBlockedScreen
import com.cuenote.record.AgeGateScreen
import com.cuenote.record.ConsentScreen
import com.cuenote.record.FramingCheckScreen
import com.cuenote.record.HomeScreen
import com.cuenote.record.InviteTeachersScreen
import com.cuenote.record.LessonTeacherDialog
import com.cuenote.record.trusted
import com.cuenote.record.NextLessonPeek
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
        if (savedInstanceState == null) {
            app.auth.handleDeepLink(intent)
            CardPushService.lessonFrom(intent)?.let { app.openLesson.value = it }
        }
        setContent {
            CueNoteTheme {
                Surface { AgeGated(app) { AppNav(app) } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        app.auth.handleDeepLink(intent)
        CardPushService.lessonFrom(intent)?.let { app.openLesson.value = it }
    }
}

private fun modelStatus(ready: Boolean, download: Pair<WorkInfo.State, Int>?): String = when {
    ready -> "Speech models are on this phone, so your notes can be made offline."
    download?.first == WorkInfo.State.RUNNING -> "Downloading speech models… ${download.second}%"
    download?.first == WorkInfo.State.ENQUEUED -> "Speech models will download when the phone is online."
    download?.first == WorkInfo.State.FAILED -> "The download didn't finish. Try again on Wi-Fi."
    else -> "Grab the speech models on Wi-Fi before your first lesson."
}

private val runtimePermissions = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

/** Nothing else in the app shows until the age question is answered, and never for under-13s. */
@Composable
private fun AgeGated(app: CueNoteApp, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(app.ageGate.status()) }
    when (status) {
        AgeGate.Status.OLD_ENOUGH -> content()
        AgeGate.Status.BLOCKED -> AgeBlockedScreen()
        AgeGate.Status.UNKNOWN -> AgeGateScreen { birth ->
            status = app.ageGate.answer(birth)
            // A sign-in link opened before answering shouldn't leave an under-13 signed in.
            if (status == AgeGate.Status.BLOCKED) scope.launch { runCatching { app.auth.signOut() } }
        }
    }
}

@Composable
private fun AppNav(app: CueNoteApp) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val lessons by app.lessons.lessons.collectAsStateWithLifecycle(emptyList())
    val cardCounts by app.lessons.cardCounts.collectAsStateWithLifecycle(emptyMap())
    val email by app.auth.signedInEmail.collectAsStateWithLifecycle(null)
    val setlist by app.lessons.setlist.collectAsStateWithLifecycle(emptyList())
    // The student's teachers, for "Marigold said" on the notes.
    var myTeachers by remember { mutableStateOf<List<InvitedTeacher>>(emptyList()) }
    LaunchedEffect(email) {
        myTeachers = if (email == null) emptyList() else runCatching { app.shares.list() }.getOrDefault(emptyList())
    }
    val firstSetup = remember(lessons.size) { app.consent.firstSetup() }
    // Before the next lesson: shared takes, questions, and the date a teacher set.
    val sharing = email != null && myTeachers.any { it.active }
    val waiting by remember { app.practice.waiting() }.collectAsStateWithLifecycle(emptyList())
    val questions by remember { app.practice.questions() }.collectAsStateWithLifecycle(emptyList())
    val nextLessonAt by app.practice.agenda.nextLessonAt.collectAsStateWithLifecycle()
    LaunchedEffect(sharing) { if (sharing) app.practice.refresh() }
    val onlyTeacher = myTeachers.filter { it.active }.singleOrNull()?.teacherName?.takeIf { it.isNotBlank() }
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
        if (!granted) Toast.makeText(app, "No camera, so posture stays off.", Toast.LENGTH_LONG).show()
        afterCamera.value?.invoke(granted)
    }

    // A tapped "card updated" notification opens its lesson, once the phone knows that lesson.
    val openLesson by app.openLesson.collectAsStateWithLifecycle()
    LaunchedEffect(openLesson, lessons) {
        val id = openLesson ?: return@LaunchedEffect
        if (lessons.none { it.id == id }) return@LaunchedEffect
        app.openLesson.value = null
        nav.navigate("lesson/$id")
    }

    // Notifications: Android 13+ asks once, after sign-in, so card-updated pushes can show.
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(email) {
        if (email != null && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // A sign-in link that fails or is still exchanging shows on the sign-in screen.
    LaunchedEffect(linkStatus) {
        if (linkStatus != null && nav.currentDestination?.route != "signin") nav.navigate("signin")
    }

    // Consent given, waiting on the who's-on-which-mic step (and the framing check, with posture).
    val pendingLesson = remember { mutableStateOf<PendingLesson?>(null) }
    // Bumped when a lesson's teacher changes, so names shown for it are read again.
    var teacherVersion by remember { mutableIntStateOf(0) }

    fun startLesson(posture: PostureSetup?) {
        val p = pendingLesson.value ?: return
        pendingLesson.value = null
        scope.launch {
            val lesson = app.lessons.create(p.consentAt)
            app.lessonTeachers.set(lesson.id, p.teacher)
            p.teacher.teacherId?.let { app.lessonTeachers.lastPicked = it }
            p.teacher.name?.let { app.lessonTeachers.lastName = it }
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
                cardCounts = cardCounts,
                setlist = setlist,
                studentName = firstSetup?.studentName,
                hasTeacher = myTeachers.any { it.active },
                teacherName = { lessonId -> teacherNameFor(app, myTeachers, firstSetup?.teacherName, lessonId, teacherVersion) },
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
                onDelete = { l ->
                    scope.launch {
                        runCatching { app.lessons.delete(l.id) }.onFailure {
                            Toast.makeText(
                                app, "Couldn't delete it online, so it's still here. Check your connection and try again.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                onOpen = { l -> nav.navigate("lesson/${l.id}") },
                onPractice = { cardId -> withPermission { nav.navigate("practice/$cardId") } },
                nextLesson = if (!sharing) null else NextLessonPeek(nextLessonAt, waiting.size, questions.count { it.open }),
                onNextLesson = { nav.navigate("next") },
                today = {
                    TodayCard(
                        plans = app.plans,
                        teacher = onlyTeacher,
                        onStart = { voice, minutes ->
                            scope.launch {
                                val id = app.plans.start(setlist, voice, minutes, nextLessonAt)
                                nav.navigate("plan/$id")
                            }
                        },
                        onResume = { id -> withPermission { nav.navigate("plan/$id/run") } },
                    )
                },
                modelStatus = modelStatus(app.lessons.modelsReady(), download),
                onDownloadModels = if (app.lessons.modelsReady() || download?.first == WorkInfo.State.RUNNING ||
                    download?.first == WorkInfo.State.ENQUEUED
                ) null else ({ app.lessons.downloadModels() }),
            )
        }
        composable("lesson/{id}") { entry ->
            val id = entry.arguments?.getString("id")!!
            lessons.firstOrNull { it.id == id }?.let { lesson ->
                var changing by remember { mutableStateOf(false) }
                if (changing) LessonTeacherDialog(
                    current = app.lessonTeachers.get(id),
                    teachers = myTeachers,
                    lastName = app.lessonTeachers.lastName,
                    onSave = { t ->
                        LessonTeacherSyncWorker.change(app, id, t)
                        t.name?.let { app.lessonTeachers.lastName = it }
                        teacherVersion++
                        changing = false
                    },
                    onDismiss = { changing = false },
                )
                LessonScreen(
                    lesson = lesson,
                    repo = app.lessons,
                    sketches = app.sketches,
                    diagrams = app.diagrams,
                    onOpenTimeline = { nav.navigate("timeline/$id") },
                    onOpenSketch = { photoId -> nav.navigate("sketch/$id/$photoId") },
                    onBack = { nav.popBackStack() },
                    taughtBy = teacherNameFor(app, myTeachers, null, id, teacherVersion),
                    onChangeTeacher = { changing = true },
                    fullLesson = if (email != null) app.fullLesson else null,
                    viewers = lessonViewers(myTeachers, app.lessonTeachers.get(id).teacherId),
                    onPractice = { cardId -> withPermission { nav.navigate("practice/$cardId") } },
                    onSendToTeacher = { nav.navigate("next") },
                    voiceNote = if (email != null) { path -> app.practice.voiceNote(path) } else null,
                    onAsk = { card -> app.practice.ask(card, null, "Can we go over this one? “${card.cue.ifBlank { card.herWords.take(80) }}”") },
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
        composable("practice/{card}") { entry ->
            PracticeScreen(
                cardId = entry.arguments?.getString("card")!!,
                repo = app.practice,
                teacherName = { lessonId -> teacherNameFor(app, myTeachers, firstSetup?.teacherName, lessonId, teacherVersion) },
                lessonRecording = recState is RecordingState.Recording,
                sharing = sharing,
                posture = app.practicePosture,
                onFraming = {
                    afterCamera.value = { granted -> if (granted) nav.navigate("framing-practice") }
                    cameraPermission.launch(Manifest.permission.CAMERA)
                },
                onTimingCheck = { nav.navigate("timing") },
                onBack = { nav.popBackStack() },
            )
        }
        composable("plan/{id}") { entry ->
            val id = entry.arguments?.getString("id")!!
            PlanScreen(
                planId = id,
                plans = app.plans,
                repo = app.practice,
                teacherName = { lessonId -> teacherNameFor(app, myTeachers, firstSetup?.teacherName, lessonId, teacherVersion) },
                onStart = { withPermission { nav.navigate("plan/$id/run") { popUpTo("plan/$id") { inclusive = true } } } },
                onBack = { nav.popBackStack() },
            )
        }
        composable("plan/{id}/run") { entry ->
            PlanRunScreen(
                planId = entry.arguments?.getString("id")!!,
                plans = app.plans,
                repo = app.practice,
                teacherName = { lessonId -> teacherNameFor(app, myTeachers, firstSetup?.teacherName, lessonId, teacherVersion) },
                lessonRecording = recState is RecordingState.Recording,
                sharing = sharing,
                posture = app.practicePosture,
                onFraming = {
                    afterCamera.value = { granted -> if (granted) nav.navigate("framing-practice") }
                    cameraPermission.launch(Manifest.permission.CAMERA)
                },
                onTimingCheck = { nav.navigate("timing") },
                onShare = { cardId -> nav.navigate("practice/$cardId") { popUpTo("home") } },
                onDone = { nav.popBackStack("home", false) },
            )
        }
        composable("next") {
            NextLessonScreen(
                repo = app.practice,
                setlist = setlist,
                teacher = onlyTeacher,
                sharing = sharing,
                onPractice = { cardId -> withPermission { nav.navigate("practice/$cardId") } },
                onBack = { nav.popBackStack() },
            )
        }
        composable("timing") { TimingCheckScreen(app.practice, onDone = { nav.popBackStack() }) }
        composable("setup") { SetupCheckScreen(onDone = { nav.popBackStack() }) }
        composable("consent") {
            // The student's teachers, for "Who's teaching today?". Offline or
            // signed out, only "Someone else" (a typed name) and "Not sure" are offered.
            var teachers by remember { mutableStateOf<List<InvitedTeacher>>(emptyList()) }
            LaunchedEffect(email) {
                if (email != null) teachers = runCatching { app.shares.list() }.getOrDefault(emptyList())
            }
            // A soundcheck just before already settled who's on which mic.
            val mics = remember { ChannelSwap.recentSoundcheck(app) }
            ConsentScreen(
                store = app.consent,
                teachers = teachers,
                lastPicked = app.lessonTeachers.lastPicked,
                lastName = app.lessonTeachers.lastName,
                mics = mics,
                onCancel = { nav.popBackStack() },
                onConsented = { c ->
                    // Trusted soundcheck: straight on (the channels are already set). Otherwise ask.
                    fun next(posture: Boolean) {
                        pendingLesson.value = PendingLesson(c.consentAt, c.teacher, posture)
                        if (mics.trusted(c.recheckMics)) ChannelSwap.set(app, mics!!.verdict.studentOnRight)
                        when {
                            !mics.trusted(c.recheckMics) -> nav.navigate("whoiswho")
                            posture -> nav.navigate("framing") { popUpTo("consent") }
                            else -> startLesson(null)
                        }
                    }
                    if (!c.posture) next(false)
                    else {
                        afterCamera.value = { granted -> next(granted) }
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
        // Posture during practice: the same framing check, kept for this sitting.
        composable("framing-practice") {
            FramingCheckScreen(
                onReady = { setup -> app.practicePosture.set(setup); nav.popBackStack() },
                onSkip = { nav.popBackStack() },
                onCancel = { nav.popBackStack() },
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

/**
 * Who taught a lesson: the teacher picked for it, or the name typed for it,
 * else the one named at first setup. [version] only makes callers re-read
 * after a change.
 */
@Suppress("UNUSED_PARAMETER")
private fun teacherNameFor(
    app: CueNoteApp,
    teachers: List<InvitedTeacher>,
    firstTeacher: String?,
    lessonId: String,
    version: Int,
): String? {
    val t = app.lessonTeachers.get(lessonId)
    return t.name
        ?: teachers.firstOrNull { it.teacherId != null && it.teacherId == t.teacherId }?.teacherName?.takeIf { it.isNotBlank() }
        ?: firstTeacher?.takeIf { it.isNotBlank() && !t.isSet }
}

/**
 * The teachers who can open this lesson, and so can be invited to hear all of
 * it: the one who taught it, and any the student lets see every lesson.
 */
private fun lessonViewers(teachers: List<InvitedTeacher>, taughtBy: String?): List<com.cuenote.lesson.LessonViewer> =
    teachers.filter { it.active && it.teacherId != null && (it.teacherId == taughtBy || !it.restrictedToTaught) }
        .map { com.cuenote.lesson.LessonViewer(it.teacherId!!, it.teacherName?.takeIf { n -> n.isNotBlank() } ?: it.email) }

private data class PendingLesson(val consentAt: Long, val teacher: LessonTeacher, val posture: Boolean)
