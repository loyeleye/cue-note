package com.cuenote.pipeline

import com.cuenote.pipeline.golden.GoldenLabels
import com.cuenote.pipeline.golden.Scorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExercisesTest {
    private fun take(n: Int, a: Int, b: Int) = Take("L-take-%03d".format(n), a * 1000L, b * 1000L)
    private fun fb(n: Int, a: Int, b: Int) = FeedbackBlock("L-fb-%03d".format(n), a * 1000L, b * 1000L)
    private fun said(a: Int, text: String) = TranscriptLine(Speaker.TEACHER, a * 1000L, a * 1000L + 2000, text)

    // a warmup in four tries with feedback across them, she moves on, then a long song take
    private val takes = listOf(take(1, 10, 20), take(2, 30, 40), take(3, 60, 65), take(4, 75, 80), take(5, 110, 190))
    private val feedback = listOf(fb(1, 2, 8), fb(2, 21, 29), fb(3, 41, 59), fb(4, 81, 108))
    private val words = listOf(
        said(42, "Let's start a little lower this time"),          // same warmup, moved down
        said(92, "Good. Now let's go to the song you brought"),     // moves on
    )

    @Test fun `tries stay together until she moves on`() {
        val r = Exercises().group(takes, feedback, words, "L")
        assertEquals(2, r.exercises.size)
        assertEquals(listOf("L-take-001", "L-take-002", "L-take-003", "L-take-004"), r.exercises[0].takeIds)
        assertEquals(Exercise.WARMUP, r.exercises[0].kind)
        assertEquals(Exercise.SONG, r.exercises[1].kind)
        assertTrue(r.exercises[1].reason, r.exercises[1].reason.contains("song"))
        // her first demo sets up the first warmup; the block where she moves on belongs to the song
        assertEquals(listOf("L-ex-001", "L-ex-001", "L-ex-001", "L-ex-002"), r.feedback.map { it.exerciseId })
        assertEquals(2_000L, r.exercises[0].startMs)
        assertEquals(81_000L, r.exercises[1].startMs)
    }

    @Test fun `a long break starts a new exercise`() {
        val far = takes.take(2) + take(3, 200, 210)
        val r = Exercises().group(far, emptyList(), emptyList(), "L")
        assertEquals(listOf(2, 1), r.exercises.map { it.takeIds.size })
        assertTrue(r.exercises[1].reason.startsWith("no singing"))
    }

    @Test fun `the block that sets up the next exercise is about no take`() {
        val g = Exercises().group(takes, feedback, words, "L")
        val linked = Linker().link(g.takes, g.feedback, emptyList()).feedback
        assertEquals("L-take-002", linked[2].takeId)
        assertNull(linked[3].takeId)
        assertEquals("sets up the next exercise", linked[3].linkReason)
    }

    @Test fun `grouping is scored against labelled exercises`() {
        val g = Exercises().group(takes, feedback, words, "L")
        val out = Pipeline.Output("L", 200_000, "x", null, emptyList(), g.takes, g.feedback, emptyList(), emptyList(), g.exercises)
        val truth = GoldenLabels(
            takes = takes.map { GoldenLabels.Span(it.startMs, it.endMs) },
            // truth: the first two tries are one warmup, the next two another
            exercises = listOf(
                GoldenLabels.ExerciseLabel(2_000, 50_000, "warmup"),
                GoldenLabels.ExerciseLabel(55_000, 108_000, "warmup"),
                GoldenLabels.ExerciseLabel(109_000, 190_000, "song"),
            ),
        )
        val s = Scorer.score(out, truth)
        // pairs: 1-2 same (right), 2-3 split (joined: wrong), 3-4 same (right), 4-5 split (right)
        assertEquals(0.75, s.groupingAccuracy!!, 1e-9)
        assertTrue(s.mistakes.any { it.contains("separate exercises but were joined") })
    }
}
