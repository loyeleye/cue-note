package com.cuenote.record

import android.app.Application
import android.os.BatteryManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cuenote.audio.ChannelSwap
import com.cuenote.audio.LessonRecorder
import com.cuenote.audio.MicInfo
import com.cuenote.audio.UsbMic
import com.cuenote.signal.Problem
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

/**
 * The soundcheck's steps, in order. Each has a friendly prompt to sing or
 * say (shown big, in quotes, so nobody reads instructions mid-lesson), who
 * should make sound, and who should stay quiet.
 */
enum class SetupStep(
    val title: String,
    val who: String,
    val prompt: String,
    val seconds: Int,
    /** Whose mic should stay quiet in this step, if anyone's. */
    val quiet: Who?,
) {
    STUDENT_SPEAK("Hi, I'm ready", "Student", "Sing \"Hi, I'm ready to sing.\"", 5, Who.TEACHER),
    TEACHER_SPEAK("Teacher says hello", "Teacher", "Say \"Hello! Ready when you are.\"", 5, Who.STUDENT),
    LOUD_NOTE("One big note", "Student", "Sing your biggest comfortable note and hold it.", 4, null),
    PIANO_ALONE("Piano solo", "Teacher", "Play an exercise at your usual volume.", 5, Who.STUDENT),
    PIANO_SOFT("Soft with piano", "Both", "Play it again. Student, sing it softly.", 6, null),
    PIANO_LOUD("Full voice + piano", "Both", "Big chords, big voice.", 5, null),
    ;

    enum class Who { STUDENT, TEACHER }
}

/** Where the current step is: waiting for the first Start, getting ready, listening, or showing its result. */
enum class StepPhase { IDLE, GET_READY, LISTENING, PASSED, FAILED }

data class SetupUiState(
    val mic: MicInfo? = null,
    val routedTo: String = "",
    val batteryPercent: Int = 100,
    val charging: Boolean = false,
    val step: SetupStep = SetupStep.STUDENT_SPEAK,
    val secondsLeft: Int? = null,
    val phase: StepPhase = StepPhase.IDLE,
    /** No chime after each step, for a quiet room. */
    val quietCheck: Boolean = false,
    val results: Map<SetupStep, StepResult> = emptyMap(),
    val error: String? = null,
    val done: Boolean = false,
    /** Student on the right input, so recordings switch the channels. */
    val channelsSwitched: Boolean = false,
    /** Set when this check switched them, to tell the user why. */
    val switchedThisCheck: Boolean = false,
) {
    val batteryLow: Boolean get() = !charging && batteryPercent < LOW_BATTERY_PERCENT

    companion object {
        /** A starting value to tune: an hour of audio-only recording should fit above this. */
        const val LOW_BATTERY_PERCENT = 50
    }
}

class SetupCheckViewModel(private val app: Application) : AndroidViewModel(app) {
    // Start from the raw inputs: the mics are handed out at random, so the
    // student step decides the order fresh every time.
    private val recorder = LessonRecorder(app).apply { switchChannels = false }
    private val rules = SetupCheck()
    private val captured = mutableMapOf<SetupStep, StereoLevels>()
    private var job: Job? = null

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()
    val liveLevels = recorder.levels
    private val prefs = app.getSharedPreferences("soundcheck", android.content.Context.MODE_PRIVATE)
    private val chime = Chime()

    init {
        val battery = app.getSystemService(BatteryManager::class.java)
        _state.update {
            it.copy(
                mic = UsbMic.describe(UsbMic.find(app)),
                batteryPercent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                charging = battery.isCharging,
                channelsSwitched = recorder.switchChannels,
                quietCheck = prefs.getBoolean("quiet", false),
            )
        }
        try {
            recorder.start()
            _state.update { it.copy(routedTo = recorder.routedDeviceName()) }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Could not open the microphone") }
        }
    }

    /**
     * Starts the check, which then runs hands-free: each step listens for
     * its seconds, a pass stamps and moves on after a short "get ready",
     * and only a failure stops and waits for "Try again".
     */
    fun runStep() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            while (true) {
                val step = _state.value.step
                if (!listen(step)) return@launch // failed: wait for Try again
                delay(PASS_HOLD_MS)
                if (!advance()) return@launch // all done
                _state.update { it.copy(phase = StepPhase.GET_READY) }
                delay(GET_READY_MS)
            }
        }
    }

    /** Listens to one step and judges it; true when it passed (or only captured). */
    private suspend fun listen(step: SetupStep): Boolean {
        recorder.resetWindow()
        _state.update { it.copy(phase = StepPhase.LISTENING) }
        for (s in step.seconds downTo 1) {
            _state.update { it.copy(secondsLeft = s) }
            delay(1_000)
        }
        captured[step] = recorder.windowLevels()
        val result = judge(step)
        if (step == SetupStep.STUDENT_SPEAK) {
            val outcome = rules.studentStep(captured.getValue(step))
            if (outcome.switchChannels) setSwitched(!recorder.switchChannels, byCheck = true)
        }
        val passed = result == null || result == StepResult.Pass
        _state.update {
            it.copy(
                secondsLeft = null,
                phase = if (passed) StepPhase.PASSED else StepPhase.FAILED,
                routedTo = recorder.routedDeviceName(),
                results = if (result != null) it.results + (step to result) else it.results,
            )
        }
        // The window is captured, so the chime can't end up in the measurement.
        if (!_state.value.quietCheck) chime.play(passed)
        return passed
    }

    fun setQuietCheck(on: Boolean) {
        prefs.edit().putBoolean("quiet", on).apply()
        _state.update { it.copy(quietCheck = on) }
    }

    /** Skips the piano steps, for lessons without a piano. */
    fun skipPiano() {
        _state.update { it.copy(done = true) }
    }

    fun retry() {
        val step = _state.value.step
        val failed = _state.value.results[step] as? StepResult.Fail
        when {
            // Both voices on one channel: the student step has to be redone too.
            failed?.problem == Problem.SAME_CHANNEL -> _state.update {
                it.copy(step = SetupStep.STUDENT_SPEAK, results = emptyMap(), switchedThisCheck = false)
            }
            // A failed piano result means redoing all three piano steps.
            step == SetupStep.PIANO_LOUD -> _state.update { it.copy(step = SetupStep.PIANO_ALONE, results = it.results - step) }
            else -> _state.update { it.copy(results = it.results - step) }
        }
        _state.update { it.copy(phase = StepPhase.GET_READY) }
        job = viewModelScope.launch {
            delay(GET_READY_MS)
            job = null
            runStep()
        }
    }

    /** Manual override: switch which input is the student's and start the check over. */
    fun switchChannelsManually() {
        if (job?.isActive == true) return
        setSwitched(!recorder.switchChannels, byCheck = false)
        captured.clear()
        _state.update { it.copy(step = SetupStep.STUDENT_SPEAK, results = emptyMap(), done = false, phase = StepPhase.IDLE) }
    }

    private fun setSwitched(on: Boolean, byCheck: Boolean) {
        recorder.switchChannels = on
        ChannelSwap.set(app, on)
        _state.update { it.copy(channelsSwitched = on, switchedThisCheck = byCheck) }
    }

    /** Moves to the next step; false when that was the last one. */
    private fun advance(): Boolean {
        val next = SetupStep.entries.getOrNull(_state.value.step.ordinal + 1)
        _state.update { if (next == null) it.copy(done = true) else it.copy(step = next, phase = StepPhase.GET_READY) }
        return next != null
    }

    /** Returns null for steps that only capture audio for a later step. */
    private fun judge(step: SetupStep): StepResult? = when (step) {
        SetupStep.STUDENT_SPEAK -> rules.studentStep(captured.getValue(step)).result
        SetupStep.TEACHER_SPEAK -> rules.teacherStep(captured.getValue(step))
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
        chime.release()
    }

    private companion object {
        /** How long a passed step's stamp shows before the next step. */
        const val PASS_HOLD_MS = 1_200L
        /** "Get ready" before each step listens. */
        const val GET_READY_MS = 2_000L
    }
}
