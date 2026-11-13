package com.cuenote.practice

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.cuenote.data.PracticeRepository
import com.cuenote.data.sketch.Bitmaps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * A photo of you from the lesson (say, the open mouth the teacher asked for),
 * shown beside the note while practicing it, so you can match the shape.
 * It stays on the phone; this only reads it.
 */

private val MouthAlignment = BiasAlignment(0f, 0.25f)

@Composable
private fun rememberMePhoto(m: PracticeRepository.MePhoto, maxSide: Int): Bitmap? =
    produceState<Bitmap?>(null, m.file.path, maxSide) {
        value = withContext(Dispatchers.IO) { runCatching { Bitmaps.loadUpright(m.file, maxSide) }.getOrNull() }
    }.value

/** The photo, square and cropped toward the mouth. */
@Composable
fun MeThumb(m: PracticeRepository.MePhoto, modifier: Modifier = Modifier) {
    val bmp = rememberMePhoto(m, 600)
    val shape = RoundedCornerShape(12.dp)
    val mod = modifier.clip(shape).border(2.dp, MaterialTheme.colorScheme.secondary, shape)
    if (bmp != null) Image(
        bmp.asImageBitmap(), contentDescription = "Your photo from the lesson" + (m.note?.let { ": $it" } ?: ""),
        contentScale = ContentScale.Crop, alignment = MouthAlignment, modifier = mod,
    )
}

/** On the note: the photo, what it is, your own line on what to notice, and tap to see it bigger. */
@Composable
fun MeReference(m: PracticeRepository.MePhoto) {
    var big by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { big = true },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        MeThumb(m, Modifier.size(120.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("How it looked in the lesson", style = MaterialTheme.typography.titleSmall)
            m.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(
                "Your photo from ${"%d:%02d".format(m.takenAtMs / 60_000, m.takenAtMs / 1000 % 60)}. Tap to see it bigger.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (big) {
        val full = rememberMePhoto(m, 1600)
        AlertDialog(
            onDismissRequest = { big = false },
            text = {
                if (full != null) Image(full.asImageBitmap(), contentDescription = "Your photo from the lesson", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = { TextButton(onClick = { big = false }) { Text("Close") } },
        )
    }
}
