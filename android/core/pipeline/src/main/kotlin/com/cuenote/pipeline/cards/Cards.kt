package com.cuenote.pipeline.cards

import com.cuenote.pipeline.FeedbackBlock
import com.cuenote.pipeline.Pipeline
import com.cuenote.pipeline.Speaker
import com.cuenote.pipeline.Take
import com.cuenote.pipeline.TranscriptLine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * M3: what turns a processed lesson into cards, minus the parts that need
 * Android (encoding, the network). Pure Kotlin so it is tested on the JVM.
 *
 *   CloudIds     the cloud uuid of each take and feedback block
 *   ClipPlan     which stretches of the lesson become replayable clips
 *   ClipAudio    stereo to one mono track for replay
 *   CueRequest   the body the phone sends to the extract-cues function
 */

/**
 * The pipeline names takes and blocks "<lesson>-take-001" and
 * "<lesson>-fb-001"; the cloud tables want uuids. The uuid is a hash of the
 * local id, so the phone, the cloud and the sketches code (photo_mark
 * .feedback_id) all agree without storing a mapping, and uploading a lesson
 * twice updates the same rows.
 */
object CloudIds {
    fun of(localId: String): String = UUID.nameUUIDFromBytes("cuenote:$localId".toByteArray()).toString()
    fun take(localId: String) = of(localId)
    fun feedback(localId: String) = of(localId)
    fun exercise(localId: String) = of(localId)
}

/** One stretch of the lesson to cut into a clip for replay. */
data class ClipSpan(
    /** "take" (the student singing) or "feedback" (the teacher's block). */
    val kind: String,
    /** The pipeline's id of the take or block. */
    val localId: String,
    /** The cloud id, which also names the clip file. */
    val cloudId: String,
    val startMs: Long,
    val endMs: Long,
) {
    val fileName get() = "$cloudId.${ClipPlan.EXTENSION}"
    val durationMs get() = endMs - startMs
}

object ClipPlan {
    /**
     * Clips are AAC in .m4a at 64 kbps mono: about the size the design doc
     * planned for Opus, and they play in every browser the teacher might use,
     * Safari on an iPhone included, and in Android's own player.
     */
    const val EXTENSION = "m4a"
    const val MIME = "audio/mp4"

    /** A little lead-in and tail, so a clip doesn't start mid-breath. */
    const val TAKE_PAD_MS = 400L
    const val FEEDBACK_PAD_MS = 200L

    /** Longer feedback is cut short; the transcript still has every word. */
    const val MAX_CLIP_MS = 5 * 60_000L

    fun plan(takes: List<Take>, feedback: List<FeedbackBlock>, lessonMs: Long): List<ClipSpan> {
        fun span(kind: String, id: String, a: Long, b: Long, pad: Long): ClipSpan {
            val start = (a - pad).coerceAtLeast(0)
            val end = minOf(b + pad, if (lessonMs > 0) lessonMs else Long.MAX_VALUE, start + MAX_CLIP_MS)
            return ClipSpan(kind, id, CloudIds.of(id), start, end)
        }
        return (takes.map { span("take", it.id, it.startMs, it.endMs, TAKE_PAD_MS) } +
            feedback.map { span("feedback", it.id, it.startMs, it.endMs, FEEDBACK_PAD_MS) })
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }
    }
}

object ClipAudio {
    /**
     * Both microphones into one track: the student's channel carries their
     * voice and the piano, hers carries her voice and her demos, so replay
     * sounds like the room. Interleaved 16-bit stereo in, mono out.
     */
    fun mixToMono(stereo: ShortArray): ShortArray {
        val n = stereo.size / 2
        return ShortArray(n) { i ->
            ((stereo[2 * i].toInt() + stereo[2 * i + 1].toInt()) / 2).toShort()
        }
    }

    /**
     * The gain that brings a clip's loudest sample to about -1 dBFS, at most
     * 4x so a near-silent clip isn't turned into hiss.
     */
    fun normalizeGain(mono: ShortArray): Float {
        var peak = 0
        for (s in mono) peak = maxOf(peak, abs(s.toInt()))
        if (peak == 0) return 1f
        return minOf(4f, 0.89f * Short.MAX_VALUE / peak)
    }

    fun applyGain(mono: ShortArray, gain: Float) {
        if (gain == 1f) return
        for (i in mono.indices) mono[i] = (mono[i] * gain).roundToInt().coerceIn(-32768, 32767).toShort()
    }
}

/** The body of a call to extract-cues (supabase/functions/extract-cues/cues.ts, Request). */
@Serializable
data class CueRequest(
    @SerialName("lesson_id") val lessonId: String,
    val blocks: List<Block>,
    val transcript: List<Line>,
    /** Set to compare models on the lesson; the function then writes no cards. */
    @SerialName("trial_models") val trialModels: List<String>? = null,
) {
    @Serializable
    data class Block(
        val id: String,
        @SerialName("local_id") val localId: String,
        @SerialName("take_id") val takeId: String?,
        @SerialName("span_start_ms") val spanStartMs: Long,
        @SerialName("span_end_ms") val spanEndMs: Long,
        @SerialName("clip_path") val clipPath: String?,
        val transcript: String,
        @SerialName("link_confidence") val linkConfidence: Float,
        val demos: Int,
        /** The warmup or song section the block is part of (cloud uuid), or null. */
        @SerialName("exercise_id") val exerciseId: String? = null,
    )

    @Serializable
    data class Line(
        val speaker: String,
        @SerialName("start_ms") val startMs: Long,
        @SerialName("end_ms") val endMs: Long,
        val text: String,
    )

    companion object {
        /** The teacher's words inside a block, in order. */
        fun wordsIn(fb: FeedbackBlock, transcript: List<TranscriptLine>): String =
            transcript.filter { it.speaker == Speaker.TEACHER && it.startMs < fb.endMs && it.endMs > fb.startMs }
                .joinToString(" ") { it.text.trim() }.trim()

        /**
         * Builds the request from a pipeline run. [clipPath] gives the storage
         * path of a block's clip once it is uploaded (null if it isn't).
         */
        fun from(lessonId: String, out: Pipeline.Output, clipPath: (localId: String) -> String?): CueRequest = CueRequest(
            lessonId = lessonId,
            blocks = out.feedback.map { fb ->
                Block(
                    id = CloudIds.feedback(fb.id),
                    localId = fb.id,
                    takeId = fb.takeId?.let(CloudIds::take),
                    spanStartMs = fb.startMs,
                    spanEndMs = fb.endMs,
                    clipPath = clipPath(fb.id),
                    transcript = wordsIn(fb, out.transcript),
                    linkConfidence = fb.linkConfidence,
                    demos = fb.demos.size,
                    exerciseId = fb.exerciseId?.let(CloudIds::exercise),
                )
            },
            transcript = out.transcript.filter { it.text.isNotBlank() }
                .map { Line(it.speaker.name.lowercase(), it.startMs, it.endMs, it.text.trim()) },
        )
    }
}

/** What a model comparison returns: each model's cards for the lesson and what it cost. */
@Serializable
data class ModelTrials(val trials: List<Trial>) {
    @Serializable
    data class Trial(
        val model: String,
        @SerialName("cost_usd") val costUsd: Double? = null,
        @SerialName("input_tokens") val inputTokens: Int = 0,
        @SerialName("output_tokens") val outputTokens: Int = 0,
        val error: String? = null,
        val cards: List<Card> = emptyList(),
    )

    @Serializable
    data class Card(
        /** The card's cloud id (feedback.id). */
        val id: String,
        val cue: String = "",
        @SerialName("her_words") val herWords: String = "",
        val category: String? = null,
        @SerialName("practice_flag") val practiceFlag: String? = null,
        @SerialName("bar_start") val barStart: Int? = null,
        @SerialName("bar_end") val barEnd: Int? = null,
        /** Its quote wasn't her words and was replaced with her sentence. */
        val repaired: Boolean = false,
        val missing: Boolean = false,
    )

    companion object {
        /** The models to compare, cheapest first, with the names people know them by. */
        val MODELS = linkedMapOf(
            "claude-haiku-5-5" to "Haiku 5.5",
            "claude-sonnet-5-5" to "Sonnet 5.5",
            "claude-opus-5-5" to "Opus 5.5",
            "claude-fable-5-2" to "Fable 5.2",
        )
    }
}
