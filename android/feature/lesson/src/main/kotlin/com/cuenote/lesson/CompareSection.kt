package com.cuenote.lesson

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.cuenote.data.CardEntity
import com.cuenote.data.LessonRepository
import com.cuenote.pipeline.cards.ModelTrials
import kotlinx.coroutines.launch

/**
 * "Compare AI models": runs the lesson through Haiku, Sonnet, Opus and Fable
 * and shows each one's card for every feedback block, with what each run
 * cost, so the student and teacher can pick the model from real cards. The
 * lesson's own cards don't change.
 */
@Composable
fun CompareSection(lessonId: String, repo: LessonRepository, cards: List<CardEntity>) {
    val scope = rememberCoroutineScope()
    var result by remember(lessonId) { mutableStateOf(repo.lastComparison(lessonId)) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Compare AI models", style = MaterialTheme.typography.titleMedium)
        Text(
            "Writes this lesson's cards with each of ${ModelTrials.MODELS.values.joinToString(", ")} side by side. " +
                "Your cards stay as they are. It costs about the four runs together, well under a dollar.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            enabled = !running && cards.isNotEmpty(),
            onClick = {
                running = true; error = null
                scope.launch {
                    runCatching { repo.compareModels(lessonId) }
                        .onSuccess { result = it }
                        .onFailure { error = it.message ?: it.toString() }
                    running = false
                }
            },
        ) { Text(if (result == null) "Compare" else "Compare again") }
        if (running) {
            Text("Asking all four models. This can take a couple of minutes.", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        error?.let { Text("Couldn't compare: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }

    val r = result ?: return
    val trials = r.trials.sortedBy { ModelTrials.MODELS.keys.indexOf(it.model) }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Cost for this lesson", style = MaterialTheme.typography.titleSmall)
        for (t in trials) {
            val name = ModelTrials.MODELS[t.model] ?: t.model
            Text(
                when {
                    t.error != null -> "$name: failed (${t.error})"
                    t.costUsd != null -> "$name: $%.3f (%,d tokens in, %,d out)".format(t.costUsd, t.inputTokens, t.outputTokens)
                    else -> name
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    // One card per block, each model's version under the others.
    val blocks = cards.sortedBy { it.spanStartMs }
    for (block in blocks) {
        val answers = trials.mapNotNull { t -> t.cards.firstOrNull { it.id == block.id }?.let { t.model to it } }
        if (answers.isEmpty() || answers.all { it.second.category == "chat" }) continue
        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("At ${clock(block.spanStartMs)}", style = MaterialTheme.typography.labelMedium)
                for ((model, c) in answers) {
                    Text(ModelTrials.MODELS[model] ?: model, style = MaterialTheme.typography.labelLarge)
                    Text(
                        when {
                            c.missing -> "(skipped this block)"
                            c.category == "chat" -> "(called it small talk)"
                            else -> c.cue.ifBlank { "(no cue)" }
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (c.herWords.isNotBlank()) {
                        Text(
                            "“${c.herWords}”" + if (c.repaired) "  (its quote wasn't in the transcript, so the real sentence is shown)" else "",
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        )
                    }
                    val tags = listOfNotNull(
                        c.category?.takeIf { it != "chat" },
                        when (c.practiceFlag) { "practice_this" -> "practice this"; "just_listen" -> "just listen"; else -> null },
                        c.barStart?.let { if (c.barEnd != null && c.barEnd != it) "bars $it–${c.barEnd}" else "bar $it" },
                    )
                    if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun clock(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
