package com.cuenote.record

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cuenote.signal.MicVerdict
import com.cuenote.signal.StepResult
import com.cuenote.signal.StereoLevels
import com.cuenote.signal.posture.Voice
import com.cuenote.ui.Cue
import com.cuenote.ui.Eyebrow
import com.cuenote.ui.Fraunces
import com.cuenote.ui.LevelRow
import com.cuenote.ui.Masthead
import com.cuenote.ui.Stamp
import com.cuenote.ui.StartButton
import com.cuenote.ui.TicketRow
import com.cuenote.ui.rememberReducedMotion

/**
 * The soundcheck, in the Setlist style: a ticket row for the mic and
 * battery, one "Now" card for the current step (a line to sing or say, a big
 * countdown, the two mics' bars), and short rows for what's done and next.
 * After the first Start it runs by itself; only a miss waits for a tap.
 */
@Composable
fun SetupCheckScreen(onDone: () -> Unit, vm: SetupCheckViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    // Kept as a State: the bars read it every frame without recomposing the screen.
    val live = vm.liveLevels.collectAsStateWithLifecycle()
    SetupCheckContent(
        s, live, onDone,
        onStart = vm::runStep, onRetry = vm::retry, onSkipPiano = vm::skipPiano,
        onQuietCheck = vm::setQuietCheck, onSwitchChannels = vm::switchChannelsManually,
        onSpoken = vm::setSpoken, onVoice = vm::setVoice,
    )
}

/** The soundcheck itself, from its state alone (so it can be previewed). */
@Composable
fun SetupCheckContent(
    s: SetupUiState,
    live: State<StereoLevels?>,
    onDone: () -> Unit,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onSkipPiano: () -> Unit,
    onQuietCheck: (Boolean) -> Unit,
    onSwitchChannels: () -> Unit,
    onSpoken: (Boolean) -> Unit = {},
    onVoice: (Voice) -> Unit = {},
) {
    val m = MaterialTheme.colorScheme
    val total = SetupStep.entries.size

    Surface(Modifier.fillMaxSize(), color = m.background) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Spacer(Modifier.weight(1f))
            Text(
                if (s.done) "All $total done" else "Step ${s.step.ordinal + 1} of $total",
                color = m.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Masthead("Sound", "check")

        MicRow(s)
        if (s.batteryLow) {
            TicketRow {
                Icon(Icons.Filled.BatteryAlert, contentDescription = null, tint = m.error)
                Text(
                    "Battery's at ${s.batteryPercent}%. Pop it on the charger, as a full lesson needs about half.",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        s.error?.let { err ->
            TicketRow {
                Icon(Icons.Filled.MicOff, contentDescription = null, tint = m.error)
                Column(Modifier.weight(1f)) {
                    Text("We couldn't open the mic", style = MaterialTheme.typography.titleSmall)
                    Text(err, color = m.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            return@Column
        }
        if (s.switchedThisCheck) {
            TicketRow {
                Icon(Icons.Filled.SwapHoriz, contentDescription = null, tint = m.primary)
                Column(Modifier.weight(1f)) {
                    Text("Swapped for you, nobody needs to move", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "The student is on the right mic, so the app switched the channels. The teacher step checks it worked.",
                        color = m.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Before the first Start, the voice choice sits up top where it's seen.
        val notStarted = !s.done && s.phase == StepPhase.IDLE && s.step == SetupStep.STUDENT_SPEAK
        if (notStarted) SpokenRow(s, onSpoken, onVoice)

        DoneRow(s)

        if (s.done) {
            WrapCard(s.verdict, onDone)
        } else {
            val reduced = rememberReducedMotion()
            if (reduced) {
                NowCard(s, s.step, live, onStart, onRetry)
            } else {
                AnimatedContent(
                    targetState = s.step,
                    transitionSpec = {
                        (fadeIn(tween(350, delayMillis = 200)) + slideInVertically(tween(500)) { it / 6 })
                            .togetherWith(fadeOut(tween(200)))
                    },
                    label = "step",
                ) { step -> NowCard(s, step, live, onStart, onRetry) }
            }
            UpNext(s.step)
            if (s.step.ordinal >= SetupStep.PIANO_ALONE.ordinal && s.phase != StepPhase.LISTENING) {
                TextButton(onClick = onSkipPiano) { Text("No piano today? Skip this one") }
            }
        }

        MicTip()

        if (!notStarted) SpokenRow(s, onSpoken, onVoice)

        TicketRow {
            Column(Modifier.weight(1f)) {
                Text("Quiet soundcheck", style = MaterialTheme.typography.titleSmall)
                Text("No chime between steps", color = m.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = s.quietCheck, onCheckedChange = onQuietCheck)
        }

        TextButton(
            onClick = onSwitchChannels,
            enabled = s.phase == StepPhase.IDLE || s.phase == StepPhase.FAILED || s.done,
        ) { Text("Switch student and teacher channels") }
        if (!s.done) {
            TextButton(onClick = onDone) { Text("Skip the check for today") }
        }
        Spacer(Modifier.height(8.dp))
    }
    }
}

@Composable
private fun MicRow(s: SetupUiState) {
    val m = MaterialTheme.colorScheme
    val mic = s.mic
    TicketRow {
        Icon(if (mic == null) Icons.Filled.MicOff else Icons.Filled.Mic, contentDescription = null, tint = if (mic == null) m.error else m.primary)
        Column(Modifier.weight(1f)) {
            when {
                mic == null -> {
                    Text("Can't hear the mic yet", style = MaterialTheme.typography.titleSmall)
                    Text("Plug the Lark receiver into the phone's USB-C port.", color = m.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                !mic.supportsStereo -> {
                    Text("Mic in, battery ${s.batteryPercent}%", style = MaterialTheme.typography.titleSmall)
                    Text("${mic.name} only reports one channel. The check will tell us for sure.", color = m.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    Text("Mic in, battery ${s.batteryPercent}%", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${mic.name}, ready for the lesson" + if (s.routedTo.isNotBlank() && s.routedTo != mic.name) " (via ${s.routedTo})" else "",
                        color = m.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (mic != null && mic.supportsStereo && !s.batteryLow) Stamp("OK", m.primary)
    }
}

/** The steps already passed, folded into one short row. */
@Composable
private fun DoneRow(s: SetupUiState) {
    val done = SetupStep.entries.filter { it.ordinal < s.step.ordinal || s.done }
    if (done.isEmpty()) return
    val m = MaterialTheme.colorScheme
    TicketRow {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = m.primary)
        Column(Modifier.weight(1f)) {
            Text(if (done.size == 1) "Done: ${done[0].title}" else "${done.size} steps done", style = MaterialTheme.typography.titleSmall)
            if (done.size > 1) {
                Text(done.joinToString(" · ") { it.title }, color = m.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
        Stamp("PASS", m.primary)
    }
}

/** The current step: what to sing or say, the countdown, and both mics. */
@Composable
private fun NowCard(
    s: SetupUiState,
    step: SetupStep,
    live: State<StereoLevels?>,
    onStart: () -> Unit,
    onRetry: () -> Unit,
) {
    val x = Cue.extras
    val reduced = rememberReducedMotion()
    // Once a step has moved on, the outgoing copy of the card just shows as it was.
    val phase = if (step == s.step) s.phase else StepPhase.PASSED
    val failed = (s.results[step] as? StepResult.Fail).takeIf { phase == StepPhase.FAILED }
    val warn = if (x.dark) Color(0xFFB3261E) else Color(0xFFFFB4AB)
    val listening = phase == StepPhase.LISTENING
    val left = s.secondsLeft.takeIf { listening && step == s.step }
    val strip by animateFloatAsState(
        targetValue = when {
            left != null -> (left - 1).coerceAtLeast(0) / step.seconds.toFloat()
            phase == StepPhase.GET_READY || phase == StepPhase.IDLE -> 1f
            else -> 0f
        },
        animationSpec = if (reduced || !listening) snap() else tween(1_000, easing = LinearEasing),
        label = "strip",
    )
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(x.nowCard)
            .then(if (failed != null) Modifier.border(2.dp, warn, shape) else Modifier)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The time left in this step, emptying as it listens.
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(x.nowTrack)) {
            Box(Modifier.fillMaxWidth(strip).height(4.dp).background(x.nowMuted))
        }
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                val lead = when (phase) {
                    StepPhase.IDLE -> "Ready when you are"
                    StepPhase.GET_READY -> "Get ready"
                    StepPhase.LISTENING -> "Listening"
                    StepPhase.PASSED -> "Nice"
                    StepPhase.FAILED -> "Not quite"
                }
                if (step.verb != null) {
                    // A Do · Re · Mi step: whose turn it is, big, in their colour.
                    Eyebrow(lead, x.nowAccent)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${step.who}'s turn",
                        color = roleColor(step),
                        fontFamily = Fraunces,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 28.sp,
                        lineHeight = 32.sp,
                    )
                    Text(step.verb, color = x.onNowCard, style = MaterialTheme.typography.titleMedium)
                } else {
                    Eyebrow("$lead · ${step.who}", x.nowAccent)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        step.prompt,
                        color = x.onNowCard,
                        fontFamily = Fraunces,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 21.sp,
                        lineHeight = 25.sp,
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp), contentAlignment = Alignment.Center) {
                when {
                    // Kept small: the time is a backstop, not the point.
                    left != null -> Text(
                        "$left",
                        color = x.nowMuted,
                        fontFamily = Fraunces,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 28.sp,
                        lineHeight = 28.sp,
                        modifier = Modifier.semantics { contentDescription = "$left seconds left" },
                    )
                    phase == StepPhase.PASSED -> Stamp("GOT IT", x.nowAccent, landing = step == s.step)
                    phase == StepPhase.FAILED -> Stamp("AGAIN", warn, landing = true)
                }
            }
        }
        if (step.verb != null) {
            val heard = when {
                step != s.step || phase == StepPhase.PASSED -> Solfege.SYLLABLES.size
                listening || phase == StepPhase.FAILED -> s.syllables
                else -> 0
            }
            DoReMi(heard, roleColor(step), x.onNowCard)
            Text(
                when {
                    listening && s.wrongMic && step == s.step -> "I heard that on the student's mic. Check the mics aren't swapped."
                    else -> step.hint.orEmpty()
                },
                color = if (listening && s.wrongMic) x.onNowCard else x.nowMuted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LevelRow(
                "Student",
                { live.value?.student?.rmsDb },
                expectQuiet = listening && step.quiet == SetupStep.Who.STUDENT,
                track = x.nowTrack, fill = x.nowAccent, text = x.onNowCard, muted = x.nowMuted, accent = x.nowAccent, warn = warn,
            )
            LevelRow(
                "Teacher",
                { live.value?.teacher?.rmsDb },
                expectQuiet = listening && step.quiet == SetupStep.Who.TEACHER,
                track = x.nowTrack, fill = x.nowFill, text = x.onNowCard, muted = x.nowMuted, accent = x.nowAccent, warn = warn,
            )
        }
        when {
            phase == StepPhase.IDLE -> StartButton(
                if (step == SetupStep.STUDENT_SPEAK) "Start soundcheck" else "Carry on",
                onStart,
                Modifier.fillMaxWidth(),
            )
            failed != null -> {
                Text(failed.detail, color = x.onNowCard, style = MaterialTheme.typography.bodyMedium)
                StartButton("Try again", onRetry, Modifier.fillMaxWidth())
            }
            else -> Text(
                "Hands off the phone. It moves on as soon as it has heard enough.",
                color = x.nowMuted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** Read the steps aloud, and in whose voice. */
@Composable
private fun SpokenRow(s: SetupUiState, onSpoken: (Boolean) -> Unit, onVoice: (Voice) -> Unit) {
    val m = MaterialTheme.colorScheme
    TicketRow {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = m.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Read the steps aloud", style = MaterialTheme.typography.titleSmall)
            Text(
                "A voice says what to do at each step, so nobody has to watch the screen.",
                color = m.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (s.spoken) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Voice.entries.forEach { v ->
                        FilterChip(selected = s.voice == v, onClick = { onVoice(v) }, label = { Text(v.label) })
                    }
                }
            }
        }
        Switch(checked = s.spoken, onCheckedChange = onSpoken)
    }
}

/** Brass for the student, lilac (plum in the light theme) for the teacher. */
@Composable
private fun roleColor(step: SetupStep): Color =
    if (step.who == "Teacher") Cue.extras.nowFill else Cue.extras.nowAccent

/** The end of the check, with which mic it judged to be whose. */
@Composable
private fun WrapCard(verdict: MicVerdict?, onDone: () -> Unit) {
    val x = Cue.extras
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(x.nowCard).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow("All set", x.nowAccent)
                Spacer(Modifier.height(4.dp))
                Text(
                    "That's a wrap on soundcheck.",
                    color = x.onNowCard,
                    fontFamily = Fraunces,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 21.sp,
                    lineHeight = 25.sp,
                )
                Text("Both voices are on their own channels.", color = x.nowMuted, style = MaterialTheme.typography.bodyMedium)
            }
            Stamp("OK", x.nowAccent, landing = true)
        }
        if (verdict != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RoleTile("Left mic", if (verdict.studentOnRight) "Teacher" else "Student", Modifier.weight(1f))
                RoleTile("Right mic", if (verdict.studentOnRight) "Student" else "Teacher", Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfidenceDots(verdict.confidence, x.nowAccent, x.nowTrack)
                Text(
                    confidenceLine(verdict),
                    color = x.onNowCard,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "Your lesson uses this, so it won't ask again. You can still change it before you record.",
                color = x.nowMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        StartButton("Back to the lesson", onDone, Modifier.fillMaxWidth())
    }
}

/** One mic and the person the check heard on it. */
@Composable
private fun RoleTile(mic: String, who: String, modifier: Modifier) {
    val x = Cue.extras
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(x.nowTrack).padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$mic: $who" },
    ) {
        Text(mic, color = x.nowMuted, style = MaterialTheme.typography.labelMedium)
        Text(who, color = x.onNowCard, fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
    }
}

/** Three dots, filled for how sure the check is. Paired with words, never alone. */
@Composable
private fun ConfidenceDots(c: MicVerdict.Confidence, on: Color, off: Color) {
    val filled = when (c) {
        MicVerdict.Confidence.HIGH -> 3
        MicVerdict.Confidence.MEDIUM -> 2
        MicVerdict.Confidence.LOW -> 1
    }
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 1..3) Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (i <= filled) on else off))
    }
}

/** "Very sure: the voices were 24 dB apart." */
internal fun confidenceLine(v: MicVerdict): String {
    val db = v.separationDb?.let { "%.0f dB".format(it) }
    return when (v.confidence) {
        MicVerdict.Confidence.HIGH -> "${v.confidence.label}: the voices were ${db ?: "well"} apart."
        MicVerdict.Confidence.MEDIUM -> "${v.confidence.label}: the voices were ${db ?: "clearly"} apart, with a little bleed."
        MicVerdict.Confidence.LOW ->
            "${v.confidence.label}: the voices were only ${db ?: "a little"} apart, so there's a lot of bleed. Worth a look before you record."
    }
}

/** The next step, and the rest folded into one row. */
@Composable
private fun UpNext(current: SetupStep) {
    val m = MaterialTheme.colorScheme
    val rest = SetupStep.entries.drop(current.ordinal + 1)
    val next = rest.firstOrNull() ?: return
    TicketRow(faded = true) {
        Icon(iconFor(next), contentDescription = null, tint = m.onSurfaceVariant)
        Text("Next: ${next.title.lowerFirst()}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Text("${next.ordinal + 1} of ${SetupStep.entries.size}", color = m.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    }
    val then = rest.drop(1)
    if (then.isEmpty()) return
    val pianoOnly = then.all { it.ordinal >= SetupStep.PIANO_ALONE.ordinal }
    TicketRow(faded = true) {
        Icon(Icons.Filled.Piano, contentDescription = null, tint = m.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    then.size == 1 -> "Then: ${then[0].title.lowerFirst()}"
                    pianoOnly -> "Then: more piano"
                    else -> "Then: big note and piano"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            if (then.size > 1) {
                Text(
                    "Steps ${then.first().ordinal + 1} to ${then.last().ordinal + 1}",
                    color = m.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** A tip that stays folded until someone wants it. */
@Composable
private fun MicTip() {
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
            Text("Where should the teacher's mic go?", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (open) "Hide tip" else "Show tip")
        }
        AnimatedVisibility(open) {
            Text(
                "Clip it on the side of the collar away from the piano, and turn noise cancellation off.",
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                color = m.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun iconFor(step: SetupStep) = when (step) {
    SetupStep.STUDENT_SPEAK, SetupStep.LOUD_NOTE -> Icons.Filled.MusicNote
    SetupStep.TEACHER_SPEAK -> Icons.Filled.People
    else -> Icons.Filled.Piano
}

private fun String.lowerFirst() = replaceFirstChar { it.lowercase() }

/** One live level bar, mapping -60..0 dBFS onto its width (used by "Who's who"). */
@Composable
fun LiveMeterBar(db: Double?, modifier: Modifier = Modifier) {
    val fraction = ((db ?: -60.0) + 60.0).coerceIn(0.0, 60.0) / 60.0
    LinearProgressIndicator(progress = { fraction.toFloat() }, modifier = modifier.fillMaxWidth())
}
