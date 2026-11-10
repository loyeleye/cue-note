package com.cuenote.lesson

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
    onOpenTimeline: () -> Unit,
    onBack: () -> Unit,
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

    // Her latest edits, each time the lesson is opened.
    LaunchedEffect(lesson.id) {
        if (cards.isNotEmpty() || lesson.pipelineState == "cards") runCatching { repo.refreshCards(lesson.id) }
    }

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
                Spacer(Modifier.padding(4.dp))
                val busy = progress?.state == WorkInfo.State.RUNNING || cardProgress?.state == WorkInfo.State.RUNNING
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (lesson.pipelineState == "processed" || lesson.pipelineState == "cards") {
                        Button(onClick = { repo.makeCards(lesson.id) }, enabled = !busy) {
                            Text(if (cards.isEmpty()) "Make cards" else "Make cards again")
                        }
                    } else {
                        Button(onClick = { repo.process(lesson.id) }, enabled = !busy) { Text("Process now") }
                    }
                    if (cards.isNotEmpty()) OutlinedButton(onClick = {
                        scope.launch {
                            refreshNote = runCatching { repo.refreshCards(lesson.id) }
                                .fold({ "Up to date with her edits." }, { "Couldn't refresh: ${it.message}" })
                        }
                    }) { Text("Refresh") }
                }
                refreshNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                player.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }

            if (cards.isNotEmpty()) {
                val (talk, cues) = cards.partition { it.category == "chat" }
                val opening = cues.filter { it.takeId == null || it.takeId !in takeNo }
                if (opening.isNotEmpty()) {
                    item { SectionTitle("Before the first take") }
                    items(opening, key = { it.id }) { CueCard(it, clipFile[it.id], player) }
                }
                for ((i, t) in takes.withIndex()) {
                    val id = CloudIds.take(t.id)
                    val mine = cues.filter { it.takeId == id }
                    item(key = "take-${t.id}") { TakeRow(i + 1, t, clipFile[id], player) }
                    items(mine, key = { it.id }) { CueCard(it, clipFile[it.id], player) }
                }
                if (talk.isNotEmpty()) item {
                    TextButton(onClick = { showTalk = !showTalk }) {
                        Text(if (showTalk) "Hide the talk between exercises" else "Show the talk between exercises (${talk.size})")
                    }
                }
                if (showTalk) items(talk, key = { it.id }) { TalkCard(it, clipFile[it.id], player) }
            } else if (takes.isNotEmpty() || feedback.isNotEmpty()) {
                // Processed, no cards yet: what the pipeline found.
                val opening = feedback.filter { it.takeId == null }
                item { SectionTitle("Takes and feedback") }
                items(opening, key = { it.id }) { FoundCard(null, listOf(it)) }
                items(takes, key = { it.id }) { t -> FoundCard(t, feedback.filter { it.takeId == t.id }) }
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
        p?.state == WorkInfo.State.ENQUEUED -> "Waiting to process. It starts when the phone is charging, or tap Process now."
        c?.state == WorkInfo.State.ENQUEUED -> "Waiting for a network to make the cards."
        cards.isNotEmpty() -> {
            val n = cards.count { it.category != "chat" }
            val edited = cards.count { it.editedByTeacher }
            "$n cards from ${takes.size} takes." + if (edited > 0) " Your teacher has edited $edited." else ""
        }
        needsSignIn -> "Processed. Sign in on the home screen to make the cards."
        lesson.pipelineState == "processed" -> "Processed: ${takes.size} takes and ${feedback.size} feedback blocks. Cards come next."
        lesson.pipelineState == "failed" -> "Processing failed. Details below say where."
        else -> "Not processed yet."
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
private fun CueCard(card: CardEntity, clip: String?, player: ClipPlayer) {
    var showAll by rememberSaveable(card.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(card.cue.ifBlank { "(no cue yet)" }, style = MaterialTheme.typography.titleLarge)
            if (card.herWords.isNotBlank()) {
                Text("“${card.herWords}”", style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                card.category?.let { Tag(it) }
                when (card.practiceFlag) {
                    "practice_this" -> Tag("Practice this")
                    "just_listen" -> Tag("Just listen")
                }
                bars(card.barStart, card.barEnd)?.let { Tag(it) }
                if (card.editedByTeacher) Tag("Edited by your teacher")
            }
            Row {
                PlayButton(clip, player, "Hear her")
                Spacer(Modifier.width(8.dp))
                if (card.transcript.isNotBlank() && card.transcript.trim() != card.herWords.trim()) {
                    TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Less" else "Everything she said") }
                }
            }
            if (showAll) Text(card.transcript, style = MaterialTheme.typography.bodyMedium)
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
private fun FoundCard(take: TakeEntity?, feedback: List<FeedbackEntity>) {
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
