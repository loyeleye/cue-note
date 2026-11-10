package com.cuenote.lesson

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cuenote.data.diagrams.DiagramSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** One diagram of the library, as listed in assets/diagrams/index.json. */
data class Diagram(val id: String, val area: String, val title: String, val shows: String)

/** The diagram library bundled with the app (drawn by tools/diagrams). */
object DiagramLibrary {
    @Volatile private var all: Map<String, Diagram>? = null

    fun get(context: Context, id: String): Diagram? {
        val known = all ?: context.assets.open("diagrams/index.json").bufferedReader().use { r ->
            val a = JSONArray(r.readText())
            (0 until a.length()).map { i ->
                a.getJSONObject(i).run { Diagram(getString("id"), getString("area"), getString("title"), getString("shows")) }
            }.associateBy { it.id }
        }.also { all = it }
        return known[id]
    }

    fun image(context: Context, id: String): ImageBitmap? = runCatching {
        context.assets.open("diagrams/$id.png").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
    }.getOrNull()
}

/**
 * "Does this diagram help?" under a card: a standard drawing the AI matched
 * to her cue, marked as a suggestion. Yes keeps it on the card; Dismiss hides
 * it for the student only (the teacher still sees it).
 */
@Composable
fun DiagramSuggestionCard(
    suggestion: DiagramSuggestion,
    onDecide: suspend (decision: String) -> Unit,
) {
    val context = LocalContext.current
    val diagram = remember(suggestion.diagramId) { DiagramLibrary.get(context, suggestion.diagramId) } ?: return
    val image by produceState<ImageBitmap?>(null, suggestion.diagramId) {
        value = withContext(Dispatchers.IO) { DiagramLibrary.image(context, suggestion.diagramId) }
    }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun decide(decision: String) {
        busy = true
        error = null
        scope.launch {
            runCatching { onDecide(decision) }.onFailure { error = "Couldn't save that: ${it.message}" }
            busy = false
        }
    }

    OutlinedCard(Modifier.fillMaxWidth().padding(top = 6.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("Suggested diagram") })
                Text(
                    if (suggestion.source == "sketch") "Matched to her sketch" else "Matched to her words",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            image?.let {
                Image(
                    it, contentDescription = "${diagram.title}: ${diagram.shows}",
                    Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(8.dp)).background(Color.White),
                )
            }
            Text(diagram.title, style = MaterialTheme.typography.titleSmall)
            Text("${diagram.shows}. A standard drawing, not her sketch.", style = MaterialTheme.typography.bodySmall)
            if (suggestion.accepted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Kept on this card.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (suggestion.studentStatus == "accepted") TextButton(onClick = { decide("dismissed") }, enabled = !busy) { Text("Remove") }
                }
            } else {
                Text("Does this diagram help?", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { decide("accepted") }, enabled = !busy) { Text("Yes, keep it") }
                    OutlinedButton(onClick = { decide("dismissed") }, enabled = !busy) { Text("Dismiss") }
                }
                Text("Dismissing hides it only for you.", style = MaterialTheme.typography.labelSmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
