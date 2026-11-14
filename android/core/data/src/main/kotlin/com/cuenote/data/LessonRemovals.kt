package com.cuenote.data

import com.cuenote.pipeline.Removal
import com.cuenote.pipeline.Removals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The parts of each lesson the student deleted (see [Removals]), read and
 * written in one place so the lesson screen, Undo and the worker that does
 * the deleting never step on each other.
 */
object LessonRemovals {
    /** How long Undo is offered after a delete. */
    const val UNDO_MS = 10_000L

    private val lock = Any()
    private val flows = ConcurrentHashMap<String, MutableStateFlow<List<Removal>>>()

    private fun flow(dir: File) = flows.getOrPut(dir.absolutePath) { MutableStateFlow(Removals.read(dir)) }

    fun observe(dir: File): StateFlow<List<Removal>> = flow(dir).asStateFlow()

    fun read(dir: File): List<Removal> = synchronized(lock) { Removals.read(dir) }

    /** Changes the list in one step and tells anyone watching. Returns the new list. */
    fun update(dir: File, change: (List<Removal>) -> List<Removal>): List<Removal> = synchronized(lock) {
        val before = Removals.read(dir)
        val after = change(before)
        if (after != before) Removals.write(dir, after)
        flow(dir).value = after
        after
    }

    /** Forgets a deleted lesson's list (its folder is gone already). */
    fun forget(dir: File) {
        flows.remove(dir.absolutePath)
    }

    /** Still inside the undo window. */
    fun undoable(r: Removal, now: Long = System.currentTimeMillis()) = !r.committed && now < r.at + UNDO_MS
}

/**
 * A lesson's exercises, tries, feedback and cards, less the parts the student
 * deleted. Pure, so it is tested on the JVM. The lesson screen hides a part
 * the moment it is deleted, before the deleting itself is done.
 */
object LessonParts {
    /** Later than any lesson runs (a thousand hours), and small enough that a midpoint can't overflow. */
    const val END_OF_LESSON = 3_600_000_000L
    data class Parts(
        val exercises: List<ExerciseEntity>,
        val takes: List<TakeEntity>,
        val feedback: List<FeedbackEntity>,
        val cards: List<CardEntity>,
    )

    fun cut(removals: List<Removal>, exercises: List<ExerciseEntity>) =
        Removals.Cut(removals, exercises.map { Removals.Cut.Span(it.id, it.startMs, it.endMs) })

    fun without(parts: Parts, removals: List<Removal>): Parts {
        if (removals.isEmpty()) return parts
        val cut = cut(removals, parts.exercises)
        val goneFeedback = parts.feedback.filter { cut.feedbackGone(it.spanStartMs, it.spanEndMs, it.exerciseId) }.map { it.id }.toSet()
        return Parts(
            exercises = parts.exercises.filterNot { it.id in cut.goneExercises },
            takes = parts.takes.filterNot { cut.takeGone(it.id, it.startMs, it.endMs, it.exerciseId) },
            feedback = parts.feedback.filterNot { it.id in goneFeedback },
            cards = parts.cards.filterNot { cardGone(it, cut, goneFeedback) },
        )
    }

    /** A card goes with its feedback block, or when it falls in a deleted part (one a teacher added, say). */
    fun cardGone(c: CardEntity, cut: Removals.Cut, goneFeedback: Set<String>) =
        (c.localId != null && c.localId in goneFeedback) || cut.parts.any { it.covers(c.spanStartMs, c.spanEndMs) }

    /** Cards the cloud still has but the student deleted, for while the delete online is still to come. */
    fun dropDeletedCards(cards: List<CardEntity>, removals: List<Removal>): List<CardEntity> {
        val parts = removals.filter { it.whole && it.committed }
        if (parts.isEmpty()) return cards
        return cards.filterNot { c -> parts.any { it.covers(c.spanStartMs, c.spanEndMs) } }
    }

    /**
     * The stretch an exercise takes up, its tries and her feedback included,
     * or ([toEnd]) everything from it to the end of the recording.
     */
    fun span(
        e: ExerciseEntity, takes: List<TakeEntity>, feedback: List<FeedbackEntity>, lessonMs: Long, toEnd: Boolean,
    ): LongRange {
        val mine = takes.filter { it.exerciseId == e.id }.map { it.startMs to it.endMs } +
            feedback.filter { it.exerciseId == e.id }.map { it.spanStartMs to it.spanEndMs }
        val start = minOf(e.startMs, mine.minOfOrNull { it.first } ?: e.startMs)
        // to the end: past anything the recording could hold, so nothing after it is left
        val last = if (toEnd) maxOf(lessonMs, END_OF_LESSON) else maxOf(e.endMs, mine.maxOfOrNull { it.second } ?: e.endMs)
        return start..last
    }
}
