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
) {
    val dao = repo.pipelineDao!!
    val cardDao = repo.cardDao!!
    val stages by dao.observeStages(lesson.id).collectAsStateWithLifecycle(emptyList())
    val takes by dao.observeTakes(lesson.id).collectAsStateWithLifecycle(emptyList())
    val feedback by dao.observeFeedback(lesson.id).collectAsStateWithLifecycle(emptyList())
    val exercises by dao.observeExercises(lesson.id).collectAsStateWithLifecycle(emptyList())
    val cards by cardDao.observeCards(lesson.id).collectAsStateWithLifecycle(emptyList())
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

    val photos by sketches.observe(lesson.id).collectAsStateWithLifecycle(emptyList())
    val lessonDir = File(lesson.dirPath)
    LaunchedEffect(lesson.id) { sketches.refresh(lesson.id, lessonDir) }
    val onCard: (String?) -> List<PhotoEntity> = { local -> if (local == null) emptyList() else photos.filter { it.feedbackId == local } }

    val suggestions by diagrams.observe(lesson.id).collectAsStateWithLifecycle(emptyList())
    val onDecide: suspend (DiagramSuggestion, String) -> Unit = { s, d -> diagrams.decide(lesson.id, s, d) }
    LaunchedEffect(lesson.id) { runCatching { diagrams.refresh(lesson.id) } }

    // Her latest edits, each time the lesson is opened.
    LaunchedEffect(lesson.id) {
        if (cards.isNotEmpty() || lesson.pipelineState == "cards") runCatching { repo.refreshCards(lesson.id) }
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
        TopAppBar(title = { Text("Lesson") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }, bottomBar = {
        if ((onPractice != null || onSendToTeacher != null) && (cards.isNotEmpty() || takes.isNotEmpty())) {
            NextStepBar(onList.size, onList.firstOrNull()?.id?.let { id -> onPractice?.let { { it(id) } } }, onSendToTeacher)
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
                )
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
                    if (lesson.pipelineState == "processed" || lesson.pipelineState == "cards") {
                        Button(onClick = { repo.makeCards(lesson.id) }, enabled = !busy) {
                            Text(if (cards.isEmpty()) "Make my notes" else "Make my notes again")
                        }
                    } else {
                        Button(onClick = { repo.process(lesson.id) }, enabled = !busy) { Text("Make my notes now") }
                    }
                    if (cards.isNotEmpty()) OutlinedButton(onClick = {
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

            if (fullLesson != null && viewers.isNotEmpty() && cards.isNotEmpty()) {
                item(key = "full-lesson") { FullLessonSection(lesson.id, fullLesson, viewers) }
            }

            if (photos.isNotEmpty()) {
                item { SectionTitle("Your teacher's sketches") }
                item { SketchStrip(photos, lessonDir, feedback) { onOpenSketch(it.id) } }
            }

            if (outline.isNotEmpty()) {
                if (cards.isEmpty()) item {
                    Text(
                        "Until your notes are made, each exercise shows the app's best guess at what she said about your tries.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val close = groups.count { it.check == LessonOutline.SummaryCheck.CLOSE }
                val corrected = groups.count { it.check == LessonOutline.SummaryCheck.FLAGGED || it.check == LessonOutline.SummaryCheck.CORRECTED }
                if (close + corrected > 0) item(key = "corrected") {
                    val parts = listOfNotNull("$close close".takeIf { close > 0 }, "$corrected wrong".takeIf { corrected > 0 })
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.tertiary))
                        Text(
                            "Your teacher reviewed the app's summary on ${plural(close + corrected, "point")}: ${parts.joinToString(", ")}. Each is marked below.",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                for (sec in outline) {
                    val key = sec.exercise?.id ?: "loose"
                    item(key = "ex-$key") { ExerciseHeader(sec, clipFile, player, first = sec === outline.first()) }
                    // The try she said was right, to compare against the one each point is about.
                    val liked = sec.takes.firstOrNull { it.id in sec.starred }
                    val likedTry = liked?.let { Clip(clipFile[CloudIds.take(it.id)], "Try ${sec.tryNo(it.id)} ★, she liked it", null) }
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
                if (cards.isNotEmpty()) item(key = "compare") {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    CompareSection(lesson.id, repo, cards)
                }
            }
        }
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
        cards.isNotEmpty() -> {
            val n = cards.count { it.category != "chat" && it.cue.isNotBlank() }
            val edited = cards.count { it.editedByTeacher }
            "${plural(n, "note")} across ${plural(exerciseCount, "exercise")}." + if (edited > 0) " Your teacher has edited $edited." else ""
        }
        needsSignIn -> "Almost there. Sign in on the home screen to finish your notes."
        lesson.pipelineState == "processed" -> "Found ${plural(exerciseCount, "exercise")} and ${plural(takes.size, "try", "tries")}. Tap Make my notes to turn what she said into notes."
        lesson.pipelineState == "failed" -> "We couldn't make your notes this time. The details below say why, and you can try again."
        else -> "Your notes haven't been made yet."
    }
    Text(text, style = MaterialTheme.typography.bodyLarge)
    if (processing) LinearProgressIndicator(progress = { (p?.percent ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
    if (carding) LinearProgressIndicator(progress = { (c?.percent ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
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
private fun ExerciseHeader(sec: LessonOutline.Section, clipFile: Map<String, String>, player: ClipPlayer, first: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = if (first) 8.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!first) HorizontalDivider(Modifier.padding(bottom = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text(sec.name, style = MaterialTheme.typography.headlineSmall)
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
    onSetlistChange: (Boolean) -> Unit,
) {
    val card = group.card
    var showOld by rememberSaveable(card.id + "-old") { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var showAll by rememberSaveable(card.id) { mutableStateOf(false) }
    val transcript = group.all.joinToString("\n\n") { it.transcript.trim() }.trim()
    val check = group.check
    val m = MaterialTheme.colorScheme
    // The same colours as the dashboard's slider: brass for close, red for corrected.
    val edge = when (check) {
        LessonOutline.SummaryCheck.CLOSE -> m.tertiary
        LessonOutline.SummaryCheck.FLAGGED, LessonOutline.SummaryCheck.CORRECTED -> m.error.copy(alpha = 0.7f)
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
                Text(card.cue.ifBlank { "A note from your teacher" }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                when (check) {
                    LessonOutline.SummaryCheck.CLOSE -> Tag("App: close", m.tertiaryContainer, m.onTertiaryContainer, bold = true)
                    LessonOutline.SummaryCheck.FLAGGED, LessonOutline.SummaryCheck.CORRECTED -> Tag("App: wrong", m.errorContainer, m.onErrorContainer, bold = true)
                    else -> {}
                }
            }
            // Two sources, never mixed: the teacher's words first, then the app's, smaller and labeled.
            val old = card.summaryRejected?.takeIf { it.isNotBlank() }
            when (check) {
                S.FLAGGED -> Text(
                    "The app got this one wrong. Go with what your teacher said.",
                    style = MaterialTheme.typography.labelMedium, color = muted,
                )
                S.CLOSE -> Text(
                    "The app's summary is close, but not quite right. Go with what your teacher said.",
                    style = MaterialTheme.typography.labelMedium, color = m.tertiary,
                )
                else -> {}
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
                        TeacherQuote(card.herWords, onBox)
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
                    TeacherQuote(card.herWords, onBox)
                }
            }
            if (old != null) {
                // Wrong, so folded away by default: one quiet line, open only if they want it.
                Disclosure("App summary (wrong)", showOld, { showOld = it }) {
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
                if (group.repeats.isNotEmpty()) Tag("She said this ${group.all.size} times")
                if (group.all.any { it.editedByTeacher }) Tag("✓ Checked by your teacher")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PlayButton(her, player)
                PlayButton(take, player)
                PlayButton(liked, player)
                FilterChip(
                    selected = onSetlist,
                    onClick = { onSetlistChange(!onSetlist) },
                    label = { Text(if (onSetlist) "✓ On my setlist" else "+ Setlist") },
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                if (transcript.isNotBlank() && transcript != card.summary.trim() && transcript != card.herWords.trim()) {
                    TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Less" else "Everything she said") }
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
                "Best guess from what she said, until your notes are made",
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
                    TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Less" else "Everything she said") }
                }
            }
            AnimatedVisibility(showAll, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Text(guess.feedback.words.trim(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
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
 * hung in the box's padding so the words line up with the label above.
 */
@Composable
private fun TeacherQuote(words: String, color: androidx.compose.ui.graphics.Color) {
    Box {
        Text(
            "“", color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = com.cuenote.ui.Fraunces, fontSize = 28.sp, lineHeight = 28.sp, fontStyle = FontStyle.Normal,
            ),
            modifier = Modifier.offset(x = (-12).dp, y = (-6).dp),
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

/** Her sketches on this card, as thumbnails; tap one to open it. */
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
                if (send != null) OutlinedButton(onClick = send, modifier = Modifier.weight(1f)) { Text("Send to teacher") }
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
