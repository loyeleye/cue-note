package com.cuenote.pipeline

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The five things that can be happening at any moment of a lesson, plus nothing. */
@Serializable
enum class Label(val short: String) {
    @SerialName("student_sing") STUDENT_SING("student_sing"),
    @SerialName("student_speak") STUDENT_SPEAK("student_speak"),
    @SerialName("teacher_speak") TEACHER_SPEAK("teacher_speak"),
    @SerialName("teacher_sing") TEACHER_SING("teacher_sing"),
    @SerialName("piano") PIANO("piano"),
    @SerialName("silence") SILENCE("silence");

    val isTeacher get() = this == TEACHER_SPEAK || this == TEACHER_SING
    val isSpeech get() = this == TEACHER_SPEAK || this == STUDENT_SPEAK
}

@Serializable
data class Segment(val label: Label, val startMs: Long, val endMs: Long) {
    val durationMs get() = endMs - startMs
}

/** A stretch of the student singing: one attempt at an exercise or a phrase. */
@Serializable
data class Take(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    /** Student pitch every 32 ms frame, in Hz, 0 where unvoiced. */
    val pitchHz: List<Float> = emptyList(),
    /** YIN confidence for each pitch frame, 0..1. Below [PipelineConfig.unclearConfidence] the frame is "unclear". */
    val pitchConfidence: List<Float> = emptyList(),
    /** True when the piano was playing during the take (accompanied). */
    val withPiano: Boolean = false,
    /** The warmup or song section this take is one try of. */
    val exerciseId: String? = null,
)

/**
 * One warmup, or one section of a song: the student's tries at it and all
 * her feedback in between, from her setting it up to moving on. Feedback
 * often runs across several tries while they work something out, so a card
 * belongs to the exercise as a whole and, when it clearly is, to one try.
 */
@Serializable
data class Exercise(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    /** "warmup" or "song"; a first guess from how long the tries run. */
    val kind: String,
    val takeIds: List<String>,
    /** Why a new exercise starts here (a long break, or what she said). */
    val reason: String = "",
) {
    companion object {
        const val WARMUP = "warmup"
        const val SONG = "song"
    }
}

/** The teacher singing a phrase for the student to copy, inside a feedback block. */
@Serializable
data class Demo(
    val startMs: Long,
    val endMs: Long,
    /** The take whose phrase this demo models, and where in that take. */
    val takeId: String? = null,
    val phraseStartMs: Long? = null,
    val phraseEndMs: Long? = null,
    /** Lower is a closer melodic match (mean semitones after shifting to the same key). */
    val matchDistance: Float? = null,
)

/** The teacher talking (and maybe demonstrating) between takes. Becomes a card in M3. */
@Serializable
data class FeedbackBlock(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    /** The take this feedback is about, or null when there is none before it (the lesson's opening chat). */
    val takeId: String? = null,
    /** How sure the linker is, 0..1. Lower when the take was long ago. */
    val linkConfidence: Float = 0f,
    val linkReason: String = "",
    val demos: List<Demo> = emptyList(),
    /** The exercise this feedback is part of, or null between exercises (the opening chat). */
    val exerciseId: String? = null,
)

@Serializable
enum class Speaker { @SerialName("student") STUDENT, @SerialName("teacher") TEACHER }

@Serializable
data class TranscriptLine(val speaker: Speaker, val startMs: Long, val endMs: Long, val text: String)

/** A mark or a photo from events.tsv, joined to the card it belongs to. */
@Serializable
data class EventLink(
    val kind: String,          // "mark" or "photo"
    val atMs: Long,
    val fileName: String? = null,
    val feedbackId: String? = null,
    val takeId: String? = null,
)

@Serializable
data class LinkResult(
    val feedback: List<FeedbackBlock>,
    val events: List<EventLink>,
)
