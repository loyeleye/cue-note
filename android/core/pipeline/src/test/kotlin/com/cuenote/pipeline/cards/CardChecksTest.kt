package com.cuenote.pipeline.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardChecksTest {
    private val said = "Lovely. But you pushed the top. Let the jaw drop and think of a yawn, okay? Like this. Try it again."

    @Test fun `a quote that is hers is kept, one that isn't becomes her closest sentence`() {
        assertEquals(CardChecks.Quote("Let the jaw drop and think of a yawn, okay?", false), CardChecks.verbatim("Let the jaw drop and think of a yawn, okay?", said))
        val fixed = CardChecks.verbatim("Drop your jaw like a yawn", said)
        assertTrue(fixed.repaired)
        assertEquals("Let the jaw drop and think of a yawn, okay?", fixed.text)
        assertEquals(CardChecks.Quote("", true), CardChecks.verbatim("anything", " "))
    }

    @Test fun `a cue is at most eight words`() {
        assertEquals("Drop the jaw like a yawn", CardChecks.cue("“Drop the jaw like a yawn.”"))
        assertEquals(8, CardChecks.cue("Keep the breath moving all the way through the end of the phrase").split(' ').size)
        assertTrue(CardChecks.cueTooLong("Keep the breath moving all the way through the end"))
        assertFalse(CardChecks.cueTooLong("Breathe low before the phrase"))
    }

    @Test fun `summaries use American spelling and stay short`() {
        assertEquals("Practice it slowly, then you've practiced it.", CardChecks.summary("Practise it   slowly, then you've practised it."))
        assertTrue(CardChecks.summary("A sentence here. ".repeat(40)).length <= 301)
    }

    @Test fun `a cue must use her words, and a copied example needs half of them`() {
        assertTrue(CardChecks.grounded("Find the vibration", "We can find it, it's vibrating in your fingers."))
        assertTrue(CardChecks.grounded("Sing it again", "Okay."))
        assertFalse(CardChecks.grounded("Sing with a sense of longing", "Oh, the..."))
        assertFalse(CardChecks.grounded("Land high notes from above, don't reach", "Great is a low for you, so high is there."))
        assertTrue(CardChecks.grounded("Land high notes from above, don't reach", "You land into those high notes instead of reaching."))
    }
}
