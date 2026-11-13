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
import com.cuenote.data.LessonRepository
import com.cuenote.data.TakeEntity
import com.cuenote.data.pipeline.LessonPipelineWorker
import com.cuenote.data.sync.LessonUploadWorker
import com.cuenote.pipeline.cards.CloudIds
import kotlinx.coroutines.launch
import java.io.File

/**
 * One lesson as a timeline of cards: each take with a button to hear it,
 * and under it her cue, her exact words and a button to hear her say them.
 * Cards come from extract-cues and carry any edits she made on the
 * dashboard. Before there are cards, it shows what processing found.
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
) {
    val dao = repo.pipelineDao!!
    val cardDao = repo.cardDao!!
    val stages by dao.observeStages(lesson.id).collectAsStateWithLifecycle(emptyList())
    val takes by dao.observeTakes(lesson.id).collectAsStateWithLifecycle(emptyList())
    val feedback by dao.observeFeedback(lesson.id).collectAsStateWithLifecycle(emptyList())
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
    val takeNo = takes.withIndex().associate { (i, t) -> CloudIds.take(t.id) to i + 1 }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Lesson") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Status(lesson, takes, feedback, cards, progress, cardProgress, stages.any { it.stage == "upload" && it.status == "waiting" })
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

            if (photos.isNotEmpty()) {
                item { SectionTitle("Your teacher's sketches") }
                item { SketchStrip(photos, lessonDir, feedback) { onOpenSketch(it.id) } }
            }

            if (cards.isNotEmpty()) {
                val (talk, cues) = cards.partition { it.category == "chat" }
                val opening = cues.filter { it.takeId == null || it.takeId !in takeNo }
                if (opening.isNotEmpty()) {
                    item { SectionTitle("Before the first take") }
                    items(opening, key = { it.id }) { CueCard(it, clipFile[it.id], player, onCard(it.localId), lessonDir, onOpenSketch, suggestions.filter { s -> s.feedbackId == it.id }, onDecide, SetlistStore.isOn(it, overrides)) { on -> repo.setlistStore.set(it.id, on) } }
                }
                for ((i, t) in takes.withIndex()) {
                    val id = CloudIds.take(t.id)
                    val mine = cues.filter { it.takeId == id }
                    item(key = "take-${t.id}") { TakeRow(i + 1, t, clipFile[id], player) }
                    items(mine, key = { it.id }) { CueCard(it, clipFile[it.id], player, onCard(it.localId), lessonDir, onOpenSketch, suggestions.filter { s -> s.feedbackId == it.id }, onDecide, SetlistStore.isOn(it, overrides)) { on -> repo.setlistStore.set(it.id, on) } }
                }
                if (talk.isNotEmpty()) item {
                    TextButton(onClick = { showTalk = !showTalk }) {
                        Text(if (showTalk) "Hide chat between songs" else "Chat between songs (${talk.size})")
                    }
                }
                if (showTalk) items(talk, key = { it.id }) { TalkCard(it, clipFile[it.id], player) }
            } else if (takes.isNotEmpty() || feedback.isNotEmpty()) {
                // Processed, no cards yet: what the pipeline found.
                val opening = feedback.filter { it.takeId == null }
                item { SectionTitle("Takes and feedback") }
                items(opening, key = { it.id }) { FoundCard(null, listOf(it), photos, lessonDir, onOpenSketch) }
                items(takes, key = { it.id }) { t -> FoundCard(t, feedback.filter { it.takeId == t.id }, photos, lessonDir, onOpenSketch) }
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
            val n = cards.count { it.category != "chat" }
            val edited = cards.count { it.editedByTeacher }
            "$n cards from ${takes.size} takes." + if (edited > 0) " Your teacher has edited $edited." else ""
        }
        needsSignIn -> "Almost there. Sign in on the home screen to finish your notes."
        lesson.pipelineState == "processed" -> "Processed: ${takes.size} takes and ${feedback.size} feedback blocks. Cards come next."
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

@Composable
private fun TakeRow(number: Int, take: TakeEntity, clip: String?, player: ClipPlayer) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Take $number", style = MaterialTheme.typography.titleMedium)
            Text(
                "at ${clock(take.startMs)}, ${(take.endMs - take.startMs) / 1000} s" + if (take.withPiano) ", with piano" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        PlayButton(clip, player, "Hear take")
    }
}

@Composable
private fun CueCard(
    card: CardEntity,
    clip: String?,
    player: ClipPlayer,
    sketches: List<PhotoEntity>,
    lessonDir: File,
    onOpenSketch: (String) -> Unit,
    suggestions: List<DiagramSuggestion>,
    onDecide: suspend (DiagramSuggestion, String) -> Unit,
    onSetlist: Boolean,
    onSetlistChange: (Boolean) -> Unit,
) {
    var showAll by rememberSaveable(card.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(card.cue.ifBlank { "(no cue yet)" }, style = MaterialTheme.typography.titleLarge)
            if (card.herWords.isNotBlank()) {
                Text("“${card.herWords}”", style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                card.category?.let { TopicChip(Topic.of(it), Topic.of(it)?.label ?: it.replaceFirstChar { c -> c.uppercase() }, Modifier.align(Alignment.CenterVertically)) }
                when (card.practiceFlag) {
                    "practice_this" -> Tag("Practice this")
                    "just_listen" -> Tag("Just listen")
                }
                bars(card.barStart, card.barEnd)?.let { Tag(it) }
                if (card.editedByTeacher) Tag("✓ Checked by your teacher")
            }
            Row {
                PlayButton(clip, player, "Their words")
                Spacer(Modifier.width(8.dp))
                if (card.transcript.isNotBlank() && card.transcript.trim() != card.herWords.trim()) {
                    TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Less" else "Everything your teacher said") }
                }
            }
            if (showAll) Text(card.transcript, style = MaterialTheme.typography.bodyMedium)
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

/** Before cards: a take with the feedback the pipeline linked to it. */
@Composable
private fun FoundCard(
    take: TakeEntity?,
    feedback: List<FeedbackEntity>,
    photos: List<PhotoEntity>,
    lessonDir: File,
    onOpenSketch: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                take?.let { "Take at ${clock(it.startMs)}, ${(it.endMs - it.startMs) / 1000} s" + if (it.withPiano) ", with piano" else "" }
                    ?: "Not about a take",
                style = MaterialTheme.typography.titleSmall,
            )
            if (feedback.isEmpty()) Text("No feedback after this take.", style = MaterialTheme.typography.bodySmall)
            for (f in feedback) {
                Text(f.words.ifBlank { "(no words transcribed)" }, style = MaterialTheme.typography.bodyMedium)
                Text("${clock(f.spanStartMs)} · ${f.linkReason}", style = MaterialTheme.typography.bodySmall)
                SketchRow(photos.filter { it.feedbackId == f.id }, lessonDir, onOpenSketch)
            }
        }
    }
}

private fun bars(a: Int?, b: Int?): String? = when {
    a == null && b == null -> null
    a == b || b == null -> "Bar $a"
    a == null -> "Bar $b"
    else -> "Bars $a–$b"
}

private fun clock(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

/** Her sketches on this card, as thumbnails; tap one to open it. */
@Composable
private fun SketchRow(sketches: List<PhotoEntity>, lessonDir: File, onOpenSketch: (String) -> Unit) {
    if (sketches.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        for (p in sketches) SketchThumb(p, lessonDir, Modifier.size(96.dp, 72.dp).clickable { onOpenSketch(p.id) })
    }
}
