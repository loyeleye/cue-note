package com.cuenote.practice

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cuenote.audio.Listening
import com.cuenote.audio.PracticeRecorder
import com.cuenote.data.PracticeRepository
import com.cuenote.data.PracticeTakeEntity
import com.cuenote.data.SetlistRow
import com.cuenote.pipeline.practice.PracticeLog
import com.cuenote.ui.Brass
import com.cuenote.ui.ClipPlayer
import com.cuenote.ui.Masthead
import com.cuenote.ui.NoteCard
import com.cuenote.ui.PlayGlyph
import com.cuenote.ui.PlayPill
import com.cuenote.ui.StartButton
import com.cuenote.ui.TicketRow
import com.cuenote.ui.Topic
import com.cuenote.ui.quoteStyle
import com.cuenote.ui.rememberClipPlayer
import com.cuenote.ui.rememberReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Practicing one note between lessons: hear the teacher's words and the
 * lesson take again, then sing practice takes and play them back. Takes stay
 * on the phone. While a take records, the screen shows just the cue and a
 * big Done at the bottom, where the thumb is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeScreen(
    cardId: String,
    repo: PracticeRepository,
    /** Who gave the note, for "Marigold said"; null for "Your teacher". */
    teacherName: (lessonId: String) -> String?,
    /** A lesson is being recorded, so the mic is busy. */
    lessonRecording: Boolean,
    /** Signed in with a teacher who has accepted, so takes can be shared and questions asked. */
    sharing: Boolean = false,
    /** Posture during takes; null when this phone can't track it. */
    posture: TakePosture? = null,
    /** Opens the framing check, for posture. */
    onFraming: () -> Unit = {},
    onTimingCheck: () -> Unit,
    onBack: () -> Unit,
    /** A step of today's suggested practice: a step bar on top, Skip and Next at the bottom. */
    step: PlanStepUi? = null,
    /** Heard, not sung: no takes today (a tired or resting voice). */
    listenOnly: Boolean = false,
) {
    val row by repo.card(cardId).collectAsStateWithLifecycle(null)
    val takes by repo.takes(cardId).collectAsStateWithLifecycle(emptyList())
    val mePhotos by remember(row?.card?.localId) { repo.mePhotos(row?.card) }.collectAsStateWithLifecycle(emptyList())
    val waiting by remember { repo.waiting() }.collectAsStateWithLifecycle(emptyList())
    val allQuestions by remember { repo.questions() }.collectAsStateWithLifecycle(emptyList())
    val questions = allQuestions.filter { it.cardId == cardId }
    var asking by remember { mutableStateOf(false) }
    var postureOn by rememberSaveable { mutableStateOf(false) }
    val postureReady by (posture?.ready ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsStateWithLifecycle()
    val devices = rememberDevices()
    val timingVersion by repo.timing.changes.collectAsStateWithLifecycle()
    val latency = remember(devices.output, timingVersion) { repo.timing.latencyMs(devices.output) }
    val player = rememberClipPlayer()
    val context = LocalContext.current
    val recorder = remember { PracticeRecorder(context) }
    val scope = rememberCoroutineScope()
    var startedAt by remember { mutableStateOf<Long?>(null) }
    var saving by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<PracticeTakeEntity?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val recording = startedAt != null
    var singingAlong by remember { mutableStateOf(false) }

    // The music from the lesson, cut from the teacher's channel the first time.
    val card = row?.card
    var backing by remember { mutableStateOf<PracticeRepository.Backing?>(null) }
    var backingLoading by remember { mutableStateOf(true) }
    LaunchedEffect(card?.id, card?.localId) {
        if (card == null) return@LaunchedEffect
        backingLoading = true
        backing = runCatching { repo.backing(card) }.getOrNull()
        backingLoading = false
    }
    var settings by remember { mutableStateOf(BackingSettings()) }
    val music = rememberLoopPlayer()
    var musicSpan by remember { mutableStateOf<Any?>(null) }
    val accompaniment = backing?.accompaniment

    // Pitch after each take, against the teacher's demo; worked out in the background.
    val pitches = remember { androidx.compose.runtime.mutableStateMapOf<String, com.cuenote.pipeline.practice.TakePitch?>() }
    LaunchedEffect(takes, backing, backingLoading) {
        if (backingLoading) return@LaunchedEffect
        for (t in takes) if (t.id !in pitches) pitches[t.id] = runCatching { repo.pitch(t, backing) }.getOrNull()
    }
    val canSingAlong = accompaniment != null && devices.output.listening != Listening.SPEAKER

    // Leaving the screen mid-take (rather than with Back) doesn't keep a half take.
    DisposableEffect(recorder) {
        onDispose {
            posture?.stop()
            recorder.stop()?.file?.let { f -> f.delete(); repo.postureDir(f).deleteRecursively() }
        }
    }
    // The screen stays on while singing.
    val view = LocalView.current
    DisposableEffect(recording, step) {
        // In a sitting of suggested practice the phone often sits on a music stand: keep it on throughout.
        view.keepScreenOn = recording || step != null
        onDispose { view.keepScreenOn = false }
    }

    /** Starts a take, singing along with [along] in the headphones when given. */
    fun start(along: PracticeRepository.Track? = null) {
        player.stop()
        music.stop()
        note = null
        scope.launch {
            val pcm = along?.let { runCatching { repo.pcm(repo.slowed(it, settings.rate)) }.getOrNull() }
            val file = repo.newTakeFile()
            runCatching { recorder.start(file, pcm) }
                .onSuccess {
                    startedAt = System.currentTimeMillis(); singingAlong = pcm != null
                    if (postureOn && postureReady) posture?.start(repo.postureDir(file))
                }
                .onFailure { note = "The mic didn't start: ${it.message}" }
        }
    }

    /** Ends the take; [keep] false throws it away. Closing the file runs off the main thread. */
    fun stop(keep: Boolean, then: () -> Unit = {}) {
        val began = startedAt ?: return
        startedAt = null
        singingAlong = false
        val card = row?.card
        saving = keep
        posture?.stop()
        scope.launch {
            val take = withContext(Dispatchers.IO) { recorder.stop() }
            if (take != null && (!keep || card == null || take.durationMs < PracticeRepository.MIN_TAKE_MS)) {
                withContext(Dispatchers.IO) { repo.postureDir(take.file).deleteRecursively() }
            }
            when {
                take == null -> Unit
                !keep || card == null -> withContext(Dispatchers.IO) { take.file.delete() }
                take.durationMs < PracticeRepository.MIN_TAKE_MS -> {
                    withContext(Dispatchers.IO) { take.file.delete() }
                    note = "That one was very short, so it wasn't kept."
                }
                else -> repo.saveTake(card, take, began, devices.output, latency)
            }
            saving = false
            then()
        }
    }

    BackHandler(enabled = recording) { confirmLeave = true }

    // A sing-along take ends a moment after the piano does.
    val backingDone by recorder.playbackDone.collectAsStateWithLifecycle()
    LaunchedEffect(singingAlong, backingDone) {
        if (singingAlong && backingDone && startedAt != null) { delay(1_000); stop(keep = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (step != null) "Today's practice" else "Practice") },
                navigationIcon = { TextButton(onClick = { if (recording) confirmLeave = true else onBack() }) { Text("Back") } },
                actions = { if (step != null && !recording) TextButton(onClick = step.onEnd) { Text("End", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
            )
        },
        bottomBar = {
            if (listenOnly) step?.let { StepButtons(it) } else Column {
            if (step != null && !recording) StepButtons(step, Modifier.padding(top = 8.dp))
            SingBar(
                recording = recording,
                startedAt = startedAt,
                enabled = !lessonRecording && row != null && !saving,
                label = when {
                    saving -> "Saving…"
                    canSingAlong -> "Sing along with the piano"
                    takes.isEmpty() -> "Sing a practice take"
                    else -> "Sing another take"
                },
                onStart = { start(if (canSingAlong) accompaniment else null) },
                onSingAlone = if (canSingAlong) ({ start() }) else null,
                hint = if (accompaniment != null && !canSingAlong) "Plug in headphones to sing along with the piano." else null,
                onDone = { stop(keep = true) },
                onStartOver = { val along = singingAlong; stop(keep = false) { start(if (along) accompaniment else null) } },
            )
            }
        },
    ) { padding ->
        val r = row
        if (recording && r != null) {
            Singing(r, { recorder.level.value }, Modifier.padding(padding), mePhotos.firstOrNull())
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (step != null) item(key = "step") { StepHeader(step) } else item(key = "mast") { Masthead("Practice ", "time") }
            if (r == null) {
                item(key = "gone") {
                    Text(
                        "This note isn't on the phone any more. It may have been removed from its lesson.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@LazyColumn
            }
            item(key = "note") { Note(r, teacherName(r.card.lessonId), player, mePhotos) }
            player.error?.let { e -> item(key = "perr") { Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }

            item(key = "backing") {
                BackingSection(
                    backing = backing,
                    loading = backingLoading,
                    settings = settings,
                    onSettings = { settings = it; music.stop() },
                    isPlaying = { t -> music.playing != null && musicSpan == t.span },
                    onPlayTrack = { t ->
                        player.stop()
                        if (musicSpan == t.span && music.playing != null) music.stop()
                        else scope.launch {
                            val f = repo.slowed(t, settings.rate)
                            music.toggle(f.path, settings.loop)
                            musicSpan = t.span
                        }
                    },
                    onPlayNote = {
                        val n = backing?.startingNote ?: return@BackingSection
                        player.stop()
                        if (musicSpan == n && music.playing != null) music.stop()
                        else scope.launch { music.toggle(repo.toneFile(n).path); musicSpan = n }
                    },
                    notePlaying = music.playing != null && musicSpan == backing?.startingNote,
                )
            }

            if (listenOnly) return@LazyColumn
            item(key = "listen") { ListeningRow(devices, latency, onTimingCheck) }
            if (posture != null) {
                item(key = "posture") {
                    PostureRow(
                        on = postureOn, ready = postureReady,
                        onToggle = { on -> postureOn = on; if (on && !postureReady) onFraming() },
                        onFraming = onFraming,
                    )
                }
            }

            if (lessonRecording) {
                item(key = "busy") {
                    Text(
                        "A lesson is recording, so practice takes wait until it ends.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            note?.let { n ->
                item(key = "msg") {
                    Text(n, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
            val teacher = teacherName(r.card.lessonId) ?: "your teacher"
            takesList(
                takes, r, player, { pitches[it] }, { it in pitches }, { repo.postureDir(java.io.File(it.file)) },
                share = if (!sharing) null else TakeSharing(
                    teacher = teacher,
                    canShareMore = PracticeLog.canShare(waiting.size),
                    onShare = { t, on ->
                        scope.launch { repo.share(t, on) }
                        note = if (on) "Shared. $teacher can listen before your lesson." else null
                    },
                ),
                repo = repo,
            ) { confirmDelete = it }
            if (sharing) {
                item(key = "askh") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Text("Questions for $teacher", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { asking = true }) { Text("Ask") }
                    }
                }
                if (questions.isEmpty()) {
                    item(key = "askhint") {
                        Text("Stuck on something here? Ask, and it goes on their list for your next lesson.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(questions, key = { "q-" + it.id }) { q ->
                    QuestionRow(q, teacher.replaceFirstChar { it.uppercase() }, where = "This note", onDrop = { scope.launch { repo.drop(q.id) } }, voice = repo to player)
                }
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Keep this take?") },
            text = { Text("You're still recording.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; stop(keep = true, then = onBack) }) { Text("Keep it") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false; stop(keep = false, then = onBack) }) { Text("Don't keep") } },
        )
    }

    if (asking) {
        val c = row?.card
        AskDialog(
            teacher = c?.let { teacherName(it.lessonId) } ?: "your teacher",
            aboutNote = true,
            onSend = { body, bar -> asking = false; scope.launch { repo.ask(c, bar, body) } },
            onDismiss = { asking = false },
        )
    }

    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this take?") },
            text = {
                Text(
                    if (t.shared) "It's taken back from your teacher too, and can't be brought back."
                    else "It's only on this phone, so it can't be brought back.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (player.playing == t.file) player.stop()
                    scope.launch { repo.deleteTake(t.id) }
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep it") } },
        )
    }
}

/** The takes, newest first, under a strip to hear the teacher's words again for comparing. */
private fun LazyListScope.takesList(
    takes: List<PracticeTakeEntity>,
    r: SetlistRow,
    player: ClipPlayer,
    pitch: (String) -> com.cuenote.pipeline.practice.TakePitch?,
    analysed: (String) -> Boolean,
    postureDir: (PracticeTakeEntity) -> java.io.File,
    share: TakeSharing?,
    repo: PracticeRepository,
    onDelete: (PracticeTakeEntity) -> Unit,
) {
    if (takes.isEmpty()) return
    item(key = "takesh") { Text("Your practice takes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
    r.wordsFile?.let { f ->
        item(key = "compare") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayPill("Their words", player.playing == f, filled = true, onClick = { player.toggle(f) })
                Text("Play a take, then their words again.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    itemsIndexed(takes, key = { _, t -> t.id }) { i, t ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TakeRow(takes.size - i, t, player.playing == t.file, share, onPlay = { player.toggle(t.file) }, onDelete = { onDelete(t) })
            if (share != null) TeacherReply(share.teacher.replaceFirstChar { it.uppercase() }, t.reply, t.replyClipPath, repo, player, Modifier.padding(horizontal = 12.dp))
            val p = pitch(t.id)
            when {
                p != null -> PitchStrip(p, Modifier.padding(horizontal = 12.dp))
                !analysed(t.id) -> Text("Listening back to your pitch…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
            }
            TakePostureLine(postureDir(t), Modifier.padding(horizontal = 12.dp))
        }
    }
    item(key = "private") {
        Text(if (share == null) "Practice takes stay on this phone." else "Practice takes stay on this phone unless you share one. Share the ones you're least sure about, not just your best.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Note(r: SetlistRow, teacher: String?, player: ClipPlayer, mePhotos: List<PracticeRepository.MePhoto> = emptyList()) {
    val c = r.card
    val topic = Topic.of(c.category)
    val words = c.herWords.ifBlank { c.cue }
    NoteCard(
        topic = topic,
        topicLabel = topic?.label ?: c.category?.replaceFirstChar { it.uppercase() } ?: "Note",
        where = listOfNotNull(shortDate(r.recordedAt), bars(r)).joinToString(", "),
        quote = words,
        teacherName = teacher ?: "Your teacher",
        checkedByTeacher = c.editedByTeacher,
    ) {
        if (c.cue.isNotBlank() && c.cue != words) {
            Text(c.cue, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (m in mePhotos) MeReference(m)
        if (r.wordsFile != null || r.takeFile != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.wordsFile?.let { f -> PlayPill("Their words", player.playing == f, filled = true, onClick = { player.toggle(f) }) }
                r.takeFile?.let { f -> PlayPill("Your take", player.playing == f, filled = false, onClick = { player.toggle(f) }) }
            }
        }
    }
}

/** Headphones and mic: a status chip, one line on what it means, then the timing check. */
@Composable
private fun ListeningRow(devices: Devices, latency: Int?, onTimingCheck: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val (title, chip, body) = when (devices.output.listening) {
        Listening.WIRED -> if (latency != null) Triple("Headphones in", "Timing checked", "Sing-along takes will line up.")
            else Triple("Headphones in", "Not checked yet", "A 10-second check lines up sing-along takes.")
        Listening.BLUETOOTH -> Triple("Bluetooth headphones", if (latency != null) "Timing checked" else "Runs a little late",
            "Fine on your own. Wired is best for singing along.")
        Listening.SPEAKER -> Triple("No headphones", null, "Fine on your own. Plug some in to sing along.")
    }
    TicketRow {
        Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Headphones, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                chip?.let { StatusChip(it, good = it == "Timing checked") }
            }
            Text(body, color = muted, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = muted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (devices.lessonMic) "Recording from your lesson mic" else "Recording from the phone's mic",
                    color = muted, style = MaterialTheme.typography.labelMedium)
            }
            if (devices.output.listening != Listening.SPEAKER) {
                OutlinedButton(onClick = onTimingCheck, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    Text(if (latency != null) "Check timing again" else "Check timing · 10 sec")
                }
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, good: Boolean) {
    val m = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (good) m.onPrimaryContainer else m.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (good) m.primaryContainer else m.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/**
 * The one button at the bottom: Sing when idle, then Done (with the time)
 * in the same spot while singing, so nothing moves under the thumb.
 */
@Composable
private fun SingBar(
    recording: Boolean,
    startedAt: Long?,
    enabled: Boolean,
    label: String,
    onStart: () -> Unit,
    /** Shown under the main button when it sings along: a take without the piano. */
    onSingAlone: (() -> Unit)?,
    hint: String?,
    onDone: () -> Unit,
    onStartOver: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!recording) {
            StartButton(label, onStart, Modifier.fillMaxWidth().defaultMinSize(minHeight = 60.dp), enabled = enabled)
            onSingAlone?.let { TextButton(onClick = it, enabled = enabled) { Text("Sing on your own") } }
            hint?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        } else {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(startedAt) { while (true) { now = System.currentTimeMillis(); delay(250) } }
            StartButton("Done, keep it · ${clock(now - (startedAt ?: now))}", onDone,
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 72.dp), dot = false)
            TextButton(onClick = onStartOver) { Text("Start over") }
        }
    }
}

/** While a take records: the cue to sing with, and a calm sign that the mic hears you. */
@Composable
private fun Singing(r: SetlistRow, level: () -> Double?, modifier: Modifier, me: PracticeRepository.MePhoto? = null) {
    val c = r.card
    Column(
        modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RecordDot()
            Spacer(Modifier.width(10.dp))
            Text("Recording", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            HearingYou(level)
        }
        Text("“${c.herWords.ifBlank { c.cue }}”", style = quoteStyle(), color = MaterialTheme.colorScheme.onSurface)
        if (c.cue.isNotBlank() && c.cue != c.herWords) {
            Text(c.cue, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        bars(r)?.let { Text(it.replaceFirstChar { ch -> ch.uppercase() }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        // the photo of you from the lesson stays up while you sing, to match the shape
        me?.let { MeThumb(it, Modifier.size(160.dp)) }
        Spacer(Modifier.weight(1f))
        Text("Sing it as many times as you like, then tap Done.", color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
    }
}

/** The brass record dot, breathing slowly (still with reduced motion). */
@Composable
private fun RecordDot() {
    val reduced = rememberReducedMotion()
    val alpha = if (reduced) 1f else {
        val t = rememberInfiniteTransition(label = "rec")
        t.animateFloat(1f, 0.45f, infiniteRepeatable(tween(1_200), RepeatMode.Reverse), label = "rec-dot").value
    }
    Box(Modifier.size(14.dp).alpha(alpha).background(Brass, CircleShape))
}

/**
 * "Hearing you" or "Listening…": a light that comes on when the mic picks the
 * student up, and never fills like a meter, so it doesn't invite pushing.
 */
@Composable
private fun HearingYou(level: () -> Double?) {
    var hearing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var lastHeard = 0L
        while (true) {
            val now = System.currentTimeMillis()
            if ((level() ?: -120.0) > -45.0) lastHeard = now
            hearing = now - lastHeard < 600
            delay(100)
        }
    }
    val m = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = if (hearing) "Hearing you" else "Listening" },
    ) {
        Box(
            Modifier.size(10.dp).then(
                if (hearing) Modifier.background(m.primary, CircleShape) else Modifier.border(1.5.dp, m.outline, CircleShape),
            ),
        )
        Spacer(Modifier.width(6.dp))
        Text(if (hearing) "Hearing you" else "Listening…", style = MaterialTheme.typography.labelLarge,
            color = if (hearing) m.primary else m.onSurfaceVariant)
    }
}

/** Sharing takes with the teacher before the lesson; null when there's no teacher to share with. */
private class TakeSharing(
    val teacher: String,
    /** Fewer than three shared takes are waiting for the teacher. */
    val canShareMore: Boolean,
    val onShare: (PracticeTakeEntity, Boolean) -> Unit,
)

/** A take: play on the left, then its number and when, and a ⋯ menu with Share and Delete. */
@Composable
private fun TakeRow(number: Int, take: PracticeTakeEntity, playing: Boolean, share: TakeSharing?, onPlay: () -> Unit, onDelete: () -> Unit) {
    val exists = remember(take.file) { File(take.file).isFile }
    var menu by remember { mutableStateOf(false) }
    val m = MaterialTheme.colorScheme
    TicketRow(Modifier.then(if (playing) Modifier.border(1.5.dp, m.primary, RoundedCornerShape(14.dp)) else Modifier)) {
        IconButton(onClick = onPlay, enabled = exists, modifier = Modifier.semantics { contentDescription = if (playing) "Stop take $number" else "Play take $number" }) {
            Box(Modifier.size(40.dp).background(if (playing) m.primary else m.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                if (playing) Box(Modifier.size(12.dp).background(m.onPrimary, RoundedCornerShape(2.dp)))
                else PlayGlyph(m.onPrimaryContainer, Modifier.size(13.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text("Take $number", style = MaterialTheme.typography.titleSmall)
            Text(
                "${whenSung(take.recordedAt)} · ${clock(take.durationMs)}",
                color = m.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            )
            if (take.shared && share != null) {
                Text(
                    when {
                        take.heardAt != null -> "${share.teacher.replaceFirstChar { it.uppercase() }} listened"
                        take.cloudShared -> "Shared with ${share.teacher}"
                        else -> "Shares when you're online"
                    },
                    color = if (take.heardAt != null) m.primary else Brass,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More for take $number", tint = m.onSurfaceVariant) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (share != null && exists) {
                    when {
                        take.shared && take.heardAt == null ->
                            DropdownMenuItem(text = { Text("Stop sharing") }, onClick = { menu = false; share.onShare(take, false) })
                        take.shared -> Unit
                        share.canShareMore ->
                            DropdownMenuItem(text = { Text("Share with ${share.teacher}") }, onClick = { menu = false; share.onShare(take, true) })
                        else -> DropdownMenuItem(
                            text = { Text("Three takes are waiting for ${share.teacher}") },
                            onClick = { menu = false }, enabled = false,
                        )
                    }
                }
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

private fun bars(r: SetlistRow): String? {
    val c = r.card
    return when {
        c.barStart != null && c.barEnd != null && c.barEnd != c.barStart -> "bars ${c.barStart}–${c.barEnd}"
        c.barStart != null -> "bar ${c.barStart}"
        else -> null
    }
}

internal fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

private fun shortDate(ms: Long): String = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))

private fun whenSung(ms: Long): String {
    val age = System.currentTimeMillis() - ms
    return when {
        age < 60_000 -> "Just now"
        age < 3_600_000 -> "${age / 60_000} min ago"
        else -> SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(ms))
    }
}
