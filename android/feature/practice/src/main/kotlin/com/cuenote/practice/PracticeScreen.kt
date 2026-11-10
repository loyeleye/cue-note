package com.cuenote.practice

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
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
import com.cuenote.ui.ClipPlayer
import com.cuenote.ui.Masthead
import com.cuenote.ui.NoteCard
import com.cuenote.ui.PlayGlyph
import com.cuenote.ui.PlayPill
import com.cuenote.ui.StartButton
import com.cuenote.ui.TicketRow
import com.cuenote.ui.Topic
import com.cuenote.ui.quoteStyle
import com.cuenote.ui.rememberClipPlayer
import com.cuenote.ui.rememberReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Practicing one note between lessons: hear the teacher's words and the
 * lesson take again, then sing practice takes and play them back. Takes stay
 * on the phone. While a take records, the screen shows just the cue and a
 * big Done at the bottom, where the thumb is.
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
    var saving by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<PracticeTakeEntity?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val recording = startedAt != null

    // Leaving the screen mid-take (rather than with Back) doesn't keep a half take.
    DisposableEffect(recorder) {
        onDispose { recorder.stop()?.file?.delete() }
    }
    // The screen stays on while singing.
    val view = LocalView.current
    DisposableEffect(recording) {
        view.keepScreenOn = recording
        onDispose { view.keepScreenOn = false }
    }

    fun start() {
        player.stop()
        note = null
        runCatching { recorder.start(repo.newTakeFile()) }
            .onSuccess { startedAt = System.currentTimeMillis() }
            .onFailure { note = "The mic didn't start: ${it.message}" }
    }

    /** Ends the take; [keep] false throws it away. Closing the file runs off the main thread. */
    fun stop(keep: Boolean, then: () -> Unit = {}) {
        val began = startedAt ?: return
        startedAt = null
        val card = row?.card
        saving = keep
        scope.launch {
            val take = withContext(Dispatchers.IO) { recorder.stop() }
            when {
                take == null -> Unit
                !keep || card == null -> withContext(Dispatchers.IO) { take.file.delete() }
                take.durationMs < PracticeRepository.MIN_TAKE_MS -> {
                    withContext(Dispatchers.IO) { take.file.delete() }
                    note = "That one was very short, so it wasn't kept."
                }
                else -> repo.saveTake(card, take, began, devices.output, latency)
            }
            saving = false
            then()
        }
    }

    BackHandler(enabled = recording) { confirmLeave = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Practice") },
                navigationIcon = { TextButton(onClick = { if (recording) confirmLeave = true else onBack() }) { Text("Back") } },
            )
        },
        bottomBar = {
            SingBar(
                recording = recording,
                startedAt = startedAt,
                enabled = !lessonRecording && row != null && !saving,
                label = when {
                    saving -> "Saving…"
                    takes.isEmpty() -> "Sing a practice take"
                    else -> "Sing another take"
                },
                onStart = ::start,
                onDone = { stop(keep = true) },
                onStartOver = { stop(keep = false) { start() } },
            )
        },
    ) { padding ->
        val r = row
        if (recording && r != null) {
            Singing(r, { recorder.level.value }, Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "mast") { Masthead("Practice ", "time") }
            if (r == null) {
                item(key = "gone") {
                    Text(
                        "This note isn't on the phone any more. It may have been removed from its lesson.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@LazyColumn
            }
            item(key = "note") { Note(r, teacherName(r.card.lessonId), player) }
            player.error?.let { e -> item(key = "perr") { Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }

            item(key = "listen") { ListeningRow(devices, latency, onTimingCheck) }

            if (lessonRecording) {
                item(key = "busy") {
                    Text(
                        "A lesson is recording, so practice takes wait until it ends.",
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
            takesList(takes, r, player) { confirmDelete = it }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Keep this take?") },
            text = { Text("You're still recording.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; stop(keep = true, then = onBack) }) { Text("Keep it") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false; stop(keep = false, then = onBack) }) { Text("Don't keep") } },
        )
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

/** The takes, newest first, under a strip to hear the teacher's words again for comparing. */
private fun LazyListScope.takesList(
    takes: List<PracticeTakeEntity>,
    r: SetlistRow,
    player: ClipPlayer,
    onDelete: (PracticeTakeEntity) -> Unit,
) {
    if (takes.isEmpty()) return
    item(key = "takesh") { Text("Your practice takes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
    r.wordsFile?.let { f ->
        item(key = "compare") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayPill("Their words", player.playing == f, filled = true, onClick = { player.toggle(f) })
                Text("Play a take, then their words again.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    itemsIndexed(takes, key = { _, t -> t.id }) { i, t ->
        TakeRow(takes.size - i, t, player.playing == t.file, onPlay = { player.toggle(t.file) }, onDelete = { onDelete(t) })
    }
    item(key = "private") {
        Text("Practice takes stay on this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Note(r: SetlistRow, teacher: String?, player: ClipPlayer) {
    val c = r.card
    val topic = Topic.of(c.category)
    val words = c.herWords.ifBlank { c.cue }
    NoteCard(
        topic = topic,
        topicLabel = topic?.label ?: c.category?.replaceFirstChar { it.uppercase() } ?: "Note",
        where = listOfNotNull(shortDate(r.recordedAt), bars(r)).joinToString(", "),
        quote = words,
        teacherName = teacher ?: "Your teacher",
        checkedByTeacher = c.editedByTeacher,
    ) {
        if (c.cue.isNotBlank() && c.cue != words) {
            Text(c.cue, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (r.wordsFile != null || r.takeFile != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.wordsFile?.let { f -> PlayPill("Their words", player.playing == f, filled = true, onClick = { player.toggle(f) }) }
                r.takeFile?.let { f -> PlayPill("Your take", player.playing == f, filled = false, onClick = { player.toggle(f) }) }
            }
        }
    }
}

/** Headphones and mic: a status chip, one line on what it means, then the timing check. */
@Composable
private fun ListeningRow(devices: Devices, latency: Int?, onTimingCheck: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val (title, chip, body) = when (devices.output.listening) {
        Listening.WIRED -> if (latency != null) Triple("Headphones in", "Timing checked", "Sing-along takes will line up.")
            else Triple("Headphones in", "Not checked yet", "A 10-second check lines up sing-along takes.")
        Listening.BLUETOOTH -> Triple("Bluetooth headphones", if (latency != null) "Timing checked" else "Runs a little late",
            "Fine on your own. Wired is best for singing along.")
        Listening.SPEAKER -> Triple("No headphones", null, "Fine on your own. Plug some in to sing along.")
    }
    TicketRow {
        Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Headphones, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                chip?.let { StatusChip(it, good = it == "Timing checked") }
            }
            Text(body, color = muted, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = muted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (devices.lessonMic) "Recording from your lesson mic" else "Recording from the phone's mic",
                    color = muted, style = MaterialTheme.typography.labelMedium)
            }
            if (devices.output.listening != Listening.SPEAKER) {
                OutlinedButton(onClick = onTimingCheck, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    Text(if (latency != null) "Check timing again" else "Check timing · 10 sec")
                }
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, good: Boolean) {
    val m = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (good) m.onPrimaryContainer else m.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (good) m.primaryContainer else m.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/**
 * The one button at the bottom: Sing when idle, then Done (with the time)
 * in the same spot while singing, so nothing moves under the thumb.
 */
@Composable
private fun SingBar(
    recording: Boolean,
    startedAt: Long?,
    enabled: Boolean,
    label: String,
    onStart: () -> Unit,
    onDone: () -> Unit,
    onStartOver: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!recording) {
            StartButton(label, onStart, Modifier.fillMaxWidth().defaultMinSize(minHeight = 60.dp), enabled = enabled)
        } else {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(startedAt) { while (true) { now = System.currentTimeMillis(); delay(250) } }
            StartButton("Done, keep it · ${clock(now - (startedAt ?: now))}", onDone,
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 72.dp), dot = false)
            TextButton(onClick = onStartOver) { Text("Start over") }
        }
    }
}

/** While a take records: the cue to sing with, and a calm sign that the mic hears you. */
@Composable
private fun Singing(r: SetlistRow, level: () -> Double?, modifier: Modifier) {
    val c = r.card
    Column(
        modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RecordDot()
            Spacer(Modifier.width(10.dp))
            Text("Recording", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            HearingYou(level)
        }
        Text("“${c.herWords.ifBlank { c.cue }}”", style = quoteStyle(), color = MaterialTheme.colorScheme.onSurface)
        if (c.cue.isNotBlank() && c.cue != c.herWords) {
            Text(c.cue, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        bars(r)?.let { Text(it.replaceFirstChar { ch -> ch.uppercase() }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.weight(1f))
        Text("Sing it as many times as you like, then tap Done.", color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
    }
}

/** The brass record dot, breathing slowly (still with reduced motion). */
@Composable
private fun RecordDot() {
    val reduced = rememberReducedMotion()
    val alpha = if (reduced) 1f else {
        val t = rememberInfiniteTransition(label = "rec")
        t.animateFloat(1f, 0.45f, infiniteRepeatable(tween(1_200), RepeatMode.Reverse), label = "rec-dot").value
    }
    Box(Modifier.size(14.dp).alpha(alpha).background(Brass, CircleShape))
}

/**
 * "Hearing you" or "Listening…": a light that comes on when the mic picks the
 * student up, and never fills like a meter, so it doesn't invite pushing.
 */
@Composable
private fun HearingYou(level: () -> Double?) {
    var hearing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var lastHeard = 0L
        while (true) {
            val now = System.currentTimeMillis()
            if ((level() ?: -120.0) > -45.0) lastHeard = now
            hearing = now - lastHeard < 600
            delay(100)
        }
    }
    val m = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = if (hearing) "Hearing you" else "Listening" },
    ) {
        Box(
            Modifier.size(10.dp).then(
                if (hearing) Modifier.background(m.primary, CircleShape) else Modifier.border(1.5.dp, m.outline, CircleShape),
            ),
        )
        Spacer(Modifier.width(6.dp))
        Text(if (hearing) "Hearing you" else "Listening…", style = MaterialTheme.typography.labelLarge,
            color = if (hearing) m.primary else m.onSurfaceVariant)
    }
}

/** A take: play on the left, then its number and when, and a ⋯ menu with Delete. */
@Composable
private fun TakeRow(number: Int, take: PracticeTakeEntity, playing: Boolean, onPlay: () -> Unit, onDelete: () -> Unit) {
    val exists = remember(take.file) { File(take.file).isFile }
    var menu by remember { mutableStateOf(false) }
    val m = MaterialTheme.colorScheme
    TicketRow(Modifier.then(if (playing) Modifier.border(1.5.dp, m.primary, RoundedCornerShape(14.dp)) else Modifier)) {
        IconButton(onClick = onPlay, enabled = exists, modifier = Modifier.semantics { contentDescription = if (playing) "Stop take $number" else "Play take $number" }) {
            Box(Modifier.size(40.dp).background(if (playing) m.primary else m.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                if (playing) Box(Modifier.size(12.dp).background(m.onPrimary, RoundedCornerShape(2.dp)))
                else PlayGlyph(m.onPrimaryContainer, Modifier.size(13.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text("Take $number", style = MaterialTheme.typography.titleSmall)
            Text(
                "${whenSung(take.recordedAt)} · ${clock(take.durationMs)}",
                color = m.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More for take $number", tint = m.onSurfaceVariant) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

private fun bars(r: SetlistRow): String? {
    val c = r.card
    return when {
        c.barStart != null && c.barEnd != null && c.barEnd != c.barStart -> "bars ${c.barStart}–${c.barEnd}"
        c.barStart != null -> "bar ${c.barStart}"
        else -> null
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
