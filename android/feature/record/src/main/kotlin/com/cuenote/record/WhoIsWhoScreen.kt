package com.cuenote.record

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * A five-second step before every lesson: the student talks, and the app
 * works out which input their transmitter is on. The two transmitters look
 * the same and get handed out at random, so this is decided fresh each time.
 */
data class WhoIsWhoState(
    val secondsLeft: Int? = null,
    val problem: String? = null,
    /** True once the channels are settled; [switched] says how. */
    val done: Boolean = false,
    val switched: Boolean = false,
    val error: String? = null,
)

class WhoIsWhoViewModel(private val app: Application) : AndroidViewModel(app) {
    private val recorder = LessonRecorder(app).apply { switchChannels = false } // judge the raw inputs
    private val rules = SetupCheck()
    private var job: Job? = null
    private val _state = MutableStateFlow(WhoIsWhoState())
    val state: StateFlow<WhoIsWhoState> = _state
    val liveLevels = recorder.levels

    init {
        try {
            recorder.start()
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Could not open the microphone") }
        }
    }

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
            if (fail == null) finish(outcome.switchChannels)
            else _state.update { it.copy(secondsLeft = null, problem = fail.detail) }
        }
    }

    /** Record anyway with the student assumed on the left. */
    fun skip() = finish(false)

    private fun finish(switch: Boolean) {
        job?.cancel()
        recorder.stop() // free the mic for the recording service
        ChannelSwap.set(app, switch)
        _state.update { it.copy(secondsLeft = null, done = true, switched = switch) }
    }

    override fun onCleared() = recorder.stop()

    companion object { const val SECONDS = 4 }
}

@Composable
fun WhoIsWhoScreen(onReady: () -> Unit, onCancel: () -> Unit, vm: WhoIsWhoViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val live by vm.liveLevels.collectAsStateWithLifecycle()
    LaunchedEffect(s.done) { if (s.done) onReady() }

    Column(Modifier.padding(16.dp)) {
        Text("Who's on which mic?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.padding(4.dp))
        s.error?.let {
            Text(it, color = Color(0xFFB3261E))
            TextButton(onClick = onCancel) { Text("Back") }
            return@Column
        }
        Text("Student: say a sentence or two, like \"Hi, I'm ready to sing.\" Teacher: stay quiet for a moment.")
        Spacer(Modifier.padding(8.dp))
        // Raw inputs here: the app hasn't decided yet who is on which side.
        Text("Left input", style = MaterialTheme.typography.labelMedium)
        LiveMeterBar(live?.student?.rmsDb)
        Text("Right input", style = MaterialTheme.typography.labelMedium)
        LiveMeterBar(live?.teacher?.rmsDb)
        Spacer(Modifier.padding(8.dp))
        when {
            s.secondsLeft != null -> Text("Listening… ${s.secondsLeft}")
            else -> {
                s.problem?.let { Text(it, color = Color(0xFFB3261E)) }
                Button(onClick = vm::listen, modifier = Modifier.fillMaxWidth()) {
                    Text(if (s.problem == null) "Start" else "Try again")
                }
                TextButton(onClick = vm::skip) { Text("Skip: the student is on the left mic") }
            }
        }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}
