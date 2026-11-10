package com.cuenote.signal.posture

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * One body point from the pose model. [x] and [y] are 0..1 across the
 * upright camera frame (x to the right of the image, y downward), [z] is
 * depth on roughly the same scale as x (smaller is nearer the camera), and
 * [visibility] is how sure the model is the point is actually in view.
 */
data class Landmark(val x: Float, val y: Float, val z: Float, val visibility: Float)

/**
 * The 33 body points of one person in one frame. [aspect] is the frame's
 * width divided by its height, so distances can be measured in the same
 * units across and down. "Left" and "right" are the person's own sides.
 */
data class Pose(val landmarks: List<Landmark>, val aspect: Float) {
    operator fun get(i: Int): Landmark = landmarks[i]

    fun seen(i: Int, minVisibility: Float = 0.5f) = landmarks.getOrNull(i)?.let { it.visibility >= minVisibility } == true

    /** Point [i] in frame-height units: (x * aspect, y). */
    fun px(i: Int) = landmarks[i].x * aspect
    fun py(i: Int) = landmarks[i].y

    /** Middle of the shoulders, in 0..1 frame coordinates. */
    val shoulderCenterX get() = (landmarks[LEFT_SHOULDER].x + landmarks[RIGHT_SHOULDER].x) / 2
    val shoulderCenterY get() = (landmarks[LEFT_SHOULDER].y + landmarks[RIGHT_SHOULDER].y) / 2

    /**
     * Middle of whichever shoulders are clearly seen (both from the front,
     * often just the near one side-on), or null if neither is.
     */
    val bodyCenter: Pair<Float, Float>? get() {
        val seen = listOf(LEFT_SHOULDER, RIGHT_SHOULDER).filter { seen(it) }
        if (seen.isEmpty()) return null
        return seen.map { landmarks[it].x }.average().toFloat() to seen.map { landmarks[it].y }.average().toFloat()
    }

    /** How big the person looks: the diagonal around their clearly seen points, in frame-height units. */
    val size: Float get() {
        val seen = landmarks.indices.filter { seen(it) }
        if (seen.size < 2) return 0f
        val xs = seen.map { px(it) }
        val ys = seen.map { py(it) }
        return hypot(xs.max() - xs.min(), ys.max() - ys.min())
    }

    /** Shoulder to shoulder, in frame-height units. The ruler for everything else. */
    val shoulderWidth: Float get() = hypot(px(LEFT_SHOULDER) - px(RIGHT_SHOULDER), py(LEFT_SHOULDER) - py(RIGHT_SHOULDER))

    companion object {
        const val COUNT = 33
        const val NOSE = 0
        const val LEFT_EYE = 2
        const val RIGHT_EYE = 5
        const val LEFT_EAR = 7
        const val RIGHT_EAR = 8
        const val LEFT_SHOULDER = 11
        const val RIGHT_SHOULDER = 12
        const val LEFT_HIP = 23
        const val RIGHT_HIP = 24
    }
}

/**
 * The few numbers the posture flags look at, each measured against the
 * student's own shoulder width so it doesn't matter how far away they stand.
 */
data class PostureMetrics(
    /** Degrees the ear line leans away from the shoulder line; 0 is level. */
    val headTiltDeg: Float,
    /** Ear height above the shoulders, in shoulder widths. Shrinks when the shoulders rise. */
    val neckLength: Float,
    /** How far the nose sits in front of the shoulders, in shoulder widths. Grows when the chin juts forward. */
    val noseForward: Float,
    /** Nose height above the ears, in shoulder widths. Grows when the chin lifts. */
    val chinLift: Float,
    /**
     * Shoulder width over torso height. Drops when the student turns away
     * from the phone, since their shoulders look narrower. Null when the hips
     * aren't in view.
     */
    val facing: Float? = null,
    /** Which way the student stands to the phone; the side view uses the two fields below instead. */
    val view: PostureView = PostureView.FRONT,
    /** Side view: how far the ear sits ahead of the shoulder, in torso lengths. Grows with a forward head. */
    val earForward: Float = 0f,
    /** Side view: degrees the ear-to-nose line points above level. Up is a lifted chin, down a dropped one. */
    val headPitchDeg: Float = 0f,
) {
    companion object {
        /** The metrics for [pose] seen from [view], or null when the points needed aren't clearly in view. */
        fun of(pose: Pose, view: PostureView): PostureMetrics? =
            if (view == PostureView.SIDE) SideView.metrics(pose) else of(pose)

        /** The front-view metrics for [pose], or null when the head and shoulders aren't clearly in view. */
        fun of(pose: Pose): PostureMetrics? {
            val need = intArrayOf(Pose.NOSE, Pose.LEFT_EAR, Pose.RIGHT_EAR, Pose.LEFT_SHOULDER, Pose.RIGHT_SHOULDER)
            if (need.any { !pose.seen(it) }) return null
            val w = pose.shoulderWidth
            if (w < 0.02f) return null
            val ears = angleDeg(pose.px(Pose.RIGHT_EAR), pose.py(Pose.RIGHT_EAR), pose.px(Pose.LEFT_EAR), pose.py(Pose.LEFT_EAR))
            val shoulders = angleDeg(
                pose.px(Pose.RIGHT_SHOULDER), pose.py(Pose.RIGHT_SHOULDER),
                pose.px(Pose.LEFT_SHOULDER), pose.py(Pose.LEFT_SHOULDER),
            )
            val earY = (pose.py(Pose.LEFT_EAR) + pose.py(Pose.RIGHT_EAR)) / 2
            val shoulderY = (pose.py(Pose.LEFT_SHOULDER) + pose.py(Pose.RIGHT_SHOULDER)) / 2
            val shoulderZ = (pose[Pose.LEFT_SHOULDER].z + pose[Pose.RIGHT_SHOULDER].z) / 2
            val facing = if (pose.seen(Pose.LEFT_HIP) && pose.seen(Pose.RIGHT_HIP)) {
                val hx = (pose.px(Pose.LEFT_HIP) + pose.px(Pose.RIGHT_HIP)) / 2
                val hy = (pose.py(Pose.LEFT_HIP) + pose.py(Pose.RIGHT_HIP)) / 2
                val sx = (pose.px(Pose.LEFT_SHOULDER) + pose.px(Pose.RIGHT_SHOULDER)) / 2
                val torso = hypot(hx - sx, hy - shoulderY)
                if (torso > 0.01f) w / torso else null
            } else null
            return PostureMetrics(
                headTiltDeg = wrap(ears - shoulders),
                neckLength = (shoulderY - earY) / w,
                noseForward = (shoulderZ - pose[Pose.NOSE].z) / w,
                chinLift = (earY - pose.py(Pose.NOSE)) / w,
                facing = facing,
            )
        }

        /** The middle value of each metric, so one odd frame doesn't skew a baseline. */
        fun median(all: List<PostureMetrics>): PostureMetrics? {
            if (all.isEmpty()) return null
            fun med(f: (PostureMetrics) -> Float) = all.map(f).sorted()[all.size / 2]
            val facings = all.mapNotNull { it.facing }.sorted()
            return PostureMetrics(
                med { it.headTiltDeg }, med { it.neckLength }, med { it.noseForward }, med { it.chinLift },
                facings.getOrNull(facings.size / 2),
                all.first().view, med { it.earForward }, med { it.headPitchDeg },
            )
        }

        private fun angleDeg(x1: Float, y1: Float, x2: Float, y2: Float): Float =
            Math.toDegrees(atan2((y2 - y1).toDouble(), abs(x2 - x1).toDouble())).toFloat()

        private fun wrap(d: Float): Float = when {
            d > 180 -> d - 360
            d < -180 -> d + 360
            else -> d
        }
    }

    /**
     * Whether the student is turned too far from the phone to judge, compared
     * with how they faced it for this baseline. Unknown (hips out of view) counts as facing.
     */
    fun turnedAwayFrom(baseline: PostureMetrics, keep: Float = 0.75f): Boolean {
        val now = facing ?: return false
        val then = baseline.facing ?: return false
        // Side-on, "turned away" is the other way round: turning to face the
        // phone makes the shoulders look wider.
        if (baseline.view == PostureView.SIDE) return now > maxOf(then / keep, then + SideView.TURN_MARGIN)
        return now < then * keep
    }

    /** The numbers on one line, for handing the baseline to the camera service. */
    fun encode(): String =
        "$headTiltDeg,$neckLength,$noseForward,$chinLift,${facing ?: ""},${view.name},$earForward,$headPitchDeg"
}

fun decodePostureMetrics(s: String?): PostureMetrics? {
    val raw = s?.split(',') ?: return null
    val p = raw.take(4).mapNotNull { it.toFloatOrNull() }
    if (p.size != 4) return null
    val view = raw.getOrNull(5)?.let { v -> PostureView.entries.firstOrNull { it.name == v } } ?: PostureView.FRONT
    return PostureMetrics(
        p[0], p[1], p[2], p[3], raw.getOrNull(4)?.toFloatOrNull(),
        view, raw.getOrNull(6)?.toFloatOrNull() ?: 0f, raw.getOrNull(7)?.toFloatOrNull() ?: 0f,
    )
}

/**
 * Picks the student when the camera sees two people (the teacher at the
 * piano, say): the one nearest where the student was last seen. Starts from
 * where the framing check found them.
 */
class StudentPicker(startX: Float?, startY: Float?) {
    private var lastX = startX
    private var lastY = startY
    private var lastSeenMs = -1L

    fun pick(poses: List<Pose>, tMs: Long): Pose? {
        val usable = poses.mapNotNull { p -> p.bodyCenter?.let { p to it } }
        if (usable.isEmpty()) return null
        val lx = lastX
        val ly = lastY
        val (chosen, center) = if (lx == null || ly == null) {
            usable.maxBy { (p, _) -> p.size } // nearest the camera
        } else {
            val best = usable.minBy { (_, c) -> hypot(c.first - lx, c.second - ly) }
            val jump = hypot(best.second.first - lx, best.second.second - ly)
            // A lone person far from where the student just was is more likely the teacher.
            val recent = lastSeenMs >= 0 && tMs - lastSeenMs < 10_000
            if (recent && jump > MAX_JUMP) return null
            best
        }
        lastX = center.first
        lastY = center.second
        lastSeenMs = tMs
        return chosen
    }

    companion object { const val MAX_JUMP = 0.25f }
}

/** One line of pose.tsv: the time, the frame's aspect, then x, y, z and visibility for all 33 points. */
object PoseTsv {
    val HEADER: String = buildString {
        append("t_ms\taspect")
        for (i in 0 until Pose.COUNT) append("\tx$i\ty$i\tz$i\tv$i")
    }

    fun line(tMs: Long, pose: Pose): String = buildString {
        append(tMs).append('\t').append(f(pose.aspect))
        pose.landmarks.forEach { l ->
            append('\t').append(f(l.x)).append('\t').append(f(l.y)).append('\t').append(f(l.z)).append('\t').append(f(l.visibility))
        }
    }

    fun parse(line: String): Pair<Long, Pose>? {
        val p = line.split('\t')
        if (p.size != 2 + Pose.COUNT * 4) return null
        val t = p[0].toLongOrNull() ?: return null
        val aspect = p[1].toFloatOrNull() ?: return null
        val marks = (0 until Pose.COUNT).map { i ->
            val o = 2 + i * 4
            Landmark(p[o].toFloat(), p[o + 1].toFloat(), p[o + 2].toFloat(), p[o + 3].toFloat())
        }
        return t to Pose(marks, aspect)
    }

    private fun f(v: Float) = "%.4f".format(java.util.Locale.US, v)
}
