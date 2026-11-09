package com.cuenote.lesson

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.cuenote.data.FeedbackEntity
import com.cuenote.data.LessonEntity
import com.cuenote.data.LessonRepository
import com.cuenote.data.TakeEntity
import com.cuenote.data.pipeline.LessonPipelineWorker
import java.io.File

/**
 * One lesson: where processing stands, and what it found (each take with the
 * feedback linked to it). The cards with her cues arrive in M3; for M2 this
 * is the window onto the pipeline, plus the debug timeline.
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
    val stages by dao.observeStages(lesson.id).collectAsStateWithLifecycle(emptyList())
    val takes by dao.observeTakes(lesson.id).collectAsStateWithLifecycle(emptyList())
    val feedback by dao.observeFeedback(lesson.id).collectAsStateWithLifecycle(emptyList())
    val progress by repo.progress(lesson.id).collectAsStateWithLifecycle(null)
    val hasTimeline = File(lesson.dirPath, "pipeline/timeline.html").isFile

    Scaffold(topBar = {
        TopAppBar(title = { Text("Lesson") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                val p = progress
                val running = p?.state == WorkInfo.State.RUNNING
                val waiting = p?.state == WorkInfo.State.ENQUEUED
                Text(
                    when {
                        running -> (LessonPipelineWorker.STAGE_NAMES[p?.stage] ?: "Processing") + "… ${p?.percent ?: 0}%"
                        waiting -> "Waiting to process. It starts when the phone is charging, or tap Process now."
                        lesson.pipelineState == "processed" -> "Processed: ${takes.size} takes, ${feedback.size} feedback, " +
                            "${feedback.count { it.takeId != null }} linked to a take."
                        lesson.pipelineState == "failed" -> "Processing failed. The stages below say where."
                        else -> "Not processed yet."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (running) LinearProgressIndicator(progress = { (p?.percent ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.padding(4.dp))
                Row {
                    Button(onClick = { repo.process(lesson.id) }, enabled = !running) { Text("Process now") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { repo.process(lesson.id, again = true) }, enabled = !running) { Text("Start over") }
                }
                if (hasTimeline) TextButton(onClick = onOpenTimeline) { Text("Open the debug timeline") }
            }
            if (stages.isNotEmpty()) item { Text("Stages", style = MaterialTheme.typography.titleMedium) }
            items(stages, key = { "s-" + it.stage }) { s ->
                Text("${s.stage}: ${s.status} in ${(s.finishedAt - s.startedAt) / 1000} s. ${s.detail}", style = MaterialTheme.typography.bodySmall)
            }
            val opening = feedback.filter { it.takeId == null }
            if (takes.isNotEmpty() || opening.isNotEmpty()) item { Text("Takes and feedback", style = MaterialTheme.typography.titleMedium) }
            items(opening, key = { it.id }) { FeedbackCard(null, listOf(it)) }
            items(takes, key = { it.id }) { t -> FeedbackCard(t, feedback.filter { it.takeId == t.id }) }
        }
    }
}

@Composable
private fun FeedbackCard(take: TakeEntity?, feedback: List<FeedbackEntity>) {
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

private fun clock(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
