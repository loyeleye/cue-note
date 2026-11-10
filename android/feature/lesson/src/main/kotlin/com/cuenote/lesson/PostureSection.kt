package com.cuenote.lesson

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.cuenote.signal.posture.PostureEvent
import com.cuenote.signal.posture.PostureSummary
import java.io.File

/**
 * For a lesson that tracked posture: how long the camera watched, why it
 * paused, and each flagged moment with its snapshot. Lessons without
 * posture show nothing here.
 */
internal fun LazyListScope.postureSection(lessonDir: File) {
    val log = File(lessonDir, "posture/events.tsv")
    if (!log.isFile) return
    val summary = runCatching { PostureSummary.parse(log.readText()) }.getOrNull() ?: return
    item(key = "posture") {
        Column {
            Text("Posture", style = MaterialTheme.typography.titleMedium)
            val minutes = summary.trackedMs / 60_000.0
            Text(
                "Watched for %.1f minutes of singing. %d flagged moments.".format(minutes, summary.flags.size),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (summary.rebaselines.isNotEmpty()) {
                val why = summary.rebaselines.groupingBy {
                    when (it.why) { "manual" -> "recalibrated"; "moved" -> "phone moved"; "photo" -> "after a photo"; else -> it.why }
                }.eachCount()
                Text(
                    "New baseline: " + why.entries.joinToString { (w, n) -> if (n == 1) w else "$w ($n times)" },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val pauses = summary.pauses.groupingBy { it.reason }.eachCount()
            if (pauses.isNotEmpty()) {
                Text(
                    "Paused: " + pauses.entries.joinToString { (why, n) -> if (n == 1) why else "$why ($n times)" },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    items(summary.flags, key = { "pf-${it.atMs}-${it.flag.key}" }) { FlagCard(lessonDir, it) }
}

@Composable
private fun FlagCard(lessonDir: File, f: PostureEvent.Flagged) {
    val image = remember(f.snapshot) {
        runCatching {
            BitmapFactory.decodeFile(File(lessonDir, "posture/${f.snapshot}").path, BitmapFactory.Options().apply { inSampleSize = 2 })
        }.getOrNull()
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            image?.let { Image(it.asImageBitmap(), contentDescription = "Snapshot at ${clockOf(f.atMs)}", Modifier.size(96.dp)) }
            Spacer(Modifier.size(12.dp))
            Column {
                Text("${f.flag.label} at ${clockOf(f.atMs)}", style = MaterialTheme.typography.titleSmall)
                if (f.detail.isNotBlank()) Text(f.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

