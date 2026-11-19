package com.cuenote.pipeline

import com.cuenote.pipeline.practice.CarNote
import com.cuenote.pipeline.practice.CarPart
import com.cuenote.pipeline.practice.CarPlaylist
import com.cuenote.pipeline.practice.PlanStep
import com.cuenote.pipeline.practice.StepKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlaylistTest {
    private val steps = listOf(
        PlanStep(StepKind.GENTLE_START, minutes = 2),
        PlanStep(StepKind.DRILL, "a", minutes = 4),
        PlanStep(StepKind.DRILL, "b", minutes = 3, listenOnly = true, hint = "Listen only today"),
        PlanStep(StepKind.COOL_DOWN, minutes = 2),
    )
    private val titles = listOf("Gentle start", "Breathe low", "Main point", "Cool-down")
    private val full = CarNote("Breathe low", "so breathe low", "Marigold", hasWords = true, accompanimentMs = 30_000, demos = 2)
    private val notes = mapOf("a" to full, "b" to full)

    private fun build(from: Int = 0, check: Int? = 2, n: Map<String, CarNote> = notes) =
        CarPlaylist.build(steps, titles, n, { "Hums on mm." }, check, from)

    @Test fun playsEveryStepInOrderAndEndsWithClosingWords() {
        val parts = build()
        assertEquals(parts.map { it.step }, parts.map { it.step }.sorted())
        assertEquals((0..4).toList(), parts.map { it.step }.distinct())
        assertTrue(parts.last() is CarPart.Say && parts.last().step == steps.size)
    }

    @Test fun eachStepIsSpokenBeforeAnythingPlays() {
        val parts = build()
        for (i in steps.indices) {
            val first = parts.first { it.step == i && (it !is CarPart.Say || it.text.startsWith("Step")) }
            assertTrue("step $i starts with its instruction", first is CarPart.Say && first.text.startsWith("Step ${i + 1} of 4"))
        }
    }

    @Test fun fixedStepsGetQuietTimeForTheirMinutes() {
        val quiet = build().filterIsInstance<CarPart.Quiet>()
        assertEquals(listOf(0 to 120, 3 to 120), quiet.map { it.step to it.seconds })
    }

    @Test fun aSungDrillPlaysHerWordsDemoThenPianoLongEnough() {
        val drill = build().filter { it.step == 1 && it.main }
        assertTrue(drill[0] is CarPart.Words)
        assertTrue(drill[1] is CarPart.Demo)
        val acc = drill[2] as CarPart.Accompaniment
        assertEquals(6, acc.repeats) // 4 minutes of a 30 s phrase, capped
    }

    @Test fun listenOnlyPlaysDemosAndNoPiano() {
        val parts = build().filter { it.step == 2 }
        assertTrue(parts.none { it is CarPart.Accompaniment || it is CarPart.Quiet })
        assertEquals(2, parts.count { it is CarPart.Demo })
        assertTrue(parts.filterIsInstance<CarPart.Say>().first { it.text.startsWith("Step") }.text.contains("Just listen"))
    }

    @Test fun halfwayCheckComesBeforeItsStepOnce() {
        val parts = build()
        val i = parts.indexOfFirst { it.title == "Still feel easy?" }
        assertEquals(2, parts[i].step)
        assertEquals(1, parts.count { it.title == "Still feel easy?" })
        assertTrue(parts.subList(0, i).none { it.step >= 2 })
    }

    @Test fun pickingUpStartsAtThatStepAndSkipsTheCheckThere() {
        val parts = build(from = 2)
        assertEquals(2, parts.first().step)
        assertTrue(parts.none { it.title == "Still feel easy?" })
        assertTrue(parts.none { it.step < 2 })
    }

    @Test fun aNoteWithNothingOnThePhoneIsSungOnItsOwn() {
        val bare = CarNote("", "", null, hasWords = false, accompanimentMs = null, demos = 0)
        val parts = build(n = mapOf("a" to bare, "b" to full)).filter { it.step == 1 }
        assertTrue(parts.any { it is CarPart.Quiet && it.seconds == 240 })
    }

    @Test fun longWordsAreCutAtASentence() {
        val long = "Keep the breath low. " + "And then ".repeat(40)
        assertEquals("Keep the breath low.", CarPlaylist.spoken(long).take(20))
        assertTrue(CarPlaylist.spoken(long).length <= CarPlaylist.MAX_SPOKEN + 1)
        assertEquals("Tall vowels.", CarPlaylist.spoken("  Tall   vowels "))
    }

    @Test fun oneNoteOnItsOwnPlaysThePianoOnce() {
        val parts = CarPlaylist.forNote("a", "Breathe low", full)
        assertEquals(1, parts.filterIsInstance<CarPart.Accompaniment>().single().repeats)
    }
}
