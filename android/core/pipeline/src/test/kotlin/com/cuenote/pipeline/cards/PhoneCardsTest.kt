package com.cuenote.pipeline.cards

import com.cuenote.pipeline.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCardsTest {
    private val said = "Lovely. But you pushed the top. Let the jaw drop and think of a yawn, okay? Like this. Try it again."

    private fun block(id: String, words: String = said, ex: String? = "e1") = PhoneBlock(
        localId = id, exerciseId = ex, exercise = "a warmup", sung = "la la la",
        lines = listOf(Speaker.TEACHER to words), words = words, demos = 1, afterTake = true,
    )

    /** Answers from a list, and remembers what it was asked. */
    private class Fake(private val answers: MutableList<String>) : CardModel {
        override val name = "fake"
        val asked = mutableListOf<String>()
        override fun complete(system: String, user: String, schema: String): String {
            asked += user
            return answers.removeAt(0)
        }
    }

    @Test fun `an answer is checked as the cloud's is`() {
        val c = PhoneCards.check(block("b1"), """Sure! {"cue":"Drop the jaw like a yawn.","her_words":"drop your jaw","summary":"Practise letting the jaw drop.","category":"vowel","weight":1.4}""")
        assertEquals("Drop the jaw like a yawn", c.cue)
        assertTrue(c.repaired)
        assertEquals("Let the jaw drop and think of a yawn, okay?", c.herWords)
        assertEquals("Practice letting the jaw drop.", c.summary)
        assertEquals(1f, c.weight)
        assertFalse(c.cueCut)
    }

    @Test fun `chat has no cue, and an answer that isn't JSON leaves her words only`() {
        val chat = PhoneCards.check(block("b1"), """{"cue":"Say hi","her_words":"Lovely.","summary":"x","category":"chat","weight":0.5}""")
        assertEquals("", chat.cue)
        assertEquals(0f, chat.weight)
        val bad = PhoneCards.check(block("b2"), "I think she means the jaw.")
        assertTrue(bad.unreadable)
        assertEquals("", bad.cue)
        assertTrue(bad.herWords.isNotBlank())
    }

    @Test fun `a cue over eight words is asked for again, and dropped if it stays long`() {
        val long = """{"cue":"Let the jaw drop and think of a yawn on the top","her_words":"Let the jaw drop and think of a yawn, okay?","summary":"","category":"vowel","weight":0.8}"""
        val short = """{"cue":"Drop the jaw like a yawn","her_words":"Let the jaw drop and think of a yawn, okay?","summary":"","category":"vowel","weight":0.8}"""
        val fixed = PhoneCards.write(Fake(mutableListOf(long, short)), listOf(block("b1"))).single()
        assertEquals("Drop the jaw like a yawn", fixed.cue)
        assertTrue(fixed.retried)
        val dropped = PhoneCards.write(Fake(mutableListOf(long, long)), listOf(block("b1"))).single()
        assertEquals("", dropped.cue)
        assertEquals("Let the jaw drop and think of a yawn, okay?", dropped.herWords)
    }

    @Test fun `earlier cues in the exercise go into the next prompt, and a bare yes gets no call`() {
        val a = """{"cue":"Drop the jaw like a yawn","her_words":"Let the jaw drop and think of a yawn, okay?","summary":"","category":"vowel","weight":0.8}"""
        val fake = Fake(mutableListOf(a, a))
        val cards = PhoneCards.write(fake, listOf(block("b1"), block("b2", "Yes."), block("b3")))
        assertEquals(listOf("b1", "b3"), cards.map { it.localId })
        assertTrue(fake.asked[1], fake.asked[1].contains("Earlier cues in this exercise: “Drop the jaw like a yawn”"))
        assertFalse(fake.asked[0].contains("Earlier cues"))
    }
}
