package com.cuenote.pipeline

import com.cuenote.pipeline.practice.PlanNote
import com.cuenote.pipeline.practice.PlanStep
import com.cuenote.pipeline.practice.PracticePlan
import com.cuenote.pipeline.practice.StepKind
import com.cuenote.pipeline.practice.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticePlanTest {
    private val day = PracticePlan.DAY_MS
    private val now = 100 * day

    private fun note(
        id: String,
        weight: Float? = 0.5f,
        checked: Boolean = true,
        repeats: Int = 1,
        last: Long? = now - 3 * day,
        kind: String? = "song",
        exercise: String? = "verse2",
        pinned: Boolean = false,
        text: String = "",
        span: Long = 0,
    ) = PlanNote(id, now - 5 * day, span, exercise, kind, exercise?.let { "Morning Light, $it" }, weight, checked, repeats, last, pinned, text)

    private val warm = note("trill", kind = "warmup", exercise = "trill")
    private val ribs = note("ribs", weight = 0.6f)
    private val hill = note("hill", weight = 0.9f, text = "Don't reach up for the high F. Think down and over.")
    private val jaw = note("jaw", weight = 0.4f)
    private val notes = listOf(ribs, hill, jaw)

    private fun kinds(steps: List<PlanStep>) = steps.map { it.kind }

    @Test fun fine20_hasGentleStartWarmupTwoDrillsSongCoolDown() {
        val p = PracticePlan.build(notes, Voice.FINE, 20, now, warmup = warm)
        assertEquals(
            listOf(StepKind.GENTLE_START, StepKind.WARMUP, StepKind.DRILL, StepKind.DRILL, StepKind.SONG, StepKind.COOL_DOWN),
            kinds(p.steps),
        )
        assertEquals(listOf("hill", "ribs"), p.steps.filter { it.kind == StepKind.DRILL }.map { it.cardId })
        assertTrue(p.total <= 20)
    }

    @Test fun lengthsSetTheNumberOfDrills() {
        assertEquals(1, PracticePlan.build(notes, Voice.FINE, 10, now, warm).steps.count { it.kind == StepKind.DRILL })
        assertEquals(3, PracticePlan.build(notes, Voice.FINE, 30, now, warm).steps.count { it.kind == StepKind.DRILL })
    }

    @Test fun gentleStartAndCoolDownAreNeverCut() {
        for (m in PracticePlan.LENGTHS) {
            val p = PracticePlan.build(notes, Voice.FINE, m, now, warm)
            assertEquals(StepKind.GENTLE_START, p.steps.first().kind)
            assertEquals(StepKind.COOL_DOWN, p.steps.last().kind)
            assertEquals(2, p.steps.first().minutes)
            assertEquals(2, p.steps.last().minutes)
            assertTrue("$m: ${p.total}", p.total <= m)
        }
    }

    @Test fun pinnedComesFirstWhateverTheScore() {
        val pinned = jaw.copy(pinned = true, weight = 0.1f, lastPracticedAt = now - 1000)
        val p = PracticePlan.build(listOf(ribs, hill, pinned), Voice.FINE, 10, now, warm)
        assertEquals("jaw", p.steps.first { it.kind == StepKind.DRILL }.cardId)
        assertTrue(p.steps.first { it.kind == StepKind.DRILL }.pinned)
    }

    @Test fun uncheckedWeightCountsZero() {
        val guess = note("guess", weight = 1f, checked = false)
        val p = PracticePlan.build(listOf(guess, ribs), Voice.FINE, 10, now)
        assertEquals("ribs", p.steps.first { it.kind == StepKind.DRILL }.cardId)
    }

    @Test fun aNoteSungTodayDropsDown() {
        val today = hill.copy(lastPracticedAt = now - 3_600_000)
        val p = PracticePlan.build(listOf(today, ribs), Voice.FINE, 10, now)
        assertEquals("ribs", p.steps.first { it.kind == StepKind.DRILL }.cardId)
    }

    @Test fun warmupNoteIsNotAlsoADrill() {
        val p = PracticePlan.build(notes + warm.copy(weight = 1f), Voice.FINE, 30, now, warm)
        assertEquals(1, p.steps.count { it.cardId == "trill" })
    }

    @Test fun noSongStepWithoutASongSection() {
        val warmups = notes.map { it.copy(exerciseKind = "warmup") }
        assertFalse(PracticePlan.build(warmups, Voice.FINE, 20, now).steps.any { it.kind == StepKind.SONG })
    }

    @Test fun tired_isShortMiddleRangeAndNeverSingsHighOrTheSong() {
        val p = PracticePlan.build(notes, Voice.TIRED, 20, now, warm)
        assertTrue(p.total <= PracticePlan.TIRED_MINUTES)
        assertFalse(p.steps.any { it.kind == StepKind.SONG })
        val high = p.steps.first { it.cardId == "hill" }
        assertTrue(high.listenOnly)
        assertTrue(p.steps.filter { !it.listenOnly && it.cardId != null }.none { it.cardId == "hill" })
        assertEquals("Lower half only, nothing loud.", p.steps.first { it.kind == StepKind.WARMUP }.hint)
        assertEquals(StepKind.COOL_DOWN, p.steps.last().kind)
    }

    @Test fun hoarse_isListeningOnly() {
        val p = PracticePlan.build(notes, Voice.HOARSE, 20, now, warm)
        assertTrue(p.steps.isNotEmpty())
        assertTrue(p.steps.all { it.listenOnly })
        assertFalse(p.steps.any { it.kind == StepKind.GENTLE_START || it.kind == StepKind.COOL_DOWN })
    }

    @Test fun tiredAtHalfwayGoesToCoolDownNotTheSong() {
        val p = PracticePlan.build(notes, Voice.FINE, 20, now, warm)
        val at = PracticePlan.easyCheckBefore(p)!!
        val after = PracticePlan.toCoolDown(p.steps, at)
        assertEquals(p.steps.take(at), after.take(at))
        assertEquals(listOf(StepKind.COOL_DOWN), after.drop(at).map { it.kind })
    }

    @Test fun easyCheckSitsBetweenSungSteps() {
        val p = PracticePlan.build(notes, Voice.FINE, 20, now, warm)
        val at = PracticePlan.easyCheckBefore(p)!!
        assertTrue(at in 2 until p.steps.size - 1)
        assertNull(PracticePlan.easyCheckBefore(PracticePlan.build(notes, Voice.HOARSE, 20, now)))
    }

    @Test fun lessonTomorrowLeansTowardTheSong() {
        val normal = PracticePlan.build(notes, Voice.FINE, 20, now, warm)
        val eve = PracticePlan.build(notes, Voice.FINE, 20, now, warm, lessonTomorrow = true)
        assertTrue(eve.steps.count { it.kind == StepKind.DRILL } < normal.steps.count { it.kind == StepKind.DRILL })
        assertTrue(eve.steps.first { it.kind == StepKind.SONG }.minutes > normal.steps.first { it.kind == StepKind.SONG }.minutes)
    }

    @Test fun whyIsTheAppsReasonOrNothing() {
        assertEquals("not sung since Tue", PracticePlan.why(ribs, now) { "Tue" })
        assertEquals("not practiced yet", PracticePlan.why(ribs.copy(lastPracticedAt = null), now) { "x" })
        assertEquals("your teacher came back to this 3 times", PracticePlan.why(ribs.copy(repeats = 3), now) { "x" })
        assertNull(PracticePlan.why(ribs.copy(lastPracticedAt = now - 1000), now) { "x" })
        assertNull(PracticePlan.why(ribs.copy(pinned = true), now) { "x" })
    }

    @Test fun lengthsRespectTheCap() {
        assertEquals(listOf(10, 20), PracticePlan.lengths())
        assertEquals(listOf(10, 20, 30), PracticePlan.lengths(30))
        assertEquals(listOf(10), PracticePlan.lengths(5))
    }

    @Test fun emptySetlistStillGivesGentleStartAndCoolDown() {
        val p = PracticePlan.build(emptyList(), Voice.FINE, 10, now)
        assertEquals(listOf(StepKind.GENTLE_START, StepKind.COOL_DOWN), kinds(p.steps))
    }
}
