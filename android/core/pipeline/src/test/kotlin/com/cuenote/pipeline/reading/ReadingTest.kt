package com.cuenote.pipeline.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class ReadingTest {
    private fun hz(midi: Double) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    /** A voice-like tone (a few harmonics) at [midi], 48 kHz mono. */
    private fun tone(midi: Double, ms: Int, amp: Double = 0.3): ShortArray {
        val f = hz(midi)
        val n = 48 * ms
        return ShortArray(n) { i ->
            val t = i / 48_000.0
            val v = sin(2 * PI * f * t) + 0.5 * sin(4 * PI * f * t) + 0.25 * sin(6 * PI * f * t)
            (amp * v / 1.75 * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /** Feeds [pcm] in 50 ms blocks, as the mic does. */
    private fun live(pcm: ShortArray): List<LivePitch.Frame> {
        val p = LivePitch()
        val out = mutableListOf<LivePitch.Frame>()
        var i = 0
        while (i < pcm.size) {
            val n = minOf(2_400, pcm.size - i)
            out += p.push(pcm.copyOfRange(i, i + n))
            i += n
        }
        return out
    }

    @Test fun livePitchFollowsASungNote() {
        for (midi in listOf(45.0, 57.0, 64.3, 69.0)) {
            val frames = live(tone(midi, 1_000))
            // About one frame every 32 ms, less the analysis window at the start.
            assertTrue(frames.size in 25..32)
            val clear = frames.filter { it.clear }
            assertTrue("clear frames for $midi", clear.size > 20)
            for (f in clear) assertEquals(midi, f.semis.toDouble(), 0.15)
        }
    }

    @Test fun silenceIsNotANote() {
        val frames = live(ShortArray(48_000))
        assertTrue(frames.none { it.clear })
        assertTrue(frames.all { it.db < NoteMatch.SILENT_DB })
    }

    private fun frame(semis: Float) = LivePitch.Frame(semis, 0.9f, -20.0)
    private val quiet = LivePitch.Frame(Float.NaN, 0f, -80.0)

    @Test fun matchSettlesAfterAboutASecondOnTheNote() {
        val m = NoteMatch(60)
        var s = m.push(frame(60.2f))
        assertEquals(NoteMatch.Hint.ON, s.hint)
        assertFalse(s.settled)
        repeat(29) { s = m.push(frame(60.1f)) }
        assertTrue(s.settled)
        assertEquals(1f, s.held)
        assertEquals(0, m.matchedOctave)
        // Once matched, wandering off doesn't take it back.
        s = m.push(frame(63f))
        assertTrue(s.settled)
    }

    @Test fun hintsSayWhichWayToGo() {
        val m = NoteMatch(60)
        assertEquals(NoteMatch.Hint.WAY_LOW, m.push(frame(55f)).hint)
        assertEquals(NoteMatch.Hint.LOW, m.push(frame(58.5f)).hint)
        assertEquals(NoteMatch.Hint.A_LITTLE_LOW, m.push(frame(59.3f)).hint)
        assertEquals(NoteMatch.Hint.A_LITTLE_HIGH, m.push(frame(60.7f)).hint)
        assertEquals(NoteMatch.Hint.HIGH, m.push(frame(61.5f)).hint)
        assertEquals(NoteMatch.Hint.WAY_HIGH, m.push(frame(65f)).hint)
        assertEquals(NoteMatch.Hint.WAITING, m.push(quiet).hint)
        assertEquals(NoteMatch.Hint.UNCLEAR, m.push(LivePitch.Frame(Float.NaN, 0.3f, -25.0)).hint)
    }

    @Test fun anOctaveAwayCountsAndIsReported() {
        val m = NoteMatch(60)
        var s = m.push(frame(48.1f))
        assertEquals(NoteMatch.Hint.ON, s.hint)
        assertEquals(-1, s.octave)
        repeat(40) { s = m.push(frame(48f)) }
        assertTrue(s.settled)
        assertEquals(-1, m.matchedOctave)
        // Halfway between octaves, the hint keeps the real direction.
        assertEquals(NoteMatch.Hint.WAY_LOW, NoteMatch(60).push(frame(53f)).hint)
    }

    @Test fun shortDropoutsDontResetTheHold() {
        val m = NoteMatch(60)
        repeat(15) { m.push(frame(60f)) }
        repeat(2) { m.push(quiet) }
        var s = m.push(frame(60f))
        repeat(14) { s = m.push(frame(60f)) }
        assertTrue(s.settled)
        // A long break does.
        val m2 = NoteMatch(60)
        repeat(15) { m2.push(frame(60f)) }
        repeat(10) { m2.push(quiet) }
        var s2 = m2.push(frame(60f))
        repeat(14) { s2 = m2.push(frame(60f)) }
        assertFalse(s2.settled)
    }

    @Test fun realSungToneSettles() {
        val m = NoteMatch(57)
        val s = live(tone(57.2, 1_600)).map { m.push(it) }.last()
        assertTrue(s.settled)
    }

    @Test fun notesSitOnTheRightLinesAndSpaces() {
        val e4 = Staff.place(64, Clef.TREBLE)
        assertEquals(0, e4.step); assertTrue(e4.onLine); assertTrue(e4.ledgers.isEmpty())
        assertEquals("on the bottom line", Staff.describe(e4))
        assertEquals(1, Staff.place(65, Clef.TREBLE).step)          // F4, first space
        assertEquals("in the first space", Staff.describe(Staff.place(65, Clef.TREBLE)))
        assertEquals(4, Staff.place(71, Clef.TREBLE).step)          // B4, middle line
        assertEquals(8, Staff.place(77, Clef.TREBLE).step)          // F5, top line
        val c4 = Staff.place(60, Clef.TREBLE)
        assertEquals(-2, c4.step); assertEquals(listOf(-2), c4.ledgers)
        assertEquals("below the staff, on the first short line", Staff.describe(c4))
        assertEquals("in the space under the staff", Staff.describe(Staff.place(62, Clef.TREBLE)))
        val a3 = Staff.place(57, Clef.TREBLE)
        assertEquals(-4, a3.step); assertEquals(listOf(-2, -4), a3.ledgers)
        assertEquals("below the staff, on the second short line", Staff.describe(a3))
        assertEquals("below the staff, past 2 short lines", Staff.describe(Staff.place(55, Clef.TREBLE)))  // G3
        // Bass clef: G2 bottom line, A3 top line; middle C one ledger above.
        assertEquals(0, Staff.place(43, Clef.BASS).step)
        assertEquals(8, Staff.place(57, Clef.BASS).step)
        assertEquals(listOf(10), Staff.place(60, Clef.BASS).ledgers)
        // An octave-down treble clef reads men's notes on the staff.
        assertEquals(Staff.place(64, Clef.TREBLE).step, Staff.place(52, Clef.TREBLE_8VB).step)
        // Sharps and flats share a step with their letter.
        val fs = Staff.place(66, Clef.TREBLE)
        assertEquals(1, fs.step); assertEquals(Accidental.SHARP, fs.accidental)
        val bb = Staff.place(70, Clef.TREBLE, flats = true)
        assertEquals(4, bb.step); assertEquals(Accidental.FLAT, bb.accidental)
    }

    @Test fun noteNames() {
        assertEquals("C4", Staff.name(60))
        assertEquals("A3", Staff.name(57))
        assertEquals("F♯4", Staff.name(66))
        assertEquals("G", Staff.letter(55))
        assertEquals("F", Staff.letter(66))
    }

    @Test fun voiceHomeStartsWhereYouTalk() {
        val talk = List(60) { frame(53f + (it % 3) * 0.4f) }  // around F3
        val h = VoiceHome.fromSpeech(talk)
        assertNotNull(h)
        assertEquals(55, h!!.speaking)                     // a step up from the speaking voice, G3
        assertEquals(Clef.TREBLE, h.clef)
        val low = VoiceHome.fromSpeech(List(60) { frame(45f) })!!
        assertEquals(Clef.TREBLE_8VB, low.clef)
        assertNull(VoiceHome.fromSpeech(List(10) { frame(50f) }))
        // Stage 1's five notes are five natural notes in a row.
        assertEquals(listOf(55, 57, 59, 60, 62), h.firstFive)
        assertEquals(listOf(57, 59, 60, 62, 64), VoiceHome.DEFAULT.firstFive)
        assertTrue(h.easyNotes.all { Staff.isNatural(it) && it <= 64 })
        // Never sends anyone up to C5.
        assertTrue(VoiceHome.fromSpeech(List(60) { frame(70f) })!!.easyNotes.all { it < 81 })
    }

    @Test fun deckBringsMissedNotesBackSooner() {
        var d = NameDeck()
        val pool = listOf(57, 59, 60, 62, 64)
        repeat(4) { d = d.answer(60, right = true) }
        assertEquals(5, d.box(60))
        d = d.answer(57, right = false)
        assertEquals(1, d.box(57))
        val r = Random(7)
        val picks = List(2_000) { d.next(pool, last = null, random = r) }
        assertTrue(picks.count { it == 57 } > 5 * picks.count { it == 60 })
        assertTrue((0 until 50).all { d.next(pool, last = 57, random = r) != 57 })
        assertFalse(d.knows(pool))
        for (n in pool) repeat(3) { d = d.answer(n, right = true) }
        assertTrue(d.knows(pool))
        d = d.answer(62, right = false)
        assertFalse(d.knows(pool))
    }

    @Test fun ladderMovesOnOverMoreThanOneDay() {
        var p = ReadingProgress()
        assertEquals(ReadingStage.FIND_YOUR_VOICE, ReadingLadder.stage(p))
        p = p.copy(home = VoiceHome.DEFAULT).matched(day = 100, n = 20)
        assertEquals(ReadingStage.FIND_YOUR_VOICE, ReadingLadder.stage(p))   // one day isn't enough
        p = p.matched(day = 101)
        assertEquals(ReadingStage.THE_STAFF, ReadingLadder.stage(p))
        val five = p.voice.firstFive
        for (n in five) { repeat(3) { p = p.copy(deck = p.deck.answer(n, true)) } }
        assertEquals(ReadingStage.THE_STAFF, ReadingLadder.stage(p))         // named, not yet sung
        for (n in five) repeat(2) { p = p.sang(n) }
        assertEquals(ReadingStage.STEPS_SKIPS_LEAPS, ReadingLadder.stage(p))
        assertTrue(ReadingLadder.progressWords(p).isNotBlank())
        assertFalse(ReadingLadder.progressWords(p).any { it == '%' })
    }

    @Test fun progressRoundTripsAsJson() {
        val p = ReadingProgress(VoiceHome(50, Clef.TREBLE_8VB)).matched(3).sang(52).copy(deck = NameDeck().answer(52, true))
        val json = kotlinx.serialization.json.Json.encodeToString(ReadingProgress.serializer(), p)
        assertEquals(p, kotlinx.serialization.json.Json.decodeFromString(ReadingProgress.serializer(), json))
    }

    @Test fun dayIsLocal() {
        val pst = -8 * 3_600_000
        // 8:41 PM on 18 November 2026 in Los Angeles is the 19th in UTC.
        val t = 1_795_063_260_000L
        assertEquals(ReadingLadder.day(t, 0) - 1, ReadingLadder.day(t, pst))
        assertEquals(20_776L, ReadingLadder.day(t, 0))
    }
}
