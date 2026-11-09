package com.cuenote.pipeline

import com.cuenote.ml.Transcriber
import com.cuenote.ml.TranscriptPiece
import com.cuenote.signal.LessonEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun seg(l: Label, a: Double, b: Double) = Segment(l, (a * 1000).toLong(), (b * 1000).toLong())

class StructureTest {
    private val s = Structure()

    @Test fun `a breath and a piano interlude stay inside one take, talking ends it`() {
        val r = s.build(listOf(
            seg(Label.STUDENT_SING, 0.0, 5.0), seg(Label.PIANO, 5.2, 7.0), seg(Label.STUDENT_SING, 7.5, 12.0),
            seg(Label.TEACHER_SPEAK, 12.5, 15.0), seg(Label.STUDENT_SING, 15.5, 20.0),
        ))
        assertEquals(listOf(0L to 12_000L, 15_500L to 20_000L), r.takes.map { it.startMs to it.endMs })
    }

    @Test fun `a student question inside feedback keeps it one block, and demos are found`() {
        val r = s.build(listOf(
            seg(Label.STUDENT_SING, 0.0, 8.0),
            seg(Label.TEACHER_SPEAK, 9.0, 12.0), seg(Label.TEACHER_SING, 12.5, 15.0), seg(Label.TEACHER_SPEAK, 15.3, 17.0),
            seg(Label.STUDENT_SPEAK, 17.6, 19.0), seg(Label.TEACHER_SPEAK, 19.5, 22.0),
            seg(Label.STUDENT_SING, 23.0, 30.0), seg(Label.TEACHER_SPEAK, 31.0, 33.0),
        ))
        assertEquals(listOf(9_000L to 22_000L, 31_000L to 33_000L), r.feedback.map { it.startMs to it.endMs })
        assertEquals(1, r.feedback[0].demos.size)
    }

    @Test fun `student speech is never a take`() {
        assertTrue(s.build(listOf(seg(Label.STUDENT_SPEAK, 0.0, 6.0))).takes.isEmpty())
    }
}

class LinkerTest {
    private fun take(id: String, a: Double, b: Double) = Take(id, (a * 1000).toLong(), (b * 1000).toLong())
    private fun fb(id: String, a: Double, b: Double) = FeedbackBlock(id, (a * 1000).toLong(), (b * 1000).toLong())

    @Test fun `feedback links to the most recent take, opening chat to nothing`() {
        val takes = listOf(take("t1", 10.0, 20.0), take("t2", 40.0, 50.0))
        val r = Linker().link(takes, listOf(fb("f0", 0.0, 8.0), fb("f1", 21.0, 35.0), fb("f2", 52.0, 60.0)), emptyList())
        assertEquals(listOf(null, "t1", "t2"), r.feedback.map { it.takeId })
        assertEquals(1f, r.feedback[1].linkConfidence)
    }

    @Test fun `confidence falls with the wait, and gives up after three minutes`() {
        val takes = listOf(take("t1", 0.0, 10.0))
        val r = Linker().link(takes, listOf(fb("f1", 70.0, 80.0), fb("f2", 400.0, 410.0)), emptyList())
        assertTrue(r.feedback[0].linkConfidence in 0.3f..0.99f)
        assertNull(r.feedback[1].takeId)
    }

    @Test fun `photos join the feedback they follow, marks the take they follow`() {
        val takes = listOf(take("t1", 0.0, 10.0), take("t2", 100.0, 110.0))
        val r = Linker().link(
            takes, listOf(fb("f1", 12.0, 40.0), fb("f2", 112.0, 130.0)),
            listOf(LessonEvent.Photo(70_000, "a.jpg"), LessonEvent.Mark(111_000), LessonEvent.Photo(500_000, "far.jpg")),
        )
        assertEquals(listOf("f1", "f2", null), r.events.map { it.feedbackId })
        assertEquals("t2", r.events[1].takeId)
    }

    @Test fun `subsequence matching finds the copied phrase, an octave apart`() {
        val hay = doubleArrayOf(0.0, 2.0, 4.0, 5.0, 7.0, 5.0, 4.0, 2.0, 0.0, 0.0)
        val needle = doubleArrayOf(17.0, 19.0, 17.0, 16.0)   // 5,7,5,4 an octave up
        val m = Linker.subsequenceDtw(needle, hay)!!
        assertEquals(3, m.start); assertEquals(6, m.end)
        assertTrue(m.distance < 0.01)
    }
}

class TranscriptionTest {
    /** Returns one piece per loud stretch of the batch, so time mapping can be checked. */
    private class Fake : Transcriber {
        override val name = "fake"
        val calls = mutableListOf<Int>()
        override fun transcribe(samples: FloatArray, prompt: String?): List<TranscriptPiece> {
            calls += samples.size
            return listOf(TranscriptPiece(0, samples.size / 16L, "words"))
        }
    }

    @Test fun `utterances join, split at the window and stay per speaker`() {
        val t = Transcription()
        val u = t.utterances(listOf(
            seg(Label.TEACHER_SPEAK, 0.0, 2.0), seg(Label.TEACHER_SPEAK, 2.5, 4.0),
            seg(Label.STUDENT_SPEAK, 4.5, 5.0), seg(Label.TEACHER_SPEAK, 6.0, 70.0),
        ))
        assertEquals(Speaker.TEACHER, u[0].speaker); assertEquals(4_000L, u[0].endMs)
        assertEquals(Speaker.STUDENT, u[1].speaker)
        assertTrue(u.drop(2).all { it.endMs - it.startMs <= 28_000 })
        val b = t.batches(u)
        assertTrue(b.all { batch -> batch.utterances.all { it.speaker == batch.speaker } })
    }
}
