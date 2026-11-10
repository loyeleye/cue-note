package com.cuenote.signal.posture

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Notices the phone being picked up or moved, from the accelerometer. A
 * phone on a stand reads a steady 9.8 m/s² of gravity; handling it adds
 * more. Moving starts at once on a jolt and ends after [stillForMs] of calm,
 * at which point the camera's view has probably changed and posture needs a
 * fresh baseline.
 */
class MotionWatch(
    private val movingAbove: Float = 1.2f,
    private val calmBelow: Float = 0.4f,
    private val stillForMs: Long = 3_000,
) {
    sealed interface Change {
        data object StartedMoving : Change
        data object Settled : Change
    }

    var moving = false
        private set
    private var calmSinceMs = -1L

    /** Call with each accelerometer reading (m/s², phone axes). */
    fun onAccel(tMs: Long, x: Float, y: Float, z: Float): Change? {
        val off = abs(sqrt(x * x + y * y + z * z) - GRAVITY)
        if (off > movingAbove) {
            calmSinceMs = -1
            if (!moving) { moving = true; return Change.StartedMoving }
            return null
        }
        if (!moving) return null
        if (off < calmBelow) {
            if (calmSinceMs < 0) calmSinceMs = tMs
            if (tMs - calmSinceMs >= stillForMs) { moving = false; calmSinceMs = -1; return Change.Settled }
        } else {
            calmSinceMs = -1
        }
        return null
    }

    companion object { const val GRAVITY = 9.81f }
}

/**
 * Which way up the phone is, from gravity, as a display rotation (0, 1, 2, 3
 * for Surface.ROTATION_0, 90, 180, 270). This works even with auto-rotate
 * off, so the camera's pictures can be turned the right way up for the pose
 * model. Null when the phone is lying nearly flat and it can't tell.
 */
fun rotationFromGravity(x: Float, y: Float, z: Float): Int? {
    if (abs(z) > 0.8f * MotionWatch.GRAVITY) return null
    return if (abs(x) > abs(y)) {
        if (x > 0) 1 else 3 // left edge down: turned a quarter counterclockwise
    } else {
        if (y > 0) 0 else 2
    }
}

/**
 * A quiet, automatic re-baseline: after the phone has been moved, or a photo
 * taken, wait until the student is clearly in view and facing the phone for
 * [holdMs], and take how they stand then as the new baseline. Nothing is
 * spoken; flags simply wait until it's done.
 */
class BaselineSettler(private val previous: PostureMetrics, private val holdMs: Long = 3_000) {
    private var sinceMs = -1L
    private val held = mutableListOf<PostureMetrics>()

    fun onFrame(tMs: Long, pose: Pose?): PostureMetrics? {
        val m = pose?.let(PostureMetrics::of)
        // Head and shoulders clearly seen and facing the phone. Off center is fine: the camera may now point elsewhere.
        val good = m != null && !m.turnedAwayFrom(previous, keep = 0.7f)
        if (!good) { sinceMs = -1; held.clear(); return null }
        if (sinceMs < 0) sinceMs = tMs
        held += m!!
        return if (tMs - sinceMs >= holdMs) PostureMetrics.median(held) else null
    }
}
