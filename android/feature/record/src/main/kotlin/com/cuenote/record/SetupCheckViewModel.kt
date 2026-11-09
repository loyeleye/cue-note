package com.cuenote.record

import android.app.Application
import android.os.BatteryManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cuenote.audio.LessonRecorder
import com.cuenote.audio.MicInfo
import com.cuenote.audio.UsbMic
import com.cuenote.signal.Channel
import com.cuenote.signal.SetupCheck
import com.cuenote.signal.StepResult
import com.cuenote.signal.StereoLevels
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SetupStep(val title: String, val instruction: String, val seconds: Int) {
    STUDENT_SPEAK("Student speaks", "Student: talk normally for a few seconds. Teacher: stay quiet.", 5),
    TEACHER_SPEAK("Teacher speaks", "Teacher: talk normally for a few seconds. Student: stay quiet.", 5),
    LOUD_NOTE("Loud note", "Student: sing your loudest comfortable note and hold it.", 4),
    PIANO_ALONE("Piano alone", "Teacher: play one exercise at your usual volume. Student: stay quiet.", 5),
    PIANO_SOFT("Soft singing with piano", "Teacher: play the exercise again. Student: sing it softly.", 6),
    PIANO_LOUD("Loud singing with piano", "Teacher: play loud chords. Student: sing loudly.", 5),
}

data class SetupUiState(
    val mic: MicInfo? = null,
    val routedTo: String = "",
    val batteryPercent: Int = 100,
    val charging: Boolean = false,
    val step: SetupStep = SetupStep.STUDENT_SPEAK,
    val secondsLeft: Int? = null,
    val results: Map<SetupStep, StepResult> = emptyMap(),
    val error: String? = null,
    val done: Boolean = false,
) {
    val batteryLow: Boolean get() = !charging && batteryPercent < LOW_BATTERY_PERCENT

    companion object {
        /** A starting value to tune: an hour of audio-only recording should fit above this. */
        const val LOW_BATTERY_PERCENT = 50
    }
}

class SetupCheckViewModel(app: Application) : AndroidViewModel(app) {
    private val recorder = LessonRecorder(app)
    private val rules = SetupCheck()
    private val captured = mutableMapOf<SetupStep, StereoLevels>()
    private var job: Job? = null

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()
    val liveLevels = recorder.levels

    init {
        val battery = app.getSystemService(BatteryManager::class.java)
        _state.update {
            it.copy(
                mic = UsbMic.describe(UsbMic.find(app)),
                batteryPercent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                charging = battery.isCharging,
            )
        }
        try {
            recorder.start()
            _state.update { it.copy(routedTo = recorder.routedDeviceName()) }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Could not open the microphone") }
        }
    }

    fun runStep() {
        if (job?.isActive == true) return
        val step = _state.value.step
        job = viewModelScope.launch {
            recorder.resetWindow()
            for (s in step.seconds downTo 1) {
                _state.update { it.copy(secondsLeft = s) }
                delay(1_000)
            }
            captured[step] = recorder.windowLevels()
            val result = judge(step)
            _state.update {
                it.copy(
                    secondsLeft = null,
                    routedTo = recorder.routedDeviceName(),
                    results = if (result != null) it.results + (step to result) else it.results,
                )
            }
            if (result == null || result == StepResult.Pass) advance()
        }
    }

    /** Skips the piano steps, for lessons without a piano. */
    fun skipPiano() {
        _state.update { it.copy(done = true) }
    }

    fun retry() {
        val step = _state.value.step
        // A failed piano result means redoing all three piano steps.
        val target = if (step == SetupStep.PIANO_LOUD) SetupStep.PIANO_ALONE else step
        _state.update { it.copy(step = target, results = it.results - step) }
    }

    private fun advance() {
        val next = SetupStep.entries.getOrNull(_state.value.step.ordinal + 1)
        _state.update { if (next == null) it.copy(done = true) else it.copy(step = next) }
    }

    /** Returns null for steps that only capture audio for a later step. */
    private fun judge(step: SetupStep): StepResult? = when (step) {
        SetupStep.STUDENT_SPEAK -> rules.voiceStep(Channel.STUDENT, captured.getValue(step))
        SetupStep.TEACHER_SPEAK -> rules.voiceStep(Channel.TEACHER, captured.getValue(step))
        SetupStep.LOUD_NOTE -> rules.loudStep(captured.getValue(step))
        SetupStep.PIANO_ALONE, SetupStep.PIANO_SOFT -> null
        SetupStep.PIANO_LOUD -> rules.pianoStep(
            captured.getValue(SetupStep.PIANO_ALONE),
            captured.getValue(SetupStep.PIANO_SOFT),
            captured.getValue(SetupStep.PIANO_LOUD),
        )
    }

    override fun onCleared() {
        recorder.stop()
    }
}
