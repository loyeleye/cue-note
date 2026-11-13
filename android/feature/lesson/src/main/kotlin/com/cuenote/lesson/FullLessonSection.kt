package com.cuenote.lesson

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cuenote.data.FullLessonPass
import com.cuenote.data.FullLessonRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A teacher who could be invited to hear a whole lesson. */
data class LessonViewer(val id: String, val name: String)

/**
 * Who hears this lesson. By default a teacher hears only the takes on
 * "Practice this" notes and what the student sends. The student can invite
 * a teacher to hear all of it for 14 days, and answers a teacher who asks.
 */
@Composable
fun FullLessonSection(lessonId: String, repo: FullLessonRepository, teachers: List<LessonViewer>) {
    if (teachers.isEmpty()) return
    val scope = rememberCoroutineScope()
    var passes by remember { mutableStateOf<List<FullLessonPass>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var inviting by remember { mutableStateOf<LessonViewer?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(lessonId, reload) {
        passes = runCatching { repo.forLesson(lessonId) }.onFailure { error = "Couldn't check who can hear this lesson. You may be offline." }.getOrNull()
    }
    fun run(f: suspend () -> Unit) = scope.launch {
        error = null; busy = true
        runCatching { f() }.onFailure { error = "That didn't go through. Check you're online and try again." }
        busy = false
        reload++
    }
    val m = MaterialTheme.colorScheme

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Who hears this lesson", style = MaterialTheme.typography.titleMedium)
            if (busy) Text("Sending…", style = MaterialTheme.typography.bodySmall, color = m.onSurfaceVariant)
            for (t in teachers) {
                val p = passes?.firstOrNull { it.teacherId == t.id && it.current() }
                when {
                    p?.state() == "active" -> {
                        Text("${t.name} can hear all of this lesson until ${day(p.endsAtMs!!)}.", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { run { repo.end(p.id) } }, enabled = !busy) { Text("End it now") }
                    }
                    p != null && p.askedBy == "teacher" -> {
                        Text("${t.name} asked to hear this whole lesson.", style = MaterialTheme.typography.bodyMedium)
                        p.note?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium, color = m.onSurfaceVariant) }
                        Text("If you say yes, they can hear every take for 14 days. You can end it sooner.",
                            style = MaterialTheme.typography.bodySmall, color = m.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { run { repo.answer(p.id, true) } }, enabled = !busy) { Text("Allow for 14 days") }
                            OutlinedButton(onClick = { run { repo.answer(p.id, false) } }, enabled = !busy) { Text("Not this time") }
                        }
                    }
                    p != null -> {
                        Text("Waiting for ${t.name} to accept your invite.", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { run { repo.end(p.id) } }, enabled = !busy) { Text("Take back") }
                    }
                    else -> {
                        Text(
                            "${t.name} hears the notes you're practicing and the takes you send. The rest of this lesson is yours.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { inviting = t }, enabled = passes != null && !busy) { Text("Let ${t.name} hear the whole lesson") }
                    }
                }
            }
            error?.let { Text(it, color = m.error, style = MaterialTheme.typography.bodySmall) }
        }
    }

    inviting?.let { t ->
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { inviting = null },
            title = { Text("Let ${t.name} hear the whole lesson?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Once they accept, they can hear every take for 14 days. Either of you can end it sooner.")
                    OutlinedTextField(
                        value = note, onValueChange = { note = it.take(500) },
                        label = { Text("Tell ${t.name} why (optional)") },
                        placeholder = { Text("e.g. Recital on the 30th") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = { Button(onClick = { inviting = null; run { repo.invite(lessonId, t.id, note) } }) { Text("Send invite") } },
            dismissButton = { TextButton(onClick = { inviting = null }) { Text("Cancel") } },
        )
    }
}

private fun day(ms: Long): String = SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date(ms))
