package com.cuenote.practice

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.cuenote.signal.posture.PostureSummary
import com.cuenote.ui.TicketRow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Posture during practice, the same tracking as in lessons: the camera is on
 * only while a take records, MediaPipe's body points stay on the phone, no
 * video is saved, and a snapshot is kept at each flagged moment.
 */
interface TakePosture {
    /** True once the framing check has found the student at their singing spot, this sitting. */
    val ready: StateFlow<Boolean>

    /** A take began; posture for it goes in [dir]/posture/. */
    fun start(dir: File)

    /** The take ended (kept or not). */
    fun stop()
}

/** The switch for posture during practice, and a way back to the framing check. */
@Composable
internal fun PostureRow(on: Boolean, ready: Boolean, onToggle: (Boolean) -> Unit, onFraming: () -> Unit) {
    val m = MaterialTheme.colorScheme
    TicketRow {
        Column(Modifier.weight(1f)) {
            Text("Watch my posture", style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    !on -> "The camera watches only while you sing a take. No video is kept."
                    ready -> "On for your takes. Stay where the framing check found you."
                    else -> "First, a quick framing check from your singing spot."
                },
                color = m.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
            if (on && ready) TextButton(onClick = onFraming) { Text("Check framing again") }
        }
        Switch(checked = on, onCheckedChange = onToggle)
    }
}

/** Under a take: how posture went, with the snapshot of each flagged moment. */
@Composable
internal fun TakePostureLine(dir: File, modifier: Modifier = Modifier) {
    val summary = remember(dir) {
        File(dir, "posture/events.tsv").takeIf { it.isFile }?.let { runCatching { PostureSummary.parse(it.readText()) }.getOrNull() }
    } ?: return
    val m = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            when {
                summary.trackedMs < 1_000 -> "Posture: the camera didn't get a clear look this time."
                summary.flags.isEmpty() -> "Posture: steady all the way through."
                else -> "Posture: " + summary.flags.map { it.flag.label.lowercase() }.distinct().joinToString(", ")
                    .replaceFirstChar { it.uppercase() } + " for a moment."
            },
            color = if (summary.flags.isEmpty()) m.onSurfaceVariant else m.onSurface,
            style = MaterialTheme.typography.bodySmall,
        )
        summary.flags.take(3).forEach { f ->
            val image = remember(f.snapshot) {
                runCatching {
                    BitmapFactory.decodeFile(File(dir, "posture/${f.snapshot}").path, BitmapFactory.Options().apply { inSampleSize = 2 })
                }.getOrNull()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                image?.let {
                    Image(
                        it.asImageBitmap(), contentDescription = "${f.flag.label} at ${clock(f.atMs)}",
                        contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                    )
                }
                Text("${f.flag.label} at ${clock(f.atMs)}", style = MaterialTheme.typography.bodySmall, color = m.onSurfaceVariant)
            }
        }
    }
}
