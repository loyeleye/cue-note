package com.cuenote.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cuenote.data.InvitedTeacher
import com.cuenote.data.LessonTeacher

/**
 * Changes who taught a lesson after it was recorded: one of the student's
 * teachers on Cue Note, someone else by name, or nobody. [teachers] includes
 * past teachers too, since an old lesson may be from before one left.
 */
@Composable
fun LessonTeacherDialog(
    current: LessonTeacher,
    teachers: List<InvitedTeacher>,
    lastName: String?,
    onSave: (LessonTeacher) -> Unit,
    onDismiss: () -> Unit,
) {
    // Someone with an account who has accepted at some point; their lessons can be credited to them.
    val choices = teachers.filter { it.teacherId != null }.distinctBy { it.teacherId }
    val currentIsLinked = current.teacherId != null && choices.any { it.teacherId == current.teacherId }
    var picked by rememberSaveable { mutableStateOf(current.teacherId.takeIf { currentIsLinked }) }
    var someoneElse by rememberSaveable { mutableStateOf(current.name != null) }
    var otherName by rememberSaveable { mutableStateOf(current.name ?: lastName.orEmpty()) }
    val result = if (someoneElse) LessonTeacher.named(otherName) else LessonTeacher(teacherId = picked)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Who taught this lesson?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (choices.isEmpty()) "None of your teachers are on Cue Note yet. You can still put a name on it."
                    else "A teacher on Cue Note sees it among their lessons. A name for someone else is just a label.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TeacherPicker(
                    choices = choices,
                    picked = picked,
                    someoneElse = someoneElse,
                    otherName = otherName,
                    onPick = { picked = it; someoneElse = false },
                    onSomeoneElse = { someoneElse = true },
                    onOtherName = { otherName = it.take(LessonTeacher.MAX_NAME) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(result) }, enabled = !someoneElse || result.isSet) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
