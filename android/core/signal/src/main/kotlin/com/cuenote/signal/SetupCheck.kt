package com.cuenote.signal

/**
 * The rules behind the in-app setup check, which replaces the manual Audacity
 * test. Each step records a few seconds and is judged here. The numbers in
 * [SetupThresholds] are starting values to tune after the first real lessons.
 */
data class SetupThresholds(
    /** Each voice must be this much louder on its own channel than on the other. */
    val separationDb: Double = 10.0,
    /** Below this a voice is too quiet: transmitter off, muted or too far away. */
    val minVoiceDb: Double = -50.0,
    /** More clipped samples than this fails the loud-note step. */
    val maxClipFraction: Double = 0.0005,
    /** Above this correlation with near-equal levels, the phone mixed to mono. */
    val monoCorrelation: Double = 0.98,
    val monoLevelDiffDb: Double = 1.5,
    /** The student's soft singing must stay this far above piano bleed on the left channel. */
    val pianoMarginDb: Double = 6.0,
)

sealed interface StepResult {
    data object Pass : StepResult
    data class Fail(val problem: Problem, val detail: String) : StepResult
}

enum class Problem {
    MONO_MIX,         // both channels carry the same signal
    TOO_QUIET,        // nothing on the expected channel
    CHANNELS_SWAPPED, // the voice is on the other person's channel
    NOT_SEPARATED,    // voice is on both channels about equally
    CLIPPING,         // too loud: lower receiver gain or move the mic
    PIANO_TOO_LOUD,   // the piano drowns the student's soft singing
}

class SetupCheck(private val t: SetupThresholds = SetupThresholds()) {

    /** One person speaks; their voice should land on [expected]. */
    fun voiceStep(expected: Channel, levels: StereoLevels): StepResult {
        if (isMonoMix(levels)) return StepResult.Fail(
            Problem.MONO_MIX,
            "Both channels sound the same. The phone may be mixing the mic to mono. Check the receiver is in stereo mode.",
        )
        val own = levels[expected].rmsDb
        val other = levels[expected.other].rmsDb
        if (own < t.minVoiceDb && other < t.minVoiceDb) return StepResult.Fail(
            Problem.TOO_QUIET,
            "Almost nothing was heard. Check the transmitter is on and not muted.",
        )
        if (other - own >= t.separationDb) return StepResult.Fail(
            Problem.CHANNELS_SWAPPED,
            "This voice landed on the other channel. Swap the transmitters, or swap the channels on the receiver.",
        )
        if (own - other < t.separationDb) return StepResult.Fail(
            Problem.NOT_SEPARATED,
            "This voice is on both channels (only %.0f dB apart). Clip each transmitter close to its wearer's mouth.".format(own - other),
        )
        return StepResult.Pass
    }

    /** Someone sings a loud note; neither channel may clip. */
    fun loudStep(levels: StereoLevels): StepResult {
        val worst = Channel.entries.maxBy { levels[it].clipFraction }
        if (levels[worst].clipFraction > t.maxClipFraction) return StepResult.Fail(
            Problem.CLIPPING,
            "The ${worst.label()} channel clipped. Lower the receiver gain or move that mic a little further from the mouth.",
        )
        return StepResult.Pass
    }

    /**
     * The piano test. [pianoOnly] is the teacher playing alone, [softWithPiano]
     * the student singing softly over it, [loudWithPiano] singing loudly while
     * she plays loud chords.
     */
    fun pianoStep(pianoOnly: StereoLevels, softWithPiano: StereoLevels, loudWithPiano: StereoLevels): StepResult {
        val margin = softWithPiano.student.rmsDb - pianoOnly.student.rmsDb
        if (margin < t.pianoMarginDb) return StepResult.Fail(
            Problem.PIANO_TOO_LOUD,
            "Soft singing is only %.0f dB above the piano on your channel. Clip your transmitter on the side away from the piano, or ask her to play a little softer.".format(margin),
        )
        return loudStep(loudWithPiano)
    }

    fun isMonoMix(levels: StereoLevels): Boolean =
        levels.correlation >= t.monoCorrelation &&
            kotlin.math.abs(levels.student.rmsDb - levels.teacher.rmsDb) <= t.monoLevelDiffDb

    private fun Channel.label() = if (this == Channel.STUDENT) "student (left)" else "teacher (right)"
}
