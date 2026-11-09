package com.cuenote.record

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cuenote.data.LessonEntity
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    lessons: List<LessonEntity>,
    recordingInProgress: Boolean,
    signedInEmail: String?,
    onStartLesson: () -> Unit,
    onReturnToRecording: () -> Unit,
    onSetupCheck: () -> Unit,
    onSignIn: () -> Unit,
    onExport: (LessonEntity) -> Unit,
    onDelete: (LessonEntity) -> Unit,
    onOpen: (LessonEntity) -> Unit = {},
    /** null hides the row; otherwise what to say about the speech models. */
    modelStatus: String? = null,
    onDownloadModels: (() -> Unit)? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cue Note") },
                actions = {
                    TextButton(onClick = onSignIn) { Text(signedInEmail ?: "Sign in") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            if (recordingInProgress) {
                Button(onClick = onReturnToRecording, modifier = Modifier.fillMaxWidth()) {
                    Text("Recording in progress: return to it")
                }
            } else {
                Button(onClick = onStartLesson, modifier = Modifier.fillMaxWidth()) { Text("Start a lesson") }
                Spacer(Modifier.padding(4.dp))
                OutlinedButton(onClick = onSetupCheck, modifier = Modifier.fillMaxWidth()) { Text("Run the setup check") }
            }
            if (modelStatus != null) {
                Spacer(Modifier.padding(4.dp))
                if (onDownloadModels != null) {
                    OutlinedButton(onClick = onDownloadModels, modifier = Modifier.fillMaxWidth()) {
                        Text("Download speech models (about 150 MB)")
                    }
                }
                Text(modelStatus, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.padding(8.dp))
            Text("Recorded lessons", style = MaterialTheme.typography.titleMedium)
            if (lessons.isEmpty()) {
                Text("None yet. Run the setup check, then start a lesson.", style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(lessons, key = { it.id }) { LessonRow(it, onExport, onDelete, onOpen) }
            }
        }
    }
}

@Composable
private fun LessonRow(
    l: LessonEntity,
    onExport: (LessonEntity) -> Unit,
    onDelete: (LessonEntity) -> Unit,
    onOpen: (LessonEntity) -> Unit,
) {
    Card(onClick = { onOpen(l) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(DateFormat.getDateTimeInstance().format(Date(l.recordedAt)), style = MaterialTheme.typography.titleSmall)
            Text(
                "${formatDuration(l.durationMs)} · ${l.chunkCount} chunks · " +
                    "${l.markCount} marks · ${l.photoCount} photos",
            )
            val gapText = if (l.gapCount == 0) "No gaps" else "${l.gapCount} gaps (audio was dropped)"
            Text("$gapText · ${l.micName.ifBlank { "unknown mic" }}", style = MaterialTheme.typography.bodySmall)
            Text(pipelineText(l.pipelineState), style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { onExport(l) }) { Text("Export to Downloads") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onDelete(l) }) { Text("Delete") }
            }
        }
    }
}

private fun pipelineText(state: String) = when (state) {
    "recording" -> "Recording"
    "recorded" -> "Waiting to process (starts when charging; tap to open)"
    "processing" -> "Processing…"
    "processed" -> "Processed: tap to see takes and feedback"
    "failed" -> "Processing failed: tap for details"
    else -> state
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
