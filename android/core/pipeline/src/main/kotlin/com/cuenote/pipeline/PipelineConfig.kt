package com.cuenote.pipeline

import kotlinx.serialization.Serializable

/**
 * Every threshold the pipeline uses, in one place. The values are starting
 * points tuned on synthetic lessons; the golden set of real lessons is what
 * they get tuned against next. A lesson's pipeline/config.json, when present,
 * overrides them for that lesson, which is handy while tuning.
 */
@Serializable
data class PipelineConfig(
    // --- who is active -----------------------------------------------------
    /** Below this a channel counts as quiet, in dBFS. */
    val activeFloorDb: Double = -50.0,
    /** Voice-detector probability that counts as a voice. */
    val vadThreshold: Float = 0.5f,
    /**
     * Each person's own mic hears them much louder than the other mic does.
     * A voice is the student's when the student channel is at least this many
     * dB less loud than the teacher's (so a negative number allows the piano
     * on her channel to be louder), and the teacher's when the teacher
     * channel is at least this much louder than the student's.
     */
    val studentDominanceDb: Double = -4.0,
    val teacherDominanceDb: Double = 4.0,
    /** A pitched sound steadier than this (cents, frame to frame) is an instrument, not a voice. */
    val instrumentSteadinessCents: Double = 4.0,
    /** A note fading faster than this (dB per second) with little wobble is a struck string. */
    val instrumentDecayDbPerSec: Double = -6.0,
    val instrumentFadingWobbleCents: Double = 14.0,

    // --- singing or speaking -----------------------------------------------
    /** YIN confidence above which a frame has a usable pitch. */
    val voicedConfidence: Double = 0.5,
    /** Below this confidence a take's pitch frame is shown as unclear. */
    val unclearConfidence: Float = 0.6f,
    /** A held note: voiced frames that stay within this many semitones of each other. */
    val noteToleranceSemitones: Double = 0.9,
    /** ... for at least this many frames (32 ms each). */
    val minNoteFrames: Int = 8,
    /** Fraction of a window spent on held notes that makes it singing. */
    val singingSustainRatio: Double = 0.45,
    /** Unbroken voicing this long (frames) is legato singing, even when the notes are quick. */
    val legatoFrames: Int = 20,
    val singingLegatoRatio: Double = 0.6,
    /** A student voice with piano notes this close (frames either side, about 2 s) is singing along. */
    val accompanimentWindowFrames: Int = 62,
    /** Window used for the singing/speaking decision, in frames each side. */
    val styleWindowFrames: Int = 23,

    // --- tidying -----------------------------------------------------------
    val smoothFrames: Int = 7,
    val minSegmentMs: Long = 300,
    /** Silence shorter than this between two segments of the same kind joins them. */
    val bridgeMs: Long = 700,

    // --- takes and feedback ------------------------------------------------
    /** Pauses (silence or piano only) shorter than this stay inside one take. */
    val takeJoinMs: Long = 4_000,
    val minTakeMs: Long = 1_200,
    /** When the pause is mostly piano (a song's interlude), the take holds together this long. */
    val takePianoJoinMs: Long = 10_000,
    /** Talking or teacher singing shorter than this inside a take does not end it. */
    val takeBreakMs: Long = 1_000,
    /** Teacher stretches closer than this, even with a student question between, form one feedback block. */
    val feedbackJoinMs: Long = 5_000,
    val minFeedbackMs: Long = 600,
    val minDemoMs: Long = 800,

    // --- linking -------------------------------------------------------------
    /** Feedback that starts this long after its take still links, with falling confidence. */
    val linkFullConfidenceMs: Long = 20_000,
    val linkMaxMs: Long = 180_000,
    /** Photos join the feedback block that ends up to this long before them, or starts this long after. */
    val photoAfterMs: Long = 90_000,
    val photoBeforeMs: Long = 30_000,
    val markWindowMs: Long = 30_000,

    // --- transcription -----------------------------------------------------
    val utteranceJoinMs: Long = 1_000,
    val maxUtteranceMs: Long = 28_000,
    val utterancePadMs: Long = 250,
    val transcriptPrompt: String =
        "A singing lesson. Breath support, vowel, legato, passaggio, head voice, chest voice, " +
            "placement, resonance, pitch, flat, sharp, bar, phrase, vibrato.",
)
