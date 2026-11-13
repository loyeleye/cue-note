package com.cuenote.lesson

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
    val clipFile: Map<String, String> = clips.associate { it.id to it.file }
    val outline = remember(exercises, takes, feedback, cards) { LessonOutline.build(exercises, takes, feedback, cards) }
    val takeByCloud = remember(takes) { takes.associateBy { CloudIds.take(it.id) } }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Lesson") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Status(lesson, takes, feedback, cards, outline.size, progress, cardProgress, stages.any { it.stage == "upload" && it.status == "waiting" })
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
                for (sec in outline) {
                    val key = sec.exercise?.id ?: "loose"
                    item(key = "ex-$key") { ExerciseHeader(sec, clipFile, player) }
                    val card: @Composable (LessonOutline.CueGroup) -> Unit = { g ->
                        val c = g.card
                        val take = c.takeId?.let(takeByCloud::get)
                        CueCard(
                            g, clipFile[c.id], c.takeId?.let(clipFile::get), sec.tryNo(take?.id), take?.id in sec.starred, player,
                            g.all.flatMap { onCard(it.localId) }, lessonDir, onOpenSketch,
                            suggestions.filter { s -> g.all.any { it.id == s.feedbackId } }, onDecide,
                            SetlistStore.isOn(c, overrides),
                        ) { on -> repo.setlistStore.set(c.id, on) }
                    }
                    items(sec.cues, key = { it.card.id }) { card(it) }
                    items(sec.guesses, key = { "guess-" + it.feedback.id }) { g ->
                        val take = g.feedback.takeId
                        GuessCard(g, clipFile[CloudIds.feedback(g.feedback.id)], take?.let { clipFile[CloudIds.take(it)] }, sec.tryNo(take), take in sec.starred, player)
                    }
                    if (sec.more.isNotEmpty()) {
                        val open = key in openMore
                        item(key = "more-$key") {
                            TextButton(onClick = { openMore = if (open) openMore - key else openMore + key }) {
                                Text(if (open) "Fewer notes" else "${sec.more.size} more ${if (sec.more.size == 1) "note" else "notes"}")
                            }
                        }
                        if (open) items(sec.more, key = { it.card.id }) { card(it) }
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
                        OutlinedButton(onClick = { repo.process(lesson.id, again = true) }) { Text("Process again from scratch") }
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
) {
    val processing = p?.state == WorkInfo.State.RUNNING
    val carding = c?.state == WorkInfo.State.RUNNING
    val text = when {
        processing -> (LessonPipelineWorker.STAGE_NAMES[p?.stage] ?: "Processing") + "… ${p?.percent ?: 0}%"
        carding -> (LessonUploadWorker.STAGE_NAMES[c?.stage] ?: "Making cards") + "… ${c?.percent ?: 0}%"
        p?.state == WorkInfo.State.ENQUEUED -> "Your notes will be ready after the phone's next charge, or tap Make my notes now."
        c?.state == WorkInfo.State.ENQUEUED -> "Waiting for the internet to finish your notes."
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

@Composable
private fun PlayButton(file: String?, player: ClipPlayer, label: String) {
    if (file == null || !File(file).isFile) return
    val on = player.playing == file
    OutlinedButton(onClick = { player.toggle(file) }) { Text(if (on) "Stop" else label) }
}

/** An exercise's name, what kind it is, and a button for each try. */
@Composable
private fun ExerciseHeader(sec: LessonOutline.Section, clipFile: Map<String, String>, player: ClipPlayer) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(sec.name, style = MaterialTheme.typography.titleLarge)
        val kind = when (sec.kind) { "song" -> "Song"; "warmup" -> "Warmup"; else -> null }
        val bits = listOfNotNull(
            kind?.takeIf { sec.exercise?.title != null },
            plural(sec.takes.size, "try", "tries").takeIf { sec.takes.isNotEmpty() },
            clock(sec.startMs),
        )
        Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val playable = sec.takes.withIndex().mapNotNull { (i, t) ->
            clipFile[CloudIds.take(t.id)]?.takeIf { File(it).isFile }?.let { Triple(i + 1, it, t.id in sec.starred) }
        }
        if (playable.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Hear a try", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterVertically).padding(end = 2.dp))
            for ((n, file, star) in playable) {
                val on = player.playing == file
                // ★ is the try she said was right: the one to copy.
                AssistChip(
                    onClick = { player.toggle(file) },
                    label = { Text((if (on) "■ " else "▶ ") + n + if (star) " ★" else "") },
                    colors = if (star) androidx.compose.material3.AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer, labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ) else androidx.compose.material3.AssistChipDefaults.assistChipColors(),
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
 * One cue: the instruction, one sentence of hers, and the try it was about.
 * Everything else she said is a tap away, never on the card.
 */
@Composable
private fun CueCard(
    group: LessonOutline.CueGroup,
    clip: String?,
    takeClip: String?,
    tryNo: Int?,
    starred: Boolean,
    player: ClipPlayer,
    sketches: List<PhotoEntity>,
    lessonDir: File,
    onOpenSketch: (String) -> Unit,
    suggestions: List<DiagramSuggestion>,
    onDecide: suspend (DiagramSuggestion, String) -> Unit,
    onSetlist: Boolean,
    onSetlistChange: (Boolean) -> Unit,
) {
    val card = group.card
    var showAll by rememberSaveable(card.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(card.cue.ifBlank { "A note from your teacher" }, style = MaterialTheme.typography.titleLarge)
            // Her point in a sentence or two; her exact words are a tap away.
            if (group.text.isNotBlank()) {
                Text(
                    if (card.summary.isBlank()) "“${group.text}”" else group.text,
                    style = if (card.summary.isBlank()) MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic) else MaterialTheme.typography.bodyLarge,
                    maxLines = 4, overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                card.category?.let { TopicChip(Topic.of(it), Topic.of(it)?.label ?: it.replaceFirstChar { c -> c.uppercase() }, Modifier.align(Alignment.CenterVertically)) }
                if (group.practice) PracticeTag() else if (card.practiceFlag == "just_listen") Tag("Just listen")
                bars(card.barStart, card.barEnd)?.let { Tag(it) }
                if (group.repeats.isNotEmpty()) Tag("She said this ${group.all.size} times")
                if (group.all.any { it.editedByTeacher }) Tag("✓ Checked by your teacher")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayButton(clip, player, "Her words")
                PlayButton(takeClip, player, tryLabel(tryNo, starred))
                val transcript = group.all.joinToString("\n\n") { it.transcript.trim() }.trim()
                if (transcript.isNotBlank() && transcript != group.text.trim()) {
                    TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Less" else "Everything she said") }
                }
            }
            if (showAll) Text(
                group.all.joinToString("\n\n") { it.transcript.trim() }.trim(),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The student's own switch: on for "practice this" cards to start with.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .toggleable(onSetlist, role = Role.Switch, onValueChange = onSetlistChange)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("On my setlist", Modifier.weight(1f), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge)
                Switch(checked = onSetlist, onCheckedChange = null)
            }
            SketchRow(sketches, lessonDir, onOpenSketch)
            for (sg in suggestions) DiagramSuggestionCard(sg) { d -> onDecide(sg, d) }
        }
    }
}

/**
 * Before the notes are made: a stretch where she talked at length about a
 * try, as her own words cut to a sentence or two, marked as the app's guess.
 */
@Composable
private fun GuessCard(guess: LessonOutline.Guess, clip: String?, takeClip: String?, tryNo: Int?, starred: Boolean, player: ClipPlayer) {
    var showAll by rememberSaveable(guess.feedback.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                listOfNotNull(tryNo?.let { "After try $it" }, "best guess, not yet summarised").joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "“${guess.excerpt}”",
                style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                maxLines = 4, overflow = TextOverflow.Ellipsis,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayButton(clip, player, "Her words")
                PlayButton(takeClip, player, tryLabel(tryNo, starred))
                if (guess.feedback.words.trim().length > guess.excerpt.length + 10) {
                    TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Less" else "Everything she said") }
                }
            }
            if (showAll) Text(guess.feedback.words.trim(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun Tag(text: String) {
    AssistChip(onClick = {}, label = { Text(text) })
}

/** "Practice this" in brass, the colour that means "what to work on" on the phone and the dashboard. */
@Composable
private fun PracticeTag() {
    val m = MaterialTheme.colorScheme
    AssistChip(
        onClick = {},
        label = { Text("Practice this", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) },
        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(containerColor = m.tertiaryContainer, labelColor = m.onTertiaryContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, m.tertiary),
    )
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
