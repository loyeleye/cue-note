package com.cuenote.record

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cuenote.audio.ChannelSwap
import com.cuenote.audio.LessonRecorder
import com.cuenote.signal.SetupCheck
import com.cuenote.signal.StepResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A short step before every lesson: which input is the student's transmitter
 * on? The two transmitters look the same and get handed out at random, so
 * this is decided fresh each time. The teacher taps the student's mic, or
 * lets the app listen for the student and pick it for them. A soundcheck run
 * just before already decided it: then its answer is filled in here, and
 * this screen only shows when the check wasn't sure or someone asks to change it.
 */
data class WhoIsWhoState(
    /** null until someone picks; true when the student is on the right input. */
    val studentOnRight: Boolean? = null,
    /** How the current pick was made: true when the app heard it. */
    val heard: Boolean = false,
    val secondsLeft: Int? = null,
    val problem: String? = null,
    val done: Boolean = false,
    val error: String? = null,
    /** Set while the pick is the one a recent soundcheck made. */
    val fromSoundcheck: ChannelSwap.Checked? = null,
)

class WhoIsWhoViewModel(private val app: Application) : AndroidViewModel(app) {
    private val recorder = LessonRecorder(app).apply { switchChannels = false } // judge the raw inputs
    private val rules = SetupCheck()
    private var job: Job? = null
    private val _state = MutableStateFlow(WhoIsWhoState())
    val state: StateFlow<WhoIsWhoState> = _state
    val liveLevels = recorder.levels

    init {
        ChannelSwap.recentSoundcheck(app)?.let { c ->
            _state.update { it.copy(studentOnRight = c.verdict.studentOnRight, fromSoundcheck = c) }
        }
        try {
            recorder.start()
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Could not open the microphone") }
        }
    }

    /** The teacher tapped a mic: that one is the student's. */
    fun pick(studentOnRight: Boolean) {
        job?.cancel()
        _state.update { it.copy(studentOnRight = studentOnRight, heard = false, secondsLeft = null, problem = null, fromSoundcheck = null) }
    }

    /** Listen to the student for a few seconds and pick their mic. */
    fun listen() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.update { it.copy(problem = null) }
            recorder.resetWindow()
            for (s in SECONDS downTo 1) {
                _state.update { it.copy(secondsLeft = s) }
                delay(1_000)
            }
            val outcome = rules.studentStep(recorder.windowLevels())
            val fail = outcome.result as? StepResult.Fail
            _state.update {
                if (fail == null) it.copy(secondsLeft = null, studentOnRight = outcome.switchChannels, heard = true, fromSoundcheck = null)
                else it.copy(secondsLeft = null, problem = fail.detail)
            }
        }
    }

    fun confirm() {
        val right = _state.value.studentOnRight ?: return
        job?.cancel()
        recorder.stop() // free the mic for the recording service
        ChannelSwap.set(app, right)
        // Picked by hand or by listening: that's newer than the soundcheck, so a lesson right after asks again.
        if (_state.value.fromSoundcheck == null) ChannelSwap.clearSoundcheck(app)
        _state.update { it.copy(secondsLeft = null, done = true) }
    }

    override fun onCleared() = recorder.stop()

    companion object { const val SECONDS = 4 }
}

@Composable
fun WhoIsWhoScreen(onReady: () -> Unit, onCancel: () -> Unit, vm: WhoIsWhoViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val live by vm.liveLevels.collectAsStateWithLifecycle()
    LaunchedEffect(s.done) { if (s.done) onReady() }

    Scaffold(
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.height(56.dp)) { Text("Cancel") }
                Button(
                    onClick = vm::confirm,
                    enabled = s.error == null && s.studentOnRight != null,
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(20.dp),
                ) { Text("Continue", style = MaterialTheme.typography.labelLarge) }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 8.dp),
        ) {
            Text("Who's on which mic?", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            s.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            Text(
                "Tap the mic the student is wearing. Not sure? Ask the student to say a few words and watch which meter moves.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            s.fromSoundcheck?.let { c ->
                Spacer(Modifier.height(16.dp))
                SoundcheckNote(c)
            }
            Spacer(Modifier.height(20.dp))

            // Raw inputs here: nothing is switched until the teacher continues.
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MicTile("Left mic", live?.student?.rmsDb, chosen = s.studentOnRight == false, other = s.studentOnRight == true,
                    onClick = { vm.pick(false) }, modifier = Modifier.weight(1f))
                MicTile("Right mic", live?.teacher?.rmsDb, chosen = s.studentOnRight == true, other = s.studentOnRight == false,
                    onClick = { vm.pick(true) }, modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(24.dp))
            ListenRow(s, onListen = vm::listen)
        }
    }
}

/**
 * One input as a big tappable tile: its live meter, and the role it gets.
 * [chosen] marks the student's mic; [other] marks the teacher's once a pick is made.
 */
@Composable
private fun MicTile(label: String, db: Double?, chosen: Boolean, other: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.selectable(selected = chosen, onClick = onClick, role = Role.RadioButton),
        shape = RoundedCornerShape(20.dp),
        color = if (chosen) colors.primaryContainer else colors.surfaceContainerLowest,
        border = BorderStroke(if (chosen) 2.dp else 1.dp, if (chosen) colors.primary else colors.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                RadioDot(chosen)
            }
            Spacer(Modifier.height(16.dp))
            LiveMeterBar(db, Modifier.height(8.dp).clip(RoundedCornerShape(50)))
            Spacer(Modifier.height(16.dp))
            val role = when {
                chosen -> "Student"
                other -> "Teacher"
                else -> "Tap if student"
            }
            Text(
                role,
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    chosen -> colors.onPrimaryContainer
                    else -> colors.onSurfaceVariant
                },
            )
        }
    }
}

/** A radio mark drawn in the app's own colours: plum ring, filled when chosen. */
@Composable
private fun RadioDot(on: Boolean) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.size(22.dp).border(2.dp, if (on) c.primary else c.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Box(Modifier.size(12.dp).background(c.primary, CircleShape))
    }
}

/** The pick a recent soundcheck made, and how sure it was. */
@Composable
private fun SoundcheckNote(c: ChannelSwap.Checked) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = colors.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val ago = if (c.minutesAgo < 1) "just now" else "${c.minutesAgo} min ago"
            Text(
                "From your soundcheck $ago: student on the ${if (c.verdict.studentOnRight) "right" else "left"} mic",
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSecondaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                confidenceLine(c.verdict) + " Tap the other mic if that's wrong.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSecondaryContainer,
            )
        }
    }
}

/** The shortcut: the app listens for the student and makes the pick. */
@Composable
private fun ListenRow(s: WhoIsWhoState, onListen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Let the app find out", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Student: sing \"Do - Re - Mi.\" Teacher: stay quiet for a moment.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            val listening = s.secondsLeft != null
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onListen, enabled = !listening) {
                    Text(
                        when {
                            listening -> "Listening… ${s.secondsLeft}"
                            s.problem != null -> "Listen again"
                            else -> "Listen"
                        },
                    )
                }
                if (s.heard && s.studentOnRight != null && !listening) {
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.size(8.dp).background(Brass, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Heard the student on the ${if (s.studentOnRight == true) "right" else "left"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            s.problem?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = colors.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
