package com.cuenote.signal.posture

/**
 * What the framing check tells the student, out loud, from their singing
 * spot. Directions are the student's own left and right: the camera faces
 * them, so someone on the left of the picture needs to step to their left.
 */
enum class FramingAdvice(val line: VoiceLine) {
    NOT_SEEN(VoiceLine.NOT_SEEN),
    STEP_LEFT(VoiceLine.STEP_LEFT),
    STEP_RIGHT(VoiceLine.STEP_RIGHT),
    STEP_BACK(VoiceLine.STEP_BACK),
    STEP_CLOSER(VoiceLine.STEP_CLOSER),
    IN_FRAME(VoiceLine.IN_FRAME),
    ;

    val spoken: String get() = line.text
}

object FramingRules {
    /** Shoulders should sit in this middle part of the picture, side to side. */
    const val MIN_CENTER_X = 0.3f
    const val MAX_CENTER_X = 0.7f
    /** Shoulder width, as a fraction of the picture's width: too small is too far away. */
    const val MIN_SHOULDER_FRACTION = 0.12f
    const val MAX_SHOULDER_FRACTION = 0.6f
    /** Keep the top of the head (well above the nose) and the shoulders inside the picture. */
    const val HEAD_MARGIN = 0.05f
    const val BOTTOM_MARGIN = 0.97f

    fun judge(pose: Pose?): FramingAdvice {
        if (pose == null) return FramingAdvice.NOT_SEEN
        val head = intArrayOf(Pose.NOSE, Pose.LEFT_SHOULDER, Pose.RIGHT_SHOULDER)
        if (head.any { !pose.seen(it) }) return FramingAdvice.NOT_SEEN
        val cx = pose.shoulderCenterX
        // The picture's left is the student's right, so they step to their left.
        if (cx < MIN_CENTER_X) return FramingAdvice.STEP_LEFT
        if (cx > MAX_CENTER_X) return FramingAdvice.STEP_RIGHT
        val widthFraction = pose.shoulderWidth / pose.aspect
        val shoulderBottom = maxOf(pose[Pose.LEFT_SHOULDER].y, pose[Pose.RIGHT_SHOULDER].y)
        // The top of the head is roughly a shoulder width above the nose.
        val headTop = pose[Pose.NOSE].y - pose.shoulderWidth * 0.6f
        if (widthFraction > MAX_SHOULDER_FRACTION || headTop < HEAD_MARGIN || shoulderBottom > BOTTOM_MARGIN) {
            return FramingAdvice.STEP_BACK
        }
        if (!pose.seen(Pose.LEFT_EAR) || !pose.seen(Pose.RIGHT_EAR)) return FramingAdvice.NOT_SEEN
        if (widthFraction < MIN_SHOULDER_FRACTION) return FramingAdvice.STEP_CLOSER
        return FramingAdvice.IN_FRAME
    }
}

/**
 * The hands-free framing check, as a clock-driven state machine so it can
 * be tested without a camera. Feed it each analyzed frame with [onFrame];
 * it says when to speak, and finishes after [holdMs] in frame (keeping the
 * posture seen then as the student's baseline) or gives up after [timeoutMs].
 */
class FramingCheck(
    private val holdMs: Long = 2_000,
    private val timeoutMs: Long = 30_000,
    /** Say the same advice again if nothing changes for this long. */
    private val repeatMs: Long = 5_000,
) {
    sealed interface Step {
        data class Speak(val advice: FramingAdvice) : Step
        data class Done(val baseline: PostureMetrics, val centerX: Float, val centerY: Float) : Step
        data object TimedOut : Step
    }

    private var startMs = -1L
    private var lastAdvice: FramingAdvice? = null
    private var lastSpokenMs = -1L
    private var inFrameSinceMs = -1L
    private val held = mutableListOf<PostureMetrics>()
    private var finished = false
    private var candidate: FramingAdvice? = null
    private var candidateFrames = 0
    var advice: FramingAdvice = FramingAdvice.NOT_SEEN
        private set

    fun onFrame(tMs: Long, pose: Pose?): Step? {
        if (finished) return null
        if (startMs < 0) startMs = tMs
        val a = FramingRules.judge(pose)
        advice = a
        val metrics = pose?.let(PostureMetrics::of)
        if (a == FramingAdvice.IN_FRAME && metrics != null) {
            if (inFrameSinceMs < 0) { inFrameSinceMs = tMs; held.clear() }
            held += metrics
            if (tMs - inFrameSinceMs >= holdMs) {
                finished = true
                return Step.Done(PostureMetrics.median(held)!!, pose.shoulderCenterX, pose.shoulderCenterY)
            }
        } else {
            inFrameSinceMs = -1
        }
        if (tMs - startMs >= timeoutMs) {
            finished = true
            return Step.TimedOut
        }
        // Only speak a change once it has held for two frames, so a wobble at an edge isn't read out.
        if (a == candidate) candidateFrames++ else { candidate = a; candidateFrames = 1 }
        val changed = a != lastAdvice && (candidateFrames >= 2 || lastAdvice == null)
        val stale = lastSpokenMs >= 0 && tMs - lastSpokenMs >= repeatMs && a != FramingAdvice.IN_FRAME
        if (changed || stale) {
            lastAdvice = a
            lastSpokenMs = tMs
            return Step.Speak(a)
        }
        return null
    }
}
