package com.cuenote.pipeline.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class MelodyTest {
    private val baritone = VoiceHome(50, Clef.BASS)

    @Test fun everyTuneParsesAndFitsEachVoice() {
        assertTrue(Melodies.TUNES.size >= MelodyProgress.SUNG_TUNES)
        assertEquals(Melodies.TUNES.size, Melodies.TUNES.map { it.id }.toSet().size)
        for (home in listOf(baritone, VoiceHome(45, Clef.BASS), VoiceHome.DEFAULT, VoiceHome(62, Clef.TREBLE))) {
            for (t in Melodies.TUNES) {
                val m = Melodies.place(t, home)
                for (n in m.notes) assertTrue("${t.id} for ${home.speaking}: ${Staff.name(n.midi)}", n.midi in home.speaking - 3..home.speaking + 9)
                // Every note is in the key, so the key signature covers it.
                for (n in m.notes) assertNull(m.key.place(n.midi, home.clef).accidental)
            }
        }
    }

    @Test fun badBarsAreCaught() {
        val e = runCatching { Melodies.tune("x", "x", 4, "1q 2q 3q | 1w") }.exceptionOrNull()
        assertTrue(e?.message?.contains("3.0 beats") == true)
    }

    @Test fun placesAndSpellsInKey() {
        val walk = Melodies.place(Melodies.byId("walk")!!, baritone)
        assertEquals(Key.C, walk.key)
        assertEquals(listOf(48, 50, 52, 53, 55), walk.notes.take(5).map { it.midi })   // C3 D3 E3 F3 G3
        assertEquals(listOf(0f, 1f, 2f, 3f, 4f), walk.notes.take(5).map { it.start })
        assertEquals(4, walk.bars)
        // Sol below do doesn't fit in C for a baritone; G major does, with its F sharp in the key signature.
        val below = Melodies.place(Melodies.byId("below")!!, baritone)
        assertEquals(Key.G, below.key)
        assertEquals(50, below.notes[0].midi)   // D3, sol in G
        assertEquals(6, Key.G.signatureStep(Clef.BASS))      // F3, the fourth line
        assertEquals(8, Key.G.signatureStep(Clef.TREBLE))    // F5, the top line
        assertEquals(4, Key.F.signatureStep(Clef.TREBLE))    // B4, the middle line
        assertEquals(2, Key.F.signatureStep(Clef.BASS))      // B2, the second line
        assertNull(Key.G.place(54, Clef.BASS).accidental)     // F sharp needs no sign in G
        assertEquals(Accidental.SHARP, Key.C.place(54, Clef.BASS).accidental)
        assertNull(Key.F.place(58, Clef.TREBLE).accidental)   // B flat in F
    }

    private fun frame(midi: Double) = LivePitch.Frame((midi).toFloat(), 0.9f, -20.0)

    @Test fun followsTheVoiceNoteByNote() {
        val m = Melodies.place(Melodies.byId("walk")!!, baritone)
        val f = MelodyFollow(m)
        for (n in m.notes.take(3)) repeat(40) { f.push(frame(n.midi + 0.1)) }
        assertEquals(3, f.index)
        // Off pitch, it waits.
        repeat(60) { f.push(frame(m.notes[3].midi + 1.5)) }
        assertEquals(3, f.index)
        f.helpWith()
        repeat(40) { f.push(frame(m.notes[3].midi.toDouble())) }
        assertEquals(MelodyFollow.Mark.HELPED, f.marks[3])
        while (!f.done) { val n = f.current!!; repeat(40) { f.push(frame(n.midi.toDouble())) } }
        assertTrue(f.clean)
        // Sung an octave down, it still counts.
        val g = MelodyFollow(m)
        while (!g.done) { val n = g.current!!; repeat(40) { g.push(frame(n.midi - 12.0)) } }
        assertTrue(g.clean)
        // Skipping half of it isn't clean.
        val h = MelodyFollow(m)
        while (!h.done) { if (h.index % 2 == 0) h.skip() else { val n = h.current!!; repeat(40) { h.push(frame(n.midi.toDouble())) } } }
        assertFalse(h.clean)
    }

    @Test fun judgesTapsGently() {
        val onsets = listOf(0f, 1f, 2f, 4f)
        val r = RhythmCheck.judge(onsets, listOf(0.05f, 0.9f, 2.2f, 4.1f))
        assertTrue(r.marks.all { it == RhythmCheck.Mark.ON_TIME })
        assertTrue(r.clean)
        val late = RhythmCheck.judge(onsets, listOf(0.0f, 1.4f, 2.4f, 4.45f, 5.5f))
        assertEquals(listOf(RhythmCheck.Mark.ON_TIME, RhythmCheck.Mark.LATE, RhythmCheck.Mark.LATE, RhythmCheck.Mark.LATE), late.marks)
        assertEquals(1, late.extra)
        assertFalse(late.clean)
        assertTrue(late.words.contains("late"))
        val missed = RhythmCheck.judge(onsets, listOf(0f))
        assertEquals(3, missed.marks.count { it == RhythmCheck.Mark.MISSED })
    }

    @Test fun soundHasNotesWhereTheBeatsAre() {
        val m = Melodies.place(Melodies.byId("walk")!!, baritone)
        val pcm = MelodySound.render(m, notes = true, click = false, countIn = 4, sampleRate = 16_000)
        val beat = (MelodySound.beatMs() * 16).toInt()
        // Quiet through the count-in with no click, then the first note.
        assertTrue(pcm.take(4 * beat - 100).all { it.toInt() == 0 })
        val firstNote = pcm.copyOfRange(4 * beat + 200, 4 * beat + 200 + 2048)
        val hz = zeroCrossHz(firstNote, 16_000)
        assertEquals(48.0, 69 + 12 * ln(hz / 440) / ln(2.0), 0.5)
        val clicks = MelodySound.render(m, notes = false, click = true, countIn = 4, sampleRate = 16_000)
        assertTrue(clicks[10].toInt() != 0 && clicks[beat + 10].toInt() != 0)
    }

    private fun zeroCrossHz(x: ShortArray, sr: Int): Double {
        var c = 0
        for (i in 1 until x.size) if (x[i - 1] < 0 && x[i] >= 0) c++
        return c * sr.toDouble() / x.size
    }

    @Test fun stageFourEndsWithMelodiesRhythmsAndAnchors() {
        var mp = MelodyProgress()
        Melodies.TUNES.take(MelodyProgress.SUNG_TUNES).forEach { mp = mp.sangClean(it.id) }
        Melodies.TUNES.take(MelodyProgress.TAPPED_TUNES).forEach { mp = mp.tappedClean(it.id) }
        assertFalse(mp.done)
        for (a in Anchor.entries) repeat(MelodyProgress.ANCHOR_RIGHT) { mp = mp.anchorRight(a) }
        assertTrue(mp.done)

        var m = MotionProgress()
        for (x in Motion.PRACTICED) {
            repeat(MotionProgress.BY_EAR) { m = m.heardRight(x) }
            repeat(MotionProgress.BY_EYE) { m = m.seenRight(x) }
            repeat(MotionProgress.SUNG) { m = m.sang(x) }
        }
        var p = ReadingProgress(VoiceHome.DEFAULT).matched(1, 20).matched(2).copy(motions = m)
        for (n in p.voice.firstFive) { repeat(3) { p = p.copy(deck = p.deck.answer(n, true)) }; repeat(2) { p = p.sang(n) } }
        assertEquals(ReadingStage.SHORT_MELODIES, ReadingLadder.stage(p))
        p = p.copy(melodies = MelodyProgress().sangClean("walk"))
        assertEquals("Still to do: sing 7 more tunes from the page, tap 6 more rhythms, learn the songs for each leap.", ReadingLadder.progressWords(p))
        p = p.copy(melodies = mp)
        assertEquals(ReadingStage.THE_SCORE, ReadingLadder.stage(p))

        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val back = json.decodeFromString(ReadingProgress.serializer(), json.encodeToString(ReadingProgress.serializer(), p))
        assertEquals(p, back)
        val old = json.decodeFromString(ReadingProgress.serializer(), """{"home":{"speaking":50,"clef":"BASS"}}""")
        assertEquals(MelodyProgress(), old.melodies)
    }

    @Test fun anchorsPlayTheirLeap() {
        assertEquals(43 to 48, Anchor.FOURTH_UP.notes(43))
        assertEquals(47 to 43, Anchor.THIRD_DOWN.notes(43))
    }
}
