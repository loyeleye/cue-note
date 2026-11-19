package com.cuenote.practice.reading

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cuenote.audio.Listening
import com.cuenote.data.ReadingStore
import com.cuenote.pipeline.reading.Clef
import com.cuenote.pipeline.reading.LivePitch
import com.cuenote.pipeline.reading.NoteMatch
import com.cuenote.pipeline.reading.ReadingLadder
import com.cuenote.pipeline.reading.ReadingProgress
import com.cuenote.pipeline.reading.ReadingStage
import com.cuenote.pipeline.reading.Staff
import com.cuenote.pipeline.reading.VoiceHome
import com.cuenote.practice.rememberDevices
import com.cuenote.ui.Brass
import com.cuenote.ui.Masthead
import com.cuenote.ui.StartButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class Exercise(val title: String) {
    HUB("Reading and pitch"),
    VOICE_CHECK("Voice check"),
    MATCH("Match the note"),
    SING_SEEN("Sing what you see"),
    NAME_IT("Name it"),
    CHECK_PITCH("Check my pitch"),
}

/**
 * The reading and pitch track: a beginner's way from matching a note to
 * reading a piano-vocal score. This first part holds Stages 1 and 2: the
 * voice check, Match the note, Check my pitch, Name it and Sing what you see.
 * Its own track, beside the teacher's homework, never inside it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingScreen(store: ReadingStore, onBack: () -> Unit) {
    val progress by store.progress.collectAsStateWithLifecycle()
    var exercise by rememberSaveable { mutableStateOf(Exercise.HUB) }
    BackHandler(enabled = exercise != Exercise.HUB) { exercise = Exercise.HUB }
    val view = LocalView.current
    DisposableEffect(exercise) {
        // The phone often sits on a music stand while singing.
        view.keepScreenOn = exercise != Exercise.HUB
        onDispose { view.keepScreenOn = false }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(exercise.title) },
                navigationIcon = {
                    TextButton(onClick = { if (exercise == Exercise.HUB) onBack() else exercise = Exercise.HUB }) { Text("Back") }
                },
            )
        },
    ) { padding ->
        val body = Modifier.padding(padding).fillMaxSize()
        when (exercise) {
            Exercise.HUB -> Hub(progress, body, onOpen = { exercise = it }, onClef = { c ->
                store.update { p -> p.copy(home = p.voice.copy(clef = c)) }
            })
            Exercise.VOICE_CHECK -> VoiceCheck(body) { home ->
                store.update { it.copy(home = home) }
                exercise = Exercise.HUB
            }
            Exercise.MATCH -> MatchRound(progress, showStaff = false, body,
                onMatched = { midi -> store.update { it.matched(store.today()) } },
                onDone = { exercise = Exercise.HUB })
            Exercise.SING_SEEN -> MatchRound(progress, showStaff = true, body,
                onMatched = { midi -> store.update { it.matched(store.today()).sang(midi) } },
                onDone = { exercise = Exercise.HUB })
            Exercise.NAME_IT -> NameIt(progress, body,
                onAnswer = { midi, right -> store.update { it.copy(deck = it.deck.answer(midi, right)) } },
                onDone = { exercise = Exercise.HUB })
            Exercise.CHECK_PITCH -> CheckPitch(progress.voice.clef, body)
        }
    }
}

// ---- The hub ----

@Composable
private fun Hub(progress: ReadingProgress, modifier: Modifier, onOpen: (Exercise) -> Unit, onClef: (Clef) -> Unit) {
    val m = MaterialTheme.colorScheme
    val stage = ReadingLadder.stage(progress)
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Masthead("Reading ", "and pitch") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StageDots(stage)
                Text("Stage ${stage.ordinal + 1}: ${stage.title}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(ReadingLadder.progressWords(progress), color = m.onSurfaceVariant)
            }
        }
        item { SectionLabel("Stage 1: Find your voice") }
        item {
            ExerciseRow("Voice check", if (progress.home == null) "Say a sentence, so every note starts where you talk. About 10 seconds."
                else "Done. Your notes start around ${Staff.name(progress.voice.speaking)}. Tap to check again.",
                done = progress.home != null, primary = progress.home == null) { onOpen(Exercise.VOICE_CHECK) }
        }
        item {
            ExerciseRow("Match the note", "A note plays. Sing it and hold it until the ring fills.",
                done = ReadingLadder.foundVoice(progress), primary = progress.home != null && stage == ReadingStage.FIND_YOUR_VOICE) { onOpen(Exercise.MATCH) }
        }
        item {
            ExerciseRow("Check my pitch", "Sing anything and see which note it is. Handy for giving yourself a pitch.", done = false) { onOpen(Exercise.CHECK_PITCH) }
        }
        item { SectionLabel("Stage 2: The staff") }
        val five = progress.voice.firstFive
        item {
            val named = five.count { progress.deck.box(it) >= com.cuenote.pipeline.reading.NameDeck.KNOWN_BOX }
            ExerciseRow("Name it", "A note on the staff. Tap its letter. Your five notes: ${five.joinToString(" ") { Staff.letter(it) }}. Known: $named of 5.",
                done = progress.deck.knows(five), primary = stage == ReadingStage.THE_STAFF) { onOpen(Exercise.NAME_IT) }
        }
        item {
            ExerciseRow("Sing what you see", "A note on the staff. Sing it. Turn on \"Try before I hear it\" when you're ready.",
                done = five.all { (progress.sungFromPage[it] ?: 0) >= ReadingLadder.SUNG_EACH }, primary = false) { onOpen(Exercise.SING_SEEN) }
        }
        item { SectionLabel("Coming next") }
        items(ReadingStage.entries.drop(2).size) { i ->
            val s = ReadingStage.entries[i + 2]
            Column(Modifier.alpha(0.55f).padding(vertical = 2.dp)) {
                Text("Stage ${s.ordinal + 1}: ${s.title}", style = MaterialTheme.typography.titleSmall)
                Text(s.about, style = MaterialTheme.typography.bodySmall, color = m.onSurfaceVariant)
            }
        }
        item { SectionLabel("Reading in") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Women's and children's parts are written in treble clef. Baritone and bass parts are usually in bass clef. Tenor parts are often in treble clef with a small 8 below, which means an octave lower.",
                    style = MaterialTheme.typography.bodySmall, color = m.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Clef.entries.forEach { c ->
                        val picked = progress.voice.clef == c
                        if (picked) FilledTonalButton(onClick = {}) { Text(c.label) } else OutlinedButton(onClick = { onClef(c) }) { Text(c.label) }
                    }
                }
            }
        }
        item {
            Text("Short and often beats long and rare: a few minutes most days is the way your teacher suggests practicing.",
                style = MaterialTheme.typography.bodySmall, color = m.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
}

@Composable
private fun ExerciseRow(title: String, about: String, done: Boolean, primary: Boolean = false, onClick: () -> Unit) {
    val m = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (primary) m.primaryContainer else m.surface)
            .clickable(onClickLabel = "Start $title") { onClick() }
            .defaultMinSize(minHeight = 64.dp).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (primary) m.onPrimaryContainer else m.onSurface)
            Text(about, style = MaterialTheme.typography.bodySmall, color = if (primary) m.onPrimaryContainer else m.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        DoneDot(done)
    }
}

// ---- Voice check ----

@Composable
private fun VoiceCheck(modifier: Modifier, onDone: (VoiceHome) -> Unit) {
    val m = MaterialTheme.colorScheme
    val voice = rememberLiveVoice()
    var listening by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<VoiceHome?>(null) }
    var missed by remember { mutableStateOf(false) }
    val frames = remember { mutableStateListOf<LivePitch.Frame>() }
    LaunchedEffect(listening) {
        if (!listening) return@LaunchedEffect
        frames.clear()
        voice.start()
        val collect = launch { voice.frames.collect { frames += it } }
        delay(VOICE_CHECK_MS)
        collect.cancel()
        voice.stop()
        listening = false
        result = VoiceHome.fromSpeech(frames.toList())
        missed = result == null
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Say this out loud in your normal speaking voice, the way you'd talk to a friend:", style = MaterialTheme.typography.bodyLarge)
        Text("\"Hello, I'm learning to read music, one note at a time, and today I'm finding where my voice lives.\"",
            style = MaterialTheme.typography.headlineSmall)
        Text("Singing starts where you talk, so the app puts your first notes near your speaking voice. Nothing is recorded.",
            style = MaterialTheme.typography.bodySmall, color = m.onSurfaceVariant)
        voice.error?.let { Text(it, color = m.error) }
        val r = result
        when {
            listening -> {
                val clear = frames.count { it.clear }
                Text(if (clear < 5) "Listening… start talking" else "Listening… keep going", style = MaterialTheme.typography.titleLarge,
                    color = m.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            r != null -> {
                Text("Your voice sits around ${Staff.name(r.speaking)}.", style = MaterialTheme.typography.titleLarge)
                Text("Exercises will start there. You'll read in ${r.clef.label.lowercase()} clef; you can change that on the last screen.",
                    color = m.onSurfaceVariant)
                StaffNote(r.speaking, r.clef)
                StartButton("Use this", { onDone(r) }, Modifier.fillMaxWidth())
                TextButton(onClick = { result = null; listening = true }) { Text("Try again") }
            }
            else -> {
                if (missed) Text("That was hard to hear. Try a little louder, or closer to the phone.", color = m.onSurfaceVariant)
                StartButton("Start talking", { missed = false; listening = true }, Modifier.fillMaxWidth())
            }
        }
    }
}

private const val VOICE_CHECK_MS = 7_000L

// ---- Match the note, and Sing what you see ----

/**
 * A round of notes to match. With [showStaff] the note is shown on the staff
 * first (Sing what you see); otherwise it's only heard (Match the note).
 */
@Composable
private fun MatchRound(
    progress: ReadingProgress,
    showStaff: Boolean,
    modifier: Modifier,
    onMatched: (Int) -> Unit,
    onDone: () -> Unit,
) {
    val m = MaterialTheme.colorScheme
    val home = progress.voice
    val pool = if (showStaff) home.firstFive else home.easyNotes
    val voice = rememberLiveVoice()
    val tone = rememberTonePlayer()
    val devices = rememberDevices()
    val speaker = devices.output.listening == Listening.SPEAKER
    val view = LocalView.current

    var round by remember { mutableIntStateOf(0) }
    var index by remember { mutableIntStateOf(0) }
    val notes = remember(round) { roundOf(pool, ROUND) }
    var held by remember { mutableIntStateOf(0) }
    var hearFirst by rememberSaveable { mutableStateOf(true) }
    val target = notes.getOrNull(index)

    // Per note: play it (unless trying first), then listen. On a speaker, the
    // mic waits for the tone to fade so it doesn't hear the piano.
    var match by remember { mutableStateOf<NoteMatch?>(null) }
    var state by remember { mutableStateOf<NoteMatch.State?>(null) }
    val trail = remember { mutableStateListOf<Double?>() }
    var playing by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun hear(note: Int) {
        playing = true
        if (speaker) voice.stop()
        val ms = tone.play(note)
        if (speaker) delay(ms + 250L)
        playing = false
        revealed = true
        voice.start()
    }
    LaunchedEffect(target) {
        if (target == null) { voice.stop(); return@LaunchedEffect }
        match = NoteMatch(target); state = null; trail.clear()
        revealed = !showStaff || hearFirst
        if (!showStaff || hearFirst) hear(target) else voice.start()
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
                held++
                onMatched(notes[index])
            }
        }
    }
    // A moment to enjoy it, then the next note.
    LaunchedEffect(state?.settled, index) {
        if (state?.settled == true) {
            if (showStaff && !revealed) { tone.play(notes[index]); revealed = true }
            delay(1_300)
            index++
        }
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (target == null) {
            Text("That's a round.", style = MaterialTheme.typography.headlineSmall)
            Text(if (held == ROUND) "You held every note." else "You held $held of them. The others will come round again.",
                color = m.onSurfaceVariant)
            StartButton("Another round", { round++; index = 0; held = 0 }, Modifier.fillMaxWidth())
            OutlinedButton(onClick = onDone, Modifier.fillMaxWidth()) { Text("Back to reading and pitch") }
            return@Column
        }
        Text("Note ${index + 1} of $ROUND", style = MaterialTheme.typography.labelLarge, color = m.onSurfaceVariant)
        if (showStaff) {
            StaffNote(target, home.clef, noteColor = if (state?.settled == true) m.primary else m.onSurface)
            val p = Staff.place(target, home.clef)
            Text("${Staff.letter(target)}, ${Staff.describe(p)}", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Try before I hear it", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = !hearFirst, onCheckedChange = { hearFirst = !it })
            }
        }
        LiveMeter(if (playing) null else state, trail)
        when {
            playing -> Text("Listen…", color = m.onSurfaceVariant)
            showStaff && !revealed -> Text("Imagine it first, then sing. You'll hear the real note after.", color = m.onSurfaceVariant)
        }
        voice.error?.let { Text(it, color = m.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { scope.launch { hear(target) } }, enabled = !playing) { Text(if (showStaff && !revealed) "Hear it" else "Hear it again") }
            TextButton(onClick = { index++ }) { Text("Skip this one") }
        }
        if (speaker) {
            Text("With headphones, the note can keep playing while you sing.", style = MaterialTheme.typography.bodySmall, color = m.onSurfaceVariant)
        }
        if (state?.hint == NoteMatch.Hint.WAY_LOW || state?.hint == NoteMatch.Hint.WAY_HIGH) {
            Text("Far off? Slide on an \"ooo\" like a siren, and stop when the dot reaches the band.",
                style = MaterialTheme.typography.bodySmall, color = Brass)
        }
    }
}

private const val ROUND = 5

/** [n] notes from [pool], no note twice in a row. */
private fun roundOf(pool: List<Int>, n: Int): List<Int> {
    val out = mutableListOf<Int>()
    repeat(n) { out += pool.filter { it != out.lastOrNull() }.ifEmpty { pool }.random() }
    return out
}

// ---- Name it ----

@Composable
private fun NameIt(progress: ReadingProgress, modifier: Modifier, onAnswer: (Int, Boolean) -> Unit, onDone: () -> Unit) {
    val m = MaterialTheme.colorScheme
    val home = progress.voice
    val pool = home.firstFive
    val tone = rememberTonePlayer()
    var count by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf(progress.deck.next(pool, null)) }
    var answered by remember { mutableStateOf<String?>(null) }
    val right = answered == Staff.letter(note)

    fun next() {
        count++
        note = progress.deck.next(pool, note)
        answered = null
    }
    LaunchedEffect(answered, note) {
        if (answered != null && right) { delay(1_200); next() }
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (count >= NAME_ROUND) {
            Text("That's ten.", style = MaterialTheme.typography.headlineSmall)
            Text("The ones you missed come back more often until they stick.", color = m.onSurfaceVariant)
            StartButton("Ten more", { count = 0 }, Modifier.fillMaxWidth())
            OutlinedButton(onClick = onDone, Modifier.fillMaxWidth()) { Text("Back to reading and pitch") }
            return@Column
        }
        Text("${count + 1} of $NAME_ROUND", style = MaterialTheme.typography.labelLarge, color = m.onSurfaceVariant)
        StaffNote(note, home.clef, noteColor = when {
            answered == null -> m.onSurface
            right -> m.primary
            else -> Brass
        })
        Text("Which note is this?", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Staff.letters.forEach { l ->
                val isAnswer = answered != null && l == Staff.letter(note)
                val isWrongPick = answered == l && !right
                val bg = when { isAnswer -> m.primary; isWrongPick -> Brass.copy(alpha = 0.35f); else -> m.surfaceVariant }
                val fg = if (isAnswer) m.onPrimary else m.onSurface
                Text(
                    l, color = fg, style = MaterialTheme.typography.titleLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(bg)
                        .clickable(enabled = answered == null, onClickLabel = "Answer $l") {
                            answered = l
                            onAnswer(note, l == Staff.letter(note))
                            tone.play(note)
                        }
                        .padding(vertical = 16.dp),
                )
            }
        }
        val p = Staff.place(note, home.clef)
        when {
            answered == null -> Text("Tip: count up from a line you know. In treble clef the bottom line is E.",
                style = MaterialTheme.typography.bodySmall, color = m.onSurfaceVariant)
            right -> Text("Yes, ${Staff.letter(note)}, ${Staff.describe(p)}.", style = MaterialTheme.typography.titleMedium, color = m.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            else -> {
                Text("That one's ${Staff.letter(note)}, ${Staff.describe(p)}.", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                StartButton("Next", { next() }, Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { tone.play(note) }) { Text("Hear this note") }
    }
}

private const val NAME_ROUND = 10

// ---- Check my pitch ----

/** Sing anything and see the nearest note, on the staff and by name. */
@Composable
private fun CheckPitch(clef: Clef, modifier: Modifier) {
    val m = MaterialTheme.colorScheme
    val voice = rememberLiveVoice()
    val tone = rememberTonePlayer()
    var nearest by remember { mutableStateOf<Int?>(null) }
    var state by remember { mutableStateOf<NoteMatch.State?>(null) }
    val trail = remember { mutableStateListOf<Double?>() }
    LaunchedEffect(Unit) { voice.start() }
    LaunchedEffect(voice.listening) {
        if (!voice.listening) return@LaunchedEffect
        voice.frames.collect { f ->
            if (!f.clear) {
                state = state?.copy(hint = if (f.db < NoteMatch.SILENT_DB) NoteMatch.Hint.WAITING else NoteMatch.Hint.UNCLEAR, cents = null)
                trail += null
            } else {
                val n = f.semis.roundToInt()
                nearest = n
                val c = (f.semis - n) * 100.0
                state = NoteMatch.State(if (kotlin.math.abs(c) <= NoteMatch.BEGINNER_CENTS) NoteMatch.Hint.ON else if (c < 0) NoteMatch.Hint.A_LITTLE_LOW else NoteMatch.Hint.A_LITTLE_HIGH,
                    c, 0, 0f, false)
                trail += c
            }
            if (trail.size > 90) trail.removeAt(0)
        }
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        val n = nearest
        Text(if (n == null) "Sing or hum any note" else "Nearest note: ${Staff.name(n)}", style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        StaffNote(n, clef)
        LiveMeter(state, trail)
        voice.error?.let { Text(it, color = m.error) }
        if (n != null) OutlinedButton(onClick = { voice.stop(); tone.play(n) }) { Text("Play ${Staff.name(n)}") }
        if (!voice.listening) TextButton(onClick = { voice.start() }) { Text("Listen again") }
        Text("Your teacher's tip: give yourself a pitch, and check it often while you practice.",
            style = MaterialTheme.typography.bodySmall, color = m.onSurfaceVariant)
    }
}
