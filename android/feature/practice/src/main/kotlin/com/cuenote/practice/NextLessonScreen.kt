package com.cuenote.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cuenote.data.PracticeRepository
import com.cuenote.data.SetlistRow
import com.cuenote.pipeline.practice.OfferTake
import com.cuenote.pipeline.practice.PracticeLog
import com.cuenote.pipeline.practice.PracticeWeek
import com.cuenote.ui.Masthead
import com.cuenote.ui.PlayPill
import com.cuenote.ui.TicketRow
import com.cuenote.ui.rememberClipPlayer
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Before the next lesson: the week's practice, the takes shared for the
 * teacher to hear, and the questions for them. The teacher sees the same
 * list on their dashboard as the lesson's agenda.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextLessonScreen(
    repo: PracticeRepository,
    /** The notes on the phone, to name what each take and question is about. */
    setlist: List<SetlistRow>,
    /** The teacher's name when there's one teacher, else null for "your teacher". */
    teacher: String?,
    /** Signed in with a teacher who has accepted, so takes can be shared. */
    sharing: Boolean = false,
    onPractice: (cardId: String) -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { repo.refresh() }
    val week by remember { repo.week() }.collectAsStateWithLifecycle(PracticeWeek(0, 0, 0, 0))
    val waiting by remember { repo.waiting() }.collectAsStateWithLifecycle(emptyList())
    val questions by remember { repo.questions() }.collectAsStateWithLifecycle(emptyList())
    val replied by remember { repo.replied() }.collectAsStateWithLifecycle(emptyList())
    val next by repo.agenda.nextLessonAt.collectAsStateWithLifecycle()
    val player = rememberClipPlayer()
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    val who = teacher ?: "your teacher"
    val whoCap = who.replaceFirstChar { it.uppercase() }
    val cards = remember(setlist) { setlist.associateBy { it.card.id } }
    fun about(cardId: String?): String? = cardId?.let { cards[it] }?.card?.let { c -> c.cue.ifBlank { c.herWords }.take(60) }
    val m = MaterialTheme.colorScheme

    // The offer: the latest take on each note the teacher marked "practice
    // this", since the last lesson, ticked to start with. Nothing is sent
    // until the student taps Send; an unticked take isn't offered again.
    val homework = remember(setlist) { setlist.filter { it.card.practiceFlag == "practice_this" }.map { it.card.id }.toSet() }
    val since = remember(setlist) { setlist.maxOfOrNull { it.recordedAt } ?: 0L }
    val recent by remember(since) { repo.takesSince(since) }.collectAsStateWithLifecycle(emptyList())
    val skipped by repo.agenda.skipped.collectAsStateWithLifecycle()
    val offer = remember(recent, homework, waiting, skipped) {
        val byId = recent.associateBy { it.id }
        PracticeLog.toOffer(homework, recent.map { OfferTake(it.id, it.cardId, it.recordedAt, it.shared) }, waiting.size, since, skipped)
            .mapNotNull { byId[it.id] }
    }
    var unticked by remember { mutableStateOf(emptySet<String>()) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Next lesson") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "mast") { Masthead("Before your ", "lesson") }
            item(key = "when") {
                Text(
                    next?.let { "Your next lesson is ${lessonDate(it)}. $whoCap sees this list before then." }
                        ?: "$whoCap sees this list before your next lesson.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            item(key = "weekh") { Text("This week", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
            item(key = "week") {
                TicketRow {
                    Text(weekLine(week), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }

            if (sharing && offer.isNotEmpty()) {
                item(key = "offerh") { Text("Send $who your practice takes?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
                item(key = "offer0") {
                    Text(
                        "Your latest go at each note $who asked you to practise. Untick any you'd rather keep to yourself.",
                        color = m.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    )
                }
                items(offer, key = { "o-" + it.id }) { t ->
                    val on = t.id !in unticked
                    // The whole row ticks; Play sits at the far end so it isn't hit by mistake.
                    TicketRow(onClick = { unticked = if (on) unticked + t.id else unticked - t.id }) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Column(Modifier.weight(1f)) {
                            Text(about(t.cardId) ?: "A practice take", style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text("${dayAndTime(t.recordedAt)} · ${clock(t.durationMs)}", color = m.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        PlayPill("Play", player.playing == t.file, filled = false, onClick = { player.toggle(t.file) })
                    }
                }
                item(key = "offerb") {
                    val send = offer.filter { it.id !in unticked }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = send.isNotEmpty(), onClick = {
                            scope.launch {
                                send.forEach { repo.share(it, true) }
                                repo.agenda.skip(offer.filter { it.id in unticked }.map { it.id })
                                unticked = emptySet()
                            }
                        }) { Text(if (send.size == 1) "Send 1 take" else "Send ${send.size} takes") }
                        OutlinedButton(onClick = { repo.agenda.skip(offer.map { it.id }); unticked = emptySet() }) { Text("Not this time") }
                    }
                }
            }

            item(key = "takesh") { Text("Takes for $who to hear", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
            if (waiting.isEmpty()) {
                item(key = "takes0") {
                    Text(
                        "Share up to ${PracticeLog.MAX_WAITING} practice takes from a note's ⋮ menu. Pick the ones you're least sure about, not your best.",
                        color = m.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(waiting, key = { "t-" + it.id }) { t ->
                TicketRow(onClick = { onPractice(t.cardId) }) {
                    PlayPill("Play", player.playing == t.file, filled = false, onClick = { player.toggle(t.file) })
                    Column(Modifier.weight(1f)) {
                        Text(about(t.cardId) ?: "A practice take", style = MaterialTheme.typography.titleSmall, maxLines = 2)
                        Text(
                            "${dayAndTime(t.recordedAt)} · ${clock(t.durationMs)}" + if (t.cloudShared) "" else " · shares when you're online",
                            color = m.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (replied.isNotEmpty()) {
                item(key = "rh") { Text("$whoCap replied", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
                items(replied, key = { "r-" + it.id }) { t ->
                    TicketRow(onClick = { onPractice(t.cardId) }) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(about(t.cardId) ?: "A practice take", style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text("Your take from ${dayAndTime(t.recordedAt)}", color = m.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            TeacherReply(whoCap, t.reply, t.replyClipPath, repo, player)
                        }
                        PlayPill("Your take", player.playing == t.file, filled = false, onClick = { player.toggle(t.file) })
                    }
                }
            }

            item(key = "qh") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Questions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { asking = true }) { Text("Ask $who") }
                }
            }
            if (questions.isEmpty()) {
                item(key = "q0") {
                    Text(
                        "Something feel odd, or not sure you've got it? A question on a note goes there; a general one goes here.",
                        color = m.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(questions, key = { "q-" + it.id }) { q ->
                QuestionRow(q, whoCap, where = about(q.cardId)?.let { "On \"$it\"" }, onDrop = { scope.launch { repo.drop(q.id) } }, voice = repo to player)
            }
        }
    }

    if (asking) {
        AskDialog(
            teacher = who,
            aboutNote = false,
            onSend = { body, _ -> asking = false; scope.launch { repo.ask(null, null, body) } },
            onDismiss = { asking = false },
        )
    }
}

/** "3 sittings, 42 minutes, on 5 notes", or a gentle line for an empty week. */
internal fun weekLine(w: PracticeWeek): String = when {
    w.empty -> "No practice yet this week. Even ten minutes on one note helps."
    else -> {
        val sittings = if (w.sittings == 1) "One sitting" else "${w.sittings} sittings"
        val notes = if (w.notes == 1) "one note" else "${w.notes} notes"
        "$sittings, ${w.minutes} min, on $notes"
    }
}

private fun lessonDate(ms: Long): String = SimpleDateFormat("EEEE, MMM d 'at' h:mm a", Locale.getDefault()).format(Date(ms))

private fun dayAndTime(ms: Long): String = SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(ms))
