package com.cuenote.practice.reading

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cuenote.audio.Listening
import com.cuenote.pipeline.reading.Motion
import com.cuenote.pipeline.reading.MotionDrill
import com.cuenote.pipeline.reading.MotionPair
import com.cuenote.pipeline.reading.NoteMatch
import com.cuenote.pipeline.reading.ReadingProgress
import com.cuenote.practice.rememberDevices
import com.cuenote.ui.Brass
import com.cuenote.ui.StartButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Stage 3: steps, skips and leaps. Three ways at the same idea, so the ear,
 * the eye and the voice learn it together: hear two notes and say how far
 * they moved, see two notes and say how far, then sing the second note from
 * the first.
 */

private const val PAIR_ROUND = 10
private const val SING_ROUND = 6
private const val GAP_MS = 250L
private const val PAIR_NOTE_MS = 900

/** Plays a pair one after the other; returns when the second has finished. */
private suspend fun playPair(tone: TonePlayer, pair: MotionPair) {
    delay(tone.play(pair.from, PAIR_NOTE_MS).toLong() + GAP_MS)
    delay(tone.play(pair.to, PAIR_NOTE_MS).toLong())
}

/** The teacher's cue for singing this motion, from Chad's Singer's To-Do List. */
private fun cueFor(pair: MotionPair): String = when {
    pair.motion == Motion.STEP -> "Slurp to it: change notes smoothly on one breath, without a click."
    pair.up -> "More, less, least: lighten up as you go up."
    else -> "Keep the space inside as you come down. Don't let it drop."
}

/**
 * Hear two notes, or see them ([byEar] false), and say whether the second is
 * a step, a skip or a leap away. After an answer the pair is shown and played
 * either way, so the sound and the picture connect.
 */
@Composable
internal fun MotionQuiz(
    progress: ReadingProgress,
    byEar: Boolean,
    modifier: Modifier,
    onRight: (Motion) -> Unit,
    onDone: () -> Unit,
) {
    val m = MaterialTheme.colorScheme
    val home = progress.voice
    val tone = rememberTonePlayer()
    val scope = rememberCoroutineScope()
    var count by remember { mutableIntStateOf(0) }
    var pair by remember { mutableStateOf(MotionDrill.pair(home)) }
    var answer by remember { mutableStateOf<Motion?>(null) }
    var playing by remember { mutableStateOf(false) }
    val right = answer == pair.motion

    fun hear() = scope.launch { playing = true; playPair(tone, pair); playing = false }
    fun next() { count++; pair = MotionDrill.pair(home, last = pair); answer = null }

    LaunchedEffect(pair) { if (byEar) hear() }
    LaunchedEffect(answer) { if (answer != null && right) { delay(1_800); next() } }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (count >= PAIR_ROUND) {
            Text("That's ten.", style = MaterialTheme.typography.headlineSmall)
            Text(if (byEar) "Steps sound like walking, skips like a hop, leaps like a jump." else "Steps go line to space, skips line to line or space to space, and leaps go further.",
                color = m.onSurfaceVariant, textAlign = TextAlign.Center)
            StartButton("Ten more", { count = 0 }, Modifier.fillMaxWidth())
            OutlinedButton(onClick = onDone, Modifier.fillMaxWidth()) { Text("Back to reading and pitch") }
            return@Column
        }
        Text("${count + 1} of $PAIR_ROUND", style = MaterialTheme.typography.labelLarge, color = m.onSurfaceVariant)
        // By ear the staff stays empty until the answer; by eye it shows the pair from the start.
        val shown = !byEar || answer != null
        StaffNotes(
            if (shown) listOf(pair.from, pair.to) else emptyList(), home.clef,
            colors = listOf(m.onSurface, if (answer == null) m.onSurface else if (right) m.primary else Brass),
        )
        Text(if (byEar) "How far did it move?" else "How far does it move?", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Motion.PRACTICED.forEach { mo ->
                val isAnswer = answer != null && mo == pair.motion
                val wrongPick = answer == mo && !right
                Text(
                    mo.word.replaceFirstChar { it.uppercase() },
                    color = if (isAnswer) m.onPrimary else m.onSurface,
                    style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(when { isAnswer -> m.primary; wrongPick -> Brass.copy(alpha = 0.35f); else -> m.surfaceVariant })
                        .clickable(enabled = answer == null && !(byEar && playing), onClickLabel = "Answer ${mo.word}") {
                            answer = mo
                            if (mo == pair.motion) onRight(mo)
                            hear()
                        }
                        .padding(vertical = 16.dp),
                )
            }
        }
        when {
            answer == null -> Text(
                if (byEar) "Listen for whether it walks to the next note, hops over one, or jumps further."
                else "Count the lines and spaces: next door is a step, skipping one is a skip, more is a leap.",
                style = MaterialTheme.typography.bodySmall, color = m.onSurfaceVariant, textAlign = TextAlign.Center)
            right -> Text("Yes. ${pair.say(home.clef)}", style = MaterialTheme.typography.titleMedium, color = m.primary,
                textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            else -> {
                Text("That one's ${pair.say(home.clef).replaceFirstChar { it.lowercase() }}", style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                StartButton("Next", { next() }, Modifier.fillMaxWidth())
            }
        }
        TextButton(onClick = { hear() }, enabled = !playing) { Text(if (byEar) "Hear it again" else "Hear it") }
    }
}

/**
 * Step or leap from here: the first note plays and both are shown with how
 * far apart they are; the student sings the second note before hearing it,
 * judged live as in Match the note.
 */
@Composable
internal fun SingMotion(
    progress: ReadingProgress,
    modifier: Modifier,
    onSung: (Motion) -> Unit,
    onDone: () -> Unit,
) {
    val m = MaterialTheme.colorScheme
    val home = progress.voice
    val voice = rememberLiveVoice()
    val tone = rememberTonePlayer()
    val devices = rememberDevices()
    val speaker = devices.output.listening == Listening.SPEAKER
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var count by remember { mutableIntStateOf(0) }
    var pair by remember { mutableStateOf(MotionDrill.pair(home)) }
    var match by remember { mutableStateOf<NoteMatch?>(null) }
    var state by remember { mutableStateOf<NoteMatch.State?>(null) }
    val trail = remember { mutableStateListOf<Double?>() }
    var playing by remember { mutableStateOf(false) }

    suspend fun play(note: Int, ms: Int = PAIR_NOTE_MS) {
        playing = true
        if (speaker) voice.stop()
        val len = tone.play(note, ms)
        if (speaker) delay(len + 250L)
        playing = false
        voice.start()
    }
    LaunchedEffect(pair, count) {
        if (count >= SING_ROUND) { voice.stop(); return@LaunchedEffect }
        match = NoteMatch(pair.to); state = null; trail.clear()
        play(pair.from, 1_200)
    }
    LaunchedEffect(voice.listening) {
        if (!voice.listening) return@LaunchedEffect
        voice.frames.collect { f ->
            val mm = match ?: return@collect
            if (playing && speaker) return@collect
            val before = state?.settled == true
            val s = mm.push(f)
            state = s
            trail += s.cents
            if (trail.size > 90) trail.removeAt(0)
            if (s.settled && !before) {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onSung(pair.motion)
            }
        }
    }
    LaunchedEffect(state?.settled) {
        if (state?.settled == true) {
            // Hear the real pair, then on to the next.
            playing = true
            if (speaker) voice.stop()
            playPair(tone, pair)
            playing = false
            delay(400)
            count++
            pair = MotionDrill.pair(home, last = pair)
        }
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (count >= SING_ROUND) {
            Text("That's a round.", style = MaterialTheme.typography.headlineSmall)
            Text("Singing from one note to the next is most of what reading a melody is.", color = m.onSurfaceVariant, textAlign = TextAlign.Center)
            StartButton("Another round", { count = 0; pair = MotionDrill.pair(home, last = pair) }, Modifier.fillMaxWidth())
            OutlinedButton(onClick = onDone, Modifier.fillMaxWidth()) { Text("Back to reading and pitch") }
            return@Column
        }
        Text("${count + 1} of $SING_ROUND", style = MaterialTheme.typography.labelLarge, color = m.onSurfaceVariant)
        StaffNotes(listOf(pair.from, pair.to), home.clef,
            colors = listOf(m.onSurfaceVariant, if (state?.settled == true) m.primary else m.onSurface))
        Text(pair.say(home.clef), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text("You'll hear the first note. Sing the second one, then hold it.", color = m.onSurfaceVariant, textAlign = TextAlign.Center)
        LiveMeter(if (playing) null else state, trail)
        Text(cueFor(pair), style = MaterialTheme.typography.bodySmall, color = m.onSurfaceVariant, textAlign = TextAlign.Center)
        voice.error?.let { Text(it, color = m.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { scope.launch { play(pair.from, 1_200) } }, enabled = !playing) { Text("First note again") }
            TextButton(onClick = { count++; pair = MotionDrill.pair(home, last = pair) }) { Text("Skip this one") }
        }
        TextButton(onClick = { scope.launch { playing = true; if (speaker) voice.stop(); playPair(tone, pair); playing = false; voice.start() } },
            enabled = !playing) { Text("I'm stuck: play both") }
    }
}
