package com.cuenote.data

import com.cuenote.pipeline.cards.CloudIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonOutlineTest {
    private fun ex(id: String, start: Long, end: Long, kind: String = "warmup", title: String? = null) =
        ExerciseEntity(id, "l", start, end, kind, "", title)

    private fun take(id: String, start: Long, ex: String?) =
        TakeEntity(id, "l", start, start + 3_000, "{}", "[]", withPiano = true, exerciseId = ex)

    private fun fb(id: String, start: Long, ex: String?) =
        FeedbackEntity(id, "l", null, start, start + 5_000, 1f, "", "[]", "words", ex)

    private fun card(local: String, start: Long, cue: String, flag: String? = null, category: String? = "resonance", take: String? = null) =
        CardEntity(
            id = CloudIds.feedback(local), lessonId = "l", localId = local, takeId = take?.let(CloudIds::take),
            spanStartMs = start, spanEndMs = start + 5_000, cue = cue, category = category, herWords = "", practiceFlag = flag,
            barStart = null, barEnd = null, transcript = "", editedByTeacher = false, updatedAt = "",
        )

    @Test fun `cards sit under their exercise, chat left out, and repeats become one`() {
        val exercises = listOf(ex("e1", 0, 60_000), ex("e2", 60_000, 200_000, "song", title = "Morning Light, verse"))
        val takes = listOf(take("t1", 1_000, "e1"), take("t2", 10_000, "e1"), take("t3", 70_000, "e2"))
        val feedback = listOf(fb("f1", 5_000, "e1"), fb("f2", 15_000, "e1"), fb("f3", 80_000, "e2"), fb("f4", 90_000, "e2"), fb("f5", 95_000, "e2"))
        val cards = listOf(
            card("f1", 5_000, "Oo lips, ee inside"),
            card("f2", 15_000, "oo lips — ee inside!", flag = "practice_this"),
            card("f3", 80_000, "Land high notes from above", take = "t3"),
            card("f4", 90_000, "", category = "chat"),
            card("f5", 95_000, "Breathe low before the phrase", flag = "practice_this"),
        )
        val out = LessonOutline.build(exercises, takes, feedback, cards)
        assertEquals(listOf("Warmup", "Morning Light, verse"), out.map { it.name })
        val warm = out[0]
        assertEquals(listOf("t1", "t2"), warm.takes.map { it.id })
        assertEquals(1, warm.cues.size)
        assertEquals("f2", warm.cues[0].card.localId)   // the "Practice this" one leads
        assertEquals(listOf("f1"), warm.cues[0].repeats.map { it.localId })
        assertTrue(warm.cues[0].practice)
        val song = out[1]
        // Practice this first, chat gone.
        assertEquals(listOf("Breathe low before the phrase", "Land high notes from above"), song.cues.map { it.card.cue })
        assertEquals(3, song.feedbackCount)
    }

    @Test fun `only three cues show, the rest and the blank ones fold away`() {
        val exercises = listOf(ex("e1", 0, 100_000))
        val cards = (1..5).map { card("f$it", it * 1_000L, "Cue $it") } + card("f9", 9_000, "")
        val s = LessonOutline.build(exercises, emptyList(), emptyList(), cards).single()
        assertEquals(LessonOutline.MAIN_CUES, s.cues.size)
        assertEquals(listOf("Cue 4", "Cue 5", ""), s.more.map { it.card.cue })
    }

    @Test fun `unnamed exercises are numbered, and a lesson without exercises is one section`() {
        val names = LessonOutline.names(listOf(ex("a", 0, 1), ex("b", 2, 3), ex("c", 4, 5, "song"), ex("d", 6, 7, "song")))
        assertEquals(listOf("Warmup 1", "Warmup 2", "Song, part 1", "Song, part 2"), listOf("a", "b", "c", "d").map(names::getValue))
        val old = LessonOutline.build(emptyList(), listOf(take("t1", 0, null)), emptyList(), listOf(card("f1", 4_000, "Sing it")))
        assertEquals("This lesson", old.single().name)
        assertEquals(1, old.single().cues.size)
    }

    @Test fun `a card with no link falls into the exercise it was said in`() {
        val exercises = listOf(ex("e1", 0, 50_000), ex("e2", 50_000, 90_000))
        val loose = card("teacher-added", 60_000, "Keep the hum buzzing").copy(localId = null)
        val out = LessonOutline.build(exercises, emptyList(), emptyList(), listOf(loose))
        assertEquals(1, out[1].cues.size)
        assertTrue(out[0].cues.isEmpty())
    }
}
