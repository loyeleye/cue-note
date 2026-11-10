package com.cuenote.signal.posture

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** How the student stands to the phone for posture: facing it, or side-on. */
enum class PostureView(val label: String) {
    FRONT("Front"),
    SIDE("Side"),
}

/**
 * Side-on posture. With the student's side to the phone, the near ear,
 * shoulder and hip are clear, and the far ones are mostly hidden. The ruler
 * is the near shoulder-to-hip length (the torso), since shoulder width can't
 * be seen. This is the best view for a forward head: the ear moves ahead of
 * the shoulder.
 */
object SideView {
    /** Side-on, the shoulders look narrow. Wider than this many torso lengths means facing the phone. */
    const val FACING_PHONE = 0.45f
    /** How much wider the shoulders may look than at the baseline before the student counts as turned. */
    const val TURN_MARGIN = 0.2f

    private data class Near(val ear: Int, val shoulder: Int, val hip: Int)

    /** The side nearer the camera: whichever of ear, shoulder and hip the model is surer of. */
    private fun near(pose: Pose): Near {
        val left = pose[Pose.LEFT_EAR].visibility + pose[Pose.LEFT_SHOULDER].visibility + pose[Pose.LEFT_HIP].visibility
        val right = pose[Pose.RIGHT_EAR].visibility + pose[Pose.RIGHT_SHOULDER].visibility + pose[Pose.RIGHT_HIP].visibility
        return if (left >= right) Near(Pose.LEFT_EAR, Pose.LEFT_SHOULDER, Pose.LEFT_HIP)
        else Near(Pose.RIGHT_EAR, Pose.RIGHT_SHOULDER, Pose.RIGHT_HIP)
    }

    private fun torso(pose: Pose, n: Near) =
        hypot(pose.px(n.shoulder) - pose.px(n.hip), pose.py(n.shoulder) - pose.py(n.hip))

    /** +1 if the student faces the right of the picture, -1 if the left (the nose is ahead of the ear). */
    private fun facingSign(pose: Pose, n: Near) = if (pose.px(Pose.NOSE) >= pose.px(n.ear)) 1f else -1f

    fun metrics(pose: Pose): PostureMetrics? {
        val n = near(pose)
        if (!pose.seen(Pose.NOSE) || !pose.seen(n.ear) || !pose.seen(n.shoulder) || !pose.seen(n.hip)) return null
        val t = torso(pose, n)
        if (t < 0.05f) return null
        val dir = facingSign(pose, n)
        val pitch = Math.toDegrees(
            atan2((pose.py(n.ear) - pose.py(Pose.NOSE)).toDouble(), abs(pose.px(Pose.NOSE) - pose.px(n.ear)).toDouble()),
        ).toFloat()
        return PostureMetrics(
            headTiltDeg = 0f,
            neckLength = (pose.py(n.shoulder) - pose.py(n.ear)) / t,
            noseForward = 0f,
            chinLift = 0f,
            facing = pose.shoulderWidth / t,
            view = PostureView.SIDE,
            earForward = (pose.px(n.ear) - pose.px(n.shoulder)) * dir / t,
            headPitchDeg = pitch,
        )
    }

    /** Framing advice for standing side-on. "Forward" is the way the student faces. */
    fun judge(pose: Pose?): FramingAdvice {
        if (pose == null) return FramingAdvice.SIDE_NOT_SEEN
        val n = near(pose)
        val bothSeen = pose.seen(Pose.LEFT_SHOULDER) && pose.seen(Pose.RIGHT_SHOULDER) &&
            pose.seen(Pose.LEFT_EAR) && pose.seen(Pose.RIGHT_EAR)
        val t = torso(pose, n)
        if (bothSeen && t > 0.05f && pose.shoulderWidth / t > FACING_PHONE) return FramingAdvice.TURN_SIDE
        if (!pose.seen(Pose.NOSE) || !pose.seen(n.ear) || !pose.seen(n.shoulder)) return FramingAdvice.SIDE_NOT_SEEN
        if (!pose.seen(n.hip)) {
            return if (pose[n.shoulder].y > 0.5f) FramingAdvice.MOVE_AWAY else FramingAdvice.SIDE_NOT_SEEN
        }
        val x = pose[n.shoulder].x
        val dir = facingSign(pose, n)
        // To move toward the picture's right, a student facing right walks forward.
        if (x < FramingRules.MIN_CENTER_X) return if (dir > 0) FramingAdvice.STEP_FORWARD else FramingAdvice.STEP_BACKWARD
        if (x > FramingRules.MAX_CENTER_X) return if (dir > 0) FramingAdvice.STEP_BACKWARD else FramingAdvice.STEP_FORWARD
        val headTop = pose[n.ear].y - t * 0.5f
        if (headTop < FramingRules.HEAD_MARGIN || pose[n.hip].y > FramingRules.BOTTOM_MARGIN || t > 0.7f) {
            return FramingAdvice.MOVE_AWAY
        }
        if (t < MIN_TORSO) return FramingAdvice.MOVE_NEARER
        return FramingAdvice.IN_FRAME
    }

    /** The torso should take up at least this much of the picture's height. */
    const val MIN_TORSO = 0.18f
}
