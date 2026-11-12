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
import com.cuenote.data.ConsentStore
import com.cuenote.data.InvitedTeacher
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
 * With more than one teacher, the student also says who is teaching today,
 * so each teacher can be shown only their own lessons. [teachers] are the
 * active ones; with one, they're picked without asking.
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
    onConsented: (consentAt: Long, teacherId: String?, posture: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var names by remember(store) { mutableStateOf(store.firstSetup()?.let { it.studentName to it.teacherName }) }
    // Carried from the welcome to the who's-teaching page, when that follows.
    var welcomePosture by rememberSaveable { mutableStateOf(false) }
    val choices = teachers.filter { it.active && it.teacherId != null }
    var picked by rememberSaveable(choices.map { it.teacherId }) {
        mutableStateOf(
            when {
                choices.size == 1 -> choices[0].teacherId
                choices.any { it.teacherId == lastPicked } -> lastPicked
                else -> null
            },
        )
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val n = names
        if (n == null) {
            TeacherWelcome(
                knownTeacher = choices.singleOrNull()?.teacherName?.takeIf { it.isNotBlank() },
                onBothIn = { student, teacher, posture ->
                    store.saveFirstSetup(student, teacher)
                    // Both just said yes. Only ask who's teaching when that's unclear.
                    if (choices.size > 1) { welcomePosture = posture; names = student to teacher }
                    else onConsented(System.currentTimeMillis(), picked, posture)
                },
                onCancel = onCancel,
            )
        } else {
            EveryLesson(
                student = n.first,
                teacher = n.second,
                choices = choices,
                picked = picked,
                onPick = { picked = it },
                initialPosture = welcomePosture,
                onConsented = { posture -> onConsented(System.currentTimeMillis(), picked, posture) },
                onCancel = onCancel,
            )
        }
    }
}

// ------------------------------------------------------------------ every lesson

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EveryLesson(
    student: String,
    teacher: String,
    choices: List<InvitedTeacher>,
    picked: String?,
    onPick: (String?) -> Unit,
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
        Text("$student and $teacher, nothing records until you both say yes.", style = MaterialTheme.typography.bodyLarge)
        if (choices.size > 1) {
            Text("Who's teaching today?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEachIndexed { i, t ->
                    PickChip(t.title, avatarColor(i + 1), picked == t.teacherId) { onPick(t.teacherId) }
                }
                PickChip("Not sure", null, picked == null) { onPick(null) }
            }
        }
        PostureSwitch(posture) { posture = it }
        Fold("What gets recorded?") { ConsentText() }
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
    onBothIn: (student: String, teacher: String, posture: Boolean) -> Unit,
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
        Step.AGREE -> AgreePage(s, t, onBothIn = { posture -> onBothIn(s, t, posture) }, onCancel = onCancel, onBack = { go(Step.PERKS) })
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
private fun AgreePage(student: String, teacher: String, onBothIn: (posture: Boolean) -> Unit, onCancel: () -> Unit, onBack: () -> Unit) {
    var studentIn by rememberSaveable { mutableStateOf(false) }
    var teacherIn by rememberSaveable { mutableStateOf(false) }
    var posture by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    val m = MaterialTheme.colorScheme
    Page(bottom = {
        Dots(Step.AGREE)
        StartButton(
            if (posture) "Let's make music, with posture" else "Let's make music",
            onClick = { onBothIn(posture) },
            enabled = studentIn && teacherIn,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onCancel) { Text("Not today") }
    }) {
        TopRow(onBack = onBack, label = null)
        Masthead("You teach. ", "Cue Note takes the notes.", staff = false, modifier = Modifier.semantics { heading() })
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Promise("$student's phone records the lesson and keeps the full recording.")
            Promise("Afterwards your feedback becomes cue cards, in your own words, with a clip of you saying it.")
            Promise("You see every card on your dashboard, and your edits always win.")
            Promise("Nothing records unless you both say yes, every time.")
        }
        Column {
            Text(
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
            AnimatedVisibility(details) { ConsentText() }
        }
        InRow(student, avatarColor(0), studentIn) { studentIn = it }
        InRow(teacher, avatarColor(1), teacherIn) { teacherIn = it }
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
private fun PickChip(name: String, color: Color?, selected: Boolean, onClick: () -> Unit) {
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
        Avatar(if (color == null) "?" else name, color ?: m.outline)
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.labelLarge, color = if (selected) m.onPrimaryContainer else m.onSurface)
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
