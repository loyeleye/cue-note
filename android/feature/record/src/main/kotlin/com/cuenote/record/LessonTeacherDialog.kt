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
import com.cuenote.data.LocalOnly

/**
 * Changes who taught a lesson after it was recorded: one of the student's
 * teachers on Cue Note, someone else by name, or nobody. [teachers] includes
 * past teachers too, since an old lesson may be from before one left.
 *
 * Moving a lesson to a teacher who keeps lessons on the phone asks first,
 * since anything of it already online is deleted. Moving it away says that
 * nothing goes up until the student taps Make my notes.
 */
@Composable
fun LessonTeacherDialog(
    current: LessonTeacher,
    teachers: List<InvitedTeacher>,
    lastName: String?,
    /** Teachers who keep their lessons on this phone. */
    savedLocal: List<String> = emptyList(),
    onSave: (LessonTeacher) -> Unit,
    onDismiss: () -> Unit,
) {
    // Someone with an account who has accepted at some point; their lessons can be credited to them.
    val choices = teachers.filter { it.teacherId != null }.distinctBy { it.teacherId }
    val currentIsLinked = current.teacherId != null && choices.any { it.teacherId == current.teacherId }
    var picked by rememberSaveable { mutableStateOf(current.teacherId.takeIf { currentIsLinked }) }
    var someoneElse by rememberSaveable { mutableStateOf(current.name != null) }
    var otherName by rememberSaveable { mutableStateOf(current.name ?: lastName.orEmpty()) }
    var localOnly by rememberSaveable { mutableStateOf(current.localOnly) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val result = if (someoneElse) LessonTeacher.named(otherName, localOnly) else LessonTeacher(teacherId = picked)
    val change = LocalOnly.change(current, result)
    if (confirming) {
        val who = result.name ?: "your teacher"
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(if (change == LocalOnly.Change.DELETE_ONLINE) "Keep this lesson on this phone?" else "Let this lesson go online?") },
            text = {
                Text(
                    if (change == LocalOnly.Change.DELETE_ONLINE)
                        "Anything of this lesson already online (clips, notes and sketch photos) is deleted from Cue Note and from " +
                            "your teachers' dashboards. Its notes on this phone go back to the phone's own best guess. This can't be undone."
                    else
                        "${current.name ?: "This teacher"} asked that this lesson stays on this phone. Once you tap Make my notes, " +
                            "clips of their voice, their words and sketch photos go to Cue Note, and an AI reads the words to write notes. " +
                            "Nothing goes until you tap it. Check with them first.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; onSave(result) }) {
                    Text(if (change == LocalOnly.Change.DELETE_ONLINE) "Keep it on this phone" else "Change teacher")
                }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
        return
    }
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
                    onPick = { picked = it; someoneElse = false; localOnly = false },
                    onSomeoneElse = { someoneElse = true; localOnly = false },
                    onOtherName = { otherName = it.take(LessonTeacher.MAX_NAME) },
                    localOnly = localOnly,
                    onLocalOnly = { localOnly = it },
                    savedLocal = savedLocal,
                    onPickLocal = { someoneElse = true; otherName = it; localOnly = true },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (change == LocalOnly.Change.DELETE_ONLINE || change == LocalOnly.Change.NOW_SHAREABLE) confirming = true
                else onSave(result)
            }, enabled = !someoneElse || result.isSet) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
