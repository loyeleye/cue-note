package com.cuenote.practice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cuenote.audio.Listening
import com.cuenote.audio.PracticeRecorder
import com.cuenote.data.PracticeRepository
import com.cuenote.data.PracticeTakeEntity
import com.cuenote.data.SetlistRow
import com.cuenote.ui.Brass
import com.cuenote.ui.LevelRow
import com.cuenote.ui.Masthead
import com.cuenote.ui.NoteCard
import com.cuenote.ui.PlayPill
import com.cuenote.ui.StartButton
import com.cuenote.ui.TicketRow
import com.cuenote.ui.Topic
import com.cuenote.ui.rememberClipPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Practicing one note between lessons: hear the teacher's words and the
 * lesson take again, then sing practice takes and play them back. Takes stay
 * on the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeScreen(
    cardId: String,
    repo: PracticeRepository,
    /** Who gave the note, for "Marigold said"; null for "Your teacher". */
    teacherName: (lessonId: String) -> String?,
    /** A lesson is being recorded, so the mic is busy. */
    lessonRecording: Boolean,
    onTimingCheck: () -> Unit,
    onBack: () -> Unit,
) {
    val row by repo.card(cardId).collectAsStateWithLifecycle(null)
    val takes by repo.takes(cardId).collectAsStateWithLifecycle(emptyList())
    val devices = rememberDevices()
    val timingVersion by repo.timing.changes.collectAsStateWithLifecycle()
    val latency = remember(devices.output, timingVersion) { repo.timing.latencyMs(devices.output) }
    val player = rememberClipPlayer()
    val context = LocalContext.current
    val recorder = remember { PracticeRecorder(context) }
    val scope = rememberCoroutineScope()
    var startedAt by remember { mutableStateOf<Long?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<PracticeTakeEntity?>(null) }

    // Leaving mid-take stops it; a half take isn't kept.
    DisposableEffect(recorder) {
        onDispose { recorder.stop()?.file?.delete() }
    }
    // The screen stays on while singing.
    val view = LocalView.current
    DisposableEffect(startedAt != null) {
        view.keepScreenOn = startedAt != null
        onDispose { view.keepScreenOn = false }
    }

    fun start() {
        player.stop()
        note = null
        runCatching { recorder.start(repo.newTakeFile()) }
            .onSuccess { startedAt = System.currentTimeMillis() }
            .onFailure { note = "The mic didn't start: ${it.message}" }
    }

    fun stop() {
        val began = startedAt ?: return
        startedAt = null
        val take = recorder.stop() ?: return
        val card = row?.card ?: return
        if (take.durationMs < PracticeRepository.MIN_TAKE_MS) {
            take.file.delete()
            note = "That one was very short, so it wasn't kept."
            return
        }
        scope.launch { repo.saveTake(card, take, began, devices.output, latency) }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Practice") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "mast") { Masthead("Practice ", "time") }
            val r = row
            if (r == null) {
                item(key = "gone") {
                    Text(
                        "This note isn't on the phone any more. It may have been removed from its lesson.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@LazyColumn
            }
            item(key = "note") { Note(r, teacherName(r.card.lessonId), player.playing, player::toggle) }
            player.error?.let { e -> item(key = "perr") { Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }

            item(key = "listen") { ListeningRow(devices, latency, onTimingCheck) }

            item(key = "sing") {
                if (startedAt == null) {
                    StartButton(
                        if (takes.isEmpty()) "Sing a practice take" else "Sing another take",
                        ::start,
                        Modifier.fillMaxWidth(),
                        enabled = !lessonRecording,
                    )
                } else {
                    Singing(startedAt!!, { recorder.level.value }, ::stop)
                }
            }
            if (lessonRecording) {
                item(key = "busy") {
                    Text(
                        "A lesson is recording, so practice takes are paused until it ends.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            note?.let { n ->
                item(key = "msg") {
                    Text(n, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }

            if (takes.isNotEmpty()) {
                item(key = "takesh") { Text("Your practice takes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
                itemsIndexed(takes, key = { _, t -> t.id }) { i, t ->
                    TakeRow(
                        number = takes.size - i,
                        take = t,
                        playing = player.playing == t.file,
                        onPlay = { player.toggle(t.file) },
                        onDelete = { confirmDelete = t },
                    )
                }
                item(key = "private") {
                    Text(
                        "Practice takes stay on this phone.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this take?") },
            text = { Text("It's only on this phone, so it can't be brought back.") },
            confirmButton = {
                TextButton(onClick = {
                    if (player.playing == t.file) player.stop()
                    scope.launch { repo.deleteTake(t.id) }
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun Note(r: SetlistRow, teacher: String?, playing: String?, toggle: (String) -> Unit) {
    val c = r.card
    val topic = Topic.of(c.category)
    val bars = when {
        c.barStart != null && c.barEnd != null && c.barEnd != c.barStart -> "bars ${c.barStart}–${c.barEnd}"
        c.barStart != null -> "bar ${c.barStart}"
        else -> null
    }
    val words = c.herWords.ifBlank { c.cue }
    NoteCard(
        topic = topic,
        topicLabel = topic?.label ?: c.category?.replaceFirstChar { it.uppercase() } ?: "Note",
        where = listOfNotNull(shortDate(r.recordedAt), bars).joinToString(", "),
        quote = words,
        teacherName = teacher ?: "Your teacher",
        checkedByTeacher = c.editedByTeacher,
    ) {
        if (c.cue.isNotBlank() && c.cue != words) {
            Text(c.cue, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (r.wordsFile != null || r.takeFile != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.wordsFile?.let { f -> PlayPill("Their words", playing == f, filled = true, onClick = { toggle(f) }) }
                r.takeFile?.let { f -> PlayPill("Your lesson take", playing == f, filled = false, onClick = { toggle(f) }) }
            }
        }
    }
}

/** Where the take is recorded from, what it plays through, and the timing check. */
@Composable
private fun ListeningRow(devices: Devices, latency: Int?, onTimingCheck: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    TicketRow {
        Icon(Icons.Filled.Headphones, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val (title, body) = when (devices.output.listening) {
                Listening.WIRED -> "Headphones in" to
                    if (latency != null) "Timing's checked for these, so sing-along takes will line up."
                    else "A quick timing check helps sing-along takes line up."
                Listening.BLUETOOTH -> "Bluetooth headphones" to
                    "These usually run a little behind your voice. Wired ones are best for singing along; on your own, these are fine."
                Listening.SPEAKER -> "No headphones" to
                    "That's fine for singing on your own. To sing along, plug in wired headphones so the backing stays out of your take."
            }
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, color = muted, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = muted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    if (devices.lessonMic) "Recording from your lesson mic" else "Recording from the phone's mic",
                    color = muted, style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (devices.output.listening != Listening.SPEAKER) {
            TextButton(onClick = onTimingCheck) { Text(if (latency != null) "Check again" else "Check timing") }
        }
    }
}

/** While a take records: the time, a level bar and Done. */
@Composable
private fun Singing(startedAt: Long, level: () -> Double?, onStop: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) { now = System.currentTimeMillis(); delay(250) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(Modifier.size(12.dp).background(Brass, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text("Recording · ${clock(now - startedAt)}", style = MaterialTheme.typography.titleSmall)
        }
        LevelRow("You", level)
        StartButton("Done", onStop, Modifier.fillMaxWidth(), dot = false)
    }
}

@Composable
private fun TakeRow(number: Int, take: PracticeTakeEntity, playing: Boolean, onPlay: () -> Unit, onDelete: () -> Unit) {
    val exists = remember(take.file) { File(take.file).isFile }
    TicketRow {
        Column(Modifier.weight(1f)) {
            Text("Take $number", style = MaterialTheme.typography.titleSmall)
            Text(
                "${whenSung(take.recordedAt)} · ${clock(take.durationMs)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (exists) PlayPill("Play", playing, filled = false, onClick = onPlay)
        TextButton(onClick = onDelete) { Text("Delete") }
    }
}

internal fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

private fun shortDate(ms: Long): String = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))

private fun whenSung(ms: Long): String {
    val age = System.currentTimeMillis() - ms
    return when {
        age < 60_000 -> "Just now"
        age < 3_600_000 -> "${age / 60_000} min ago"
        else -> SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(ms))
    }
}
