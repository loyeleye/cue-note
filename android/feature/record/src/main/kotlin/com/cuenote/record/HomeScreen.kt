package com.cuenote.record

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cuenote.data.CardCount
import com.cuenote.data.LessonEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Brass from the art pass: the one warm accent, for the moment that matters. */
private val Brass = Color(0xFFE0A94A)
private val LogoPlum = Color(0xFF5B3A8C)

@Composable
fun HomeScreen(
    lessons: List<LessonEntity>,
    recordingInProgress: Boolean,
    signedInEmail: String?,
    onStartLesson: () -> Unit,
    onReturnToRecording: () -> Unit,
    onSetupCheck: () -> Unit,
    onSignIn: () -> Unit,
    onTeachers: () -> Unit = {},
    onExport: (LessonEntity) -> Unit,
    onDelete: (LessonEntity) -> Unit,
    onOpen: (LessonEntity) -> Unit = {},
    /** null hides the row; otherwise what to say about the speech models. */
    modelStatus: String? = null,
    onDownloadModels: (() -> Unit)? = null,
    /** Cards and practice items per lesson id, for the badges. */
    cardCounts: Map<String, CardCount> = emptyMap(),
) {
    Scaffold { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 20.dp).fillMaxSize()) {
            Header(signedInEmail, onSignIn)
            Spacer(Modifier.height(16.dp))

            if (recordingInProgress) {
                BigButton("Recording in progress: return to it", onReturnToRecording)
            } else {
                BigButton("Start a lesson", onStartLesson)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onSetupCheck, modifier = Modifier.weight(1f).height(52.dp)) { Text("Setup check") }
                    OutlinedButton(onClick = onTeachers, modifier = Modifier.weight(1f).height(52.dp)) { Text("Invite a teacher") }
                }
            }
            if (modelStatus != null) {
                Spacer(Modifier.height(12.dp))
                if (onDownloadModels != null) {
                    TextButton(onClick = onDownloadModels, contentPadding = PaddingValues(0.dp)) {
                        Text("Download speech models (about 150 MB)")
                    }
                }
                Text(modelStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(24.dp))
            Text("Your lessons", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            if (lessons.isEmpty()) {
                Text(
                    "None yet. Run the setup check, then start a lesson.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(lessons, key = { it.id }) { LessonCard(it, cardCounts[it.id], onExport, onDelete, onOpen) }
            }
        }
    }
}

@Composable
private fun Header(signedInEmail: String?, onSignIn: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(LogoPlum, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.cue_mark), contentDescription = null, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text("Cue Note", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        // The name before the @ keeps the header short; the full address is on the sign-in screen.
        TextButton(onClick = onSignIn) {
            Text(signedInEmail?.substringBefore('@') ?: "Sign in", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The one big plum button, with the brass dot that marks recording. */
@Composable
private fun BigButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(20.dp)) {
        Box(Modifier.size(12.dp).background(Brass, CircleShape))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.titleMedium.copy(fontFamily = MaterialTheme.typography.bodyLarge.fontFamily))
    }
}

@Composable
private fun LessonCard(
    l: LessonEntity,
    counts: CardCount?,
    onExport: (LessonEntity) -> Unit,
    onDelete: (LessonEntity) -> Unit,
    onOpen: (LessonEntity) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = { onOpen(l) },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 14.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date(l.recordedAt)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(minutes(l.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    TextButton(onClick = { menu = true }) { Text("⋯") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Export to Downloads") }, onClick = { menu = false; onExport(l) })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete(l) })
                    }
                }
            }
            val status = statusText(l)
            if (counts != null && counts.cards > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp, end = 12.dp)) {
                    Badge("${counts.cards} cards", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                    if (counts.practice > 0) {
                        Badge("${counts.practice} to practice", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            } else if (status != null) {
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
            }
            if (l.gapCount > 0) {
                Text(
                    "${l.gapCount} gaps: some audio was dropped",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String, background: Color, content: Color) {
    Surface(color = background, contentColor = content, shape = RoundedCornerShape(50)) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

private fun minutes(ms: Long): String {
    val m = (ms + 30_000) / 60_000
    return if (m < 1) formatDuration(ms) else "$m min"
}

/** A plain line for a lesson without cards yet. */
private fun statusText(l: LessonEntity): String? = when (l.pipelineState) {
    "recording" -> "Recording"
    "recorded" -> "Processing when the phone is charging"
    "processing" -> "Processing…"
    "processed" -> "Processed: tap to see takes and feedback"
    "failed" -> "Processing failed: tap for details"
    else -> null
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
