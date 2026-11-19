package com.cuenote.practice.reading

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cuenote.audio.Listening
import com.cuenote.pipeline.reading.Anchor
import com.cuenote.pipeline.reading.Melodies
import com.cuenote.pipeline.reading.MelodyFollow
import com.cuenote.pipeline.reading.MelodySound
import com.cuenote.pipeline.reading.NoteMatch
import com.cuenote.pipeline.reading.ReadingProgress
import com.cuenote.pipeline.reading.RhythmCheck
import com.cuenote.pipeline.reading.Staff
import com.cuenote.practice.rememberDevices
import com.cuenote.ui.Brass
import com.cuenote.ui.StartButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Stage 4: short melodies. Sing a few bars from just the first note, tap
 * the same bars in time, and learn a song you know for each leap.
 */

/** The first tune not yet done in [done], or the first tune. */
private fun firstNotDone(done: Map<String, Int>): Int =
    Melodies.TUNES.indexOfFirst { (done[it.id] ?: 0) == 0 }.coerceAtLeast(0)

/**
 * Sing a melody: the first note plays, then the student sings through the
 * tune in their own time. Each note turns green once it's held in tune;
 * hearing a note (or skipping it) moves on but counts as help.
 */
@Composable
internal fun SightSing(progress: ReadingProgress, modifier: Modifier, onClean: (String) -> Unit, onDone: () -> Unit) {
    val m = MaterialTheme.colorScheme
    val home = progress.voice
    val voice = rememberLiveVoice()
    val tone = rememberTonePlayer()
    val devices = rememberDevices()
    val speaker = devices.output.listening == Listening.SPEAKER
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var tuneIdx by remember { mutableIntStateOf(firstNotDone(progress.melodies.sung)) }
    var attempt by remember { mutableIntStateOf(0) }
    val melody = remember(tuneIdx, home) { Melodies.place(Melodies.TUNES[tuneIdx], home) }
    var follow by remember { mutableStateOf(MelodyFollow(melody)) }
    var tick by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<NoteMatch.State?>(null) }
    val trail = remember { mutableStateListOf<Double?>() }
    var playing by remember { mutableStateOf(false) }
    var heardTune by remember { mutableStateOf(false) }

    suspend fun play(note: Int, ms: Int = 1_200) {
        playing = true
        if (speaker) voice.stop()
        val len = tone.play(note, ms)
        if (speaker) delay(len + 250L)
        playing = false
        if (!follow.done) voice.start()
    }
    suspend fun playTune() {
        playing = true
        voice.stop()
        val len = tone.playPcm(MelodySound.render(melody, notes = true, click = false, countIn = 0))
        delay(len.toLong() + 250L)
        playing = false
        if (!follow.done) voice.start()
    }

    LaunchedEffect(melody, attempt) {
        follow = MelodyFollow(melody); tick++; state = null; trail.clear(); heardTune = false
        play(melody.notes[0].midi)
    }
    LaunchedEffect(voice.listening) {
        if (!voice.listening) return@LaunchedEffect
        voice.frames.collect { f ->
            val fo = follow
            if (fo.done || (playing && speaker)) return@collect
            val before = fo.index
            val s = fo.push(f) ?: return@collect
            if (fo.index != before) {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                state = null; trail.clear(); tick++
                if (fo.done) {
                    voice.stop()
                    if (fo.clean && !heardTune) onClean(melody.tune.id)
                }
            } else {
                state = s
                trail += s.cents
                if (trail.size > 90) trail.removeAt(0)
            }
        }
    }

    @Suppress("UNUSED_VARIABLE") val t = tick   // redraw the marks when the voice moves on
    val fo = follow
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Tune ${tuneIdx + 1} of ${Melodies.TUNES.size}: ${melody.tune.title}", style = MaterialTheme.typography.labelLarge, color = m.onSurfaceVariant)
        MelodyStaff(melody, home.clef, color = { i ->
            when (fo.marks.getOrNull(i)) { MelodyFollow.Mark.CLEAN -> m.primary; MelodyFollow.Mark.HELPED -> Brass; null -> m.onSurface }
        }, current = if (fo.done) null else fo.index)
        if (fo.done) {
            val clean = fo.clean && !heardTune
            val helped = fo.marks.count { it == MelodyFollow.Mark.HELPED }
            Text(when {
                heardTune -> "You heard it first, so that one was practice. Next time, sing it from the page."
                helped == 0 -> "All of it in tune, from just the first note."
                clean -> "Most of it in tune. The gold notes are the ones you needed help with."
                else -> "A good start. Tunes often come on the second try."
            }, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, color = if (clean) m.primary else m.onSurface,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            OutlinedButton(onClick = { scope.launch { playTune() } }, Modifier.fillMaxWidth(), enabled = !playing) { Text("Hear how it goes") }
            StartButton("Sing it again", { attempt++ }, Modifier.fillMaxWidth())
            OutlinedButton(onClick = { tuneIdx = (tuneIdx + 1) % Melodies.TUNES.size }, Modifier.fillMaxWidth()) { Text("Next tune") }
            TextButton(onClick = onDone) { Text("Back to reading and pitch") }
            return@Column
        }
        Text(melody.key.words, style = MaterialTheme.typography.bodyMedium, color = m.onSurfaceVariant, textAlign = TextAlign.Center)
        Text(if (fo.index == 0) "It starts on ${Staff.letter(melody.notes[0].midi)}. You'll hear it, then sing on from there in your own time."
            else "Note ${fo.index + 1} of ${melody.notes.size}. Look at the next one: a step, a skip or a leap?",
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        LiveMeter(if (playing) null else state, trail)
        Text("Each note turns green once you hold it in tune. Longer notes, hold a little longer.",
            style = MaterialTheme.typography.bodySmall, color = m.onSurfaceVariant, textAlign = TextAlign.Center)
        voice.error?.let { Text(it, color = m.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { fo.helpWith(); scope.launch { play(fo.current!!.midi) } }, enabled = !playing) { Text("Play this note") }
            TextButton(onClick = {
                fo.skip(); state = null; trail.clear(); tick++
                if (fo.done) { voice.stop(); if (fo.clean && !heardTune) onClean(melody.tune.id) }
            }) { Text("Skip this note") }
        }
        TextButton(onClick = { heardTune = true; scope.launch { playTune() } }, enabled = !playing) { Text("I'm stuck: play the whole tune") }
    }
}

/**
 * Tap the rhythm: a bar of clicks counts in, then the student taps once for
 * each note, where it starts, with the click going on underneath.
 */
@Composable
internal fun TapRhythm(progress: ReadingProgress, modifier: Modifier, onClean: (String) -> Unit, onDone: () -> Unit) {
    val m = MaterialTheme.colorScheme
    val home = progress.voice
    val tone = rememberTonePlayer()
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var tuneIdx by remember { mutableIntStateOf(firstNotDone(progress.melodies.tapped)) }
    val melody = remember(tuneIdx, home) { Melodies.place(Melodies.TUNES[tuneIdx], home) }
    var running by remember { mutableStateOf(false) }
    var hearing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RhythmCheck.Result?>(null) }
    var beatNow by remember { mutableFloatStateOf(-1f) }
    val taps = remember { mutableStateListOf<Long>() }
    val countIn = melody.beatsPerBar
    val beatNs = MelodySound.beatMs() * 1_000_000

    LaunchedEffect(melody) { result = null; taps.clear() }

    /** Plays the click (and the notes, when [withNotes]), following the beat; returns when the first sample sounded. */
    suspend fun run(withNotes: Boolean): Long {
        val calledAt = System.nanoTime()
        val len = tone.playPcm(MelodySound.render(melody, notes = withNotes, click = true, countIn = countIn))
        var start = calledAt
        val endAt = calledAt + len * 1_000_000L
        while (System.nanoTime() < endAt) {
            tone.startedAtNanos()?.let { start = it }
            beatNow = ((System.nanoTime() - start) / beatNs - countIn).toFloat()
            delay(30)
        }
        beatNow = -1f
        return start
    }

    fun go() = scope.launch {
        result = null; taps.clear(); running = true
        val start = run(withNotes = false)
        running = false
        val beats = taps.map { ((it - start) / beatNs - countIn).toFloat() }
        val r = RhythmCheck.judge(melody.notes.map { it.start }, beats)
        result = r
        if (r.clean) onClean(melody.tune.id)
    }

    val nowIdx = melody.notes.indexOfFirst { beatNow >= it.start && beatNow < it.end }.takeIf { it >= 0 }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Tune ${tuneIdx + 1} of ${Melodies.TUNES.size}: ${melody.tune.title}", style = MaterialTheme.typography.labelLarge, color = m.onSurfaceVariant)
        val r = result
        MelodyStaff(melody, home.clef, color = { i ->
            when (r?.marks?.getOrNull(i)) {
                RhythmCheck.Mark.ON_TIME -> m.primary
                RhythmCheck.Mark.EARLY, RhythmCheck.Mark.LATE -> Brass
                RhythmCheck.Mark.MISSED -> m.onSurfaceVariant.copy(alpha = 0.5f)
                null -> m.onSurface
            }
        }, current = nowIdx)
        when {
            running && beatNow < 0 -> Text("Counting in… ${(beatNow + countIn).toInt().coerceIn(0, countIn - 1) + 1}",
                style = MaterialTheme.typography.titleLarge)
            running -> Text("Tap each note as it starts", style = MaterialTheme.typography.titleLarge)
            r != null -> {
                Text(r.words, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                    color = if (r.clean) m.primary else m.onSurface, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text("Green notes were on time, gold ones early or late, and faint ones had no tap.",
                    style = MaterialTheme.typography.bodySmall, color = m.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            else -> Text("${melody.beatsPerBar} beats to a bar. A quarter note is one beat, a half note two, a dotted half three, a whole note four; two eighths share a beat.",
                style = MaterialTheme.typography.bodyMedium, color = m.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        // The pad: a press counts the moment the finger lands.
        Box(
            Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(20.dp))
                .background(if (running) m.primaryContainer else m.surfaceVariant)
                .semantics { role = Role.Button }
                .pointerInput(running) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        if (running) {
                            taps += System.nanoTime()
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Text(if (running) "Tap" else "The tap pad", style = MaterialTheme.typography.headlineSmall, color = if (running) m.onPrimaryContainer else m.onSurfaceVariant) }
        if (!running) {
            StartButton(if (r == null) "Start: a bar of clicks, then tap" else "Try it again", { go() }, Modifier.fillMaxWidth())
            OutlinedButton(onClick = { scope.launch { hearing = true; run(withNotes = true); hearing = false } }, Modifier.fillMaxWidth(), enabled = !hearing) {
                Text("Hear it with the click first")
            }
            if (r != null) OutlinedButton(onClick = { tuneIdx = (tuneIdx + 1) % Melodies.TUNES.size }, Modifier.fillMaxWidth()) { Text("Next tune") }
            Text("Tap once where each note starts, then wait out its length. Counting out loud helps: \"one and two and…\"",
                style = MaterialTheme.typography.bodySmall, color = m.onSurfaceVariant, textAlign = TextAlign.Center)
            TextButton(onClick = onDone) { Text("Back to reading and pitch") }
        }
    }
}

private const val ANCHOR_ROUND = 10

/**
 * Leap anchors: a song you know for each leap, then a quiz that plays a
 * leap and asks which song it sounds like.
 */
@Composable
internal fun LeapAnchors(progress: ReadingProgress, modifier: Modifier, onRight: (Anchor) -> Unit, onDone: () -> Unit) {
    val m = MaterialTheme.colorScheme
    val home = progress.voice
    val tone = rememberTonePlayer()
    val scope = rememberCoroutineScope()
    var quiz by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    val low = home.firstFive.first()

    fun hear(a: Anchor, from: Int = low) = scope.launch {
        playing = true
        val (x, y) = a.notes(from)
        delay(tone.play(x, 900).toLong() + 250L)
        delay(tone.play(y, 1_100).toLong())
        playing = false
    }

    if (!quiz) {
        Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("When you see a leap, think of a song that starts with it, and hear the song in your head before you sing.",
                color = m.onSurfaceVariant)
            Anchor.entries.forEach { a ->
                val known = (progress.melodies.anchors[a] ?: 0) >= com.cuenote.pipeline.reading.MelodyProgress.ANCHOR_RIGHT
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(m.surface).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DoneDot(known)
                        Text(a.interval, style = MaterialTheme.typography.titleMedium)
                    }
                    Text("${a.song}: ${a.words}", color = m.onSurfaceVariant)
                    val (x, y) = a.notes(low)
                    StaffNotes(listOf(x, y), home.clef, space = 9.dp)
                    TextButton(onClick = { hear(a) }, enabled = !playing) { Text("Hear it") }
                }
            }
            StartButton("Quiz me", { quiz = true }, Modifier.fillMaxWidth())
            TextButton(onClick = onDone) { Text("Back to reading and pitch") }
        }
        return
    }

    // Leaps start anywhere in the easy range, as long as the top note stays within an octave of the speaking voice.
    fun startFor(a: Anchor) = home.easyNotes.filter { n -> a.notes(n).let { maxOf(it.first, it.second) <= home.speaking + 12 } }
        .ifEmpty { home.easyNotes }.random()
    var count by remember { mutableIntStateOf(0) }
    var anchor by remember { mutableStateOf(Anchor.entries.random()) }
    var from by remember { mutableIntStateOf(startFor(anchor)) }
    var answer by remember { mutableStateOf<Anchor?>(null) }
    fun next() {
        count++
        anchor = Anchor.entries.filter { it != anchor }.random()
        from = startFor(anchor)
        answer = null
    }
    LaunchedEffect(anchor, from, count) { if (count < ANCHOR_ROUND) hear(anchor, from) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (count >= ANCHOR_ROUND) {
            Text("That's ten.", style = MaterialTheme.typography.headlineSmall)
            Text("Next time you see a leap on the page, hum the song's start first.", color = m.onSurfaceVariant, textAlign = TextAlign.Center)
            StartButton("Ten more", { count = 0; answer = null }, Modifier.fillMaxWidth())
            OutlinedButton(onClick = { quiz = false; count = 0; answer = null }, Modifier.fillMaxWidth()) { Text("See the songs again") }
            TextButton(onClick = onDone) { Text("Back to reading and pitch") }
            return@Column
        }
        Text("${count + 1} of $ANCHOR_ROUND", style = MaterialTheme.typography.labelLarge, color = m.onSurfaceVariant)
        Text("Which song starts like this?", style = MaterialTheme.typography.titleLarge)
        Anchor.entries.forEach { a ->
            val isAnswer = answer != null && a == anchor
            val wrongPick = answer == a && a != anchor
            Text(a.song, color = if (isAnswer) m.onPrimary else m.onSurface, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(when { isAnswer -> m.primary; wrongPick -> Brass.copy(alpha = 0.35f); else -> m.surfaceVariant })
                    .clickable(enabled = answer == null && !playing, onClickLabel = "Answer ${a.song}") {
                        answer = a
                        if (a == anchor) onRight(a)
                        hear(anchor, from)
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp))
        }
        answer?.let { picked ->
            Text(if (picked == anchor) "Yes: ${anchor.interval.replaceFirstChar { it.lowercase() }}, like ${anchor.words} in ${anchor.song}."
                else "That was ${anchor.interval.replaceFirstChar { it.lowercase() }}: ${anchor.song}, ${anchor.words}.",
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                color = if (picked == anchor) m.primary else m.onSurface, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            val (x, y) = anchor.notes(from)
            StaffNotes(listOf(x, y), home.clef)
            StartButton("Next", { next() }, Modifier.fillMaxWidth())
        }
        TextButton(onClick = { hear(anchor, from) }, enabled = !playing) { Text("Hear it again") }
    }
}
