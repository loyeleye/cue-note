package com.cuenote.practice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cuenote.audio.Listening
import com.cuenote.audio.PracticeRecorder
import com.cuenote.data.PracticeRepository
import com.cuenote.signal.WavReader
import com.cuenote.signal.practice.LatencyCheck
import com.cuenote.ui.Masthead
import com.cuenote.ui.StartButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface Step {
    data object Intro : Step
    data object Running : Step
    data class Done(val result: LatencyCheck.Result) : Step
}

/**
 * The headphone timing check: ten clicks in the headphones, a short "ta"
 * sung on each one after the first two. The delay it finds is saved for
 * these headphones and used to line up sing-along takes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimingCheckScreen(repo: PracticeRepository, onDone: () -> Unit) {
    val context = LocalContext.current
    val devices = rememberDevices()
    val recorder = remember { PracticeRecorder(context) }
    var step by remember { mutableStateOf<Step>(Step.Intro) }
    var click by remember { mutableIntStateOf(-1) }
    DisposableEffect(recorder) { onDispose { recorder.stop()?.file?.delete() } }

    if (step == Step.Running) {
        LaunchedEffect(Unit) {
            val file = File(context.cacheDir, "timing-check.wav")
            val output = devices.output
            val started = runCatching { recorder.start(file, LatencyCheck.clickTrack()) }
            if (started.isFailure) {
                step = Step.Done(LatencyCheck.Result.TooQuiet)
                return@LaunchedEffect
            }
            // Light up a dot for each click as it is heard: from what the
            // headphones have actually played, so the dots never run ahead
            // of the sound (which would tempt an early "ta").
            while (!recorder.playbackDone.value) {
                val ms = recorder.playbackHeardMs() ?: -1L
                click = LatencyCheck.clickTimesMs.indexOfLast { it <= ms }
                delay(16)
            }
            val take = recorder.stop()
            val result = withContext(Dispatchers.Default) {
                if (take == null) LatencyCheck.Result.TooQuiet
                else {
                    val pcm = WavReader.read(take.file).samples
                    take.file.delete()
                    LatencyCheck.measure(pcm, take.playbackStartFrame ?: 0L)
                }
            }
            if (result is LatencyCheck.Result.Measured) repo.timing.save(output, result.latencyMs)
            click = -1
            step = Step.Done(result)
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Timing check") }, navigationIcon = { TextButton(onClick = onDone) { Text("Back") } })
    }) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Masthead("Check your ", "timing")
            val noHeadphones = devices.output.listening == Listening.SPEAKER
            when (val s = step) {
                Step.Intro -> {
                    Text(
                        "Headphones take a moment to play sound, and the mic takes a moment to hear it. " +
                            "This check measures that, so when you sing along later, your take lines up with the music.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "You'll hear ten clicks. Listen to the first two, then sing a short \"ta\" right on each of the rest. " +
                            "It takes about ten seconds.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (noHeadphones) {
                        Text(
                            "Plug in your headphones first, so the clicks don't reach the mic.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    StartButton("Start the clicks", { click = -1; step = Step.Running }, Modifier.fillMaxWidth(), enabled = !noHeadphones, dot = false)
                }
                Step.Running -> {
                    val sung = LatencyCheck.CLICKS - LatencyCheck.LISTEN_CLICKS
                    Text(
                        if (click < LatencyCheck.LISTEN_CLICKS) "Listen…"
                        else "Sing \"ta\" · ${click - LatencyCheck.LISTEN_CLICKS + 1} of $sung",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    ClickDots(click)
                    Text(
                        "Listen to two clicks, then a short, light \"ta\" right on each one.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { recorder.stop()?.file?.delete(); click = -1; step = Step.Intro },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    ) { Text("Stop") }
                }
                is Step.Done -> {
                    val (title, body, ok) = describe(s.result)
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Text(body, style = MaterialTheme.typography.bodyLarge)
                    if (ok) {
                        Spacer(Modifier.weight(1f))
                        StartButton("Back to practice", onDone, Modifier.fillMaxWidth(), dot = false)
                        TextButton(onClick = { step = Step.Running }) { Text("Check again") }
                    } else {
                        StartButton("Try again", { step = Step.Running }, Modifier.fillMaxWidth(), enabled = !noHeadphones, dot = false)
                        TextButton(onClick = onDone) { Text("Not now") }
                    }
                }
            }
        }
    }
}

/**
 * One dot per click, each in a fixed slot so nothing shifts: the two
 * listen-only clicks in grey under "Listen", the sung ones in plum under
 * "Sing", filled as each click is heard and the current one ringed.
 */
@Composable
private fun ClickDots(current: Int) {
    val m = MaterialTheme.colorScheme
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 8.dp).semantics { contentDescription = "Click ${current + 1} of ${LatencyCheck.CLICKS}" },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until LatencyCheck.CLICKS) {
                val on = i <= current
                val listen = i < LatencyCheck.LISTEN_CLICKS
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(18.dp)
                            .then(if (i == current) Modifier.border(2.dp, if (listen) m.onSurfaceVariant else m.primary, CircleShape).padding(3.dp) else Modifier)
                            .then(
                                when {
                                    on && !listen -> Modifier.background(m.primary, CircleShape)
                                    on -> Modifier.background(m.onSurfaceVariant, CircleShape)
                                    else -> Modifier.border(1.5.dp, m.outline, CircleShape)
                                },
                            ),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Listen", style = MaterialTheme.typography.labelMedium, color = m.onSurfaceVariant,
                modifier = Modifier.width(24.dp * LatencyCheck.LISTEN_CLICKS + 8.dp * (LatencyCheck.LISTEN_CLICKS - 1)))
            Text("Sing", style = MaterialTheme.typography.labelMedium, color = m.primary)
        }
    }
}

/** What to tell the student: a title, a line, and whether it worked. */
private fun describe(r: LatencyCheck.Result): Triple<String, String, Boolean> = when (r) {
    is LatencyCheck.Result.Measured ->
        if (!r.tooSlow) Triple("All set", "Sing-along takes with these headphones will line up with the music.", true)
        else Triple(
            "Saved, with one tip",
            "These headphones run quite a bit behind, so singing along may feel a little late. " +
                "Your takes will still line up afterwards. Wired headphones feel more natural.",
            true,
        )
    LatencyCheck.Result.TooQuiet -> Triple(
        "The mic didn't catch you",
        "Hold the phone a little closer, then try again. A short, clear \"ta\" is plenty.",
        false,
    )
    is LatencyCheck.Result.TooFewHits -> Triple(
        "A few clicks were missed",
        "Sing a short \"ta\" on every click after the first two.",
        false,
    )
    is LatencyCheck.Result.Uneven -> Triple(
        "The timing wandered a little",
        "Try again, keeping each \"ta\" short and right on the click.",
        false,
    )
}
