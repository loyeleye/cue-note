package com.cuenote.signal.posture

import kotlin.math.abs

/** The three things posture tracking flags, compared with how the student stood at the framing check. */
enum class PostureFlag(val key: String, val label: String) {
    CHIN_FORWARD("chin_forward", "Chin forward"),
    SHOULDERS_RAISED("shoulders_raised", "Shoulders raised"),
    HEAD_TILT("head_tilt", "Head tilt"),
}

/**
 * How far from the baseline counts as a flag. Each is in the metric's own
 * units (degrees, or shoulder widths). Starting values, to tune after the
 * first real lessons.
 */
data class FlagThresholds(
    val headTiltDeg: Float = 10f,
    /** Neck shorter than the baseline by this fraction. */
    val neckShorterBy: Float = 0.18f,
    val noseForwardBy: Float = 0.25f,
    val chinLiftBy: Float = 0.15f,
    /** The condition has to hold for this long, on most frames, before it is flagged. */
    val holdMs: Long = 1_500,
    val holdFraction: Float = 0.8f,
    /** And it ends once it has been clear for this long. */
    val clearMs: Long = 1_000,
    /** No second snapshot of the same flag within this long. */
    val cooldownMs: Long = 20_000,
)

/**
 * Turns a stream of per-frame metrics into flag start and end events. A
 * flag starts when its condition holds for most of [FlagThresholds.holdMs],
 * so one odd frame never flags, and ends when it has been clear for
 * [FlagThresholds.clearMs].
 */
class PostureFlagDetector(
    private val baseline: PostureMetrics,
    private val t: FlagThresholds = FlagThresholds(),
) {
    sealed interface Event {
        val flag: PostureFlag
        data class Started(override val flag: PostureFlag, val detail: String) : Event
        data class Ended(override val flag: PostureFlag) : Event
    }

    private class Track {
        val window = ArrayDeque<Pair<Long, Boolean>>()
        var active = false
        var lastTrueMs = -1L
        var lastStartMs = Long.MIN_VALUE / 2
    }

    private val tracks = PostureFlag.entries.associateWith { Track() }

    /** Which flags are on right now. */
    fun active(): Set<PostureFlag> = tracks.filterValues { it.active }.keys

    /** Call for each analyzed frame; [m] is null when the student wasn't clearly in view. */
    fun onFrame(tMs: Long, m: PostureMetrics?): List<Event> {
        val out = mutableListOf<Event>()
        // Turned away from the phone, the shoulders look narrower and the head
        // angles shift: those moments aren't judged at all.
        val judged = m?.takeUnless { it.turnedAwayFrom(baseline) }
        for ((flag, track) in tracks) {
            val (on, detail) = if (judged == null) false to "" else check(flag, judged)
            track.window.addLast(tMs to on)
            while (track.window.isNotEmpty() && tMs - track.window.first().first > t.holdMs) track.window.removeFirst()
            if (on) track.lastTrueMs = tMs
            val span = tMs - track.window.first().first
            val fraction = track.window.count { it.second }.toFloat() / track.window.size
            if (!track.active) {
                val held = span >= t.holdMs * 0.9 && fraction >= t.holdFraction && on
                if (held && tMs - track.lastStartMs >= t.cooldownMs) {
                    track.active = true
                    track.lastStartMs = tMs
                    out += Event.Started(flag, detail)
                }
            } else if (!on && tMs - track.lastTrueMs >= t.clearMs) {
                track.active = false
                out += Event.Ended(flag)
            }
        }
        return out
    }

    /** End anything still on, for when tracking stops or pauses. */
    fun endAll(): List<Event> = tracks.filterValues { it.active }.map { (flag, track) ->
        track.active = false
        track.window.clear()
        Event.Ended(flag)
    }

    private fun check(flag: PostureFlag, m: PostureMetrics): Pair<Boolean, String> = when (flag) {
        PostureFlag.HEAD_TILT -> {
            val d = m.headTiltDeg - baseline.headTiltDeg
            (abs(d) >= t.headTiltDeg) to "tilt ${"%.0f".format(d)} deg"
        }
        PostureFlag.SHOULDERS_RAISED -> {
            val shorter = if (baseline.neckLength <= 0f) 0f else 1f - m.neckLength / baseline.neckLength
            (shorter >= t.neckShorterBy) to "neck ${"%.0f".format(shorter * 100)}% shorter"
        }
        PostureFlag.CHIN_FORWARD -> {
            val fwd = m.noseForward - baseline.noseForward
            val lift = m.chinLift - baseline.chinLift
            when {
                fwd >= t.noseForwardBy -> true to "forward ${"%.2f".format(fwd)}"
                lift >= t.chinLiftBy -> true to "lifted ${"%.2f".format(lift)}"
                else -> false to ""
            }
        }
    }
}
