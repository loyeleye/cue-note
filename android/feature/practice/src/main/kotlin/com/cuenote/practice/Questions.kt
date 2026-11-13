package com.cuenote.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cuenote.data.PracticeQuestionEntity
import com.cuenote.pipeline.practice.Questions
import com.cuenote.ui.TicketRow

/**
 * Asks the teacher something. [aboutNote] adds a bar box, for questions like
 * "bar 12 still cracks"; a general question has just the words.
 */
@Composable
fun AskDialog(teacher: String, aboutNote: Boolean, onSend: (body: String, bar: Int?) -> Unit, onDismiss: () -> Unit) {
    var body by remember { mutableStateOf("") }
    var bar by remember { mutableStateOf("") }
    val ok = Questions.clean(body) != null && (bar.isBlank() || Questions.bar(bar) != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (aboutNote) "Ask $teacher about this note" else "Ask $teacher") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it.take(Questions.MAX_LENGTH) },
                    label = { Text("Your question") },
                    placeholder = { Text(if (aboutNote) "It still cracks on the high note" else "How long should I warm up?") },
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (aboutNote) {
                    OutlinedTextField(
                        value = bar,
                        onValueChange = { v -> bar = v.filter(Char::isDigit).take(4) },
                        label = { Text("Bar (if you know it)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    "It goes on their list for your next lesson.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onSend(body, Questions.bar(bar)) }) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One question: the words, and the answer or where it stands. */
@Composable
fun QuestionRow(
    q: PracticeQuestionEntity,
    teacher: String,
    where: String?,
    onDrop: () -> Unit,
    /** To play a voice answer; without it only the words show. */
    voice: Pair<com.cuenote.data.PracticeRepository, com.cuenote.ui.ClipPlayer>? = null,
) {
    val m = MaterialTheme.colorScheme
    TicketRow(faded = q.answered && q.answer == null && q.answerClipPath == null) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(listOfNotNull(where, q.bar?.let { "bar $it" }).joinToString(", ").ifEmpty { "Question" }.replaceFirstChar { it.uppercase() },
                color = m.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Text(q.body, style = MaterialTheme.typography.bodyLarge)
            when {
                q.answerClipPath != null && voice != null -> TeacherReply(teacher, q.answer, q.answerClipPath, voice.first, voice.second)
                q.answer != null -> Text("$teacher: ${q.answer}", color = m.primary, style = MaterialTheme.typography.bodyMedium)
                q.answered -> Text("Talked about in your lesson", color = m.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                q.dirty -> Text("Sends when you're online", color = m.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                else -> Text("On $teacher's list for your next lesson", color = m.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (q.open) TextButton(onClick = onDrop) { Text("Take back") }
    }
}
