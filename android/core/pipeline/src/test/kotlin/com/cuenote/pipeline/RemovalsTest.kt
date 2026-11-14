package com.cuenote.pipeline

import com.cuenote.pipeline.audio.AudioEraser
import com.cuenote.pipeline.audio.ChunkDecoder
import com.cuenote.pipeline.audio.CompressedAudio
import com.cuenote.pipeline.audio.LessonFolder
import com.cuenote.signal.ChunkedWavSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RemovalsTest {
    /** A warmup (0-10 s) with two tries, then chatter after the lesson (20-60 s) with one. */
    private val out = Pipeline.Output(
        lessonId = "L", durationMs = 60_000, vad = "energy", transcriber = null,
        segments = listOf(Segment(Label.STUDENT_SING, 1_000, 3_000), Segment(Label.TEACHER_SPEAK, 30_000, 40_000)),
        takes = listOf(
            Take("t1", 1_000, 3_000, exerciseId = "e1"),
            Take("t2", 5_000, 7_000, exerciseId = "e1"),
            Take("t3", 25_000, 27_000, exerciseId = "e2"),
        ),
        feedback = listOf(
            FeedbackBlock("f1", 3_000, 4_500, takeId = "t1", linkConfidence = 0.9f, exerciseId = "e1",
                demos = listOf(Demo(3_500, 4_000, takeId = "t1", phraseStartMs = 0, phraseEndMs = 500))),
            FeedbackBlock("f2", 28_000, 40_000, takeId = "t3", exerciseId = "e2"),
        ),
        transcript = listOf(
            TranscriptLine(Speaker.STUDENT, 1_200, 2_000, "la la"),
            TranscriptLine(Speaker.TEACHER, 3_000, 4_500, "Down and over"),
            TranscriptLine(Speaker.TEACHER, 30_000, 35_000, "So how was your week"),
        ),
        events = listOf(EventLink("photo", 31_000, "p.jpg", feedbackId = "f2")),
        exercises = listOf(
            Exercise("e1", 0, 10_000, Exercise.WARMUP, listOf("t1", "t2")),
            Exercise("e2", 20_000, 60_000, Exercise.WARMUP, listOf("t3")),
        ),
    )

    @Test fun `deleting an exercise takes its tries, notes and words with it`() {
        val r = Removal("r", Removal.PART, 20_000, 60_000, exerciseIds = listOf("e2"), committed = true)
        val left = Removals.apply(out, listOf(r))
        assertEquals(listOf("e1"), left.exercises.map { it.id })
        assertEquals(listOf("t1", "t2"), left.takes.map { it.id })
        assertEquals(listOf("f1"), left.feedback.map { it.id })
        assertEquals(listOf("la la", "Down and over"), left.transcript.map { it.text })
        assertEquals(1, left.segments.size)
        // the photo stays, just no longer on a deleted card
        assertNull(left.events.single().feedbackId)
    }

    @Test fun `an exercise numbered differently after processing again is still caught by its time`() {
        val r = Removal("r", Removal.PART, 20_000, 60_000, exerciseIds = listOf("old-id"), committed = true)
        assertEquals(listOf("e1"), Removals.apply(out, listOf(r)).exercises.map { it.id })
    }

    @Test fun `deleting a try keeps her note but unlinks it`() {
        val r = Removal("r", Removal.TAKE, 1_000, 3_000, takeId = "t1", committed = true)
        val left = Removals.apply(out, listOf(r))
        assertEquals(listOf("t2", "t3"), left.takes.map { it.id })
        assertEquals(listOf("t2"), left.exercises.first().takeIds)
        val f1 = left.feedback.first()
        assertEquals("f1", f1.id)
        assertNull(f1.takeId)
        assertNull(f1.demos.single().takeId)
        // what the student sang goes; what the teacher said stays
        assertEquals(listOf("Down and over", "So how was your week"), left.transcript.map { it.text })
    }

    @Test fun `removals survive a round trip through the lesson folder`() {
        val dir = Files.createTempDirectory("removals").toFile()
        try {
            assertTrue(Removals.read(dir).isEmpty())
            val list = listOf(Removal("a", Removal.PART, 1, 2, label = "Warmup 2", at = 5), Removal("b", Removal.TAKE, 3, 4, takeId = "t"))
            Removals.write(dir, list)
            assertEquals(list, Removals.read(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun tone(frames: Int) = ShortArray(frames * 2) { 1000 }

    @Test fun `deleted audio is silenced in place, across chunk edges`() {
        val dir = Files.createTempDirectory("erase").toFile()
        try {
            val sink = ChunkedWavSink(File(dir, "audio"))
            val all = tone(48_000 * 70)   // 70 s: three chunks
            sink.write(all, all.size); sink.close()
            val before = LessonFolder(dir)
            val frames = before.chunkFrames
            assertEquals(2, AudioEraser.silence(dir, listOf(25_000L..35_000L)))
            val after = LessonFolder(dir)
            assertEquals(frames, after.chunkFrames)
            val f = after.readFrames(0, 48_000 * 70)
            fun at(s: Double) = f[(s * 48_000).toInt() * 2]
            assertEquals(1000.toShort(), at(24.9))
            assertEquals(0.toShort(), at(25.1))
            assertEquals(0.toShort(), at(30.5))
            assertEquals(0.toShort(), at(34.9))
            assertEquals(1000.toShort(), at(35.1))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `a compressed chunk goes back to a silenced WAV`() {
        val dir = Files.createTempDirectory("erase-packed").toFile()
        val audio = File(dir, "audio")
        try {
            val sink = ChunkedWavSink(audio)
            val all = tone(48_000 * 10)
            sink.write(all, all.size); sink.close()
            val wav = LessonFolder(dir).chunks.single()
            val pcm = LessonFolder(dir).samplesOf(wav)
            val packed = File(audio, wav.nameWithoutExtension + "." + CompressedAudio.EXTENSION)
            packed.writeText("stand-in")
            CompressedAudio.writeEntry(audio, CompressedAudio.Entry(packed.name, pcm.size / 2L, 0))
            wav.delete()
            LessonFolder.decoder = ChunkDecoder { pcm }
            AudioEraser.silence(dir, listOf(2_000L..4_000L))
            val after = LessonFolder(dir)
            assertFalse(after.isCompressed(after.chunks.single()))
            assertFalse(packed.exists())
            assertTrue(CompressedAudio.readIndex(audio).isEmpty())
            val f = after.readFrames(0, 48_000 * 10)
            assertEquals(1000.toShort(), f[48_000 * 2])
            assertEquals(0.toShort(), f[48_000 * 3 * 2])
            assertEquals(1000.toShort(), f[48_000 * 5 * 2])
        } finally {
            LessonFolder.decoder = null
            dir.deleteRecursively()
        }
    }
}
