package com.cuenote.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyllableCounterTest {
    private fun block(studentDb: Double, teacherDb: Double) = StereoLevels(
        ChannelLevel(studentDb, studentDb + 3, 0.0), ChannelLevel(teacherDb, teacherDb + 3, 0.0), 0.2, 4_800,
    )
    private fun student(db: Double) = block(db, -60.0)
    private fun teacher(db: Double) = block(-60.0, db)
    private val quiet = block(-62.0, -63.0)

    private fun SyllableCounter.feed(vararg blocks: StereoLevels) = blocks.forEach { add(it) }
    private fun SyllableCounter.feed(b: StereoLevels, ms: Long) = repeat((ms / 100).toInt()) { add(b) }

    @Test fun `three detached syllables light three`() {
        val c = SyllableCounter(Channel.STUDENT)
        c.feed(quiet, 500)
        c.feed(student(-24.0), 300); c.feed(quiet, 200)
        assertEquals(1, c.heard)
        c.feed(student(-22.0), 300); c.feed(quiet, 200)
        assertEquals(2, c.heard)
        c.feed(student(-20.0), 300)
        assertEquals(3, c.heard)
        assertTrue(c.done)
    }

    @Test fun `a dip and rise inside a phrase is a new syllable`() {
        val c = SyllableCounter(Channel.STUDENT)
        c.feed(student(-24.0), student(-24.0), student(-24.0), student(-31.0), student(-24.0), student(-24.0))
        assertEquals(2, c.heard)
    }

    @Test fun `a steady held note is one syllable until legato fills the rest`() {
        val c = SyllableCounter(Channel.STUDENT)
        c.feed(student(-24.0), 1_400)
        assertEquals(1, c.heard)
        c.feed(student(-24.0), 200)
        assertEquals(3, c.heard)
    }

    @Test fun `syllables never come faster than the refractory gap`() {
        val c = SyllableCounter(Channel.STUDENT)
        c.feed(student(-24.0), quiet, student(-24.0))
        assertEquals(1, c.heard)
    }

    @Test fun `room noise lights nothing`() {
        val c = SyllableCounter(Channel.STUDENT)
        c.feed(quiet, 10_000)
        assertEquals(0, c.heard)
    }

    @Test fun `the other mic lights nothing and says so`() {
        val c = SyllableCounter(Channel.TEACHER)
        c.feed(student(-22.0), 600)
        assertEquals(0, c.heard)
        assertTrue(c.wrongMic)
        c.feed(quiet, teacher(-24.0))
        assertEquals(1, c.heard)
        assertFalse(c.wrongMic)
    }

    @Test fun `bleed on the other mic is fine`() {
        val c = SyllableCounter(Channel.TEACHER)
        c.feed(block(-36.0, -24.0), 300)
        assertEquals(1, c.heard)
        assertFalse(c.wrongMic)
    }

    @Test fun `before the channels are settled either mic counts`() {
        val c = SyllableCounter(null)
        c.feed(teacher(-24.0), 300)
        assertEquals(1, c.heard)
    }

    @Test fun `it stops at three`() {
        val c = SyllableCounter(Channel.STUDENT)
        repeat(6) { c.feed(student(-24.0), 200); c.feed(quiet, 300) }
        assertEquals(3, c.heard)
    }
}
