package com.cuenote.pipeline

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Parts of a lesson the student deleted on the phone: a whole exercise (or
 * everything from one on, when the recorder was left running after the
 * lesson), or a single try. Kept in <lesson>/removed.json, outside
 * <lesson>/pipeline/, so "Process again from scratch" keeps them.
 *
 * A deletion is a stretch of the lesson rather than a list of ids, since
 * processing again can number exercises and takes differently. Something is in
 * the stretch when its middle is.
 *
 * A deletion starts out pending, so the student can undo it for a few seconds.
 * Only [Removal.committed] ones change what the pipeline gives out
 * ([apply]); once committed the audio in the stretch is silenced too
 * ([com.cuenote.pipeline.audio.AudioEraser]).
 */
@Serializable
data class Removal(
    val id: String,
    /** [PART]: everything in the stretch. [TAKE]: only the try (her notes around it stay). */
    val kind: String,
    val startMs: Long,
    val endMs: Long,
    /** For [TAKE]: the pipeline's id of the try. */
    val takeId: String? = null,
    /** For [PART]: the pipeline's ids of the exercises it deletes. */
    val exerciseIds: List<String> = emptyList(),
    /** What the student saw ("Warmup 3", "Try 2 of Warmup 1"), for the Undo bar. */
    val label: String = "",
    /** Epoch ms the student deleted it. */
    val at: Long = 0,
    /** Past the undo window: the phone's copies are gone. */
    val committed: Boolean = false,
    /** The phone's copies are gone: its clips, notes and audio. */
    val localDone: Boolean = false,
    /** Gone online too (or there was never anything online). */
    val cloudDone: Boolean = false,
) {
    fun covers(startMs: Long, endMs: Long): Boolean {
        val mid = (startMs + endMs) / 2
        return mid >= this.startMs && mid < this.endMs
    }

    val whole get() = kind == PART

    companion object {
        const val PART = "part"
        const val TAKE = "take"
    }
}

object Removals {
    const val FILE = "removed.json"
    private val list = ListSerializer(Removal.serializer())

    fun file(lessonDir: File) = File(lessonDir, FILE)

    fun read(lessonDir: File): List<Removal> =
        file(lessonDir).takeIf { it.isFile }
            ?.let { runCatching { Pipeline.json.decodeFromString(list, it.readText()) }.getOrNull() }
            .orEmpty()

    /** Written to a temporary file first, so a crash never leaves half a list. */
    fun write(lessonDir: File, removals: List<Removal>) {
        lessonDir.mkdirs()
        val tmp = File(lessonDir, "$FILE.part")
        tmp.writeText(Pipeline.json.encodeToString(list, removals))
        val dest = file(lessonDir)
        check(tmp.renameTo(dest) || run { dest.delete(); tmp.renameTo(dest) }) { "could not save $FILE" }
    }

    /** What is left of the pipeline's output once [removals] are taken out. */
    fun apply(out: Pipeline.Output, removals: List<Removal>): Pipeline.Output {
        if (removals.isEmpty()) return out
        val cut = Cut(removals, out.exercises.map { Cut.Span(it.id, it.startMs, it.endMs) })
        val takes = out.takes.filterNot { cut.takeGone(it) }
        val keptTakes = takes.map { it.id }.toSet()
        val exercises = out.exercises.filterNot { it.id in cut.goneExercises }
            .map { e -> e.copy(takeIds = e.takeIds.filter { it in keptTakes }) }
        val feedback = out.feedback.filterNot { cut.feedbackGone(it.startMs, it.endMs, it.exerciseId) }.map { f ->
            f.copy(
                takeId = f.takeId?.takeIf { it in keptTakes },
                linkConfidence = if (f.takeId != null && f.takeId !in keptTakes) 0f else f.linkConfidence,
                demos = f.demos.map { d -> if (d.takeId != null && d.takeId !in keptTakes) d.copy(takeId = null, phraseStartMs = null, phraseEndMs = null) else d },
            )
        }
        val keptFeedback = feedback.map { it.id }.toSet()
        return out.copy(
            segments = out.segments.filterNot { s -> removals.any { it.covers(s.startMs, s.endMs) } },
            takes = takes,
            feedback = feedback,
            exercises = exercises,
            // Everything said in a deleted part; in a deleted try, what the student sang.
            transcript = out.transcript.filterNot { l ->
                cut.parts.any { it.covers(l.startMs, l.endMs) } ||
                    (l.speaker == Speaker.STUDENT && cut.tries.any { it.covers(l.startMs, l.endMs) })
            },
            events = out.events.map { e ->
                e.copy(
                    feedbackId = e.feedbackId?.takeIf { it in keptFeedback },
                    takeId = e.takeId?.takeIf { it in keptTakes },
                )
            },
        )
    }

    /** Which exercises, takes and feedback a set of removals takes out. */
    class Cut(private val removals: List<Removal>, exercises: List<Span>) {
        /** An exercise's id and where it runs. */
        data class Span(val id: String, val startMs: Long, val endMs: Long)

        val parts = removals.filter { it.whole }
        val tries = removals.filter { !it.whole }
        private val tryIds = tries.mapNotNull { it.takeId }.toSet()

        val goneExercises: Set<String> = exercises.filter { e ->
            parts.any { e.id in it.exerciseIds || it.covers(e.startMs, e.endMs) }
        }.map { it.id }.toSet()

        fun takeGone(t: Take) = takeGone(t.id, t.startMs, t.endMs, t.exerciseId)

        fun takeGone(id: String, startMs: Long, endMs: Long, exerciseId: String?) =
            id in tryIds || exerciseId in goneExercises || removals.any { it.covers(startMs, endMs) }

        fun feedbackGone(startMs: Long, endMs: Long, exerciseId: String?) =
            exerciseId in goneExercises || parts.any { it.covers(startMs, endMs) }
    }
}
