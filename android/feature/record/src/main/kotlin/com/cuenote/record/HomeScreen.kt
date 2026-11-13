package com.cuenote.record

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuenote.data.CardCount
import com.cuenote.data.LessonEntity
import com.cuenote.data.SetlistRow
import com.cuenote.ui.ClipPlayer
import com.cuenote.ui.Eyebrow
import com.cuenote.ui.Fraunces
import com.cuenote.ui.Masthead
import com.cuenote.ui.NoteCard
import com.cuenote.ui.PlayPill
import com.cuenote.ui.Plum
import com.cuenote.ui.StartButton
import com.cuenote.ui.TicketRow
import com.cuenote.ui.Topic
import com.cuenote.ui.colors
import com.cuenote.ui.rememberClipPlayer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Brass from the art pass: the one warm accent, for the moment that matters. */
internal val Brass = com.cuenote.ui.Brass

/**
 * Home, in the Setlist style. Three tabs: Home leads with Start lesson, so
 * it's one tap at the teacher's; Practice has the daily plan, the warm-up and
 * every note; Lessons has every lesson. Teachers and sign-in live behind the avatar.
 */
@Composable
fun HomeScreen(
    lessons: List<LessonEntity>,
    recordingInProgress: Boolean,
    signedInEmail: String?,
    onStartLesson: () -> Unit,
    onReturnToRecording: () -> Unit,
    onSetupCheck: () -> Unit,
    onSignIn: () -> Unit,
    onTeachers: () -> Unit = {},
    onExport: (LessonEntity) -> Unit,
    onDelete: (LessonEntity) -> Unit,
    onOpen: (LessonEntity) -> Unit = {},
    /** null hides the row; otherwise what to say about the speech models. */
    modelStatus: String? = null,
    onDownloadModels: (() -> Unit)? = null,
    /** Cards and practice items per lesson id, for the badges. */
    cardCounts: Map<String, CardCount> = emptyMap(),
    /** The notes to practice, newest lesson first. */
    setlist: List<SetlistRow> = emptyList(),
    /** The student's first name, for the greeting. */
    studentName: String? = null,
    /** Who taught a lesson, by lesson id, when known. */
    teacherName: (String) -> String? = { null },
    /** True once a teacher has accepted an invite. */
    hasTeacher: Boolean = false,
    /** Opens practice for a note (by card id). */
    onPractice: (String) -> Unit = {},
    /** What's ready for the next lesson; null hides the row (no teacher yet). */
    nextLesson: NextLessonPeek? = null,
    onNextLesson: () -> Unit = {},
    /** Today's suggested practice (the check-in), shown above the setlist when there are notes. */
    today: (@Composable () -> Unit)? = null,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val player = rememberClipPlayer()
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf("Home" to Icons.Filled.Home, "Practice" to Icons.Filled.QueueMusic, "Lessons" to Icons.Filled.History)
                    .forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i; player.stop() },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(label) },
                        )
                    }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "top") {
                TopRow(
                    caption = if (lessons.isEmpty()) "Getting started" else timeOfDay(),
                    signedInEmail = signedInEmail,
                    studentName = studentName,
                    onSignIn = onSignIn,
                    onTeachers = onTeachers,
                )
            }
            player.error?.let { e -> item(key = "err") { Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            when (tab) {
                0 -> homeTab(
                    lessons, recordingInProgress, setlist, studentName, teacherName, hasTeacher, player,
                    modelStatus, onDownloadModels, onStartLesson, onReturnToRecording, onSetupCheck, onTeachers,
                    onAllNotes = { tab = 1 },
                    cardCounts = cardCounts,
                    onOpen = onOpen,
                    onAllLessons = { tab = 2 },
                    onPractice = onPractice,
                    nextLesson = nextLesson,
                    onNextLesson = onNextLesson,
                )
                1 -> setlistTab(setlist, teacherName, player, onPractice, today)
                else -> lessonsTab(lessons, cardCounts, teacherName, onExport, onDelete, onOpen)
            }
        }
    }
}

private fun LazyListScope.homeTab(
    lessons: List<LessonEntity>,
    recordingInProgress: Boolean,
    setlist: List<SetlistRow>,
    studentName: String?,
    teacherName: (String) -> String?,
    hasTeacher: Boolean,
    player: ClipPlayer,
    modelStatus: String?,
    onDownloadModels: (() -> Unit)?,
    onStartLesson: () -> Unit,
    onReturnToRecording: () -> Unit,
    onSetupCheck: () -> Unit,
    onTeachers: () -> Unit,
    onAllNotes: () -> Unit,
    cardCounts: Map<String, CardCount>,
    onOpen: (LessonEntity) -> Unit,
    onAllLessons: () -> Unit,
    onPractice: (String) -> Unit,
    nextLesson: NextLessonPeek?,
    onNextLesson: () -> Unit,
) {
    val firstRun = lessons.isEmpty()
    val last = lessons.firstOrNull()
    item(key = "mast") {
        val name = studentName?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }
        when {
            firstRun && name != null -> Masthead("Welcome, ", name)
            firstRun -> Masthead("Welcome to ", "Cue Note")
            name != null -> Masthead("Ready to ", "sing", ", $name?")
            else -> Masthead("Ready to ", "sing", "?")
        }
    }
    if (recordingInProgress) {
        item(key = "rec") { StartButton("You're recording. Back to the lesson", onReturnToRecording, Modifier.fillMaxWidth()) }
    }
    if (onDownloadModels != null) {
        item(key = "models") {
            TicketRow(onClick = onDownloadModels) {
                Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text("One quick download first", style = MaterialTheme.typography.titleSmall)
                    Text("The speech models, so your notes are made on this phone", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Text("150 MB", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        }
    } else if (modelStatus != null && firstRun) {
        item(key = "models") { Text(modelStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    }

    // Home is for the lesson: Start comes first. Practice has its own tab.
    if (firstRun) {
        item(key = "steps") { FirstSteps(hasTeacher, onTeachers, onSetupCheck, onStartLesson) }
    } else if (!recordingInProgress) {
        item(key = "start") { StartCard(onStartLesson, onSetupCheck) }
    }
    if (nextLesson != null && !firstRun) {
        item(key = "next") { NextLessonRow(nextLesson, onNextLesson) }
    }
    if (!firstRun) {
        if (setlist.isEmpty()) {
            item(key = "empty") {
                EmptySetlist(false, last?.takeIf { it.pipelineState == "processing" || it.pipelineState == "recorded" } != null, last?.let { teacherName(it.id) })
            }
        } else {
            item(key = "practice") { PracticeRow(setlist.size, onAllNotes) }
        }
    }
    if (last != null && last.pipelineState != "recording") {
        item(key = "lasth") { SectionHead("Your last lesson", if (lessons.size > 1) "All ${lessons.size}" else null, onAllLessons) }
        item(key = "last") { LastLesson(last, cardCounts[last.id], teacherName(last.id), onOpen) }
    }
}

/** One row on Home that opens the Practice tab. */
@Composable
private fun PracticeRow(notes: Int, onOpen: () -> Unit) {
    TicketRow(onClick = onOpen) {
        Icon(Icons.Filled.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text("Today's practice", style = MaterialTheme.typography.titleSmall)
            Text(
                if (notes == 1) "1 note on your setlist" else "$notes notes on your setlist",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("Practice", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}

private fun LazyListScope.setlistTab(
    setlist: List<SetlistRow>,
    teacherName: (String) -> String?,
    player: ClipPlayer,
    onPractice: (String) -> Unit,
    today: (@Composable () -> Unit)?,
) {
    item(key = "mast") { Masthead("Time to ", "practice") }
    if (setlist.isEmpty()) {
        item(key = "empty") { EmptySetlist(firstRun = false, notesOnTheWay = false, teacher = null) }
    } else {
        today?.let { t -> item(key = "today") { t() } }
    }
    item(key = "warmh") { SectionHead("Today's warm-up", null) {} }
    item(key = "warm") { WarmUpCard(warmUpFor(Calendar.getInstance())) }
    if (setlist.isEmpty()) return
    item(key = "seth") { SectionHead("Your setlist", null) {} }
    item(key = "hint") {
        Text(
            "What your teacher asked you to practice, newest first. Switch a note off in its lesson once you've got it.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    items(setlist, key = { "s-" + it.card.id }) { SetlistNote(it, teacherName, player, onPractice) }
}

private fun LazyListScope.lessonsTab(
    lessons: List<LessonEntity>,
    cardCounts: Map<String, CardCount>,
    teacherName: (String) -> String?,
    onExport: (LessonEntity) -> Unit,
    onDelete: (LessonEntity) -> Unit,
    onOpen: (LessonEntity) -> Unit,
) {
    item(key = "mast") { Masthead("Your ", "lessons") }
    if (lessons.isEmpty()) {
        item(key = "empty") {
            Text("Your lessons show up here after the first one.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        }
    }
    items(lessons, key = { it.id }) { LessonRow(it, cardCounts[it.id], teacherName(it.id), onExport, onDelete, onOpen) }
}

@Composable
private fun TopRow(caption: String, signedInEmail: String?, studentName: String?, onSignIn: () -> Unit, onTeachers: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).background(Plum, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.cue_mark), contentDescription = null, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(caption, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        val initial = (studentName?.trim()?.firstOrNull() ?: signedInEmail?.firstOrNull())?.uppercaseChar()?.toString()
        Box {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable { menu = true }
                    .semantics { contentDescription = "Profile and teachers" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(36.dp).background(Plum, CircleShape), contentAlignment = Alignment.Center) {
                    Text(initial ?: "?", color = Color.White, fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Your teachers") }, onClick = { menu = false; onTeachers() })
                DropdownMenuItem(
                    text = { Text(if (signedInEmail == null) "Sign in" else "Signed in as ${signedInEmail.substringBefore('@')}") },
                    onClick = { menu = false; onSignIn() },
                )
            }
        }
    }
}

@Composable
private fun SectionHead(title: String, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/** One setlist note: the teacher's words, buttons to hear them and your take, and Practice. */
@Composable
private fun SetlistNote(r: SetlistRow, teacherName: (String) -> String?, player: ClipPlayer, onPractice: (String) -> Unit) {
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
        teacherName = teacherName(c.lessonId) ?: "Your teacher",
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
        // A footer of its own, so the play pills keep the whole row.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = "Practice this note") { player.stop(); onPractice(c.id) }
                .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Practice this note", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * No grey void: an example of the note that will land here, marked as an
 * example, and a line on when the real ones come.
 */
@Composable
private fun EmptySetlist(firstRun: Boolean, notesOnTheWay: Boolean, teacher: String?) {
    val m = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            NoteCard(
                topic = Topic.BREATH,
                topicLabel = Topic.BREATH.label,
                where = "Example",
                quote = "Breathe low, like filling a rubber ring around your waist.",
                teacherName = teacher ?: "Your teacher",
                checkedByTeacher = false,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .graphicsLayer { rotationZ = -1f; alpha = 0.92f }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "An example note: breathe low, like filling a rubber ring around your waist."
                    },
            )
        }
        Text(
            when {
                notesOnTheWay -> "Your notes from the last lesson are on their way. They'll land here, in your teacher's words and voice."
                firstRun -> "After each lesson, your teacher's notes land here, in their words and their voice."
                else -> "Your setlist fills up after your next lesson, in your teacher's words and voice."
            },
            color = m.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** What the student has ready for the next lesson, for the row on Home. */
data class NextLessonPeek(
    /** Epoch ms of the next lesson, when a teacher has set it. */
    val at: Long?,
    val sharedTakes: Int,
    val openQuestions: Int,
)

/** "Before your next lesson": the date, and what's on the list so far. */
@Composable
private fun NextLessonRow(p: NextLessonPeek, onOpen: () -> Unit) {
    val m = MaterialTheme.colorScheme
    TicketRow(onClick = onOpen) {
        Column(Modifier.weight(1f)) {
            Text(
                p.at?.let { "Next lesson " + SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(it)) } ?: "Before your next lesson",
                style = MaterialTheme.typography.titleSmall,
            )
            val parts = listOfNotNull(
                p.sharedTakes.takeIf { it > 0 }?.let { if (it == 1) "1 take shared" else "$it takes shared" },
                p.openQuestions.takeIf { it > 0 }?.let { if (it == 1) "1 question" else "$it questions" },
            )
            Text(
                parts.joinToString(" · ").ifEmpty { "Share a take or ask a question" },
                color = m.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("Open", color = m.primary, style = MaterialTheme.typography.labelLarge)
    }
}

/** The latest lesson in one row, so Home always shows where you are. */
@Composable
private fun LastLesson(l: LessonEntity, counts: CardCount?, teacher: String?, onOpen: (LessonEntity) -> Unit) {
    val m = MaterialTheme.colorScheme
    TicketRow(onClick = { onOpen(l) }) {
        Column(Modifier.size(width = 44.dp, height = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(SimpleDateFormat("MMM", Locale.getDefault()).format(Date(l.recordedAt)).uppercase(), color = m.primary, style = MaterialTheme.typography.labelSmall)
            Text(SimpleDateFormat("d", Locale.getDefault()).format(Date(l.recordedAt)), fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(l.recordedAt)) + (teacher?.let { " with $it" } ?: ""),
                style = MaterialTheme.typography.titleSmall,
            )
            val line = when {
                counts != null && counts.cards > 0 ->
                    "${counts.cards} notes" + (if (counts.practice > 0) " · ${counts.practice} to practice" else "") + " · ${minutes(l.durationMs)}"
                else -> listOfNotNull(statusText(l), minutes(l.durationMs)).joinToString(" · ")
            }
            Text(line, color = if (l.pipelineState == "failed") m.error else m.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Text("Open", color = m.primary, style = MaterialTheme.typography.labelLarge)
    }
}

/** A short, gentle warm-up, a different one each day. */
internal data class WarmUp(val topic: Topic, val name: String, val how: String)

internal val WARM_UPS = listOf(
    WarmUp(Topic.ONSET, "Lip trills", "Blow through loose lips so they buzz, then slide up and down an easy five-note scale."),
    WarmUp(Topic.RESONANCE, "Hum and slide", "Hum on \"mm\" from low to high and back. Feel the buzz on your lips and nose."),
    WarmUp(Topic.REGISTERS, "Sirens on \"ng\"", "Say \"sing\" and hold the \"ng\". Slide up and down like a far-off siren, light and easy."),
    WarmUp(Topic.BREATH, "Hiss it out", "Breathe in low for four counts, then hiss out slowly for eight. Keep your shoulders easy."),
    WarmUp(Topic.ONSET, "Straw slides", "Sing a slide through a straw, low to high and back. It takes the pressure off."),
    WarmUp(Topic.VOWELS, "Yawn and sigh", "Start a yawn, then sigh it out from high to low on \"ah\"."),
    WarmUp(Topic.DICTION, "Tongue twisters", "Say \"red leather, yellow leather\" slowly, then quicker, keeping your jaw loose."),
)

internal fun warmUpFor(day: Calendar): WarmUp = WARM_UPS[day.get(Calendar.DAY_OF_YEAR) % WARM_UPS.size]

@Composable
private fun WarmUpCard(w: WarmUp) {
    val c = w.topic.colors()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.container)
            .semantics(mergeDescendants = true) {}
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Eyebrow("${w.topic.label} · 2 minutes", c.content)
        Text(w.name, fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(w.how, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text("Easy and light. Stop if anything feels tight.", style = MaterialTheme.typography.bodySmall, color = c.content)
    }
}

/** The three steps to a first lesson. */
@Composable
private fun FirstSteps(hasTeacher: Boolean, onTeachers: () -> Unit, onSetupCheck: () -> Unit, onStartLesson: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow("Three steps to your first lesson", MaterialTheme.colorScheme.onSurfaceVariant)
        Step(1, "Invite your teacher", if (hasTeacher) "They're in" else "So they can see your notes", done = hasTeacher, action = if (hasTeacher) null else "Invite", onClick = onTeachers)
        Step(2, "Soundcheck", "A one-minute warm-up for the mics", done = false, action = "Run", onClick = onSetupCheck)
        Step(3, "Sing your first lesson", null, done = false, action = "Start", onClick = onStartLesson)
    }
}

@Composable
private fun Step(n: Int, title: String, sub: String?, done: Boolean, action: String?, onClick: () -> Unit) {
    val m = MaterialTheme.colorScheme
    TicketRow(onClick = onClick) {
        if (done) {
            Icon(Icons.Filled.CheckCircle, contentDescription = "Done", tint = m.primary, modifier = Modifier.size(28.dp))
        } else {
            Box(Modifier.size(28.dp).border(1.5.dp, m.primary, CircleShape), contentAlignment = Alignment.Center) {
                Text("$n", color = m.primary, style = MaterialTheme.typography.labelLarge)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (sub != null) Text(sub, color = m.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (action != null) Text(action, color = m.primary, style = MaterialTheme.typography.labelLarge)
    }
}

/** Start, with the soundcheck one tap away. */
@Composable
private fun StartCard(onStartLesson: () -> Unit, onSetupCheck: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        StartButton("Start a lesson", onStartLesson, Modifier.fillMaxWidth())
        TicketRow(onClick = onSetupCheck) {
            Icon(Icons.Filled.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text("Soundcheck", style = MaterialTheme.typography.titleSmall)
                Text("New room or new mics? A one-minute warm-up", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text("Run", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun LessonRow(
    l: LessonEntity,
    counts: CardCount?,
    teacher: String?,
    onExport: (LessonEntity) -> Unit,
    onDelete: (LessonEntity) -> Unit,
    onOpen: (LessonEntity) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val m = MaterialTheme.colorScheme
    TicketRow(onClick = { onOpen(l) }) {
        Column(Modifier.size(width = 44.dp, height = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(SimpleDateFormat("MMM", Locale.getDefault()).format(Date(l.recordedAt)).uppercase(), color = m.primary, style = MaterialTheme.typography.labelSmall)
            Text(SimpleDateFormat("d", Locale.getDefault()).format(Date(l.recordedAt)), fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(l.recordedAt)) + (teacher?.let { " with $it" } ?: ""),
                style = MaterialTheme.typography.titleSmall,
            )
            val line = when {
                counts != null && counts.cards > 0 ->
                    "${counts.cards} notes" + (if (counts.practice > 0) " · ${counts.practice} to practice" else "") + " · ${minutes(l.durationMs)}"
                else -> listOfNotNull(statusText(l), minutes(l.durationMs)).joinToString(" · ")
            }
            Text(line, color = if (l.pipelineState == "failed") m.error else m.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (l.gapCount > 0) {
                Text("${l.gapCount} gaps where the audio dropped out", color = m.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋯", modifier = Modifier.semantics { contentDescription = "More" }) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Copy to Downloads") }, onClick = { menu = false; onExport(l) })
                DropdownMenuItem(text = { Text("Delete lesson") }, onClick = { menu = false; confirmDelete = true })
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this lesson?") },
            text = {
                Text(
                    "The recording, notes, clips and sketch photos go from this phone and from your teacher's dashboard. " +
                        "This can't be undone.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(l) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it") } },
        )
    }
}

private fun minutes(ms: Long): String {
    val m = (ms + 30_000) / 60_000
    return if (m < 1) formatDuration(ms) else "$m min"
}

private fun shortDate(ms: Long): String = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(ms))

private fun timeOfDay(): String {
    val c = Calendar.getInstance()
    val day = SimpleDateFormat("EEEE", Locale.getDefault()).format(c.time)
    val part = when (c.get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "morning"
        in 12..16 -> "afternoon"
        else -> "evening"
    }
    return "$day $part"
}

/** A plain line for a lesson without notes yet. */
private fun statusText(l: LessonEntity): String? = when (l.pipelineState) {
    "recording" -> "Recording now"
    "recorded" -> "Your notes will be ready after the phone's next charge"
    "processing" -> "Writing your notes…"
    "processed" -> "Ready: tap to hear your takes"
    "failed" -> "We couldn't make your notes this time. Tap to see why and try again."
    else -> null
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
