package com.cuenote.data

import com.cuenote.pipeline.Removal
import com.cuenote.pipeline.cards.CloudIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonPartsTest {
    private fun ex(id: String, start: Long, end: Long) = ExerciseEntity(id, "l", start, end, "warmup", "")
    private fun take(id: String, start: Long, ex: String?) = TakeEntity(id, "l", start, start + 3_000, "{}", "[]", false, ex)
    private fun fb(id: String, start: Long, ex: String?, take: String? = null) =
        FeedbackEntity(id, "l", take, start, start + 5_000, 1f, "", "[]", "words", ex)
    private fun card(local: String?, start: Long) = CardEntity(
        id = CloudIds.feedback(local ?: "added-$start"), lessonId = "l", localId = local, takeId = null,
        spanStartMs = start, spanEndMs = start + 5_000, cue = "cue", category = null, herWords = "", practiceFlag = null,
        barStart = null, barEnd = null, transcript = "", editedByTeacher = false, updatedAt = "",
    )

    // The lesson (a warmup, 0-60 s), then the recorder left running (two "warmups" of chatter, 60-1800 s).
    private val exercises = listOf(ex("e1", 0, 60_000), ex("e2", 60_000, 600_000), ex("e3", 600_000, 1_800_000))
    private val takes = listOf(take("t1", 1_000, "e1"), take("t2", 10_000, "e1"), take("t3", 70_000, "e2"), take("t4", 700_000, "e3"))
    private val feedback = listOf(fb("f1", 5_000, "e1", take = "t1"), fb("f2", 80_000, "e2"), fb("f3", 650_000, "e3"))
    private val cards = listOf(card("f1", 5_000), card("f2", 80_000), card("f3", 650_000), card(null, 900_000))
    private val all = LessonParts.Parts(exercises, takes, feedback, cards)

    @Test fun `deleting from an exercise to the end leaves only the lesson`() {
        val span = LessonParts.span(exercises[1], takes, feedback, lessonMs = 1_800_000, toEnd = true)
        assertEquals(60_000L, span.first)
        assertTrue(span.last > 1_800_000)
        val r = Removal("r", Removal.PART, span.first, span.last, exerciseIds = listOf("e2", "e3"))
        val left = LessonParts.without(all, listOf(r))
        assertEquals(listOf("e1"), left.exercises.map { it.id })
        assertEquals(listOf("t1", "t2"), left.takes.map { it.id })
        assertEquals(listOf("f1"), left.feedback.map { it.id })
        // a card a teacher added in the chatter goes too
        assertEquals(listOf("f1"), left.cards.map { it.localId })
    }

    @Test fun `deleting one exercise leaves the ones after it`() {
        val span = LessonParts.span(exercises[1], takes, feedback, lessonMs = 1_800_000, toEnd = false)
        assertEquals(60_000L..600_000L, span)
        val left = LessonParts.without(all, listOf(Removal("r", Removal.PART, span.first, span.last, exerciseIds = listOf("e2"))))
        assertEquals(listOf("e1", "e3"), left.exercises.map { it.id })
        assertEquals(listOf("t1", "t2", "t4"), left.takes.map { it.id })
    }

    @Test fun `deleting a try keeps the exercise and her notes`() {
        val left = LessonParts.without(all, listOf(Removal("r", Removal.TAKE, 1_000, 4_000, takeId = "t1")))
        assertEquals(3, left.exercises.size)
        assertEquals(listOf("t2", "t3", "t4"), left.takes.map { it.id })
        assertEquals(3, left.feedback.size)
        assertEquals(4, left.cards.size)
    }

    @Test fun `cards from the cloud in a deleted part stay off until it's deleted online`() {
        val pending = Removal("r", Removal.PART, 60_000, 1_800_001)
        assertEquals(4, LessonParts.dropDeletedCards(cards, listOf(pending)).size)   // Undo still offered
        assertEquals(1, LessonParts.dropDeletedCards(cards, listOf(pending.copy(committed = true))).size)
    }

    @Test fun `Undo is offered only for a while`() {
        val r = Removal("r", Removal.PART, 0, 1, at = 1_000)
        assertTrue(LessonRemovals.undoable(r, now = 1_000 + LessonRemovals.UNDO_MS - 1))
        assertFalse(LessonRemovals.undoable(r, now = 1_000 + LessonRemovals.UNDO_MS))
        assertFalse(LessonRemovals.undoable(r.copy(committed = true), now = 1_001))
    }
}
