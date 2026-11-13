package com.cuenote.pipeline

import com.cuenote.signal.LessonEvent
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * Joins things up:
 *
 * - **Feedback to take.** Each feedback block belongs to the student's most
 *   recent take before it, within the same exercise (see [Exercises]); the
 *   block where she sets up the next exercise is about no take. Student talking in between is skipped over, so a
 *   question like "was that flat?" points her answer back at the take it asks
 *   about. Feedback before the first take (the lesson's opening chat) links to
 *   nothing. The longer the wait since the take, the lower the confidence.
 * - **Demo to phrase.** When she sings a phrase for the student to copy, the
 *   demo's melody is matched against the take before it and the take after it
 *   (often the student's next try), and the closest phrase wins.
 * - **Photos and marks to cards.** By time: a photo joins the feedback block it
 *   was taken during, or the one that ended shortly before it.
 */
class Linker(private val cfg: PipelineConfig = PipelineConfig()) {

    fun link(takes: List<Take>, feedback: List<FeedbackBlock>, events: List<LessonEvent>): LinkResult {
        val linked = feedback.map { fb -> linkOne(fb, takes) }
        return LinkResult(linked, events.mapNotNull { joinEvent(it, linked, takes) })
    }

    private fun linkOne(fb: FeedbackBlock, takes: List<Take>): FeedbackBlock {
        // "most recent take": the last take that started before the feedback did
        val idx = takes.indexOfLast { it.startMs < fb.startMs }
        val take = takes.getOrNull(idx)
        val sameExercise = take != null && fb.exerciseId != null && take.exerciseId == fb.exerciseId
        val (conf, reason) = when {
            take == null -> 0f to "no take before this feedback"
            // she is setting up the next exercise, not talking about the last try
            fb.exerciseId != null && take.exerciseId != null && !sameExercise -> 0f to "sets up the next exercise"
            else -> {
                val wait = max(0L, fb.startMs - take.endMs)
                when {
                    wait > cfg.linkMaxMs && sameExercise -> 0.3f to "most recent try of this exercise, ${wait / 1000} s earlier"
                    wait > cfg.linkMaxMs -> 0f to "last take ended ${wait / 1000} s earlier, too long ago"
                    wait <= cfg.linkFullConfidenceMs -> 1f to "most recent take"
                    else -> {
                        val span = (cfg.linkMaxMs - cfg.linkFullConfidenceMs).toFloat()
                        (1f - 0.7f * (wait - cfg.linkFullConfidenceMs) / span) to
                            "most recent take, ${wait / 1000} s earlier"
                    }
                }
            }
        }
        val takeId = if (conf > 0f) take?.id else null
        val next = takes.getOrNull(idx + 1)?.takeIf { it.startMs >= fb.endMs - 1_000 }
        val demos = fb.demos.map { matchDemo(it, take?.takeIf { takeId != null }, next) }
        return fb.copy(takeId = takeId, linkConfidence = conf, linkReason = reason, demos = demos)
    }

    /**
     * Finds which phrase a demo is modelling, by melody shape. The take before
     * is the usual answer ("not like that, like this"); the take after wins
     * only when it is a clearly closer match (she sang it first, then the
     * student copied it).
     */
    private fun matchDemo(demo: Demo, before: Take?, after: Take?): Demo {
        val pitch = demoPitch ?: return demo
        val d = contour(pitch.hz(demo.startMs, demo.endMs), pitch.conf(demo.startMs, demo.endMs))
        if (d.count { !it.isNaN() } < 4) return demo
        fun tryTake(t: Take?): Pair<Take, Match>? {
            t ?: return null
            val c = contour(t.pitchHz.toFloatArray(), t.pitchConfidence.toFloatArray())
            if (c.count { !it.isNaN() } < 4) return null
            return subsequenceDtw(d, c)?.let { t to it }
        }
        val prev = tryTake(before)
        val next = tryTake(after)
        val pick = when {
            prev == null -> next
            next == null -> prev
            next.second.distance < prev.second.distance * NEXT_TAKE_MARGIN -> next
            else -> prev
        } ?: return demo
        val (t, m) = pick
        return demo.copy(
            takeId = t.id,
            phraseStartMs = t.startMs + frameToMs(m.start * CONTOUR_STEP),
            phraseEndMs = t.startMs + frameToMs((m.end + 1) * CONTOUR_STEP),
            matchDistance = m.distance.toFloat(),
        )
    }

    /** Where the demo's teacher-channel pitch comes from; set by the pipeline before linking. */
    var demoPitch: PitchSource? = null

    interface PitchSource {
        fun hz(startMs: Long, endMs: Long): FloatArray
        fun conf(startMs: Long, endMs: Long): FloatArray
    }

    private fun joinEvent(e: LessonEvent, fb: List<FeedbackBlock>, takes: List<Take>): EventLink? = when (e) {
        is LessonEvent.Photo -> {
            val block = fb.filter { e.atMs >= it.startMs - cfg.photoBeforeMs && e.atMs <= it.endMs + cfg.photoAfterMs }
                .minByOrNull { distance(e.atMs, it.startMs, it.endMs) }
            EventLink("photo", e.atMs, e.fileName, block?.id, block?.takeId)
        }
        is LessonEvent.Mark -> {
            val block = fb.filter { e.atMs >= it.startMs - cfg.markWindowMs && e.atMs <= it.endMs + cfg.markWindowMs }
                .minByOrNull { distance(e.atMs, it.startMs, it.endMs) }
            val take = takes.filter { e.atMs >= it.startMs && e.atMs <= it.endMs + cfg.markWindowMs }
                .minByOrNull { distance(e.atMs, it.startMs, it.endMs) }
            // a mark during or right after a take is about the take; otherwise about the nearest feedback
            if (take != null && (block == null || distance(e.atMs, take.startMs, take.endMs) <= distance(e.atMs, block.startMs, block.endMs))) {
                EventLink("mark", e.atMs, takeId = take.id, feedbackId = fb.firstOrNull { it.takeId == take.id }?.id)
            } else EventLink("mark", e.atMs, feedbackId = block?.id, takeId = block?.takeId)
        }
        else -> null
    }

    private fun distance(t: Long, a: Long, b: Long) = if (t in a..b) 0L else minOf(abs(t - a), abs(t - b))

    // ---- melody matching ----------------------------------------------------

    data class Match(val start: Int, val end: Int, val distance: Double)

    companion object {
        /** Contours are compared every 3 frames (~100 ms). */
        const val CONTOUR_STEP = 3

        /** The take after a demo must match this much better (distance ratio) to beat the take before. */
        const val NEXT_TAKE_MARGIN = 0.6

        /** Semitone contour with the key removed (median subtracted), NaN where unvoiced. */
        fun contour(hz: FloatArray, conf: FloatArray): DoubleArray {
            val out = ArrayList<Double>()
            var i = 0
            while (i < hz.size) {
                val vals = (i until minOf(hz.size, i + CONTOUR_STEP))
                    .filter { hz[it] > 0f && conf.getOrElse(it) { 1f } >= 0.5f }.map { hz[it] }
                out += if (vals.isEmpty()) Double.NaN else 12 * ln(vals.sorted()[vals.size / 2] / 440.0) / ln(2.0)
                i += CONTOUR_STEP
            }
            val voiced = out.filter { !it.isNaN() }.sorted()
            if (voiced.isEmpty()) return DoubleArray(0)
            val med = voiced[voiced.size / 2]
            return out.map { if (it.isNaN()) it else it - med }.toDoubleArray()
        }

        /**
         * Subsequence dynamic time warping: the stretch of [hay] that the whole
         * of [needle] fits best, allowing for different tempos. The distance is
         * the mean semitone difference along the best path, octave-folded so a
         * man copying a woman's demo an octave down still matches.
         */
        fun subsequenceDtw(needle: DoubleArray, hay: DoubleArray): Match? {
            val n = needle.size; val m = hay.size
            if (n == 0 || m == 0) return null
            val inf = Double.MAX_VALUE / 4
            var prev = DoubleArray(m) { 0.0 }
            var prevLen = IntArray(m)
            var prevStart = IntArray(m) { it }
            for (i in 0 until n) {
                val cur = DoubleArray(m) { inf }
                val curLen = IntArray(m)
                val curStart = IntArray(m)
                for (j in 0 until m) {
                    val c = cost(needle[i], hay[j])
                    // from (i-1, j), (i, j-1), (i-1, j-1)
                    var best = if (i == 0) 0.0 else prev[j]
                    var len = if (i == 0) 0 else prevLen[j]
                    var start = if (i == 0) j else prevStart[j]
                    if (j > 0 && cur[j - 1] < best) { best = cur[j - 1]; len = curLen[j - 1]; start = curStart[j - 1] }
                    if (i > 0 && j > 0 && prev[j - 1] <= best) { best = prev[j - 1]; len = prevLen[j - 1]; start = prevStart[j - 1] }
                    cur[j] = best + c
                    curLen[j] = len + 1
                    curStart[j] = start
                }
                prev = cur; prevLen = curLen; prevStart = curStart
            }
            // the copied phrase is sung at a similar speed: between half and twice the demo's length
            var bestJ = -1
            var bestScore = inf
            for (j in 0 until m) {
                val span = j - prevStart[j] + 1
                if (span * 2 < n || span > n * 2) continue
                val score = prev[j] / prevLen[j]
                if (score < bestScore) { bestScore = score; bestJ = j }
            }
            if (bestJ < 0) return null
            return Match(prevStart[bestJ], bestJ, bestScore)
        }

        private fun cost(a: Double, b: Double): Double {
            if (a.isNaN() || b.isNaN()) return 1.5
            var d = abs(a - b) % 12.0
            if (d > 6) d = 12 - d
            return minOf(d, 4.0)
        }
    }
}
