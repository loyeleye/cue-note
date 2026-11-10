package com.cuenote.pipeline

import com.cuenote.pipeline.practice.BackingAudio
import com.cuenote.pipeline.practice.BackingPlan
import com.cuenote.pipeline.practice.BackingSpan
import com.cuenote.pipeline.practice.StartingNote
import com.cuenote.pipeline.practice.TimeStretch
import com.cuenote.signal.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class BackingPlanTest {
    private val out = Pipeline.Output(
        lessonId = "L", durationMs = 120_000, vad = "energy", transcriber = "test",
        segments = emptyList(),
        takes = listOf(
            Take("L-take-001", 1_000, 8_000, withPiano = true),
            Take("L-take-002", 30_000, 40_000, withPiano = false),
        ),
        feedback = listOf(
            FeedbackBlock("L-fb-001", 8_500, 20_000, takeId = "L-take-001",
                demos = listOf(Demo(15_000, 17_000), Demo(11_000, 13_000))),
            FeedbackBlock("L-fb-002", 41_000, 50_000, takeId = "L-take-002"),
            FeedbackBlock("L-fb-003", 0, 900),
        ),
        transcript = emptyList(), events = emptyList(),
    )

    @Test fun `an accompanied take gives a sing-along track with a lead-in, then the demos in order`() {
        val plan = BackingPlan.forCard(out, "L-fb-001")
        assertEquals(listOf(BackingSpan.Kind.ACCOMPANIMENT, BackingSpan.Kind.DEMO, BackingSpan.Kind.DEMO), plan.map { it.kind })
        assertEquals(0L, plan[0].startMs) // the lead-in stops at the start of the lesson
        assertEquals(8_000 + BackingPlan.TAIL_MS, plan[0].endMs)
        assertEquals(11_000 - BackingPlan.DEMO_PAD_MS, plan[1].startMs)
        assertEquals(listOf(1, 2), plan.drop(1).map { it.number })
        assertEquals("demo_2.wav", plan[2].fileName)
    }

    @Test fun `an unaccompanied take gives no sing-along track`() {
        assertTrue(BackingPlan.forCard(out, "L-fb-002").isEmpty())
    }

    @Test fun `opening chat and unknown blocks give nothing`() {
        assertTrue(BackingPlan.forCard(out, "L-fb-003").isEmpty())
        assertTrue(BackingPlan.forCard(out, "nope").isEmpty())
    }
}

class BackingAudioTest {
    @Test fun `keeps only the teacher's channel, faded at both ends`() {
        val n = 48_000
        val stereo = ShortArray(n * 2) { i -> if (i % 2 == 0) 20_000 else (8_000 * sin(2 * PI * 440 * (i / 2) / 48_000.0)).toInt().toShort() }
        val mono = BackingAudio.fromStereo(stereo, Channel.TEACHER)
        assertEquals(n, mono.size)
        assertEquals(0, mono[0].toInt())
        // the student's loud constant left channel is gone: the track swings both ways around zero
        val mid = mono.copyOfRange(n / 4, n / 2)
        assertTrue(mid.any { it > 10_000 } && mid.any { it < -10_000 })
    }
}

class StartingNoteTest {
    private fun take(hz: List<Float>, conf: Float = 0.9f) = Take("t", 0, hz.size * 32L, pitchHz = hz, pitchConfidence = List(hz.size) { conf })

    @Test fun `finds the first steady note, past a scoop and noise`() {
        val hz = listOf(0f, 0f, 180f, 205f) + List(10) { 220f + (it % 3) } + List(10) { 330f }
        val n = StartingNote.of(take(hz))!!
        assertEquals(57, n.midi) // A3
        assertEquals("A3", n.name)
        assertEquals(220.0, n.hz, 0.01)
    }

    @Test fun `unclear pitch gives no starting note`() {
        assertNull(StartingNote.of(take(List(20) { 220f }, conf = 0.3f)))
    }

    @Test fun `the tone is at the note's pitch`() {
        val tone = StartingNote.tone(StartingNote.Note(69), ms = 500)
        val est = Yin(sampleRate = 48_000, window = 2048, minHz = 100.0, maxHz = 1000.0)
            .estimate(FloatArray(tone.size) { tone[it] / 32768f }, 4_800)
        assertEquals(440.0, est.hz, 4.0)
    }
}

class TimeStretchTest {
    private fun sine(hz: Double, seconds: Double) =
        ShortArray((48_000 * seconds).toInt()) { (10_000 * sin(2 * PI * hz * it / 48_000)).toInt().toShort() }

    @Test fun `three quarter speed is a third longer and keeps the pitch`() {
        val x = sine(330.0, 2.0)
        val y = TimeStretch.stretch(x, 0.75)
        assertEquals(x.size / 0.75, y.size.toDouble(), 2.0)
        val yin = Yin(sampleRate = 48_000, window = 2048, minHz = 100.0, maxHz = 1000.0)
        val f = FloatArray(y.size) { y[it] / 32768f }
        for (at in listOf(10_000, 50_000, 100_000)) {
            val est = yin.estimate(f, at)
            assertTrue("at $at: ${est.hz}", abs(est.hz - 330.0) < 4)
        }
    }

    @Test fun `full speed is unchanged`() {
        val x = sine(220.0, 0.5)
        assertTrue(x.contentEquals(TimeStretch.stretch(x, 1.0)))
    }
}
