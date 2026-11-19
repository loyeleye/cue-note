package com.cuenote.pipeline.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MotionTest {
    @Test fun stepsSkipsAndLeapsCountLetters() {
        assertEquals(Motion.STEP, Motion.of(60, 62))   // C to D
        assertEquals(Motion.STEP, Motion.of(64, 65))   // E to F: a half step is still a step
        assertEquals(Motion.SKIP, Motion.of(60, 64))   // C to E
        assertEquals(Motion.SKIP, Motion.of(57, 53))   // A down to F
        assertEquals(Motion.LEAP, Motion.of(55, 60))   // G up to C, a fourth
        assertEquals(Motion.LEAP, Motion.of(48, 55))   // a fifth
        assertEquals(Motion.REPEAT, Motion.of(50, 50))
    }

    @Test fun saysItTheWayATeacherDoes() {
        // In bass clef G2 is the bottom line and B2 the second line: line to line.
        assertEquals("A skip up, from G to B: line to line.", MotionPair(43, 47).say(Clef.BASS))
        assertEquals("A step down, from A to G: space to line.", MotionPair(45, 43).say(Clef.BASS))
        assertEquals("A leap up (a fourth), from G to C: line to space.", MotionPair(43, 48).say(Clef.BASS))
    }

    @Test fun drillStaysInRangeAndMixesMotions() {
        val home = VoiceHome(50, Clef.BASS)   // a baritone
        val r = Random(3)
        var last: MotionPair? = null
        val pairs = List(600) { MotionDrill.pair(home, r, last).also { last = it } }
        for (p in pairs) {
            assertTrue(p.from in home.easyNotes && p.to in home.easyNotes)
            assertTrue(p.motion in Motion.PRACTICED)
            assertTrue(p.size <= MotionDrill.WIDEST)
        }
        for (m in Motion.PRACTICED) assertTrue("$m comes up", pairs.count { it.motion == m } > 120)
        assertTrue(pairs.any { it.up } && pairs.any { !it.up })
        assertTrue(pairs.zipWithNext().none { (a, b) -> a == b })
    }

    @Test fun stageThreeEndsWhenAllThreeAreSettled() {
        var m = MotionProgress()
        assertFalse(m.done)
        for (x in Motion.PRACTICED) {
            repeat(MotionProgress.BY_EAR) { m = m.heardRight(x) }
            repeat(MotionProgress.BY_EYE) { m = m.seenRight(x) }
        }
        assertFalse(m.done)   // not sung yet
        for (x in Motion.PRACTICED) repeat(MotionProgress.SUNG) { m = m.sang(x) }
        assertTrue(m.done)

        var p = ReadingProgress(VoiceHome.DEFAULT).matched(1, 20).matched(2)
        for (n in p.voice.firstFive) { repeat(3) { p = p.copy(deck = p.deck.answer(n, true)) }; repeat(2) { p = p.sang(n) } }
        assertEquals(ReadingStage.STEPS_SKIPS_LEAPS, ReadingLadder.stage(p))
        assertTrue(ReadingLadder.progressWords(p).contains("steps, skips and leaps"))
        p = p.copy(motions = MotionProgress().heardRight(Motion.STEP))
        assertTrue(ReadingLadder.progressWords(p).startsWith("Still to settle: steps, skips, leaps"))
        p = p.copy(motions = m)
        assertEquals(ReadingStage.SHORT_MELODIES, ReadingLadder.stage(p))
    }

    @Test fun oldSavedProgressStillLoads() {
        // Saved before Stage 3 existed: no "motions" field.
        val json = """{"home":{"speaking":50,"clef":"BASS"},"matchedDays":{"3":4}}"""
        val p = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(ReadingProgress.serializer(), json)
        assertEquals(MotionProgress(), p.motions)
        val m = MotionProgress().heardRight(Motion.LEAP)
        val back = kotlinx.serialization.json.Json.decodeFromString(MotionProgress.serializer(),
            kotlinx.serialization.json.Json.encodeToString(MotionProgress.serializer(), m))
        assertEquals(m, back)
    }
}
