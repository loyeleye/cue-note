package com.cuenote.signal

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class StepEarTest {
    private fun block(studentDb: Double, teacherDb: Double) = StereoLevels(
        ChannelLevel(studentDb, studentDb + 3, 0.0), ChannelLevel(teacherDb, teacherDb + 3, 0.0), 0.2, 4_800,
    )
    private val voice = block(-25.0, -50.0)
    private val silence = block(-62.0, -64.0)
    private val both = block(-28.0, -24.0)

    private fun StepEar.feed(b: StereoLevels, ms: Long) { repeat((ms / 100).toInt()) { add(b) } }

    @Test fun `silence is never enough`() {
        val ear = StepEar(StepEar.Need(soundMs = 1_500))
        ear.feed(silence, 30_000)
        assertFalse(ear.enough())
    }

    @Test fun `a sung line ends at the pause after it`() {
        val ear = StepEar(StepEar.Need(soundMs = 1_500, waitForPause = true))
        ear.feed(silence, 800)
        ear.feed(voice, 2_000)
        assertFalse("still singing", ear.enough())
        ear.feed(silence, 400)
        assertFalse("a breath, not yet a pause", ear.enough())
        ear.add(silence)
        assertTrue(ear.enough())
    }

    @Test fun `someone who keeps going doesn't need a pause`() {
        val ear = StepEar(StepEar.Need(soundMs = 1_500, waitForPause = true))
        ear.feed(voice, 2_900)
        assertFalse(ear.enough())
        ear.add(voice)
        assertTrue(ear.enough())
    }

    @Test fun `a held note ends without waiting for it to stop`() {
        val ear = StepEar(StepEar.Need(soundMs = 2_000))
        ear.feed(voice, 2_000)
        assertTrue(ear.enough())
    }

    @Test fun `steps for two need both mics at once`() {
        val ear = StepEar(StepEar.Need(both = true, soundMs = 2_000))
        ear.feed(voice, 3_000) // only the student so far
        assertFalse(ear.enough())
        ear.feed(both, 2_000)
        assertTrue(ear.enough())
    }

    @Test fun `the voice counts on whichever mic it lands`() {
        val ear = StepEar(StepEar.Need(soundMs = 1_000))
        ear.feed(voice.swapped(), 1_000)
        assertTrue(ear.enough())
    }
}
