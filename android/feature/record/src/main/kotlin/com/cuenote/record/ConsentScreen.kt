package com.cuenote.record

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Videocam

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cuenote.audio.ChannelSwap
import com.cuenote.data.ConsentStore
import com.cuenote.data.InvitedTeacher
import com.cuenote.data.LessonTeacher
import com.cuenote.data.LocalOnly
import com.cuenote.signal.MicVerdict
import com.cuenote.ui.Masthead
import com.cuenote.ui.NoteCard
import com.cuenote.ui.Plum
import com.cuenote.ui.StartButton
import com.cuenote.ui.TicketRow
import com.cuenote.ui.Topic
import com.cuenote.ui.colors

/**
 * Nothing records until both people agree.
 *
 * The first time, the student says who they are and who's teaching them, and
 * Cue Note welcomes the teacher in three short pages: what a note looks like,
 * what's in it for them, and what they're agreeing to, where each of them
 * taps "I'm in". That counts as this lesson's yes. After that, it's one tap
 * per lesson.
 *
 * Each lesson also says who is teaching today, so each teacher can be shown
 * only their own lessons. [teachers] are the active ones: with one, they're
 * picked to start with. "Someone else" takes a typed name for a teacher who
 * isn't on Cue Note (a guest, or one not invited yet); they get no access.
 * On the first lesson the question is only asked with more than one teacher.
 *
 * [mics] is a soundcheck's answer to who's on which mic, when one ran just
 * before. It's shown with a way to check again; otherwise, or when the check
 * wasn't sure, the who's-on-which-mic step follows.
 *
 * Posture tracking is a separate opt-in for each lesson, the first one
 * included, off by default, for when the student or teacher asks for it.
 * Left off, the lesson records audio only.
 */
@Composable
fun ConsentScreen(
    store: ConsentStore,
    teachers: List<InvitedTeacher>,
    lastPicked: String?,
    lastName: String?,
    mics: ChannelSwap.Checked?,
    onConsented: (Consented) -> Unit,
    onCancel: () -> Unit,
    /** Teachers saved by name only, offered as chips. */
    namedTeachers: List<String> = emptyList(),
    /** The saved ones who keep their lessons on this phone. */
    localOnlyTeachers: List<String> = emptyList(),
) {
    var names by remember(store) { mutableStateOf(store.firstSetup()?.let { it.studentName to it.teacherName }) }
    // Carried from the welcome to the who's-teaching page, when that follows.
    var welcomePosture by rememberSaveable { mutableStateOf(false) }
    var recheckMics by rememberSaveable { mutableStateOf(false) }
    val choices = teachers.filter { it.active && it.teacherId != null }
    // A teacher on Cue Note (by id), or "Someone else" with a typed name, or neither ("Not sure").
    var picked by rememberSaveable(choices.map { it.teacherId }) {
        mutableStateOf(
            when {
                choices.size == 1 -> choices[0].teacherId
                choices.any { it.teacherId == lastPicked } -> lastPicked
                else -> null
            },
        )
    }
    val fallbackName = (lastName ?: names?.second)?.trim().orEmpty()
    var someoneElse by rememberSaveable(choices.isEmpty()) { mutableStateOf(choices.isEmpty() && fallbackName.isNotEmpty()) }
    var otherName by rememberSaveable { mutableStateOf(fallbackName) }
    // The typed teacher asked that the lesson stays on this phone. A saved local-only teacher starts with it on.
    var localOnly by rememberSaveable { mutableStateOf(localOnlyTeachers.any { LocalOnly.sameName(it, fallbackName) }) }
    fun today(): LessonTeacher =
        if (someoneElse) LessonTeacher.named(otherName, localOnly) else LessonTeacher(teacherId = picked)
    fun done(teacher: LessonTeacher, posture: Boolean) =
        onConsented(Consented(System.currentTimeMillis(), teacher, posture, recheckMics))
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val n = names
        if (n == null) {
            TeacherWelcome(
                knownTeacher = choices.singleOrNull()?.teacherName?.takeIf { it.isNotBlank() },
                mics = mics,
                recheckMics = recheckMics,
                onRecheckMics = { recheckMics = it },
                onBothIn = { student, teacher, posture, keepOnPhone ->
                    store.saveFirstSetup(student, teacher)
                    // Both just said yes. Only ask who's teaching when that's unclear.
                    when {
                        // The teacher asked to keep it on the phone: it's the name just typed, not a dashboard teacher.
                        keepOnPhone && choices.size > 1 -> {
                            welcomePosture = posture; someoneElse = true; otherName = teacher; localOnly = true
                            names = student to teacher
                        }
                        keepOnPhone -> done(LessonTeacher.named(teacher, localOnly = true), posture)
                        choices.size > 1 -> { welcomePosture = posture; names = student to teacher }
                        choices.size == 1 -> done(LessonTeacher(teacherId = picked), posture)
                        // No teacher on Cue Note yet: the name just typed is who's teaching.
                        else -> done(LessonTeacher.named(teacher), posture)
                    }
                },
                onCancel = onCancel,
            )
        } else {
            EveryLesson(
                student = n.first,
                choices = choices,
                picked = picked.takeUnless { someoneElse },
                someoneElse = someoneElse,
                otherName = otherName,
                onPick = { picked = it; someoneElse = false; localOnly = false },
                onSomeoneElse = { someoneElse = true; localOnly = false },
                onOtherName = { otherName = it.take(LessonTeacher.MAX_NAME) },
                localOnly = localOnly,
                onLocalOnly = { localOnly = it },
                saved = namedTeachers,
                savedLocal = localOnlyTeachers,
                onPickSaved = { n -> someoneElse = true; otherName = n; localOnly = localOnlyTeachers.any { LocalOnly.sameName(it, n) } },
                mics = mics,
                recheckMics = recheckMics,
                onRecheckMics = { recheckMics = it },
                initialPosture = welcomePosture,
                onConsented = { posture -> done(today(), posture) },
                onCancel = onCancel,
            )
        }
    }
}

/** Both said yes: when, who's teaching, posture on or off, and whether to check the mics again first. */
data class Consented(val consentAt: Long, val teacher: LessonTeacher, val posture: Boolean, val recheckMics: Boolean)

/** True when the lesson can use the soundcheck's mics without asking. */
fun ChannelSwap.Checked?.trusted(recheck: Boolean): Boolean =
    this != null && !recheck && verdict.confidence != MicVerdict.Confidence.LOW

// ------------------------------------------------------------------ every lesson

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EveryLesson(
    student: String,
    choices: List<InvitedTeacher>,
    picked: String?,
    someoneElse: Boolean,
    otherName: String,
    onPick: (String?) -> Unit,
    onSomeoneElse: () -> Unit,
    onOtherName: (String) -> Unit,
    localOnly: Boolean,
    onLocalOnly: (Boolean) -> Unit,
    saved: List<String>,
    savedLocal: List<String>,
    onPickSaved: (String) -> Unit,
    mics: ChannelSwap.Checked?,
    recheckMics: Boolean,
    onRecheckMics: (Boolean) -> Unit,
    initialPosture: Boolean,
    onConsented: (posture: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var posture by rememberSaveable { mutableStateOf(initialPosture) }
    Page(bottom = {
        StartButton(
            if (posture) "We're both in. Record, with posture" else "We're both in. Let's record",
            onClick = { onConsented(posture) },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onCancel) { Text("Not today") }
    }) {
        TopRow(onBack = onCancel, label = "Before we record")
        Masthead("Ready to ", "record", " today?", modifier = Modifier.semantics { heading() })
        // The teacher picked for today, by the name they set themselves. Without one,
        // a name typed at the first lesson may be stale, so just say "your teacher".
        val today = if (someoneElse) otherName.trim().takeIf { it.isNotEmpty() }
        else choices.firstOrNull { it.teacherId == picked }?.teacherName?.trim()?.takeIf { it.isNotEmpty() }
        Text("$student and ${today ?: "your teacher"}, nothing records until you both say yes.", style = MaterialTheme.typography.bodyLarge)
        Text("Who's teaching today?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        TeacherPicker(
            choices, picked, someoneElse, otherName, onPick, onSomeoneElse, onOtherName,
            localOnly = localOnly, onLocalOnly = onLocalOnly, saved = saved, savedLocal = savedLocal, onPickSaved = onPickSaved,
        )
        val keptOnPhone = someoneElse && localOnly && otherName.isNotBlank()
        if (keptOnPhone) KeptOnPhoneRow(student, otherName.trim())
        MicsRow(mics, recheckMics, onRecheckMics)
        PostureSwitch(posture) { posture = it }
        Fold("What gets recorded?") { if (keptOnPhone) LocalConsentText(student) else ConsentText() }
    }
}

/**
 * The student's teachers on Cue Note, "Someone else" with a name field, and
 * "Not sure". Also used to change who taught a lesson afterwards.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeacherPicker(
    choices: List<InvitedTeacher>,
    picked: String?,
    someoneElse: Boolean,
    otherName: String,
    onPick: (String?) -> Unit,
    onSomeoneElse: () -> Unit,
    onOtherName: (String) -> Unit,
    localOnly: Boolean = false,
    onLocalOnly: ((Boolean) -> Unit)? = null,
    /** Teachers saved by name, with no email, each as their own chip. */
    saved: List<String> = emptyList(),
    /** The saved ones who keep lessons on this phone: their chips carry a lock. */
    savedLocal: List<String> = emptyList(),
    onPickSaved: ((String) -> Unit)? = null,
) {
    val isLocal: (String) -> Boolean = { n -> savedLocal.any { LocalOnly.sameName(it, n) } }
    val savedPicked = saved.firstOrNull { someoneElse && LocalOnly.sameName(it, otherName) && localOnly == isLocal(it) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEachIndexed { i, t ->
            PickChip(t.title, avatarColor(i + 1), !someoneElse && picked == t.teacherId) { onPick(t.teacherId) }
        }
        if (onPickSaved != null) saved.forEachIndexed { i, name ->
            PickChip(name, avatarColor(choices.size + 1 + i), savedPicked == name, locked = isLocal(name)) { onPickSaved(name) }
        }
        val nextColor = choices.size + 1 + (if (onPickSaved != null) saved.size else 0)
        PickChip("Someone else", avatarColor(nextColor), someoneElse && savedPicked == null, initial = "+", onClick = onSomeoneElse)
        PickChip("Not sure", null, !someoneElse && picked == null) { onPick(null) }
    }
    AnimatedVisibility(someoneElse && savedPicked == null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                otherName,
                onOtherName,
                label = { Text("Teacher's name") },
                supportingText = { Text("Just a name on this lesson's notes. They don't get access.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            if (onLocalOnly != null) KeepOnPhoneSwitch(localOnly, onLocalOnly)
        }
    }
}

/**
 * For a teacher who'd rather their voice didn't go online: the lesson stays
 * on this phone, with no cloud, no AI and no dashboard.
 */
@Composable
fun KeepOnPhoneSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val m = MaterialTheme.colorScheme
    TicketRow(Modifier.toggleable(value = on, role = Role.Switch, onValueChange = onChange)) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = m.primary)
        Column(Modifier.weight(1f)) {
            Text("Keep this lesson on this phone", style = MaterialTheme.typography.titleSmall)
            Text(
                if (on) "No cloud, no AI, no dashboard. The phone still makes its own notes."
                else "For a teacher who'd rather their voice didn't go online.",
                style = MaterialTheme.typography.bodySmall,
                color = m.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = null)
    }
}

/** Said before recording when the teacher keeps the lesson on the phone, so both can see it. */
@Composable
private fun KeptOnPhoneRow(student: String, teacher: String) {
    val m = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(m.secondaryContainer).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = m.onSecondaryContainer)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("This lesson stays on $student's phone", style = MaterialTheme.typography.titleSmall, color = m.onSecondaryContainer)
            Text(
                "Cue Note won't send $teacher's voice, words or sketches to its servers or to an AI. " +
                    "The phone still turns the lesson into notes by itself.",
                style = MaterialTheme.typography.bodyMedium,
                color = m.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun LocalConsentText(student: String) {
    Text(ConsentStore.localText(student), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Who's on which mic, from a soundcheck just now, with a way to check again before recording. */
@Composable
private fun MicsRow(mics: ChannelSwap.Checked?, recheck: Boolean, onRecheck: (Boolean) -> Unit) {
    if (mics == null) return
    val m = MaterialTheme.colorScheme
    val v = mics.verdict
    val sure = v.confidence != MicVerdict.Confidence.LOW
    TicketRow {
        Icon(Icons.Filled.Mic, contentDescription = null, tint = m.primary)
        Column(Modifier.weight(1f)) {
            Text("Student on the ${if (v.studentOnRight) "right" else "left"} mic", style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    !sure -> "The soundcheck wasn't sure, so you'll check it next."
                    recheck -> "You'll check it again next."
                    else -> "From your soundcheck: ${v.confidence.label.lowercase()}."
                },
                style = MaterialTheme.typography.bodySmall,
                color = m.onSurfaceVariant,
            )
        }
        if (sure) TextButton(onClick = { onRecheck(!recheck) }) { Text(if (recheck) "Keep it" else "Change") }
    }
}

/** Posture tracking for this lesson: off means audio only, and no tripod needed. */
@Composable
private fun PostureSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val m = MaterialTheme.colorScheme
    TicketRow(
        Modifier.toggleable(value = on, role = Role.Switch, onValueChange = onChange),
    ) {
        Icon(Icons.Filled.Videocam, contentDescription = null, tint = m.primary)
        Column(Modifier.weight(1f)) {
            Text("Watch my posture", style = MaterialTheme.typography.titleSmall)
            Text(
                if (on) "Camera on. No video is saved." else "Off: this lesson records audio only.",
                style = MaterialTheme.typography.bodySmall,
                color = m.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = null)
    }
    AnimatedVisibility(on) {
        Text(POSTURE_TEXT, style = MaterialTheme.typography.bodySmall, color = m.onSurfaceVariant)
    }
}

// ------------------------------------------------------------------ first time: the teacher welcome

private enum class Step { NAMES, HELLO, PERKS, AGREE }

@Composable
private fun TeacherWelcome(
    knownTeacher: String?,
    mics: ChannelSwap.Checked?,
    recheckMics: Boolean,
    onRecheckMics: (Boolean) -> Unit,
    onBothIn: (student: String, teacher: String, posture: Boolean, keepOnPhone: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var student by rememberSaveable { mutableStateOf("") }
    var teacher by rememberSaveable { mutableStateOf(knownTeacher.orEmpty()) }
    val s = student.trim()
    val t = teacher.trim()
    fun go(to: Step) { step = to.ordinal }
    val current = Step.entries[step]
    BackHandler(current != Step.NAMES) { step -= 1 }
    when (current) {
        Step.NAMES -> NamesPage(
            student = student, onStudent = { student = it },
            teacher = teacher, onTeacher = { teacher = it },
            onNext = { go(Step.HELLO) },
            onCancel = onCancel,
        )
        Step.HELLO -> HelloPage(s, t, onNext = { go(Step.PERKS) }, onSkip = { go(Step.AGREE) }, onBack = { go(Step.NAMES) })
        Step.PERKS -> PerksPage(s, onNext = { go(Step.AGREE) }, onSkip = { go(Step.AGREE) }, onBack = { go(Step.HELLO) })
        Step.AGREE -> AgreePage(
            s, t, mics, recheckMics, onRecheckMics,
            onBothIn = { posture, keep -> onBothIn(s, t, posture, keep) }, onCancel = onCancel, onBack = { go(Step.PERKS) },
        )
    }
}

@Composable
private fun NamesPage(
    student: String,
    onStudent: (String) -> Unit,
    teacher: String,
    onTeacher: (String) -> Unit,
    onNext: () -> Unit,
    onCancel: () -> Unit,
) {
    Page(bottom = {
        StartButton(
            "Introduce us",
            onClick = onNext,
            enabled = student.isNotBlank() && teacher.isNotBlank(),
            dot = false,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onCancel) { Text("Not today") }
    }) {
        TopRow(onBack = onCancel, label = "First lesson together")
        Masthead("Who's ", "teaching", " you?", modifier = Modifier.semantics { heading() })
        Text(
            "Cue Note will say hello to your teacher, then you can pass them the phone for a minute.",
            style = MaterialTheme.typography.bodyLarge,
        )
        NameField(student, onStudent, "Your name", ImeAction.Next)
        NameField(teacher, onTeacher, "Your teacher's name", ImeAction.Done)
    }
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit, label: String, ime: ImeAction) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ime),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun HelloPage(student: String, teacher: String, onNext: () -> Unit, onSkip: () -> Unit, onBack: () -> Unit) {
    Page(bottom = {
        Dots(Step.HELLO)
        StartButton("How does it work?", onClick = onNext, dot = false, modifier = Modifier.fillMaxWidth())
    }) {
        TopRow(onBack = onBack, label = null, onSkip = onSkip)
        Masthead("Hi ", teacher, ", meet Cue Note", modifier = Modifier.semantics { heading() })
        Text(
            "$student uses Cue Note to remember your lessons. It listens along and turns what you say into notes they practice all week.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(4.dp))
        NoteCard(
            topic = Topic.REGISTERS,
            topicLabel = Topic.REGISTERS.label,
            where = "Take 3, bars 5–8",
            quote = "Down and over, like going over a hill.",
            teacherName = teacher,
            checkedByTeacher = false,
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = "An example note: $teacher said, down and over, like going over a hill."
            },
        )
        Text(
            "A note like this is what $student practices with between lessons.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PerksPage(student: String, onNext: () -> Unit, onSkip: () -> Unit, onBack: () -> Unit) {
    Page(bottom = {
        Dots(Step.PERKS)
        StartButton("Sounds good", onClick = onNext, dot = false, modifier = Modifier.fillMaxWidth())
    }) {
        TopRow(onBack = onBack, label = null, onSkip = onSkip)
        Masthead("What's in it ", "for you", staff = false, modifier = Modifier.semantics { heading() })
        Perk(Icons.Filled.RecordVoiceOver, Topic.RESONANCE, "Your words, in your voice", "Each note plays back what you actually said, not a summary.")
        Perk(Icons.Filled.Draw, Topic.REGISTERS, "Sketches that stick", "Draw on paper as usual. $student snaps a photo and it lands on the right note.")
        Perk(Icons.AutoMirrored.Filled.ViewList, Topic.BREATH, "See what they practice", "Your dashboard shows each lesson and what $student is working on.")
        Perk(Icons.Filled.Edit, Topic.VOWELS, "You have the final say", "Fix, rewrite or delete any note from your lessons.")
    }
}

@Composable
private fun Perk(icon: ImageVector, tint: Topic, title: String, body: String) {
    val c = tint.colors()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.container), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = c.content, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AgreePage(
    student: String,
    teacher: String,
    mics: ChannelSwap.Checked?,
    recheckMics: Boolean,
    onRecheckMics: (Boolean) -> Unit,
    onBothIn: (posture: Boolean, keepOnPhone: Boolean) -> Unit, onCancel: () -> Unit, onBack: () -> Unit) {
    var studentIn by rememberSaveable { mutableStateOf(false) }
    var teacherIn by rememberSaveable { mutableStateOf(false) }
    var posture by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var keepOnPhone by rememberSaveable { mutableStateOf(false) }
    val m = MaterialTheme.colorScheme
    Page(bottom = {
        Dots(Step.AGREE)
        StartButton(
            if (posture) "Let's make music, with posture" else "Let's make music",
            onClick = { onBothIn(posture, keepOnPhone) },
            enabled = studentIn && teacherIn,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onCancel) { Text("Not today") }
    }) {
        TopRow(onBack = onBack, label = null)
        Masthead("You teach. ", "Cue Note takes the notes.", staff = false, modifier = Modifier.semantics { heading() })
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (keepOnPhone) {
                Promise("$student's phone records the lesson and keeps it. Cue Note doesn't send your voice, words or sketches to its servers or to an AI.")
                Promise("The phone turns the lesson into text and notes by itself, with a clip of you saying each one.")
                Promise("There's no dashboard for these lessons. Talk the notes over together instead.")
                Promise("Nothing records unless you both say yes, every time.")
            } else {
                Promise("$student's phone records the lesson and keeps the full recording.")
                Promise("Afterwards your feedback becomes cue cards, in your own words, with a clip of you saying it.")
                Promise("You see every card on your dashboard, and your edits always win.")
                Promise("Nothing records unless you both say yes, every time.")
            }
        }
        KeepOnPhoneSwitch(keepOnPhone) { keepOnPhone = it }
        Column {
            if (!keepOnPhone) Text(
                "Cards are drafted with the help of AI. Your lessons aren't used to train it.",
                style = MaterialTheme.typography.bodySmall,
                color = m.onSurfaceVariant,
            )
            Text(
                if (details) "Hide how it works" else "How it works",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = m.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { details = !details }
                    .heightIn(min = 48.dp)
                    .padding(vertical = 14.dp),
            )
            AnimatedVisibility(details) { if (keepOnPhone) LocalConsentText(student) else ConsentText() }
        }
        InRow(student, avatarColor(0), studentIn) { studentIn = it }
        InRow(teacher, avatarColor(1), teacherIn) { teacherIn = it }
        MicsRow(mics, recheckMics, onRecheckMics)
        PostureSwitch(posture) { posture = it }
    }
}

@Composable
private fun Promise(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp).padding(top = 2.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** One person's "I'm in": their name and a pill they tap themselves. */
@Composable
private fun InRow(name: String, color: Color, on: Boolean, onChange: (Boolean) -> Unit) {
    val m = MaterialTheme.colorScheme
    TicketRow(Modifier.toggleable(value = on, role = Role.Checkbox, onValueChange = onChange).semantics(mergeDescendants = true) {}) {
        Avatar(name, color)
        Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .then(if (on) Modifier.background(m.primary) else Modifier.border(1.5.dp, m.outline, RoundedCornerShape(50)))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (on) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = m.onPrimary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text("I'm in", style = MaterialTheme.typography.labelLarge, color = if (on) m.onPrimary else m.onSurface)
        }
    }
}

// ------------------------------------------------------------------ shared pieces

/** A page that scrolls, with its buttons held at the bottom, in reach of a thumb. */
@Composable
private fun Page(bottom: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = bottom,
        )
    }
}

@Composable
private fun TopRow(onBack: () -> Unit, label: String?, onSkip: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Spacer(Modifier.weight(1f))
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onSkip != null) TextButton(onClick = onSkip) { Text("Skip intro") }
    }
}

/** Which of the three welcome pages this is. */
@Composable
private fun Dots(at: Step) {
    val m = MaterialTheme.colorScheme
    val page = at.ordinal // HELLO is 1 of 3
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Page $page of 3" },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        for (i in 1..3) {
            Box(
                Modifier.height(6.dp).width(if (i == page) 18.dp else 6.dp).clip(RoundedCornerShape(50))
                    .background(if (i == page) m.primary else m.outlineVariant),
            )
        }
    }
}

@Composable
private fun Fold(title: String, content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val m = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(m.surfaceContainerLowest)
            .border(1.dp, m.outlineVariant, RoundedCornerShape(14.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { open = !open }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = m.onSurface)
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (open) "Hide" else "Show",
                tint = m.onSurface,
            )
        }
        AnimatedVisibility(open) { Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) { content() } }
    }
}

/** The full consent text, for anyone who wants every detail. */
@Composable
private fun ConsentText() {
    Text(ConsentStore.TEXT, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PickChip(name: String, color: Color?, selected: Boolean, initial: String? = null, locked: Boolean = false, onClick: () -> Unit) {
    val m = MaterialTheme.colorScheme
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .then(
                if (selected) Modifier.background(m.primaryContainer).border(1.5.dp, m.primary, RoundedCornerShape(50))
                else Modifier.background(m.surfaceContainerLowest).border(1.dp, m.outlineVariant, RoundedCornerShape(50)),
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(initial ?: if (color == null) "?" else name, color ?: m.outline)
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.labelLarge, color = if (selected) m.onPrimaryContainer else m.onSurface)
        if (locked) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.Lock, contentDescription = "Keeps lessons on this phone",
                tint = if (selected) m.onPrimaryContainer else m.onSurfaceVariant, modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun Avatar(name: String, color: Color) {
    Box(Modifier.size(32.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            color = Color.White,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        )
    }
}

/** Plum for the student, then rose and teal for teachers. All carry white text. */
private fun avatarColor(i: Int) = listOf(Plum, Color(0xFFA64D6C), Color(0xFF3E7A6B))[i % 3]

private val POSTURE_TEXT = """
    The phone's camera watches the student while they sing, and works out body points (head, shoulders, hips) on the phone. No video is saved. A still photo is kept when it flags chin forward, raised shoulders or a head tilt. Only the student is tracked, though the teacher can appear in a flagged photo if they're in view. All of it stays on this phone.
""".trimIndent()
