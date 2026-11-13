package com.cuenote.record

import android.app.Application
import android.os.BatteryManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cuenote.audio.ChannelSwap
import com.cuenote.audio.LessonRecorder
import com.cuenote.audio.MicInfo
import com.cuenote.audio.UsbMic
import com.cuenote.posture.Speaker
import com.cuenote.signal.Channel
import com.cuenote.signal.MicVerdict
import com.cuenote.signal.Problem
import com.cuenote.signal.SetupCheck
import com.cuenote.signal.StepEar
import com.cuenote.signal.SyllableCounter
import com.cuenote.signal.StepResult
import com.cuenote.signal.StereoLevels
import com.cuenote.signal.posture.Voice
import com.cuenote.signal.posture.VoiceLine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The soundcheck's steps, in order. Each has a friendly prompt to sing or
 * say (shown big, in quotes, so nobody reads instructions mid-lesson), who
 * should make sound, who should stay quiet, and the line that reads it aloud.
 * [seconds] is the most a step waits: it ends sooner once it has heard [need].
 */
enum class SetupStep(
    val title: String,
    val who: String,
    val prompt: String,
    val seconds: Int,
    /** Whose mic should stay quiet in this step, if anyone's. */
    val quiet: Who?,
    val line: VoiceLine,
    val need: StepEar.Need,
    /** For a Do · Re · Mi step: the verb shown above the big syllables, which light as they're heard. */
    val verb: String? = null,
    /** A reassuring line under the syllables. */
    val hint: String? = null,
) {
    STUDENT_SPEAK(
        "Student's Do Re Mi", "Student", "Sing \"Do · Re · Mi.\"", 12, Who.TEACHER,
        VoiceLine.CHECK_STUDENT, StepEar.Need(soundMs = 1_200, waitForPause = true),
        verb = "Sing", hint = "Any comfortable note.",
    ),
    TEACHER_SPEAK(
        "Teacher's Do Re Mi", "Teacher", "Speak or sing \"Do · Re · Mi.\"", 12, Who.STUDENT,
        VoiceLine.CHECK_TEACHER, StepEar.Need(soundMs = 1_200, waitForPause = true),
        verb = "Speak or sing", hint = "Speaking is fine.",
    ),
    LOUD_NOTE(
        "One big note", "Student", "Sing your biggest comfortable note and hold it.", 12, null,
        VoiceLine.CHECK_BIG_NOTE, StepEar.Need(soundMs = 2_500),
    ),
    PIANO_ALONE(
        "Piano solo", "Teacher", "Play an exercise at your usual volume.", 12, Who.STUDENT,
        VoiceLine.CHECK_PIANO, StepEar.Need(soundMs = 3_000),
    ),
    PIANO_SOFT(
        "Soft with piano", "Both", "Play it again. Student, sing it softly.", 15, null,
        VoiceLine.CHECK_PIANO_SOFT, StepEar.Need(both = true, soundMs = 3_000),
    ),
    PIANO_LOUD(
        "Full voice + piano", "Both", "Big chords, big voice.", 12, null,
        VoiceLine.CHECK_PIANO_LOUD, StepEar.Need(both = true, soundMs = 2_500),
    ),
    ;

    enum class Who { STUDENT, TEACHER }
}

/** Where the current step is: waiting for the first Start, getting ready, listening, or showing its result. */
enum class StepPhase { IDLE, GET_READY, LISTENING, PASSED, FAILED }

data class SetupUiState(
    /** Do · Re · Mi syllables heard so far in this step (0 to 3). */
    val syllables: Int = 0,
    /** In the teacher step: the student's mic is the one hearing it. */
    val wrongMic: Boolean = false,
    val mic: MicInfo? = null,
    val routedTo: String = "",
    val batteryPercent: Int = 100,
    val charging: Boolean = false,
    val step: SetupStep = SetupStep.STUDENT_SPEAK,
    val secondsLeft: Int? = null,
    val phase: StepPhase = StepPhase.IDLE,
    /** No chime after each step, for a quiet room. */
    val quietCheck: Boolean = false,
    /** A recorded voice reads each step aloud, so nobody has to watch the screen. */
    val spoken: Boolean = true,
    /** Whose voice reads it (and every other spoken prompt in the app). */
    val voice: Voice = Voice.DEFAULT,
    val results: Map<SetupStep, StepResult> = emptyMap(),
    val error: String? = null,
    val done: Boolean = false,
    /** Student on the right input, so recordings switch the channels. */
    val channelsSwitched: Boolean = false,
    /** Set when this check switched them, to tell the user why. */
    val switchedThisCheck: Boolean = false,
    /** Once done: which mic is whose, and how sure the check is. The lesson uses it without asking again. */
    val verdict: MicVerdict? = null,
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
    // How far apart the voices were, once the student's mic was settled (dB).
    private var studentMargin: Double? = null
    private var teacherMargin: Double? = null
    private var job: Job? = null

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()
    val liveLevels = recorder.levels
    private val prefs = app.getSharedPreferences("soundcheck", android.content.Context.MODE_PRIVATE)
    private val chime = Chime()
    private val speaker = Speaker(app)
    /** The intro is read once, before the first step of the first run. */
    private var introSaid = false

    init {
        val battery = app.getSystemService(BatteryManager::class.java)
        _state.update {
            it.copy(
                mic = UsbMic.describe(UsbMic.find(app)),
                batteryPercent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                charging = battery.isCharging,
                channelsSwitched = recorder.switchChannels,
                quietCheck = prefs.getBoolean("quiet", false),
                spoken = prefs.getBoolean("spoken", true),
                voice = Speaker.voice(app),
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
     * Starts the check, which then runs hands-free: each step is read aloud
     * (when spoken steps are on), listens until it has heard enough or its
     * seconds run out, a pass stamps and moves on after a short "get ready",
     * and only a failure stops and waits for "Try again".
     */
    fun runStep() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            if (_state.value.spoken && !introSaid && _state.value.step == SetupStep.STUDENT_SPEAK) {
                introSaid = true
                _state.update { it.copy(phase = StepPhase.GET_READY) }
                speaker.sayAndWait(VoiceLine.CHECK_INTRO)
            }
            while (true) {
                val step = _state.value.step
                getReady(step)
                if (!listen(step)) return@launch // failed: wait for Try again
                delay(PASS_HOLD_MS)
                if (!advance()) return@launch // all done
            }
        }
    }

    /**
     * The "get ready" before a step listens: its line read aloud, then a
     * beat, so the instruction never ends up in the measurement. Without the
     * voice it's a short pause, as before.
     */
    private suspend fun getReady(step: SetupStep) {
        _state.update { it.copy(phase = StepPhase.GET_READY, syllables = 0, wrongMic = false) }
        if (_state.value.spoken) {
            speaker.sayAndWait(step.line)
            delay(AFTER_LINE_MS)
        } else {
            delay(GET_READY_MS)
        }
    }

    /**
     * Listens to one step and judges it; true when it passed (or only
     * captured). It ends early once it has heard enough and the step already
     * passes, and otherwise gives everyone the full [SetupStep.seconds].
     */
    private suspend fun listen(step: SetupStep): Boolean {
        recorder.resetWindow()
        _state.update { it.copy(phase = StepPhase.LISTENING, secondsLeft = step.seconds, syllables = 0, wrongMic = false) }
        var ear = StepEar(step.need)
        // Before the student step settles the channels, either mic can be the student's.
        fun counter() = step.verb?.let { SyllableCounter(if (step == SetupStep.TEACHER_SPEAK) Channel.TEACHER else null) }
        var syllables = counter()
        fun hear() = viewModelScope.launch {
            liveLevels.filterNotNull().collect { block ->
                ear.add(block)
                syllables?.let { c ->
                    c.add(block)
                    val s = _state.value
                    if (s.syllables != c.heard || s.wrongMic != c.wrongMic) {
                        _state.update { it.copy(syllables = c.heard, wrongMic = c.wrongMic) }
                    }
                }
            }
        }
        var hearing = hear()
        var start = android.os.SystemClock.elapsedRealtime()
        var endAt = start + step.seconds * 1_000L
        var warned = false
        try {
            while (true) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now >= endAt) break
                if (syllables?.wrongMic == true && _state.value.spoken && !warned) {
                    // Said once per step, then the step starts over so the voice isn't in the measurement.
                    warned = true
                    hearing.cancel()
                    speaker.sayAndWait(VoiceLine.CHECK_WRONG_MIC)
                    delay(AFTER_LINE_MS)
                    recorder.resetWindow()
                    ear = StepEar(step.need)
                    syllables = counter()
                    _state.update { it.copy(syllables = 0, wrongMic = false, secondsLeft = step.seconds) }
                    hearing = hear()
                    start = android.os.SystemClock.elapsedRealtime()
                    endAt = start + step.seconds * 1_000L
                    continue
                }
                val heardSoFar = now - start >= MIN_LISTEN_MS && ear.enough()
                if (heardSoFar && passesSoFar(step)) break
                val left = ((endAt - now + 999) / 1_000).toInt()
                if (left != _state.value.secondsLeft) _state.update { it.copy(secondsLeft = left) }
                delay(TICK_MS)
            }
        } finally {
            hearing.cancel()
        }
        captured[step] = recorder.windowLevels()
        val result = judge(step)
        if (step == SetupStep.STUDENT_SPEAK) {
            val levels = captured.getValue(step)
            val outcome = rules.studentStep(levels)
            if (outcome.switchChannels) setSwitched(!recorder.switchChannels, byCheck = true)
            // Measured before the switch, so judged the way the switch reads it.
            studentMargin = MicVerdict.margin(if (outcome.switchChannels) levels.swapped() else levels, Channel.STUDENT)
            teacherMargin = null
        }
        if (step == SetupStep.TEACHER_SPEAK) teacherMargin = MicVerdict.margin(captured.getValue(step), Channel.TEACHER)
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
        if (!passed && _state.value.spoken) {
            delay(CHIME_MS)
            speaker.say(VoiceLine.CHECK_AGAIN)
        }
        return passed
    }

    /** Whether the step, judged on what it has heard so far, would already pass. */
    private fun passesSoFar(step: SetupStep): Boolean {
        val levels = recorder.windowLevels()
        return when (step) {
            SetupStep.STUDENT_SPEAK -> rules.studentStep(levels).result == StepResult.Pass
            SetupStep.TEACHER_SPEAK -> rules.teacherStep(levels) == StepResult.Pass
            SetupStep.LOUD_NOTE -> rules.loudStep(levels) == StepResult.Pass
            SetupStep.PIANO_ALONE, SetupStep.PIANO_SOFT -> true
            SetupStep.PIANO_LOUD -> rules.pianoStep(
                captured.getValue(SetupStep.PIANO_ALONE),
                captured.getValue(SetupStep.PIANO_SOFT),
                levels,
            ) == StepResult.Pass
        }
    }

    fun setQuietCheck(on: Boolean) {
        prefs.edit().putBoolean("quiet", on).apply()
        _state.update { it.copy(quietCheck = on) }
    }

    fun setSpoken(on: Boolean) {
        prefs.edit().putBoolean("spoken", on).apply()
        if (!on) speaker.stopAll()
        _state.update { it.copy(spoken = on) }
    }

    /** Picks the voice, and plays a line in it so you can hear the choice. */
    fun setVoice(voice: Voice) {
        Speaker.setVoice(app, voice)
        _state.update { it.copy(voice = voice) }
        if (job?.isActive != true) {
            speaker.stopAll()
            speaker.say(VoiceLine.CHECK_DONE.takeIf { _state.value.done } ?: VoiceLine.CHECK_INTRO)
        }
    }

    /** Skips the piano steps, for lessons without a piano. */
    fun skipPiano() {
        finish()
    }

    /** The check is over: decide who's on which mic and keep it for the lesson. */
    private fun finish() {
        val verdict = studentMargin?.let { MicVerdict.judge(recorder.switchChannels, it, teacherMargin) }
        if (verdict != null) ChannelSwap.saveSoundcheck(app, verdict)
        _state.update { it.copy(done = true, verdict = verdict) }
        if (_state.value.spoken) speaker.say(VoiceLine.CHECK_DONE)
    }

    fun retry() {
        val step = _state.value.step
        val failed = _state.value.results[step] as? StepResult.Fail
        if (failed?.problem == Problem.SAME_CHANNEL) { studentMargin = null; teacherMargin = null }
        when {
            // Both voices on one channel: the student step has to be redone too.
            failed?.problem == Problem.SAME_CHANNEL -> _state.update {
                it.copy(step = SetupStep.STUDENT_SPEAK, results = emptyMap(), switchedThisCheck = false)
            }
            // A failed piano result means redoing all three piano steps.
            step == SetupStep.PIANO_LOUD -> _state.update { it.copy(step = SetupStep.PIANO_ALONE, results = it.results - step) }
            else -> _state.update { it.copy(results = it.results - step) }
        }
        speaker.stopAll()
        job = null
        runStep()
    }

    /** Manual override: switch which input is the student's and start the check over. */
    fun switchChannelsManually() {
        if (job?.isActive == true) return
        setSwitched(!recorder.switchChannels, byCheck = false)
        captured.clear()
        studentMargin = null; teacherMargin = null
        _state.update {
            it.copy(step = SetupStep.STUDENT_SPEAK, results = emptyMap(), done = false, verdict = null, phase = StepPhase.IDLE)
        }
    }

    private fun setSwitched(on: Boolean, byCheck: Boolean) {
        recorder.switchChannels = on
        ChannelSwap.set(app, on)
        _state.update { it.copy(channelsSwitched = on, switchedThisCheck = byCheck) }
    }

    /** Moves to the next step; false when that was the last one. */
    private fun advance(): Boolean {
        val next = SetupStep.entries.getOrNull(_state.value.step.ordinal + 1)
        if (next == null) finish() else _state.update { it.copy(step = next, phase = StepPhase.GET_READY) }
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
        speaker.close()
    }

    private companion object {
        /** How long a passed step's stamp shows before the next step. */
        const val PASS_HOLD_MS = 1_200L
        /** "Get ready" before each step listens, when the steps aren't read aloud. */
        const val GET_READY_MS = 2_000L
        /** A beat between a step's spoken line and listening. */
        const val AFTER_LINE_MS = 400L
        /** Every step listens at least this long, however quickly it hears enough. */
        const val MIN_LISTEN_MS = 2_000L
        const val TICK_MS = 200L
        /** Lets the "not quite" chime finish before the voice explains. */
        const val CHIME_MS = 600L
    }
}
