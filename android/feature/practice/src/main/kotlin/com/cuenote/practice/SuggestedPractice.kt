package com.cuenote.practice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cuenote.data.PracticePlanEntity
import com.cuenote.data.PracticePlans
import com.cuenote.data.PracticeRepository
import com.cuenote.data.SetlistRow
import com.cuenote.pipeline.practice.PlanStep
import com.cuenote.pipeline.practice.PracticePlan
import com.cuenote.pipeline.practice.StepKind
import com.cuenote.pipeline.practice.Voice
import com.cuenote.ui.Masthead
import com.cuenote.ui.NoteCard
import com.cuenote.ui.StartButton
import com.cuenote.ui.Topic
import com.cuenote.ui.rememberClipPlayer
import kotlinx.coroutines.launch

/*
 * Suggested practice: the teacher's own "practice this" notes, put in an
 * order for today. A check-in on the setlist (voice, then time), the plan,
 * then one step at a time on the practice screen, a "Still feel easy?" check
 * once about halfway, and a neutral end. The app never adds homework; its
 * own reasons are small and say they're the app's.
 */

/** The gentle start and cool-down: fixed items the plan can't cut. Words the teacher can change come later. */
const val GENTLE_START = "Hums on \"mm\", in the middle of your range. Easy and quiet; nothing high yet."
const val COOL_DOWN = "Gentle slides down on a hum or a lip trill, soft and slow. Then drink some water."

/** The words of a gentle start or cool-down: the teacher's, when she wrote some. */
fun fixedText(s: PlanStep): String = s.hint ?: if (s.kind == StepKind.GENTLE_START) GENTLE_START else COOL_DOWN

/** A step of today's plan, as the practice screen shows it. */
data class PlanStepUi(
    val index: Int,
    val total: Int,
    val title: String,
    val hint: String?,
    val nextLabel: String,
    val onSkip: (() -> Unit)?,
    val onNext: () -> Unit,
    val onEnd: () -> Unit,
)

/** A step's name for the bar and the list. */
fun stepTitle(s: PlanStep, cue: String?, teacher: String): String = when (s.kind) {
    StepKind.GENTLE_START -> "Gentle start"
    StepKind.COOL_DOWN -> "Cool-down"
    StepKind.WARMUP -> s.title ?: "$teacher's warmup"
    StepKind.SONG -> if (s.listenOnly) "Follow along in the song" else "Put it in the song"
    StepKind.DRILL -> if (s.pinned) "Main point" else cue?.takeIf { it.isNotBlank() } ?: "Your teacher's note"
}

/** The step bar and the step's name, big enough to read from a music stand. */
@Composable
internal fun StepHeader(step: PlanStepUi) {
    val m = MaterialTheme.colorScheme
    Column(
        Modifier.semantics(mergeDescendants = true) { contentDescription = "Step ${step.index + 1} of ${step.total}: ${step.title}" },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(step.total) { i ->
                Box(
                    Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (i <= step.index) m.primary.copy(alpha = if (i == step.index) 0.55f else 1f) else m.outlineVariant),
                )
            }
        }
        Text("Step ${step.index + 1} of ${step.total} · ${step.title}", style = MaterialTheme.typography.titleLarge)
        step.hint?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = m.onSurfaceVariant) }
    }
}

/** Skip beside a full-width Next, under the main button. */
@Composable
internal fun StepButtons(step: PlanStepUi, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        step.onSkip?.let { OutlinedButton(onClick = it, modifier = Modifier.defaultMinSize(minHeight = 56.dp)) { Text("Skip") } }
        FilledTonalButton(onClick = step.onNext, modifier = Modifier.weight(1f).defaultMinSize(minHeight = 56.dp)) {
            Text(step.nextLabel, maxLines = 1)
        }
    }
}

// ---- A: the check-in on the setlist ----

/**
 * Today's practice, at the top of the setlist: how the voice feels (nothing
 * picked for the student), then how long. Picks up a sitting left halfway
 * within the hour, and says "Done today" after one.
 */
@Composable
fun TodayCard(
    plans: PracticePlans,
    teacher: String?,
    onStart: (Voice, Int) -> Unit,
    onResume: (planId: String) -> Unit,
) {
    val today by remember { plans.today() }.collectAsStateWithLifecycle(null)
    val set by (plans.settings?.settings ?: remember { kotlinx.coroutines.flow.MutableStateFlow(com.cuenote.data.PlanSettings()) })
        .collectAsStateWithLifecycle()
    val maxMinutes = set.maxMinutes ?: PracticePlan.DEFAULT_MAX
    val resting = set.resting(PracticePlans.day(System.currentTimeMillis()))
    val m = MaterialTheme.colorScheme
    var voice by rememberSaveable { mutableStateOf<Voice?>(null) }
    val lengths = PracticePlan.lengths(maxMinutes)
    var minutes by rememberSaveable { mutableStateOf(lengths.last()) }
    var again by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val t = today
    val fresh = t != null && t.endedAt == null && System.currentTimeMillis() - t.updatedAt < 3_600_000L
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(m.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("TODAY'S PRACTICE", style = MaterialTheme.typography.labelMedium, color = m.primary, fontWeight = FontWeight.SemiBold)
        when {
            t != null && fresh -> {
                val total = plans.steps(t).size
                Text("Pick up at ${t.stepIndex + 1} of $total", style = MaterialTheme.typography.titleLarge)
                StartButton("Keep going", { onResume(t.id) }, Modifier.fillMaxWidth())
                TextButton(onClick = { again = true; scope.launch { plans.end(t.id) } }) { Text("Start over") }
            }
            t != null && t.endedAt != null && !again -> {
                val total = plans.steps(t).size
                val mins = (((t.endedAt ?: t.updatedAt) - t.startedAt) / 60_000).coerceAtLeast(1)
                Text("Done today", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (t.voice == Voice.HOARSE.name) "You listened instead of singing. Rest your voice."
                    else "You practiced ${t.done} of $total, for $mins minutes.",
                    color = m.onSurfaceVariant,
                )
                TextButton(onClick = { again = true }) { Text("Practice again") }
            }
            resting -> {
                Text("Rest your voice", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${teacher ?: "Your teacher"} asked you to rest until ${restDate(set.restUntil!!)}. Listening still counts.",
                    color = m.onSurfaceVariant,
                )
                StartButton("Listen instead", { again = false; onStart(Voice.HOARSE, PracticePlan.LENGTHS.first()) }, Modifier.fillMaxWidth())
            }
            else -> {
                Text("How's your voice today?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                // One height for all three: when "Hoarse or sore" wraps, the others grow with it.
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    val opts = listOf(Voice.FINE to "Fine", Voice.TIRED to "A bit tired", Voice.HOARSE to "Hoarse or sore")
                    opts.forEachIndexed { i, (v, label) ->
                        SegmentedButton(
                            selected = voice == v, onClick = { voice = v },
                            shape = SegmentedButtonDefaults.itemShape(i, opts.size),
                            icon = {},
                            modifier = Modifier.fillMaxHeight(),
                        ) { Text(label, maxLines = 2, textAlign = TextAlign.Center) }
                    }
                }
                if (voice != Voice.HOARSE) {
                    Text("How long have you got?", style = MaterialTheme.typography.titleSmall)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        lengths.forEachIndexed { i, n ->
                            SegmentedButton(
                                selected = minutes == n, onClick = { minutes = n },
                                shape = SegmentedButtonDefaults.itemShape(i, lengths.size),
                                icon = {},
                            ) { Text("$n min") }
                        }
                    }
                    if (voice == Voice.TIRED) Text("A tired voice gets an easy ${PracticePlan.TIRED_MINUTES} minutes in the middle of your range.", color = m.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                StartButton(
                    if (voice == Voice.HOARSE) "Listen instead" else "Start today's practice",
                    { voice?.let { v -> again = false; onStart(v, minutes) } },
                    Modifier.fillMaxWidth(), enabled = voice != null,
                )
                if (voice == null) {
                    Text(
                        "Pick how your voice feels first. ${teacher?.let { "$it's" } ?: "Your teacher's"} notes, in an order for today.",
                        color = m.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}


private fun restDate(day: String): String = runCatching {
    val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(day)!!
    java.text.SimpleDateFormat("EEEE, MMM d", java.util.Locale.getDefault()).format(d)
}.getOrDefault(day)

// ---- B, C, F: the plan ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    planId: String,
    plans: PracticePlans,
    repo: PracticeRepository,
    teacherName: (lessonId: String) -> String?,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    var plan by remember { mutableStateOf<PracticePlanEntity?>(null) }
    androidx.compose.runtime.LaunchedEffect(planId) { plan = plans.get(planId) }
    val p = plan
    val steps = p?.let { plans.steps(it) }.orEmpty()
    val voice = p?.voice?.let { runCatching { Voice.valueOf(it) }.getOrNull() } ?: Voice.FINE
    val m = MaterialTheme.colorScheme
    Scaffold(
        topBar = { TopAppBar(title = { Text("Today's practice") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) },
        bottomBar = {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                StartButton(if (voice == Voice.HOARSE) "Start listening" else "Start", onStart, Modifier.fillMaxWidth(), enabled = steps.isNotEmpty())
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "mast") {
                when (voice) {
                    Voice.HOARSE -> Masthead("Rest your ", "voice")
                    Voice.TIRED -> Masthead("An easy ", "${steps.sumOf { it.minutes }} minutes")
                    Voice.FINE -> Masthead("", "${p?.minutes ?: 0} minutes")
                }
            }
            item(key = "intro") {
                Text(
                    when (voice) {
                        Voice.HOARSE -> "No singing today, and don't whisper along: whispering strains it too. Listening still counts."
                        Voice.TIRED -> "Your voice is a bit tired, so today stays in the middle of your range. Nothing high or loud."
                        Voice.FINE -> "Your teacher's notes, in an order for today. Skip any step as you go."
                    },
                    style = MaterialTheme.typography.bodyMedium, color = m.onSurfaceVariant,
                )
            }
            if (steps.isEmpty() && p != null) {
                item(key = "none") { Text("No notes to listen to yet. They come from your lessons.", color = m.onSurfaceVariant) }
            }
            itemsIndexed(steps, key = { i, _ -> "s$i" }) { i, s -> PlanRow(i, s, repo, teacherName) }
            if (voice == Voice.HOARSE) {
                item(key = "doc") {
                    Text(
                        "Your teacher will see \"voice hoarse or sore\" in your practice log. If it hurts, your voice goes suddenly, " +
                            "or hoarseness lasts over two weeks, see a doctor, ideally an ENT.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(m.secondaryContainer).padding(12.dp),
                        color = m.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanRow(i: Int, s: PlanStep, repo: PracticeRepository, teacherName: (String) -> String?) {
    val m = MaterialTheme.colorScheme
    val row by (s.cardId?.let { repo.card(it) } ?: remember { kotlinx.coroutines.flow.flowOf(null) }).collectAsStateWithLifecycle(null)
    val teacher = row?.card?.lessonId?.let(teacherName) ?: "Your teacher"
    val fixed = s.kind == StepKind.GENTLE_START || s.kind == StepKind.COOL_DOWN
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (fixed) m.surfaceContainerHigh else m.surface)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(28.dp).border(1.5.dp, m.outline, CircleShape), contentAlignment = Alignment.Center) {
            Text("${i + 1}", style = MaterialTheme.typography.labelLarge)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            when (s.kind) {
                StepKind.GENTLE_START, StepKind.COOL_DOWN -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stepTitle(s, null, teacher), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Always", style = MaterialTheme.typography.labelSmall, color = m.primary,
                            modifier = Modifier.border(1.dp, m.primary, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 1.dp),
                        )
                    }
                    Text(fixedText(s), style = MaterialTheme.typography.bodySmall, color = m.onSurfaceVariant)
                }
                StepKind.WARMUP, StepKind.SONG -> {
                    Text(stepTitle(s, null, teacher), style = MaterialTheme.typography.titleSmall)
                    if (s.kind == StepKind.WARMUP) Text("$teacher's warmup", style = MaterialTheme.typography.labelMedium, color = m.tertiary)
                    (s.title?.takeIf { s.kind == StepKind.SONG })?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = m.onSurfaceVariant) }
                    s.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = m.onSurfaceVariant) }
                }
                StepKind.DRILL -> {
                    val c = row?.card
                    Text("“${c?.herWords?.ifBlank { c.cue } ?: "…"}”", style = com.cuenote.ui.quoteStyle(), maxLines = 3)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(teacher, style = MaterialTheme.typography.labelMedium, color = m.tertiary)
                        if (s.pinned) {
                            Spacer(Modifier.width(6.dp))
                            Text("Main point", style = MaterialTheme.typography.labelSmall, color = m.tertiary,
                                modifier = Modifier.border(1.dp, m.tertiary, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 1.dp))
                        }
                    }
                    s.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = m.onSurfaceVariant) }
                    s.why?.let { Text("App's pick: $it", style = MaterialTheme.typography.bodySmall, color = m.onSurfaceVariant) }
                }
            }
        }
        Text("${s.minutes} min", style = MaterialTheme.typography.labelMedium, color = m.onSurfaceVariant)
    }
}

// ---- D, E, G: one step at a time, the halfway check, the end ----

/**
 * Runs a sitting: the current step on the practice screen (or a short text
 * step for the gentle start and cool-down), the halfway check once, and the
 * end. Each Next or Skip is saved, so the sitting can be picked up later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanRunScreen(
    planId: String,
    plans: PracticePlans,
    repo: PracticeRepository,
    teacherName: (lessonId: String) -> String?,
    lessonRecording: Boolean,
    sharing: Boolean,
    posture: TakePosture?,
    onFraming: () -> Unit,
    onTimingCheck: () -> Unit,
    onShare: (cardId: String) -> Unit,
    onDone: () -> Unit,
) {
    var plan by remember { mutableStateOf<PracticePlanEntity?>(null) }
    androidx.compose.runtime.LaunchedEffect(planId) { plan = plans.get(planId) }
    val scope = rememberCoroutineScope()
    var confirmEnd by remember { mutableStateOf(false) }
    val p = plan ?: return
    val steps = plans.steps(p)
    val i = p.stepIndex
    if (p.endedAt != null || i >= steps.size) {
        EndScreen(p, steps.size, teacherName, repo, steps, onShare, onDone)
        return
    }
    val s = steps[i]
    val voice = runCatching { Voice.valueOf(p.voice) }.getOrDefault(Voice.FINE)
    val check = PracticePlan.easyCheckBefore(com.cuenote.pipeline.practice.Plan(voice, p.minutes, steps))
    val row by (s.cardId?.let { repo.card(it) } ?: remember(i) { kotlinx.coroutines.flow.flowOf(null) }).collectAsStateWithLifecycle(null)
    val teacher = row?.card?.lessonId?.let(teacherName) ?: "your teacher"
    val nextTitle = steps.getOrNull(i + 1)?.let { n ->
        if (n.cardId == null || n.kind != StepKind.DRILL) stepTitle(n, null, teacher) else "next note"
    }
    fun go(skip: Boolean) = scope.launch { plan = plans.advance(p.id, i, skip) }
    val ui = PlanStepUi(
        index = i, total = steps.size,
        title = stepTitle(s, row?.card?.cue, teacher.replaceFirstChar { it.uppercase() }),
        hint = if (s.cardId == null) null else s.hint,
        nextLabel = nextTitle?.let { "Next: $it" } ?: "Finish",
        onSkip = if (s.kind == StepKind.COOL_DOWN || s.kind == StepKind.GENTLE_START) null else ({ go(true) }),
        onNext = { go(false) },
        onEnd = { confirmEnd = true },
    )
    key(i) {
        if (s.cardId == null) TextStep(ui, s) { confirmEnd = true }
        else PracticeScreen(
            cardId = s.cardId!!, repo = repo, teacherName = teacherName, lessonRecording = lessonRecording,
            sharing = sharing, posture = posture, onFraming = onFraming, onTimingCheck = onTimingCheck,
            onBack = { confirmEnd = true }, step = ui, listenOnly = s.listenOnly,
        )
    }

    if (check == i && !p.easyChecked) {
        EasySheet(
            onEasy = { scope.launch { plan = plans.easyChecked(p.id, toCoolDown = false) } },
            onTired = { scope.launch { plan = plans.easyChecked(p.id, toCoolDown = true) } },
        )
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End today's practice?") },
            text = { Text("What you've done is kept. A cool-down first is kinder to your voice.") },
            confirmButton = { TextButton(onClick = { confirmEnd = false; scope.launch { plans.end(p.id); plan = plans.get(p.id) } }) { Text("End") } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep going") } },
        )
    }
}

/** The gentle start and the cool-down: what to do, for how long, and Next. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextStep(step: PlanStepUi, s: PlanStep, onEnd: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Today's practice") },
                navigationIcon = { TextButton(onClick = onEnd) { Text("Back") } },
                actions = { TextButton(onClick = onEnd) { Text("End", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
            )
        },
        bottomBar = { StepButtons(step) },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StepHeader(step)
            Text(fixedText(s), style = MaterialTheme.typography.headlineSmall)
            Text("About ${s.minutes} minutes.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * "Still feel easy?", once, between steps, never while recording. It can't
 * be swiped away, so a stray tap doesn't count as "keep going". A bit tired
 * goes to the cool-down, never to the song.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EasySheet(onEasy: () -> Unit, onTired: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { false })
    ModalBottomSheet(onDismissRequest = {}, sheetState = state, dragHandle = null) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Still feel easy?", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text("Stop and rest if you notice any of these:", style = MaterialTheme.typography.bodyLarge)
            Signs()
            StartButton("Feels easy, keep going", onEasy, Modifier.fillMaxWidth(), dot = false)
            OutlinedButton(onClick = onTired, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp)) {
                Text("A bit tired: go to the cool-down")
            }
        }
    }
}

@Composable
private fun ColumnScope.Signs() {
    listOf(
        "needing to clear your throat", "a tickle or dryness", "top notes getting smaller, or more cracks",
        "a breathy sound", "feeling it in your throat, not your body",
    ).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** What was done, never what was missed. No streak. */
@Composable
private fun EndScreen(
    p: PracticePlanEntity,
    total: Int,
    teacherName: (String) -> String?,
    repo: PracticeRepository,
    steps: List<PlanStep>,
    onShare: (String) -> Unit,
    onDone: () -> Unit,
) {
    val mins = (((p.endedAt ?: System.currentTimeMillis()) - p.startedAt) / 60_000).coerceAtLeast(1)
    val listening = p.voice == Voice.HOARSE.name
    val firstCard = steps.firstOrNull { it.cardId != null && !it.listenOnly }?.cardId
    val row by (firstCard?.let { repo.card(it) } ?: remember { kotlinx.coroutines.flow.flowOf(null) }).collectAsStateWithLifecycle(null)
    val teacher = row?.card?.lessonId?.let(teacherName)
    Scaffold(
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                StartButton("Done", onDone, Modifier.fillMaxWidth(), dot = false)
                if (teacher != null && firstCard != null) TextButton(onClick = { onShare(firstCard) }) { Text("Send $teacher a take") }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Masthead("Nice ", "work", staff = false)
            Text(
                if (listening) "You listened for $mins minutes. Resting was the right call."
                else "You practiced ${p.done} of $total, for $mins minutes.",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "Drink some water. ${teacher ?: "Your teacher"} will see today in your practice log.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
