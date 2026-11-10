package com.cuenote.signal

import com.cuenote.signal.posture.FramingAdvice
import com.cuenote.signal.posture.FramingCheck
import com.cuenote.signal.posture.FramingRules
import com.cuenote.signal.posture.Landmark
import com.cuenote.signal.posture.Pose
import com.cuenote.signal.posture.PoseTsv
import com.cuenote.signal.posture.PostureBackOff
import com.cuenote.signal.posture.PostureEvent
import com.cuenote.signal.posture.PostureFlag
import com.cuenote.signal.posture.PostureFlagDetector
import com.cuenote.signal.posture.PostureMetrics
import com.cuenote.signal.posture.PostureSummary
import com.cuenote.signal.posture.SingingGate
import com.cuenote.signal.posture.StudentPicker
import com.cuenote.signal.posture.decodePostureMetrics
import com.cuenote.signal.posture.BaselineSettler
import com.cuenote.signal.posture.MotionWatch
import com.cuenote.signal.posture.rotationFromGravity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A student standing facing a portrait camera. "Left" points are the student's left, so on the picture's right. */
object Poses {
    fun standing(
        dx: Float = 0f,
        scale: Float = 1f,
        shoulderRise: Float = 0f,
        tiltEars: Float = 0f,
        noseZ: Float = -0.3f,
        noseLift: Float = 0f,
        /** Below 1: turned partly away from the phone, so everything looks narrower side to side. */
        turn: Float = 1f,
    ): Pose {
        val l = MutableList(Pose.COUNT) { Landmark(0.5f + dx, 0.6f, 0f, 0.9f) }
        fun put(i: Int, x: Float, y: Float, z: Float = 0f) {
            l[i] = Landmark(0.5f + dx + (x - 0.5f) * scale * turn, 0.3f + (y - 0.3f) * scale, z, 0.95f)
        }
        put(Pose.NOSE, 0.5f, 0.3f - noseLift, noseZ)
        put(Pose.LEFT_EAR, 0.54f, 0.29f - tiltEars)
        put(Pose.RIGHT_EAR, 0.46f, 0.29f + tiltEars)
        put(Pose.LEFT_SHOULDER, 0.6f, 0.45f - shoulderRise)
        put(Pose.RIGHT_SHOULDER, 0.4f, 0.45f - shoulderRise)
        put(Pose.LEFT_HIP, 0.57f, 0.75f)
        put(Pose.RIGHT_HIP, 0.43f, 0.75f)
        return Pose(l, aspect = 0.75f)
    }
}

class FramingTest {
    @Test fun `a centered student is in frame`() = assertEquals(FramingAdvice.IN_FRAME, FramingRules.judge(Poses.standing()))

    @Test fun `nobody is not seen`() = assertEquals(FramingAdvice.NOT_SEEN, FramingRules.judge(null))

    @Test fun `on the picture's left means step to your left`() =
        assertEquals(FramingAdvice.STEP_LEFT, FramingRules.judge(Poses.standing(dx = -0.3f)))

    @Test fun `on the picture's right means step to your right`() =
        assertEquals(FramingAdvice.STEP_RIGHT, FramingRules.judge(Poses.standing(dx = 0.3f)))

    @Test fun `far away means come closer`() =
        assertEquals(FramingAdvice.STEP_CLOSER, FramingRules.judge(Poses.standing(scale = 0.4f)))

    @Test fun `too close means step back`() =
        assertEquals(FramingAdvice.STEP_BACK, FramingRules.judge(Poses.standing(scale = 3.5f)))

    @Test fun `two seconds in frame finishes with a baseline`() {
        val check = FramingCheck()
        var done: FramingCheck.Step.Done? = null
        var t = 0L
        while (t <= 3_000 && done == null) {
            done = check.onFrame(t, Poses.standing()) as? FramingCheck.Step.Done
            t += 200
        }
        assertNotNull(done)
        assertTrue(t in 2_000..2_400)
        assertEquals(0.5f, done!!.centerX, 0.01f)
    }

    @Test fun `gives up after thirty seconds without the student`() {
        val check = FramingCheck()
        var last: FramingCheck.Step? = null
        var t = 0L
        while (t <= 31_000) { check.onFrame(t, null)?.let { last = it }; t += 200 }
        assertEquals(FramingCheck.Step.TimedOut, last)
    }

    @Test fun `speaks when the advice changes and repeats it now and then`() {
        val check = FramingCheck()
        val spoken = mutableListOf<FramingAdvice>()
        var t = 0L
        repeat(30) { // 6 s off to the side, then centered
            (check.onFrame(t, Poses.standing(dx = -0.3f)) as? FramingCheck.Step.Speak)?.let { spoken += it.advice }
            t += 200
        }
        repeat(3) {
            (check.onFrame(t, Poses.standing()) as? FramingCheck.Step.Speak)?.let { spoken += it.advice }
            t += 200
        }
        assertEquals(listOf(FramingAdvice.STEP_LEFT, FramingAdvice.STEP_LEFT, FramingAdvice.IN_FRAME), spoken)
    }

    @Test fun `a one-frame wobble isn't read out`() {
        val check = FramingCheck()
        val spoken = mutableListOf<FramingAdvice>()
        val frames = listOf(Poses.standing(dx = -0.3f), Poses.standing(dx = -0.3f), null, Poses.standing(dx = -0.3f))
        frames.forEachIndexed { i, p -> (check.onFrame(i * 200L, p) as? FramingCheck.Step.Speak)?.let { spoken += it.advice } }
        assertEquals(listOf(FramingAdvice.STEP_LEFT), spoken)
    }
}

class PostureFlagTest {
    private val baseline = PostureMetrics.of(Poses.standing())!!

    private fun run(pose: Pose, seconds: Double, from: Long = 0, d: PostureFlagDetector): List<PostureFlagDetector.Event> {
        val out = mutableListOf<PostureFlagDetector.Event>()
        var t = from
        while (t < from + seconds * 1000) { out += d.onFrame(t, PostureMetrics.of(pose)); t += 200 }
        return out
    }

    @Test fun `standing as at the check flags nothing`() {
        assertTrue(run(Poses.standing(), 10.0, d = PostureFlagDetector(baseline)).isEmpty())
    }

    @Test fun `raised shoulders are flagged after a moment`() {
        val e = run(Poses.standing(shoulderRise = 0.04f), 3.0, d = PostureFlagDetector(baseline))
        assertEquals(listOf(PostureFlag.SHOULDERS_RAISED), e.filterIsInstance<PostureFlagDetector.Event.Started>().map { it.flag })
    }

    @Test fun `a head tilt is flagged`() {
        val e = run(Poses.standing(tiltEars = 0.02f), 3.0, d = PostureFlagDetector(baseline))
        assertEquals(listOf(PostureFlag.HEAD_TILT), e.filterIsInstance<PostureFlagDetector.Event.Started>().map { it.flag })
    }

    @Test fun `the chin jutting forward is flagged`() {
        val e = run(Poses.standing(noseZ = -0.4f), 3.0, d = PostureFlagDetector(baseline))
        assertEquals(listOf(PostureFlag.CHIN_FORWARD), e.filterIsInstance<PostureFlagDetector.Event.Started>().map { it.flag })
    }

    @Test fun `the chin lifting is flagged as chin forward`() {
        val e = run(Poses.standing(noseLift = 0.03f), 3.0, d = PostureFlagDetector(baseline))
        assertEquals(listOf(PostureFlag.CHIN_FORWARD), e.filterIsInstance<PostureFlagDetector.Event.Started>().map { it.flag })
    }

    @Test fun `half a second of raised shoulders is not a flag`() {
        val d = PostureFlagDetector(baseline)
        val e = run(Poses.standing(shoulderRise = 0.04f), 0.6, d = d) + run(Poses.standing(), 3.0, from = 600, d = d)
        assertTrue(e.isEmpty())
    }

    @Test fun `a flag ends once clear and waits before flagging again`() {
        val d = PostureFlagDetector(baseline)
        val up = Poses.standing(shoulderRise = 0.04f)
        val e = run(up, 3.0, d = d) + run(Poses.standing(), 3.0, from = 3_000, d = d) + run(up, 3.0, from = 6_000, d = d)
        assertEquals(1, e.count { it is PostureFlagDetector.Event.Started })
        assertEquals(1, e.count { it is PostureFlagDetector.Event.Ended })
        val later = run(Poses.standing(), 3.0, from = 9_000, d = d) + run(up, 3.0, from = 30_000, d = d)
        assertEquals(1, later.count { it is PostureFlagDetector.Event.Started })
    }

    @Test fun `metrics don't depend on how far away the student stands`() {
        val near = PostureMetrics.of(Poses.standing(scale = 1.3f))!!
        val far = PostureMetrics.of(Poses.standing(scale = 0.7f))!!
        assertEquals(near.neckLength, far.neckLength, 0.01f)
        assertEquals(near.headTiltDeg, far.headTiltDeg, 0.5f)
    }

    @Test fun `a baseline survives the trip to the camera service`() {
        assertEquals(baseline, decodePostureMetrics(baseline.encode()))
    }
}

class StudentPickerTest {
    @Test fun `keeps following the student, not the teacher`() {
        val picker = StudentPicker(0.5f, 0.45f)
        val student = Poses.standing()
        val teacher = Poses.standing(dx = 0.35f, scale = 1.2f)
        assertEquals(student, picker.pick(listOf(teacher, student), 0))
        assertEquals(student, picker.pick(listOf(student, teacher), 200))
    }

    @Test fun `ignores a lone person far from where the student just was`() {
        val picker = StudentPicker(0.5f, 0.45f)
        picker.pick(listOf(Poses.standing()), 0)
        assertNull(picker.pick(listOf(Poses.standing(dx = 0.35f)), 200))
        // but after a while away, whoever is there is taken as the student
        assertNotNull(picker.pick(listOf(Poses.standing(dx = 0.35f)), 20_000))
    }
}

class PoseTsvTest {
    @Test fun `a pose line reads back`() {
        val p = Poses.standing()
        val (t, back) = PoseTsv.parse(PoseTsv.line(1234, p))!!
        assertEquals(1234L, t)
        assertEquals(p[Pose.NOSE].x, back[Pose.NOSE].x, 0.0001f)
        assertEquals(PoseTsv.HEADER.split('\t').size, PoseTsv.line(0, p).split('\t').size)
    }
}

class PostureControlTest {
    @Test fun `singing turns the camera on fast and off only after a long quiet`() {
        val g = SingingGate()
        assertFalse(g.onLevels(0, -30.0, -50.0))
        assertTrue(g.onLevels(400, -30.0, -50.0))
        assertTrue(g.singing)
        assertFalse(g.onLevels(3_000, -70.0, -70.0)) // a breath
        assertTrue(g.singing)
        assertTrue(g.onLevels(9_000, -70.0, -70.0))
        assertFalse(g.singing)
    }

    @Test fun `the teacher talking doesn't count as the student singing`() {
        val g = SingingGate()
        // the student's mic picks the teacher up too, but quieter than her own mic
        listOf(0L, 200, 400, 600, 800).forEach { g.onLevels(it, -38.0, -25.0) }
        assertFalse(g.singing)
    }

    @Test fun `a warm phone pauses posture until it has cooled for a minute`() {
        val b = PostureBackOff()
        b.onThermal(0, 2)
        assertEquals("phone warm", b.reason(0))
        b.onThermal(10_000, 1)
        assertEquals("phone warm", b.reason(30_000))
        assertNull(b.reason(71_000))
    }

    @Test fun `a gap pauses posture, and two gaps end it for the lesson`() {
        val b = PostureBackOff()
        b.onGap(0)
        assertEquals("audio gap", b.reason(60_000))
        assertNull(b.reason(181_000))
        b.onGap(200_000)
        assertEquals("off after 2 audio gaps", b.reason(10_000_000))
    }

    @Test fun `a photo holds posture until it's released`() {
        val b = PostureBackOff()
        b.hold("photo")
        assertEquals("photo", b.reason(0))
        b.release("photo")
        assertTrue(b.allowed(0))
    }

    @Test fun `the summary adds up camera time, flags and pauses`() {
        val log = listOf(
            PostureEvent.Started(0),
            PostureEvent.CameraOn(1_000),
            PostureEvent.Flagged(5_000, PostureFlag.HEAD_TILT, "flag_000005_head_tilt.jpg", "tilt 12 deg"),
            PostureEvent.CameraOff(61_000),
            PostureEvent.Paused(70_000, "phone warm"),
            PostureEvent.CameraOn(100_000),
            PostureEvent.Stopped(130_000),
        ).joinToString("\n") { it.toLine() }
        val s = PostureSummary.parse(log)
        assertEquals(90_000L, s.trackedMs)
        assertEquals(PostureFlag.HEAD_TILT, s.flags.single().flag)
        assertEquals("phone warm", s.pauses.single().reason)
    }
}

class RecalibrationTest {
    private val baseline = PostureMetrics.of(Poses.standing())!!

    @Test fun `turned away from the phone isn't judged, so no false chin flag`() {
        val turned = PostureMetrics.of(Poses.standing(turn = 0.5f))!!
        assertTrue(turned.turnedAwayFrom(baseline))
        val d = PostureFlagDetector(baseline)
        var t = 0L
        val events = mutableListOf<PostureFlagDetector.Event>()
        while (t < 5_000) { events += d.onFrame(t, turned); t += 200 }
        assertTrue(events.isEmpty())
    }

    @Test fun `facing the phone a little off square still counts`() {
        assertFalse(PostureMetrics.of(Poses.standing(turn = 0.9f))!!.turnedAwayFrom(baseline))
    }

    @Test fun `a baseline from before this change still decodes`() {
        val old = "1.0,2.0,0.5,0.1"
        assertEquals(PostureMetrics(1f, 2f, 0.5f, 0.1f, null), decodePostureMetrics(old))
        assertEquals(baseline.facing, decodePostureMetrics(baseline.encode())!!.facing)
    }

    @Test fun `picking the phone up is movement, and three calm seconds settle it`() {
        val m = MotionWatch()
        assertNull(m.onAccel(0, 0f, 9.81f, 0f))
        assertEquals(MotionWatch.Change.StartedMoving, m.onAccel(100, 3f, 11f, 1f))
        assertNull(m.onAccel(1_000, 0f, 9.8f, 0.2f))
        assertNull(m.onAccel(3_000, 0f, 9.8f, 0.2f))
        assertEquals(MotionWatch.Change.Settled, m.onAccel(4_100, 0f, 9.8f, 0.2f))
    }

    @Test fun `piano vibrations through the stand aren't movement`() {
        val m = MotionWatch()
        listOf(9.5f, 10.1f, 9.7f, 10.2f).forEachIndexed { i, y -> assertNull(m.onAccel(i * 60L, 0f, y, 0f)) }
        assertFalse(m.moving)
    }

    @Test fun `gravity says which way up the phone is`() {
        assertEquals(0, rotationFromGravity(0f, 9.8f, 0.5f))
        assertEquals(1, rotationFromGravity(9.8f, 0.3f, 0.5f))
        assertEquals(3, rotationFromGravity(-9.8f, 0.3f, 0.5f))
        assertEquals(2, rotationFromGravity(0.2f, -9.8f, 0.5f))
        assertNull(rotationFromGravity(0.2f, 0.3f, 9.8f)) // lying flat
    }

    @Test fun `a quiet re-baseline takes three seconds of the student in view`() {
        val s = BaselineSettler(baseline)
        val moved = Poses.standing(dx = -0.25f, scale = 0.8f) // the camera now sees them off to one side
        var got: PostureMetrics? = null
        var t = 0L
        while (got == null && t < 5_000) { got = s.onFrame(t, moved); t += 200 }
        assertNotNull(got)
        assertTrue(t in 3_000..3_400)
    }

    @Test fun `the re-baseline waits while the student is turned away`() {
        val s = BaselineSettler(baseline)
        var t = 0L
        while (t < 5_000) { assertNull(s.onFrame(t, Poses.standing(turn = 0.5f))); t += 200 }
    }

    @Test fun `a new baseline is logged and counted`() {
        val log = listOf(PostureEvent.Started(0), PostureEvent.Rebaselined(60_000, "manual")).joinToString("\n") { it.toLine() }
        assertEquals("manual", PostureSummary.parse(log).rebaselines.single().why)
    }
}

class VoiceLineTest {
    @Test fun `every line has its own clip name`() {
        val keys = com.cuenote.signal.posture.VoiceLine.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.matches(Regex("[a-z_]+")) })
    }

    @Test fun `framing advice is spoken with its line`() {
        FramingAdvice.entries.forEach { assertEquals(it.line.text, it.spoken) }
    }
}
