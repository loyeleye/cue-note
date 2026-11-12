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
    @Test fun `talking is transcribed in joined stretches, never inside a take`() {
        val t = Transcription()
        val w = t.windows(
            listOf(
                seg(Label.TEACHER_SPEAK, 0.0, 2.0), seg(Label.STUDENT_SPEAK, 3.0, 3.5),
                seg(Label.STUDENT_SING, 5.0, 20.0), seg(Label.TEACHER_SPEAK, 30.0, 100.0),
            ),
            takes = listOf(Take("t1", 5_000, 20_000)),
            durationMs = 120_000,
        )
        // 0..4 s: teacher and student joined into one piece
        assertEquals(0L, w[0].startMs); assertEquals(4_000L, w[0].endMs)
        // nothing inside the take, and long talk cut into pieces whisper can take
        assertTrue(w.none { it.endMs > 5_000 && it.startMs < 20_000 })
        assertTrue(w.drop(1).all { it.endMs - it.startMs <= 28_000 })
        assertEquals(29_500L, w[1].startMs); assertEquals(100_500L, w.last().endMs)
    }

    @Test fun `each word is kept from the louder person's mic`() {
        val f = Features(200)
        for (i in 0 until 200) {
            val studentTalks = i < 100
            f.db[0][i] = if (studentTalks) -25f else -45f   // quiet student mic
            f.db[1][i] = if (studentTalks) -30f else -20f   // student bleeds onto the teacher mic
            f.vad[0][i] = 0.9f; f.vad[1][i] = 0.9f
        }
        val words = listOf(
            // the student's reply, heard by both mics
            Transcription.Word(0, 320, 640, " Was"), Transcription.Word(0, 640, 960, " that"), Transcription.Word(0, 960, 1_400, " flat?"),
            Transcription.Word(1, 330, 650, " Was"), Transcription.Word(1, 650, 1_400, " that flat?"),
            // the teacher's answer, heard by both mics
            Transcription.Word(1, 3_500, 3_900, " A"), Transcription.Word(1, 3_900, 4_400, " little."),
            Transcription.Word(0, 3_500, 4_400, " A little."),
        )
        val lines = Transcription().assign(words, f)
        assertEquals(2, lines.size)
        assertEquals(Speaker.STUDENT, lines[0].speaker); assertEquals("Was that flat?", lines[0].text)
        assertEquals(Speaker.TEACHER, lines[1].speaker); assertEquals("A little.", lines[1].text)
        assertEquals(3_500L, lines[1].startMs); assertEquals(4_400L, lines[1].endMs)
    }

    @Test fun `words with no voice on their mic are dropped`() {
        val f = Features(100)
        for (i in 0 until 100) { f.db[0][i] = -60f; f.db[1][i] = -20f; f.vad[1][i] = 0.05f }
        // whisper writing "Thank you." over piano
        val lines = Transcription().assign(listOf(Transcription.Word(1, 0, 900, " Thank you.")), f)
        assertTrue(lines.isEmpty())
    }

    @Test fun `a quiet student is told apart from the teacher by pitch`() {
        val f = Features(400)
        for (i in 0 until 400) {
            f.vad[0][i] = 0.9f; f.vad[1][i] = 0.9f; f.pitchConf[0][i] = 0.9f; f.pitchConf[1][i] = 0.9f
            when {
                i < 150 -> { f.db[0][i] = -20f; f.db[1][i] = -40f; f.pitchHz[0][i] = 95f; f.pitchHz[1][i] = 95f }   // student, clearly
                i < 300 -> { f.db[0][i] = -50f; f.db[1][i] = -20f; f.pitchHz[0][i] = 240f; f.pitchHz[1][i] = 240f } // teacher, clearly
                else -> { f.db[0][i] = -38f; f.db[1][i] = -30f; f.pitchHz[0][i] = 100f; f.pitchHz[1][i] = 100f }  // student, quietly
            }
        }
        val t = Transcription()
        val v = t.voices(f, emptyList())
        assertTrue(v.distinct)
        // the quiet reply is louder on the teacher's mic, and only her mic caught it
        val lines = t.assign(listOf(Transcription.Word(1, 320 * 32L, 340 * 32L, " Yes.")), f, v)
        assertEquals(1, lines.size)
        assertEquals(Speaker.STUDENT, lines[0].speaker)
    }
}
