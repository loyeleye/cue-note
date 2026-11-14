package com.cuenote.data

import com.cuenote.pipeline.cards.CloudIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNotesTest {
    private val exercises = listOf(ExerciseEntity("e1", "l", 0, 200_000, "warmup", "", null))
    private val takes = listOf(
        TakeEntity("t1", "l", 1_000, 4_000, "{}", "[]", withPiano = true, exerciseId = "e1"),
        TakeEntity("t2", "l", 20_000, 23_000, "{}", "[]", withPiano = true, exerciseId = "e1"),
    )
    private val long = "Thank you very much. So, in low notes, everything is just amazing. But when we go up, it's becoming a little bit tense. " +
        "It's like you're trying to reach it instead of falling on it, like from the sky. You land into those high notes."
    private val feedback = listOf(
        FeedbackEntity("f1", "l", "t1", 5_000, 15_000, 1f, "", "[]", long, "e1"),
        FeedbackEntity("f2", "l", "t2", 25_000, 27_000, 1f, "", "[]", "Yes, that was it.", "e1"),
    )

    @Test fun `the phone's best guesses become notes with her words, no cue, and the cloud's ids`() {
        val notes = PhoneNotes.fromGuesses("l", exercises, takes, feedback)
        val n = notes.single()
        assertEquals(CloudIds.feedback("f1"), n.id)
        assertEquals("f1", n.localId)
        assertEquals(CloudIds.take("t1"), n.takeId)
        assertEquals("", n.cue)
        assertEquals("", n.summary)
        assertTrue(n.herWords, n.herWords.contains("reach"))
        assertTrue(long.replace(Regex("\\s+"), " ").contains(n.herWords.removeSuffix("…")))
        assertEquals(long, n.transcript)
        assertEquals(CardEntity.SOURCE_PHONE, n.source)
        assertTrue(n.onPhone)
        // not on the setlist until the student puts it there
        assertEquals(false, SetlistStore.isOn(n, emptyMap()))
    }

    @Test fun `with only the phone's notes, they are the points, and praise still stars a try`() {
        val notes = PhoneNotes.fromGuesses("l", exercises, takes, feedback)
        val s = LessonOutline.build(exercises, takes, feedback, notes).single()
        assertEquals(listOf(CloudIds.feedback("f1")), s.cues.map { it.card.id })
        assertTrue(s.guesses.isEmpty())
        assertEquals(setOf("t2"), s.starred)
        // her words, as the point's text
        assertEquals(notes.single().herWords, s.cues.single().text)
    }

    @Test fun `the student's own words are one trimmed line`() {
        assertEquals("Fall onto the high notes", PhoneNotes.ownWords("  Fall onto\n the   high notes "))
        assertEquals(PhoneNotes.MAX_OWN_WORDS, PhoneNotes.ownWords("a".repeat(500)).length)
        assertEquals("", PhoneNotes.ownWords("   "))
    }
}
