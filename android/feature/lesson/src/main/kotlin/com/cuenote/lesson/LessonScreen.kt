package com.cuenote.lesson

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import com.cuenote.ui.ClipPlayer
import com.cuenote.ui.rememberClipPlayer
import com.cuenote.data.SetlistStore
import com.cuenote.ui.Topic
import com.cuenote.ui.TopicChip
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import com.cuenote.data.diagrams.DiagramSuggestion
import com.cuenote.data.diagrams.DiagramRepository
import com.cuenote.data.sketch.SketchRepository
import com.cuenote.data.PhotoEntity
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.cuenote.data.CardEntity
import com.cuenote.data.FeedbackEntity
import com.cuenote.data.LessonEntity
import com.cuenote.data.LessonOutline
import com.cuenote.data.LessonParts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.cuenote.data.LessonOutline.SummaryCheck as S
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.cuenote.data.LessonRepository
import com.cuenote.data.TakeEntity
import com.cuenote.data.pipeline.LessonPipelineWorker
import com.cuenote.data.sync.LessonUploadWorker
import com.cuenote.pipeline.cards.CloudIds
import kotlinx.coroutines.launch
import java.io.File

/**
 * One lesson, exercise by exercise (see [LessonOutline]): each warmup or
 * song section with its tries to replay, then the few cues that matter, each
 * a short instruction with one sentence of hers and the try it was about.
 * Cards come from extract-cues and carry any edits she made on the
 * dashboard. Before there are cards, it shows the exercises and their tries;
 * the raw transcript and the linker's reasons stay under Details.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LessonScreen(
    lesson: LessonEntity,
    repo: LessonRepository,
    sketches: SketchRepository,
    diagrams: DiagramRepository,
    onOpenTimeline: () -> Unit,
    onOpenSketch: (photoId: String) -> Unit,
    onBack: () -> Unit,
    /** Who taught this lesson, by name, when set. */
    taughtBy: String? = null,
    /** Opens the "who taught this?" picker; null hides the row. */
    onChangeTeacher: (() -> Unit)? = null,
    /** Inviting a teacher to hear the whole lesson; null when signed out. */
    fullLesson: com.cuenote.data.FullLessonRepository? = null,
    /** The teachers who can open this lesson, and so could be invited to all of it. */
    viewers: List<LessonViewer> = emptyList(),
    /** Opens practice on one card; null hides "Start practicing". */
    onPractice: ((cardId: String) -> Unit)? = null,
    /** Opens the next-lesson page, where tries and questions go to the teacher; null hides it. */
    onSendToTeacher: (() -> Unit)? = null,
    /** Adds a point to the questions for the next lesson; null hides "Add to my questions". */
    onAsk: (suspend (CardEntity) -> Unit)? = null,
    /** Fetches a teacher's voice note to the phone; null when signed out. */
    voiceNote: (suspend (String) -> File?)? = null,
    /**
     * The teacher asked that this lesson stays on the phone: nothing of it
     * goes to the cloud or an AI, so it shows the phone's own notes, and the
     * parts that need the cloud are off.
     */
    localOnly: Boolean = false,
    /** A local-only lesson's earlier copy online is still waiting to be deleted. */
    deletingOnline: Boolean = false,
) {
    val dao = repo.pipelineDao!!
    val cardDao = repo.cardDao!!
    val stages by dao.observeStages(lesson.id).collectAsStateWithLifecycle(emptyList())
    val allTakes by dao.observeTakes(lesson.id).collectAsStateWithLifecycle(emptyList())
    val allFeedback by dao.observeFeedback(lesson.id).collectAsStateWithLifecycle(emptyList())
    val allExercises by dao.observeExercises(lesson.id).collectAsStateWithLifecycle(emptyList())
    val allCards by cardDao.observeCards(lesson.id).collectAsStateWithLifecycle(emptyList())
    // Parts the student deleted go at once; the phone deletes their files once Undo has passed.
    val removals by repo.removals(lesson.id).collectAsStateWithLifecycle()
    val parts = remember(allExercises, allTakes, allFeedback, allCards, removals) {
        LessonParts.without(LessonParts.Parts(allExercises, allTakes, allFeedback, allCards), removals.filterNot { it.localDone })
    }
    val takes = parts.takes
    val feedback = parts.feedback
    val exercises = parts.exercises
    val cards = parts.cards
    val clips by cardDao.observeClips(lesson.id).collectAsStateWithLifecycle(emptyList())
    val progress by repo.progress(lesson.id).collectAsStateWithLifecycle(null)
    val cardProgress by repo.cardProgress(lesson.id).collectAsStateWithLifecycle(null)
    val hasTimeline = File(lesson.dirPath, "pipeline/timeline.html").isFile
    val player = rememberClipPlayer()
    val scope = rememberCoroutineScope()
    var refreshNote by remember { mutableStateOf<String?>(null) }
    var showTalk by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var openMore by rememberSaveable { mutableStateOf(setOf<String>()) }
    val snackbar = remember { SnackbarHostState() }
    var deleteAsk by remember { mutableStateOf<DeleteAsk?>(null) }
    /** Shows "Deleted …" with Undo for as long as Undo is offered. */
    fun offerUndo(label: String, done: List<com.cuenote.pipeline.Removal>) = scope.launch {
        val r = snackbar.showSnackbar("Deleted $label", actionLabel = "Undo", withDismissAction = true, duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) {
            val back = done.map { repo.undoRemoval(lesson.id, it.id) }
            if (!back.all { it }) snackbar.showSnackbar("Too late to undo: it's already deleted.")
        }
    }

    val photos by sketches.observe(lesson.id).collectAsStateWithLifecycle(emptyList())
    val lessonDir = File(lesson.dirPath)
    LaunchedEffect(lesson.id) { sketches.refresh(lesson.id, lessonDir) }
    val onCard: (String?) -> List<PhotoEntity> = { local -> if (local == null) emptyList() else photos.filter { it.feedbackId == local } }

    val suggestions by diagrams.observe(lesson.id).collectAsStateWithLifecycle(emptyList())
    val onDecide: suspend (DiagramSuggestion, String) -> Unit = { s, d -> diagrams.decide(lesson.id, s, d) }
    LaunchedEffect(lesson.id, localOnly) { if (!localOnly) runCatching { diagrams.refresh(lesson.id) } }

    // A lesson kept on the phone: its notes are the phone's own, written once the lesson is processed.
    LaunchedEffect(lesson.id, localOnly, lesson.pipelineState, feedback.size) {
        if (localOnly && feedback.isNotEmpty() && (lesson.pipelineState == "processed" || lesson.pipelineState == "cards")) {
            runCatching { repo.writePhoneNotes(lesson.id) }
        }
    }

    // Her latest edits, each time the lesson is opened.
    LaunchedEffect(lesson.id, localOnly) {
        if (!localOnly && (cards.isNotEmpty() || lesson.pipelineState == "cards")) runCatching { repo.refreshCards(lesson.id) }
    }

    val overrides by repo.setlistStore.overrides.collectAsStateWithLifecycle()
    // Flagged summaries whose nudge she has answered ("Got it", or asked): kept on the phone.
    val nudgePrefs = LocalContext.current.getSharedPreferences("summary_nudges", android.content.Context.MODE_PRIVATE)
    var nudged by remember { mutableStateOf(nudgePrefs.getStringSet("done", emptySet())!!.toSet()) }
    var asked by remember { mutableStateOf(nudgePrefs.getStringSet("asked", emptySet())!!.toSet()) }
    fun doneNudge(id: String, ask: Boolean) {
        nudged = nudged + id
        if (ask) asked = asked + id
        nudgePrefs.edit().putStringSet("done", nudged).putStringSet("asked", asked).apply()
    }
    // Checked once per change, not on every frame: only clips that are on the phone.
    val clipFile: Map<String, String> = remember(clips) { clips.filter { File(it.file).isFile }.associate { it.id to it.file } }
    val clipMs: Map<String, Long> = remember(clips) { clips.associate { it.file to (it.endMs - it.startMs) } }
    val outline = remember(exercises, takes, feedback, cards) { LessonOutline.build(exercises, takes, feedback, cards) }
    val takeByCloud = remember(takes) { takes.associateBy { CloudIds.take(it.id) } }
    // Per card, once: her sketches and the diagrams suggested, across a point and its repeats.
    val groups = remember(outline) { outline.flatMap { it.cues + it.more } }
    val sketchesBy = remember(groups, photos) { groups.associate { g -> g.card.id to g.all.flatMap { onCard(it.localId) } } }
    val suggestionsBy = remember(groups, suggestions) {
        groups.associate { g -> g.card.id to suggestions.filter { s -> g.all.any { it.id == s.feedbackId } } }
    }

    // The points from this lesson on her setlist, in lesson order: what "Start practicing" opens.
    val onList = remember(groups, overrides) { groups.map { it.card }.filter { SetlistStore.isOn(it, overrides) } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Lesson", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
        )
    }, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        // Kept on the phone: practice from the phone's notes, but nothing goes to a teacher from here.
        val send = if (localOnly) null else onSendToTeacher
        if ((onPractice != null || send != null) && (cards.isNotEmpty() || takes.isNotEmpty())) {
            NextStepBar(onList.size, onList.firstOrNull()?.id?.let { id -> onPractice?.let { { it(id) } } }, send)
        }
    }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Status(
                    lesson, takes, feedback, cards, outline.size, progress, cardProgress,
                    stages.any { it.stage == "upload" && it.status == "waiting" },
                    lastFailure = stages.filter { it.status == "failed" }.maxByOrNull { it.finishedAt }?.detail,
                    localOnly = localOnly,
                )
                if (localOnly) KeptOnPhoneBanner(taughtBy, wasOnline = stages.any { it.stage == "upload" && it.status == "done" }, deletingOnline)
                if (onChangeTeacher != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (taughtBy != null) "Taught by $taughtBy" else "No teacher set for this lesson",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (taughtBy != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onChangeTeacher) { Text(if (taughtBy != null) "Change" else "Set teacher") }
                }
                Spacer(Modifier.padding(4.dp))
                val busy = progress?.state == WorkInfo.State.RUNNING || cardProgress?.state == WorkInfo.State.RUNNING
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (localOnly && (lesson.pipelineState == "processed" || lesson.pipelineState == "cards")) {
                        // Only the replay clips are left to make, on the phone.
                        if (clips.isEmpty()) Button(onClick = { repo.makeCards(lesson.id) }, enabled = !busy) { Text("Get the replays ready") }
                    } else if (lesson.pipelineState == "processed" || lesson.pipelineState == "cards") {
                        Button(onClick = { repo.makeCards(lesson.id) }, enabled = !busy) {
                            Text(if (cards.isEmpty()) "Make my notes" else "Make my notes again")
                        }
                    } else {
                        Button(onClick = { repo.process(lesson.id) }, enabled = !busy) { Text("Make my notes now") }
                    }
                    if (cards.isNotEmpty() && !localOnly) OutlinedButton(onClick = {
                        scope.launch {
                            runCatching { diagrams.refresh(lesson.id) }
                            refreshNote = runCatching { repo.refreshCards(lesson.id) }
                                .fold({ "Up to date with your teacher's edits." }, { "Couldn't refresh: ${it.message}" })
                        }
                    }) { Text("Refresh") }
                }
                refreshNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                player.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }

            if (!localOnly && fullLesson != null && viewers.isNotEmpty() && cards.isNotEmpty()) {
                item(key = "full-lesson") { FullLessonSection(lesson.id, fullLesson, viewers) }
            }

            if (photos.isNotEmpty()) {
                item { SectionTitle(if (photos.any { it.isMe }) "Photos from the lesson" else "Your teacher's sketches") }
                item { SketchStrip(photos, lessonDir, feedback) { onOpenSketch(it.id) } }
            }

            if (outline.isNotEmpty()) {
                if (cards.isNotEmpty() && cards.all { it.onPhone }) item {
                    Text(
                        "These are the phone's notes on what ${taughtBy ?: "your teacher"} said about your tries, in their own words and kept on this phone. " +
                            "The phone picked them, so check each against the lesson clip. Bookmark one to practice it, and add your own words to remember it by.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (cards.isEmpty()) item {
                    Text(
                        if (localOnly) "Each exercise shows the phone's best guess at what ${taughtBy ?: "your teacher"} said about your tries. " +
                            "It's a guess, so check it against the replay."
                        else "Until your notes are made, each exercise shows the app's best guess at what your teacher said about your tries.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val close = groups.count { it.check == LessonOutline.SummaryCheck.CLOSE }
                val corrected = groups.count { it.check == LessonOutline.SummaryCheck.FLAGGED || it.check == LessonOutline.SummaryCheck.CORRECTED }
                if (close + corrected > 0) item(key = "corrected") {
                    val parts = listOfNotNull("$close close".takeIf { close > 0 }, "$corrected misheard".takeIf { corrected > 0 })
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.outline))
                        Text(
                            "Your teacher reviewed the app's summary on ${plural(close + corrected, "point")}: ${parts.joinToString(", ")}. Each is marked below.",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                for (sec in outline) {
                    val key = sec.exercise?.id ?: "loose"
                    item(key = "ex-$key") {
                        ExerciseHeader(sec, clipFile, player, first = sec === outline.first()) {
                            ExerciseMenu(sec, last = sec === outline.last()) { deleteAsk = it }
                        }
                    }
                    // The try she said was right, to compare against the one each point is about.
                    val liked = sec.takes.firstOrNull { it.id in sec.starred }
                    val likedTry = liked?.let { Clip(clipFile[CloudIds.take(it.id)], "Try ${sec.tryNo(it.id)} ★, your teacher liked it", null) }
                    val card: @Composable (LessonOutline.CueGroup, Modifier) -> Unit = { g, mod ->
                        val c = g.card
                        val take = c.takeId?.let(takeByCloud::get)
                        val her = clipFile[c.id]
                        CueCard(
                            g, mod,
                            Clip(her, "Lesson clip", her?.let(clipMs::get)),
                            Clip(c.takeId?.let(clipFile::get), tryLabel(sec.tryNo(take?.id), take?.id in sec.starred), null),
                            likedTry?.takeIf { liked.id != take?.id },
                            player,
                            sketchesBy[c.id].orEmpty(), lessonDir, onOpenSketch,
                            suggestionsBy[c.id].orEmpty(), onDecide,
                            SetlistStore.isOn(c, overrides),
                            voiceNote = voiceNote,
                            ownWords = if (c.onPhone) { text -> scope.launch { repo.setOwnWords(c.id, text) } } else null,
                            nudge = Nudge(
                                open = c.id !in nudged, asked = c.id in asked,
                                ask = onAsk?.let { ask -> { scope.launch { runCatching { ask(c) }.onSuccess { doneNudge(c.id, true) } } } },
                                gotIt = { doneNudge(c.id, false) },
                            ),
                        ) { on -> repo.setlistStore.set(c.id, on) }
                    }
                    items(sec.cues, key = { it.card.id }) { card(it, Modifier.animateItem()) }
                    items(sec.guesses, key = { "guess-" + it.feedback.id }) { g ->
                        val take = g.feedback.takeId
                        val her = clipFile[CloudIds.feedback(g.feedback.id)]
                        GuessCard(
                            g, Modifier.animateItem(),
                            Clip(her, "Lesson clip", her?.let(clipMs::get)),
                            Clip(take?.let { clipFile[CloudIds.take(it)] }, tryLabel(sec.tryNo(take), take in sec.starred), null),
                            player,
                        )
                    }
                    if (sec.more.isNotEmpty()) {
                        val open = key in openMore
                        item(key = "more-$key") {
                            TextButton(onClick = { openMore = if (open) openMore - key else openMore + key }) {
                                Text(if (open) "Fewer notes" else "${sec.more.size} more ${if (sec.more.size == 1) "note" else "notes"}")
                            }
                        }
                        if (open) items(sec.more, key = { it.card.id }) { card(it, Modifier.animateItem()) }
                    }
                }
                val talk = cards.filter { it.category == "chat" }
                if (talk.isNotEmpty()) item {
                    TextButton(onClick = { showTalk = !showTalk }) {
                        Text(if (showTalk) "Hide chat between songs" else "Chat between songs (${talk.size})")
                    }
                }
                if (showTalk) items(talk, key = { it.id }) { TalkCard(it, clipFile[it.id], player) }
            }

            postureSection(File(lesson.dirPath))
            item {
                HorizontalDivider()
                TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "Hide details" else "Details") }
            }
            if (showDetails) {
                item {
                    Row {
                        OutlinedButton(modifier = Modifier.width(IntrinsicSize.Max), onClick = { repo.process(lesson.id, again = true) }) { Text("Process again from scratch") }
                    }
                    if (hasTimeline) TextButton(onClick = onOpenTimeline) { Text("Open the debug timeline") }
                }
                if (feedback.isNotEmpty()) item {
                    Text("How her feedback was linked to takes", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                }
                items(feedback, key = { "fb-" + it.id }) { f ->
                    Text("${clock(f.spanStartMs)} · ${f.linkReason}", style = MaterialTheme.typography.bodySmall)
                }
                items(stages, key = { "s-" + it.stage }) { s ->
                    Text("${s.stage}: ${s.status} in ${(s.finishedAt - s.startedAt) / 1000} s. ${s.detail}", style = MaterialTheme.typography.bodySmall)
                }
                if (cards.isNotEmpty() && !localOnly) item(key = "compare") {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    CompareSection(lesson.id, repo, cards)
                }
            }
        }
    }

    // Anything of it online goes too; the dialogs say so only when something went up.
    val online = !localOnly && stages.any { it.stage == "upload" && it.status == "done" }
    when (val ask = deleteAsk) {
        is DeleteAsk.Exercise -> DeleteExerciseDialog(
            ask, later = outline.filter { it.startMs > ask.section.startMs }, online = online,
            onCancel = { deleteAsk = null },
            onDelete = {
                deleteAsk = null
                val e = ask.section.exercise ?: return@DeleteExerciseDialog
                scope.launch {
                    val r = repo.removeExercise(lesson.id, e, ask.section.name, ask.toEnd)
                    offerUndo(if (ask.toEnd) "${ask.section.name} and everything after" else ask.section.name, listOf(r))
                }
            },
        )
        is DeleteAsk.Tries -> DeleteTriesDialog(
            ask.section, clipFile, player, online = online,
            onCancel = { deleteAsk = null },
            onDelete = { picked ->
                deleteAsk = null
                player.stop()
                scope.launch {
                    val done = picked.map { (n, t) -> repo.removeTake(lesson.id, t, "Try $n of ${ask.section.name}") }
                    offerUndo(if (picked.size == 1) "try ${picked.single().first} of ${ask.section.name}" else "${picked.size} tries from ${ask.section.name}", done)
                }
            },
        )
        null -> {}
    }
}

@Composable
private fun Status(
    lesson: LessonEntity,
    takes: List<TakeEntity>,
    feedback: List<FeedbackEntity>,
    cards: List<CardEntity>,
    exerciseCount: Int,
    p: com.cuenote.data.pipeline.PipelineScheduler.Progress?,
    c: LessonUploadWorker.Companion.Progress?,
    needsSignIn: Boolean,
    /** Why the last try at a stage failed, from the pipeline log. */
    lastFailure: String? = null,
    localOnly: Boolean = false,
) {
    val processing = p?.state == WorkInfo.State.RUNNING
    val carding = c?.state == WorkInfo.State.RUNNING
    val text = when {
        processing -> (LessonPipelineWorker.STAGE_NAMES[p?.stage] ?: "Processing") + "… ${p?.percent ?: 0}%"
        carding -> (LessonUploadWorker.STAGE_NAMES[c?.stage] ?: "Making cards") + "… ${c?.percent ?: 0}%"
        p?.state == WorkInfo.State.ENQUEUED -> "Your notes will be ready after the phone's next charge, or tap Make my notes now."
        // A try failed and the phone will try again: say why, not "waiting for the internet".
        c?.state == WorkInfo.State.ENQUEUED && c.attempts > 0 ->
            "Couldn't write your notes yet (${lastFailure?.take(140) ?: "no reason given"}). The phone will try again in a few minutes, or tap Make my notes."
        c?.state == WorkInfo.State.ENQUEUED -> "Waiting for the internet to finish your notes."
        c?.state == WorkInfo.State.BLOCKED -> "Your notes will start in a moment."
        c?.state == WorkInfo.State.FAILED && c.error != "not signed in" ->
            "We couldn't make your notes this time (${c.error?.take(120) ?: "no reason given"}). Tap Make my notes to try again."
        localOnly && (lesson.pipelineState == "processed" || lesson.pipelineState == "cards") ->
            "Found ${plural(exerciseCount, "exercise")} and ${plural(takes.size, "try", "tries")}. The notes below are the phone's own."
        cards.isNotEmpty() -> {
            val n = cards.count { it.category != "chat" && it.cue.isNotBlank() }
            val edited = cards.count { it.editedByTeacher }
            "${plural(n, "note")} across ${plural(exerciseCount, "exercise")}." + if (edited > 0) " Your teacher has edited $edited." else ""
        }
        needsSignIn -> "Almost there. Sign in on the home screen to finish your notes."
        lesson.pipelineState == "processed" -> "Found ${plural(exerciseCount, "exercise")} and ${plural(takes.size, "try", "tries")}. Tap Make my notes to turn what your teacher said into notes."
        lesson.pipelineState == "failed" -> "We couldn't make your notes this time. The details below say why, and you can try again."
        else -> "Your notes haven't been made yet."
    }
    Text(text, style = MaterialTheme.typography.bodyLarge)
    if (processing) LinearProgressIndicator(progress = { (p?.percent ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
    if (carding) LinearProgressIndicator(progress = { (c?.percent ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
}

/**
 * Says the lesson stays on the phone, as the teacher's choice rather than a
 * gap: what still works, what's off, and that nothing went up.
 */
@Composable
private fun KeptOnPhoneBanner(teacher: String?, wasOnline: Boolean, deletingOnline: Boolean) {
    val m = MaterialTheme.colorScheme
    val who = teacher ?: "Your teacher"
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).background(m.secondaryContainer).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(androidx.compose.material.icons.Icons.Filled.Lock, contentDescription = null, tint = m.onSecondaryContainer)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Kept on this phone", style = MaterialTheme.typography.titleSmall, color = m.onSecondaryContainer)
            Text(
                "$who asked that this lesson stays on your phone, so Cue Note doesn't send any of it online or to an AI.",
                style = MaterialTheme.typography.bodyMedium, color = m.onSecondaryContainer,
            )
            Text(
                "You still have the recording, your tries, the replays and the phone's own notes, which you can practice from. " +
                    "Off for this lesson: AI notes, the teacher dashboard, sketch captions, and sharing with your teachers.",
                style = MaterialTheme.typography.bodySmall, color = m.onSecondaryContainer,
            )
            Text(
                when {
                    deletingOnline -> "This lesson went online before it was kept on this phone. That copy is being deleted, next time there's a connection."
                    wasOnline -> "Its earlier copy online has been deleted. Nothing else has been uploaded."
                    else -> "Nothing from this lesson has been uploaded."
                } + " If this phone is lost, the lesson goes with it.",
                style = MaterialTheme.typography.bodySmall, color = m.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

/** A clip to play: the file (null when it isn't on the phone), its label, and how long it is. */
private data class Clip(val file: String?, val label: String, val ms: Long?)

/**
 * The label stays put while it plays, so the row doesn't jump and you can see
 * what's playing: the button fills, ▶ turns to ■, and a thin bar runs under it.
 */
@Composable
private fun PlayButton(clip: Clip?, player: ClipPlayer) {
    val file = clip?.file ?: return
    val on = player.playing == file
    val text = (if (on) "■ " else "▶ ") + clip.label + (clip.ms?.let { " " + clock(it) } ?: "")
    Column(Modifier.width(IntrinsicSize.Max)) {
        if (on) FilledTonalButton(onClick = { player.toggle(file) }) { Text(text) }
        else OutlinedButton(
            onClick = { player.toggle(file) },
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) { Text(text) }
        if (on) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(2.dp))
    }
}

@Composable
private fun PlayButton(file: String?, player: ClipPlayer, label: String) = PlayButton(Clip(file, label, null), player)

/** An exercise's name, what kind it is, and a button for each try (★ the one she said was right). */
@Composable
private fun ExerciseHeader(
    sec: LessonOutline.Section, clipFile: Map<String, String>, player: ClipPlayer, first: Boolean,
    menu: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(top = if (first) 8.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!first) HorizontalDivider(Modifier.padding(bottom = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(sec.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            menu()
        }
        val kind = when (sec.kind) { "song" -> "Song"; "warmup" -> "Warmup"; else -> null }
        val bits = listOfNotNull(
            kind?.takeIf { sec.exercise?.title != null },
            plural(sec.takes.size, "try", "tries").takeIf { sec.takes.isNotEmpty() },
            clock(sec.startMs),
        )
        Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val playable = sec.takes.withIndex().mapNotNull { (i, t) -> clipFile[CloudIds.take(t.id)]?.let { Triple(i + 1, it, t.id in sec.starred) } }
        if (playable.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            Text("Hear a try", style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.CenterVertically).padding(end = 2.dp))
            for ((n, file, star) in playable) {
                val on = player.playing == file
                val m = MaterialTheme.colorScheme
                AssistChip(
                    onClick = { player.toggle(file) },
                    // a fixed width, so "■ 10 ★" doesn't push the row around
                    modifier = Modifier.widthIn(min = if (star) 68.dp else 52.dp),
                    label = { Text((if (on) "■ " else "▶ ") + n + if (star) " ★" else "", maxLines = 1) },
                    colors = when {
                        on -> AssistChipDefaults.assistChipColors(containerColor = m.secondaryContainer, labelColor = m.onSecondaryContainer)
                        star -> AssistChipDefaults.assistChipColors(containerColor = m.tertiaryContainer, labelColor = m.onTertiaryContainer)
                        else -> AssistChipDefaults.assistChipColors()
                    },
                    border = BorderStroke(1.dp, if (star) m.tertiary else m.outline),
                )
            }
        }
        if (sec.starred.isNotEmpty()) Text(
            "★ Your teacher said this try was right.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

/**
 * One point: a short cue, her meaning in a sentence or two, and one row of
 * actions (her clip, the try it was about, the try she liked, everything she
 * said, the setlist). Her exact words are a tap away, never on the card.
 */
@Composable
private fun CueCard(
    group: LessonOutline.CueGroup,
    modifier: Modifier,
    her: Clip,
    take: Clip,
    liked: Clip?,
    player: ClipPlayer,
    sketches: List<PhotoEntity>,
    lessonDir: File,
    onOpenSketch: (String) -> Unit,
    suggestions: List<DiagramSuggestion>,
    onDecide: suspend (DiagramSuggestion, String) -> Unit,
    onSetlist: Boolean,
    voiceNote: (suspend (String) -> File?)?,
    nudge: Nudge,
    /** For one of the phone's own notes: saves the student's own words for it. Null for cards from the cloud. */
    ownWords: ((String) -> Unit)? = null,
    onSetlistChange: (Boolean) -> Unit,
) {
    val card = group.card
    var editing by rememberSaveable(card.id + "-edit") { mutableStateOf(false) }
    var showOld by rememberSaveable(card.id + "-old") { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var showAll by rememberSaveable(card.id) { mutableStateOf(false) }
    val transcript = group.all.joinToString("\n\n") { it.transcript.trim() }.trim()
    val check = group.check
    val m = MaterialTheme.colorScheme
    // The same colours as the dashboard's slider: brass for close, red for corrected.
    val edge = when (check) {
        // Slate, not red or brass: the app made the mistake, not the singer, and brass means praise.
        LessonOutline.SummaryCheck.CLOSE, LessonOutline.SummaryCheck.FLAGGED, LessonOutline.SummaryCheck.CORRECTED -> m.outline
        else -> null
    }
    Card(
        modifier.fillMaxWidth().animateContentSize().then(
            if (edge == null) Modifier else Modifier.drawBehind { drawRect(edge, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    card.cue.ifBlank { if (card.onPhone) "What your teacher said" else "A note from your teacher" },
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                )
                when (check) {
                    LessonOutline.SummaryCheck.CLOSE -> Tag("App: close", m.surfaceVariant, m.onSurfaceVariant, bold = true)
                    LessonOutline.SummaryCheck.FLAGGED, LessonOutline.SummaryCheck.CORRECTED -> Tag("App misheard", m.surfaceVariant, m.onSurfaceVariant, bold = true)
                    else -> {}
                }
                // On the card's first line, so the play buttons below fit on one row.
                IconToggleButton(checked = onSetlist, onCheckedChange = onSetlistChange, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (onSetlist) Icons.Filled.BookmarkAdded else Icons.Outlined.BookmarkAdd,
                        contentDescription = if (onSetlist) "On your setlist. Tap to take it off." else "Add to your setlist",
                        tint = if (onSetlist) m.primary else m.onSurfaceVariant,
                    )
                }
            }
            if (card.onPhone) PhoneNoteLine(card, onEdit = ownWords?.let { { editing = true } })
            if (editing && ownWords != null) OwnWordsDialog(card, onDismiss = { editing = false }) { ownWords(it); editing = false }
            // Two sources, never mixed: the teacher's words first, then the app's, smaller and labeled.
            val old = card.summaryRejected?.takeIf { it.isNotBlank() }
            if (check == S.FLAGGED || check == S.CLOSE) {
                // The chip already says what the app got wrong; this only says where to look.
                Text("Go with your teacher.", style = MaterialTheme.typography.labelMedium, color = muted)
            }
            // 1. Your teacher: everything in their own words, in one plum box, a thin rule between parts.
            val showQuote = (check == S.FLAGGED || (check == S.CLOSE && !group.explained)) && card.herWords.isNotBlank()
            val ownSummary = check == S.CORRECTED
            if (group.explained || showQuote || ownSummary) {
                TeacherBox { onBox ->
                    if (group.explained) TeacherNote(card.summaryNote, card.summaryNoteClip, player, voiceNote)
                    if (group.explained && (showQuote || ownSummary)) TeacherRule()
                    if (ownSummary) {
                        TeacherLabel("Your teacher's summary")
                        Text(card.summary, style = MaterialTheme.typography.bodyLarge, color = onBox)
                    } else if (showQuote) {
                        TeacherLabel("What your teacher said in the lesson")
                        TeacherQuote(card.herWords, onBox, her, player)
                    }
                }
            }
            // 2. The app.
            if (card.summary.isNotBlank() && check != S.CORRECTED) {
                if (check == S.CLOSE && (group.explained || showQuote)) {
                    // The teacher's own words say it better; the near miss folds away until asked for.
                    Disclosure("App summary (close)", showOld, { showOld = it }) {
                        Text(card.summary, style = MaterialTheme.typography.bodyMedium, color = muted)
                    }
                } else {
                    Source(
                        when (check) {
                            S.CONFIRMED -> "App summary · your teacher checked it ✓"
                            S.CLOSE -> "App summary · your teacher says it's close"
                            else -> "App summary · not checked yet"
                        },
                        teacher = false,
                    )
                    Text(
                        card.summary,
                        style = if (check == S.CLOSE) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                        maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                    if (check != S.CONFIRMED) {
                        Text("When in doubt, listen to the lesson clip.", style = MaterialTheme.typography.labelSmall, color = muted)
                    }
                }
            } else if (card.summary.isBlank() && check != S.FLAGGED && card.herWords.isNotBlank()) {
                TeacherBox { onBox ->
                    TeacherLabel("What your teacher said in the lesson")
                    TeacherQuote(card.herWords, onBox, her, player)
                }
            }
            if (old != null) {
                // Wrong, so folded away by default: one quiet line, open only if they want it.
                Disclosure("App summary (misheard)", showOld, { showOld = it }) {
                    Text(
                        "“$old” If you've been practicing from this, switch to what your teacher said.",
                        style = MaterialTheme.typography.bodySmall, color = muted,
                    )
                }
            }
            if (check == LessonOutline.SummaryCheck.FLAGGED && !group.explained) {
                if (nudge.asked) {
                    Text("Added to your questions for next lesson.", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                AnimatedVisibility(nudge.open && !nudge.asked, exit = shrinkVertically() + fadeOut()) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(m.surfaceContainerHigh).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Listen to the lesson clip. Still not sure?", style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = nudge.gotIt) { Text("Got it") }
                            nudge.ask?.let { FilledTonalButton(onClick = it) { Text("Add to my questions", maxLines = 1) } }
                        }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                card.category?.let { TopicChip(Topic.of(it), Topic.of(it)?.label ?: it.replaceFirstChar { c -> c.uppercase() }) }
                if (group.practice) PracticeTag() else if (card.practiceFlag == "just_listen") Tag("Just listen")
                bars(card.barStart, card.barEnd)?.let { Tag(it) }
                if (group.repeats.isNotEmpty()) Tag("Your teacher said this ${group.all.size} times")
                if (group.all.any { it.editedByTeacher }) Tag("✓ Checked by your teacher")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PlayButton(her, player)
                PlayButton(take, player)
                PlayButton(liked, player)
                if (transcript.isNotBlank() && transcript != card.summary.trim() && transcript != card.herWords.trim()) {
                    TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Less" else "Everything your teacher said") }
                }
            }
            AnimatedVisibility(showAll, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Text(transcript, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SketchRow(sketches, lessonDir, onOpenSketch)
            for (sg in suggestions) DiagramSuggestionCard(sg) { d -> onDecide(sg, d) }
        }
    }
}

/**
 * What she meant, when she marked the summary close or wrong: her words, as
 * long as she wrote them (folded after a few lines), and her voice note,
 * fetched the first time it's played.
 */
@Composable
private fun TeacherNote(note: String?, clipPath: String?, player: ClipPlayer, voiceNote: (suspend (String) -> File?)?) {
    val m = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var open by rememberSaveable(note) { mutableStateOf(false) }
    var local by remember(clipPath) { mutableStateOf<String?>(null) }
    var fetching by remember(clipPath) { mutableStateOf(false) }
    var failed by remember(clipPath) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TeacherLabel("What your teacher meant")
        if (!note.isNullOrBlank()) {
            Text(
                note, style = MaterialTheme.typography.bodyMedium, color = m.onSecondaryContainer,
                maxLines = if (open) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
            )
            if (note.length > 260 || note.count { it == '\n' } >= 4) {
                TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) { Text(if (open) "Less" else "Read all of it") }
            }
        }
        if (clipPath != null && voiceNote != null) {
            val on = local != null && player.playing == local
            OutlinedButton(onClick = {
                local?.let { player.toggle(it); return@OutlinedButton }
                if (fetching) return@OutlinedButton
                fetching = true; failed = false
                scope.launch {
                    val f = voiceNote(clipPath)
                    fetching = false
                    if (f == null) failed = true else { local = f.absolutePath; player.toggle(f.absolutePath) }
                }
            }) {
                // The label stays put while it downloads; a small spinner stands in for the ▶.
                if (fetching) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) else Text(if (on) "■" else "▶")
                Text(" Play your teacher's note")
            }
            if (failed) Text("Couldn't get the voice note. Check the internet and try again.", style = MaterialTheme.typography.bodySmall, color = m.error)
        }
    }
}

/** The "Still unclear?" line on a flagged summary: open until she asks or says she got it. */
private class Nudge(val open: Boolean, val asked: Boolean, val ask: (() -> Unit)?, val gotIt: () -> Unit)

/**
 * Before the notes are made: a stretch where she talked at length about a
 * try, as her own words cut to a sentence or two, marked as the app's guess.
 */
@Composable
private fun GuessCard(guess: LessonOutline.Guess, modifier: Modifier, her: Clip, take: Clip, player: ClipPlayer) {
    var showAll by rememberSaveable(guess.feedback.id) { mutableStateOf(false) }
    Card(
        modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Best guess from what your teacher said, until your notes are made",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "“${guess.excerpt}”",
                style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                maxLines = 5, overflow = TextOverflow.Ellipsis,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PlayButton(her, player)
                PlayButton(take, player)
                if (guess.feedback.words.trim().length > guess.excerpt.length + 10) {
                    TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Less" else "Everything your teacher said") }
                }
            }
            AnimatedVisibility(showAll, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Text(guess.feedback.words.trim(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Under one of the phone's own notes: where it came from, the student's own
 * words when they wrote some, and the button to write or change them.
 */
@Composable
private fun PhoneNoteLine(card: CardEntity, onEdit: (() -> Unit)?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = muted, modifier = Modifier.size(14.dp))
        Text(
            when {
                card.editedOnPhone -> "Your words · kept on this phone"
                card.source == CardEntity.SOURCE_PHONE_WRITER -> "Written on this phone · not checked by your teacher"
                else -> "The phone's notes · kept on this phone"
            },
            style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.weight(1f),
        )
        onEdit?.let {
            TextButton(onClick = it, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (card.editedOnPhone) "Edit your words" else "Add your words", maxLines = 1)
            }
        }
    }
}

/** A short line in the student's own words, to remember the point by. It stays on the phone. */
@Composable
private fun OwnWordsDialog(card: CardEntity, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable(card.id + "-words") { mutableStateOf(if (card.editedOnPhone) card.cue else "") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("In your own words") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("“${card.herWords}”", style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic), maxLines = 4, overflow = TextOverflow.Ellipsis)
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it.take(com.cuenote.data.PhoneNotes.MAX_OWN_WORDS) },
                    placeholder = { Text("Drop the jaw on the high notes") },
                    supportingText = { Text("A few words to remember it by. Kept on this phone.") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (card.editedOnPhone) TextButton(onClick = { onSave("") }) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun tryLabel(n: Int?, starred: Boolean) = (if (n != null) "Hear try $n" else "Hear the try") + if (starred) " ★" else ""

@Composable
private fun TalkCard(card: CardEntity, clip: String?, player: ClipPlayer) {
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(clock(card.spanStartMs), style = MaterialTheme.typography.labelMedium)
            Text(card.transcript.ifBlank { card.herWords }.ifBlank { "(nothing transcribed)" }, style = MaterialTheme.typography.bodyMedium)
        }
        PlayButton(clip, player, "Hear")
    }
}

/** A label, filled and borderless, so it doesn't look like something to tap. */
/** The plum box that holds anything in the teacher's own words, so it reads apart from the app's. */
@Composable
private fun TeacherBox(content: @Composable (androidx.compose.ui.graphics.Color) -> Unit) {
    val m = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().background(m.secondaryContainer, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content(m.onSecondaryContainer) }
}

@Composable
private fun TeacherLabel(text: String) = Text(
    text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
    color = MaterialTheme.colorScheme.onSecondaryContainer,
)

/** The thin plum rule between the note and the lesson quote, with equal room on both sides. */
@Composable
private fun TeacherRule() = HorizontalDivider(
    Modifier.padding(vertical = 10.dp), thickness = 1.dp,
    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f),
)

/**
 * The teacher's words from the recording, in italics, with a large plum opening quote mark
 * hung in the box's padding. Tapping the words plays them from the lesson; the mark turns to ■.
 */
@Composable
private fun TeacherQuote(words: String, color: androidx.compose.ui.graphics.Color, clip: Clip, player: ClipPlayer) {
    val file = clip.file
    val on = file != null && player.playing == file
    Box(
        if (file == null) Modifier else Modifier.clickable(
            role = Role.Button, onClickLabel = if (on) "Stop" else "Hear it in the lesson",
        ) { player.toggle(file) },
    ) {
        Text(
            if (on) "■" else "“", color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = com.cuenote.ui.Fraunces, fontSize = if (on) 14.sp else 28.sp, lineHeight = 28.sp, fontStyle = FontStyle.Normal,
            ),
            modifier = Modifier.offset(x = (-12).dp, y = (-6).dp).clearAndSetSemantics {},
        )
        Text(
            words, style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic), color = color,
            maxLines = 4, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Who a block of words comes from. The teacher's label is bold and coloured; the app's is muted. */
@Composable
private fun Source(label: String, teacher: Boolean) {
    val m = MaterialTheme.colorScheme
    Text(
        label,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (teacher) FontWeight.SemiBold else FontWeight.Normal),
        color = if (teacher) m.secondary else m.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** A one-line chevron fold: a muted label that opens to show [content] beneath it. Closed by default. */
@Composable
private fun Disclosure(label: String, open: Boolean, onOpen: (Boolean) -> Unit, content: @Composable () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 90f else 0f, label = "chevron")
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClickLabel = if (open) "Hide" else "Show") { onOpen(!open) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("›", style = MaterialTheme.typography.titleMedium, color = muted,
                modifier = Modifier.width(12.dp).graphicsLayer { rotationZ = turn })
            Text(label, style = MaterialTheme.typography.labelMedium, color = muted)
        }
        AnimatedVisibility(open) { Box(Modifier.padding(start = 18.dp, bottom = 4.dp)) { content() } }
    }
}

@Composable
private fun Tag(text: String, container: androidx.compose.ui.graphics.Color? = null, content: androidx.compose.ui.graphics.Color? = null, bold: Boolean = false) {
    val m = MaterialTheme.colorScheme
    Text(
        text,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(container ?: m.surfaceContainerHighest).padding(horizontal = 10.dp, vertical = 4.dp),
        color = content ?: m.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
    )
}

/** "Practice this" in brass, the colour that means "what to work on" on the phone and the dashboard. */
@Composable
private fun PracticeTag() {
    val m = MaterialTheme.colorScheme
    Tag("Practice this", m.tertiaryContainer, m.onTertiaryContainer, bold = true)
}

private fun bars(a: Int?, b: Int?): String? = when {
    a == null && b == null -> null
    a == b || b == null -> "Bar $a"
    a == null -> "Bar $b"
    else -> "Bars $a–$b"
}

private fun clock(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

/** The photos on this card (sketches and photos of me), as labeled thumbnails; tap one to open it. */
@Composable
private fun SketchRow(sketches: List<PhotoEntity>, lessonDir: File, onOpenSketch: (String) -> Unit) {
    if (sketches.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        for (p in sketches) SketchThumb(p, lessonDir, Modifier.size(96.dp, 72.dp).clickable { onOpenSketch(p.id) })
    }
}

/**
 * What to do after reading the lesson: practice the points on the setlist, or
 * send tries and questions to the teacher. Always in reach at the bottom.
 */
@Composable
private fun NextStepBar(onSetlist: Int, practice: (() -> Unit)?, send: (() -> Unit)?) {
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                // Opens the next-lesson page (takes to share, questions to ask). Nothing is sent from here,
                // so the label names the page, not an action it doesn't take.
                if (send != null) OutlinedButton(onClick = send, modifier = Modifier.weight(1f)) { Text("Before next lesson", maxLines = 1) }
                Button(onClick = { practice?.invoke() }, enabled = practice != null, modifier = Modifier.weight(1f)) {
                    Text("Start practicing")
                }
            }
            Text(
                if (onSetlist == 0) "Add a point to your setlist to practice it." else "${plural(onSetlist, "point")} from this lesson on your setlist.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        }
    }
}
