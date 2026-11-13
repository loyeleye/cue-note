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

    private fun fb(id: String, start: Long, ex: String?, words: String = "words", take: String? = null, link: Float = 1f) =
        FeedbackEntity(id, "l", take, start, start + 5_000, link, "", "[]", words, ex)

    private fun card(
        local: String, start: Long, cue: String, flag: String? = null, category: String? = "resonance", take: String? = null,
        weight: Float? = null, summary: String = "",
    ) = CardEntity(
        id = CloudIds.feedback(local), lessonId = "l", localId = local, takeId = take?.let(CloudIds::take),
        spanStartMs = start, spanEndMs = start + 5_000, cue = cue, category = category, herWords = "", practiceFlag = flag,
        barStart = null, barEnd = null, transcript = "", editedByTeacher = false, updatedAt = "", summary = summary, weight = weight,
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
        // In lesson order, chat gone.
        assertEquals(listOf("Land high notes from above", "Breathe low before the phrase"), song.cues.map { it.card.cue })
        assertEquals(1, song.tryNo("t3"))
        assertEquals(3, song.feedbackCount)
    }

    @Test fun `the three points most worth drilling show, in lesson order, the rest fold away`() {
        val exercises = listOf(ex("e1", 0, 100_000))
        val w = listOf(0.2f, 0.9f, 0.1f, 0.8f, 0.7f)
        val cards = (1..5).map { card("f$it", it * 1_000L, "Cue $it", weight = w[it - 1], summary = "Point $it.") } + card("f9", 9_000, "")
        val s = LessonOutline.build(exercises, emptyList(), emptyList(), cards).single()
        assertEquals(LessonOutline.MAIN_POINTS, s.cues.size)
        assertEquals(listOf("Cue 2", "Cue 4", "Cue 5"), s.cues.map { it.card.cue })
        assertEquals("Point 2.", s.cues[0].text)
        assertEquals(listOf("Cue 1", "Cue 3", ""), s.more.map { it.card.cue })
    }

    @Test fun `before cards, the longest well-linked things she said are the best guess, and praise stars a try`() {
        val exercises = listOf(ex("e1", 0, 200_000))
        val takes = listOf(take("t1", 1_000, "e1"), take("t2", 20_000, "e1"), take("t3", 40_000, "e1"))
        val long = "Thank you very much. So, in low notes, everything is just amazing. But when we go up, it's becoming a little bit tense. " +
            "It's like you're trying to reach it instead of falling on it, like from the sky. You land into those high notes."
        val feedback = listOf(
            fb("f1", 5_000, "e1", long, take = "t1"),
            fb("f2", 25_000, "e1", "Yes, that was it.", take = "t2"),
            // music theory, however long, is not the guess
            fb("f3", 45_000, "e1", "So we have seven octaves on a piano. I sing these three octaves. This is the key, and the piano plays it one octave higher. ".repeat(3), take = "t3"),
            fb("f4", 60_000, "e1", "Breathe low and open the throat. ".repeat(12), take = null),   // not about a try
            fb("f5", 70_000, "e1", "Try to vibrate right here, it is not very high. Feel the buzz in your face, wherever you find the vibration. Open the sound. Let's start somewhere lower and find it again, like a vacuum cleaner.", take = "t3"),
        )
        val s = LessonOutline.build(exercises, takes, feedback, emptyList()).single()
        assertEquals(listOf("f1", "f5"), s.guesses.map { it.feedback.id })
        // the sentences about the reaching, not the "thank you" she opens with
        assertTrue(s.guesses[0].excerpt, s.guesses[0].excerpt.contains("reach"))
        assertTrue(s.guesses[0].excerpt.length <= 220)
        assertEquals(setOf("t2"), s.starred)
        assertTrue(s.cues.isEmpty())
    }

    @Test fun `praise of a try stars it once there are cards, and isn't a point`() {
        val exercises = listOf(ex("e1", 0, 100_000))
        val takes = listOf(take("t1", 1_000, "e1"))
        val cards = listOf(card("f1", 5_000, "Keep that open sound", category = "encouragement", take = "t1", weight = 0.1f))
        val s = LessonOutline.build(exercises, takes, emptyList(), cards).single()
        assertEquals(setOf("t1"), s.starred)
        assertTrue(s.cues.isEmpty())
        assertEquals(1, s.more.size)
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

    @Test fun `the teacher's answer about a summary reads as confirmed, flagged or corrected`() {
        val base = card("f1", 1_000, "Sing it", summary = "Let the note fall in from above.")
        fun check(c: CardEntity) = LessonOutline.CueGroup(c).check
        assertEquals(LessonOutline.SummaryCheck.UNCHECKED, check(base))
        assertEquals(LessonOutline.SummaryCheck.CONFIRMED, check(base.copy(summaryCheck = "said")))
        assertEquals(LessonOutline.SummaryCheck.FLAGGED, check(base.copy(summary = "", summaryCheck = "not_said", summaryRejected = "Push up to it.")))
        assertEquals(LessonOutline.SummaryCheck.CORRECTED, check(base.copy(summaryCheck = "said", summaryRejected = "Push up to it.")))
        // A flagged point falls back to her words.
        val flagged = base.copy(summary = "", herWords = "Fall into it.", summaryRejected = "Push up to it.")
        assertEquals("Fall into it.", LessonOutline.CueGroup(flagged).text)
    }

    @Test fun `talk about where the piano plays a note is theory, not a guess`() {
        val piano = "Yes. If, look, I sing this note in exactly this position, right? But if I will play this one, I will also see this note " +
            "and still I'm singing the same note even if I play it here. "
        assertTrue(LessonOutline.guessScore(piano.repeat(2), 1f) < LessonOutline.GUESS_MIN_SCORE)
        val tech = "When you go up, don't reach for the high note. Let it fall in from above, keep the jaw loose and the throat open. "
        assertTrue(LessonOutline.guessScore(tech.repeat(2), 1f) > LessonOutline.guessScore(piano.repeat(2), 1f))
    }
}
