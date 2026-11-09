package com.cuenote.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LevelMeterTest {
    @Test fun `measures rms of a tone on each channel`() {
        val l = TestSignals.levels(TestSignals.stereo(1.0, leftDb = -20.0, rightDb = -40.0))
        assertEquals(-20.0, l.student.rmsDb, 0.5)
        assertEquals(-40.0, l.teacher.rmsDb, 0.5)
        assertTrue(l.correlation < 0.5)
    }

    @Test fun `identical channels correlate fully`() {
        val l = TestSignals.levels(TestSignals.stereo(1.0, leftDb = -20.0, rightDb = null, sameSignal = true))
        assertTrue(l.correlation > 0.99)
    }
}

class SetupCheckTest {
    private val check = SetupCheck()

    @Test fun `student voice on the left passes`() {
        val l = TestSignals.levels(TestSignals.stereo(2.0, leftDb = -25.0, rightDb = -45.0))
        assertEquals(StepResult.Pass, check.voiceStep(Channel.STUDENT, l))
    }

    @Test fun `teacher voice on the left is reported as swapped`() {
        val l = TestSignals.levels(TestSignals.stereo(2.0, leftDb = -25.0, rightDb = -45.0))
        assertEquals(Problem.CHANNELS_SWAPPED, (check.voiceStep(Channel.TEACHER, l) as StepResult.Fail).problem)
    }

    @Test fun `student on the right is fixed by switching channels`() {
        val l = TestSignals.levels(TestSignals.stereo(2.0, leftDb = -45.0, rightDb = -25.0))
        val outcome = check.studentStep(l)
        assertEquals(StepResult.Pass, outcome.result)
        assertEquals(true, outcome.switchChannels)
    }

    @Test fun `student on the left needs no switch`() {
        val outcome = check.studentStep(TestSignals.levels(TestSignals.stereo(2.0, leftDb = -25.0, rightDb = -45.0)))
        assertEquals(StepResult.Pass, outcome.result)
        assertEquals(false, outcome.switchChannels)
    }

    @Test fun `a quiet student step is not mistaken for swapped mics`() {
        val outcome = check.studentStep(TestSignals.levels(TestSignals.stereo(2.0, leftDb = null, rightDb = null)))
        assertEquals(Problem.TOO_QUIET, (outcome.result as StepResult.Fail).problem)
        assertEquals(false, outcome.switchChannels)
    }

    @Test fun `teacher on the student's channel after switching is flagged`() {
        val r = check.teacherStep(TestSignals.levels(TestSignals.stereo(2.0, leftDb = -25.0, rightDb = -45.0)))
        assertEquals(Problem.SAME_CHANNEL, (r as StepResult.Fail).problem)
    }

    @Test fun `swapping samples exchanges left and right`() {
        val buf = shortArrayOf(1, 2, 3, 4, 5)
        swapStereoInPlace(buf, 4)
        assertEquals(listOf<Short>(2, 1, 4, 3, 5), buf.toList())
    }

    @Test fun `mono mix is caught`() {
        val l = TestSignals.levels(TestSignals.stereo(2.0, leftDb = -25.0, rightDb = null, sameSignal = true))
        assertEquals(Problem.MONO_MIX, (check.voiceStep(Channel.STUDENT, l) as StepResult.Fail).problem)
    }

    @Test fun `silence is too quiet`() {
        val l = TestSignals.levels(TestSignals.stereo(2.0, leftDb = null, rightDb = null))
        assertEquals(Problem.TOO_QUIET, (check.voiceStep(Channel.STUDENT, l) as StepResult.Fail).problem)
    }

    @Test fun `voice on both channels is not separated`() {
        val l = TestSignals.levels(TestSignals.stereo(2.0, leftDb = -25.0, rightDb = -29.0))
        assertEquals(Problem.NOT_SEPARATED, (check.voiceStep(Channel.STUDENT, l) as StepResult.Fail).problem)
    }

    @Test fun `a clipped loud note fails`() {
        val l = TestSignals.levels(TestSignals.stereo(1.0, leftDb = 0.0, rightDb = -40.0))
        assertEquals(Problem.CLIPPING, (check.loudStep(l) as StepResult.Fail).problem)
        val ok = TestSignals.levels(TestSignals.stereo(1.0, leftDb = -12.0, rightDb = -40.0))
        assertEquals(StepResult.Pass, check.loudStep(ok))
    }

    @Test fun `piano bleed close to soft singing fails`() {
        val pianoOnly = TestSignals.levels(TestSignals.stereo(2.0, leftDb = -40.0, rightDb = -15.0))
        val soft = TestSignals.levels(TestSignals.stereo(2.0, leftDb = -37.0, rightDb = -15.0))
        val loud = TestSignals.levels(TestSignals.stereo(2.0, leftDb = -15.0, rightDb = -10.0))
        assertEquals(Problem.PIANO_TOO_LOUD, (check.pianoStep(pianoOnly, soft, loud) as StepResult.Fail).problem)
        val softOk = TestSignals.levels(TestSignals.stereo(2.0, leftDb = -28.0, rightDb = -15.0))
        assertEquals(StepResult.Pass, check.pianoStep(pianoOnly, softOk, loud))
    }
}

class ChunkedWavSinkTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `splits a stream into chunks with no lost frames`() {
        val framesPerChunk = 4_800 // 0.1 s, to keep the test fast
        val closed = mutableListOf<ChunkedWavSink.ChunkInfo>()
        val sink = ChunkedWavSink(tmp.root, framesPerChunk, onChunkClosed = { closed += it })
        val audio = TestSignals.stereo(0.35, leftDb = -20.0, rightDb = -30.0)
        // Feed in odd-sized blocks, as AudioRecord does.
        var i = 0
        while (i < audio.size) {
            val n = minOf(1_234 * 2, audio.size - i)
            sink.write(audio.copyOfRange(i, i + n))
            i += n
        }
        sink.close()

        assertEquals(4, closed.size)
        assertEquals(listOf(0L, 4_800L, 9_600L, 14_400L), closed.map { it.startFrame })
        assertEquals(audio.size / 2L, closed.sumOf { it.frames })

        val joined = closed.flatMap { WavReader.read(it.file).samples.toList() }.toShortArray()
        assertTrue("audio must round-trip exactly", audio.contentEquals(joined))
        val first = WavReader.read(closed[0].file)
        assertEquals(48_000, first.sampleRate)
        assertEquals(2, first.channels)
    }
}

class GapDetectorTest {
    @Test fun `steady audio has no gaps and a stall is reported once`() {
        val d = GapDetector(toleranceMs = 250)
        var now = 0L
        repeat(100) { // 100 reads of 10 ms
            assertEquals(null, d.onFrames(480, now)); now += 10_000_000
        }
        now += 1_000_000_000 // 1 s stall, no frames
        val gap = d.onFrames(480, now)
        assertTrue(gap != null && gap.missingMs in 900..1_100)
        now += 10_000_000
        assertEquals(null, d.onFrames(480, now))
        assertEquals(1, d.gaps.size)
    }
}

class LessonEventTest {
    @Test fun `events round-trip through their text form`() {
        val events = listOf(
            LessonEvent.Mark(1_000),
            LessonEvent.Photo(2_500, "photo_0001.jpg"),
            LessonEvent.Gap(3_000, 400),
            LessonEvent.MicChanged(0, "LARK M2S"),
        )
        assertEquals(events, events.map { LessonEvent.parse(it.toLine()) })
    }
}
