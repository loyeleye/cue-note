package com.cuenote.record

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cuenote.signal.StepResult
import com.cuenote.signal.StereoLevels

@Composable
fun SetupCheckScreen(onDone: () -> Unit, vm: SetupCheckViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val live by vm.liveLevels.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Setup check", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.padding(4.dp))

        // Before the steps: is the receiver there, and is there enough battery?
        when {
            s.mic == null -> Warning("No USB mic found. Plug the Lark receiver into the phone's USB-C port.")
            !s.mic!!.supportsStereo -> Warning("${s.mic!!.name} reports no stereo input. The check will confirm.")
            else -> Text("Mic: ${s.mic!!.name}. Recording from: ${s.routedTo}")
        }
        Text("Clip your transmitter on the side of your collar away from the piano, with noise cancellation off.")
        if (s.batteryLow) {
            Warning("Battery is at ${s.batteryPercent}%. Charge to at least 50% or put the phone on the wireless charger.")
        }
        s.error?.let { Warning(it); return@Column }

        Spacer(Modifier.padding(8.dp))
        LiveMeters(live, s.channelsSwitched)
        if (s.switchedThisCheck) {
            Text(
                "The student is on the right mic, so Cue Note switched the channels for you. " +
                    "Nobody needs to swap transmitters. The teacher step checks it worked.",
                color = Color(0xFF2E7D32),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.padding(8.dp))

        SetupStep.entries.forEach { step ->
            val r = s.results[step]
            val mark = when {
                r == StepResult.Pass -> "✓"
                r is StepResult.Fail -> "✗"
                step == s.step && !s.done -> "▶"
                else -> "·"
            }
            Text("$mark ${step.title}", style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.padding(8.dp))

        if (s.done) {
            Text("All set. Both voices are on their own channels.", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            return@Column
        }

        val failed = s.results[s.step] as? StepResult.Fail
        Text(s.step.instruction, style = MaterialTheme.typography.titleMedium)
        when {
            s.secondsLeft != null -> Text("Listening… ${s.secondsLeft}")
            failed != null -> {
                Warning(failed.detail)
                Button(onClick = vm::retry) { Text("Try again") }
            }
            else -> Button(onClick = vm::runStep, modifier = Modifier.fillMaxWidth()) { Text("Start") }
        }
        if (s.step.ordinal >= SetupStep.PIANO_ALONE.ordinal) {
            TextButton(onClick = vm::skipPiano) { Text("No piano today: skip the piano test") }
        }
        TextButton(onClick = vm::switchChannelsManually, enabled = s.secondsLeft == null) {
            Text("Switch student and teacher channels")
        }
        OutlinedButton(onClick = onDone) { Text("Leave the check") }
    }
}

@Composable
fun LiveMeters(levels: StereoLevels?, switched: Boolean = false) {
    // Levels are already in student/teacher order; the side says which input each came from.
    Column {
        Meter(if (switched) "Student (right)" else "Student (left)", levels?.student?.rmsDb)
        Meter(if (switched) "Teacher (left)" else "Teacher (right)", levels?.teacher?.rmsDb)
    }
}

@Composable
private fun Meter(label: String, db: Double?) {
    Row {
        Text(label, Modifier.width(130.dp))
        LiveMeterBar(db, Modifier.padding(top = 8.dp))
    }
}

/** One live level bar, mapping -60..0 dBFS onto its width. */
@Composable
fun LiveMeterBar(db: Double?, modifier: Modifier = Modifier) {
    val fraction = ((db ?: -60.0) + 60.0).coerceIn(0.0, 60.0) / 60.0
    LinearProgressIndicator(progress = { fraction.toFloat() }, modifier = modifier.fillMaxWidth())
}

@Composable
private fun Warning(text: String) {
    Text(text, color = Color(0xFFB3261E), style = MaterialTheme.typography.bodyMedium)
}
