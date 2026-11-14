package com.cuenote.lesson

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cuenote.data.LessonOutline
import com.cuenote.data.TakeEntity
import com.cuenote.pipeline.cards.CloudIds
import com.cuenote.ui.ClipPlayer

/** What the student asked to delete from a lesson, before they confirm it. */
sealed interface DeleteAsk {
    val section: LessonOutline.Section

    /** The exercise, or ([toEnd]) it and everything recorded after it. */
    data class Exercise(override val section: LessonOutline.Section, val toEnd: Boolean) : DeleteAsk

    /** Some of the exercise's tries. */
    data class Tries(override val section: LessonOutline.Section) : DeleteAsk
}

/** The ⋯ on an exercise: delete some of its tries, the exercise, or it and everything after. */
@Composable
internal fun ExerciseMenu(sec: LessonOutline.Section, last: Boolean, onAsk: (DeleteAsk) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More for ${sec.name}") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        if (sec.takes.isNotEmpty()) DropdownMenuItem(
            text = { Text(if (sec.takes.size == 1) "Delete the try" else "Delete tries…") },
            onClick = { open = false; onAsk(DeleteAsk.Tries(sec)) },
        )
        if (sec.exercise != null) {
            DropdownMenuItem(
                text = { Text("Delete this ${if (sec.kind == "song") "part" else "exercise"}") },
                onClick = { open = false; onAsk(DeleteAsk.Exercise(sec, toEnd = false)) },
            )
            if (!last) DropdownMenuItem(
                text = { Text("Delete this and everything after") },
                onClick = { open = false; onAsk(DeleteAsk.Exercise(sec, toEnd = true)) },
            )
        }
    }
}

/**
 * "Delete Warmup 4?": what goes, and where from. [later] are the sections a
 * delete to the end also takes. [online] says the copy online goes too.
 */
@Composable
internal fun DeleteExerciseDialog(
    ask: DeleteAsk.Exercise, later: List<LessonOutline.Section>, online: Boolean,
    onDelete: () -> Unit, onCancel: () -> Unit,
) {
    val secs = listOf(ask.section) + if (ask.toEnd) later else emptyList()
    val tries = secs.sumOf { it.takes.size }
    val notes = secs.sumOf { it.cues.size + it.more.size }
    val what = listOfNotNull(
        plural(tries, "try", "tries").takeIf { tries > 0 },
        plural(notes, "note").takeIf { notes > 0 },
    ).joinToString(" and ")
    val where = if (online) "this phone and from Cue Note online, so your teacher won't see it either" else "this phone"
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (ask.toEnd) "Delete ${ask.section.name} and everything after it?" else "Delete ${ask.section.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    (if (ask.toEnd) "Everything recorded from ${clock(ask.section.startMs)} to the end"
                        + (if (secs.size > 1) " (${plural(secs.size, "section")})" else "")
                    else "This part of the lesson") +
                        (if (what.isNotEmpty()) ", its $what" else "") +
                        " and its recording are deleted from $where.",
                )
                Text(
                    "Use this for chatter or anything that wasn't part of the lesson. You can undo it for a few seconds.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Keep it") } },
    )
}

/** Pick the tries to delete, with a play button on each so the student can check first. */
@Composable
internal fun DeleteTriesDialog(
    sec: LessonOutline.Section, clipFile: Map<String, String>, player: ClipPlayer, online: Boolean,
    onDelete: (List<Pair<Int, TakeEntity>>) -> Unit, onCancel: () -> Unit,
) {
    val only = sec.takes.size == 1
    var picked by remember { mutableStateOf(if (only) setOf(sec.takes.first().id) else emptySet()) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (only) "Delete the try in ${sec.name}?" else "Delete tries from ${sec.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "A deleted try's recording goes from this phone${if (online) " and from Cue Note online" else ""}. " +
                        "What your teacher said about it stays.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!only) LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed(sec.takes, key = { _, t -> t.id }) { i, t ->
                        val file = clipFile[CloudIds.take(t.id)]
                        val on = t.id in picked
                        Row(
                            Modifier.fillMaxWidth().clickable { picked = if (on) picked - t.id else picked + t.id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = { picked = if (it) picked + t.id else picked - t.id })
                            Text(
                                "Try ${i + 1}" + (if (t.id in sec.starred) " ★" else "") + " · ${clock(t.startMs)}",
                                Modifier.weight(1f),
                            )
                            if (file != null) TextButton(onClick = { player.toggle(file) }) {
                                Text(if (player.playing == file) "■ Stop" else "▶ Play")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = picked.isNotEmpty(),
                onClick = { onDelete(sec.takes.withIndex().filter { it.value.id in picked }.map { it.index + 1 to it.value }) },
            ) {
                Text(
                    if (only || picked.size <= 1) "Delete" else "Delete ${picked.size} tries",
                    color = if (picked.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Keep them") } },
    )
}

private fun clock(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
