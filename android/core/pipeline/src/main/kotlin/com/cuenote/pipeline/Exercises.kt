package com.cuenote.pipeline

/**
 * Groups takes into exercises: one warmup, or one section of a song, with the
 * student's tries and all of her feedback in between. A new exercise starts
 * between two takes when
 *
 * - nobody sang for [PipelineConfig.exerciseBreakMs] (a long explanation, a
 *   story, a change of subject), or
 * - the first song-length take begins after only short tries (the
 *   warmups are over and the song has started), or
 * - she says something that moves the lesson on ("let's sing a scale",
 *   "now we go to the song", "next"), unless it only moves the same exercise
 *   ("let's start lower", "again", "one more time").
 *
 * Feedback goes with the exercise of the take before it, except that the
 * block in which she moves on (and the lesson's first demo, just before the
 * first take) introduces the next exercise instead.
 *
 * This is a first guess, to be tuned against labelled lessons; the timeline
 * shows the reason for every edge.
 */
class Exercises(private val cfg: PipelineConfig = PipelineConfig()) {

    data class Result(val exercises: List<Exercise>, val takes: List<Take>, val feedback: List<FeedbackBlock>)

    /** Why a new exercise starts before a take, and when she said so (for a spoken cue). */
    private data class Edge(val reason: String, val cueMs: Long?)

    fun group(
        takes: List<Take>,
        feedback: List<FeedbackBlock>,
        transcript: List<TranscriptLine>,
        lessonId: String = "lesson",
    ): Result {
        if (takes.isEmpty()) return Result(emptyList(), takes, feedback)
        val teacher = transcript.filter { it.speaker == Speaker.TEACHER }

        // which exercise each take is in, and why each exercise starts
        val group = IntArray(takes.size)
        val edges = mutableMapOf<Int, Edge>()   // exercise index -> why it starts
        var groupHasSong = isSong(takes[0])
        for (i in 1 until takes.size) {
            val prev = takes[i - 1]; val cur = takes[i]
            val edge = edgeBetween(prev, cur, teacher)
                // the first full run through the song starts it, even when she said nothing we caught
                ?: if (isSong(cur) && !groupHasSong) Edge("the first long sung take (${(cur.endMs - cur.startMs) / 1000} s)", null) else null
            group[i] = group[i - 1] + if (edge != null) 1 else 0
            if (edge != null) { edges[group[i]] = edge; groupHasSong = false }
            groupHasSong = groupHasSong || isSong(cur)
        }
        val count = group.last() + 1
        val ids = List(count) { "$lessonId-ex-%03d".format(it + 1) }

        // each feedback block's exercise
        val fbGroup = feedback.map { fb ->
            val idx = takes.indexOfLast { it.startMs < fb.startMs }
            if (idx < 0) {
                // before any singing: her first demo belongs to the first exercise, the opening chat to none
                if (fb.endMs >= takes[0].startMs - INTRO_MS) 0 else null
            } else {
                val g = group[idx]
                val next = g + 1
                val edge = edges[next]
                val cue = edge?.cueMs
                val introducesNext = next < count && edge != null && if (cue != null) {
                    fb.startMs >= cue - 1_000 || (cue in fb.startMs..fb.endMs && cue - fb.startMs <= (fb.endMs - fb.startMs) / 2)
                } else {
                    // after a long break, the talk that runs into the next try sets it up
                    fb.endMs >= takes[firstTakeOf(group, next)].startMs - INTRO_MS
                }
                if (introducesNext) next else g
            }
        }

        val exercises = (0 until count).map { g ->
            val ts = takes.filterIndexed { i, _ -> group[i] == g }
            val fbs = feedback.filterIndexed { i, _ -> fbGroup[i] == g }
            Exercise(
                id = ids[g],
                startMs = minOf(ts.first().startMs, fbs.minOfOrNull { it.startMs } ?: Long.MAX_VALUE),
                endMs = maxOf(ts.last().endMs, fbs.maxOfOrNull { it.endMs } ?: Long.MIN_VALUE),
                kind = if (ts.any { it.endMs - it.startMs >= cfg.songTakeMs }) Exercise.SONG else Exercise.WARMUP,
                takeIds = ts.map { it.id },
                reason = if (g == 0) "first singing" else edges[g]?.reason.orEmpty(),
            )
        }
        return Result(
            exercises,
            takes.mapIndexed { i, t -> t.copy(exerciseId = ids[group[i]]) },
            feedback.mapIndexed { i, fb -> fb.copy(exerciseId = fbGroup[i]?.let(ids::get)) },
        )
    }

    private fun firstTakeOf(group: IntArray, g: Int) = group.indexOfFirst { it == g }

    private fun isSong(t: Take) = t.endMs - t.startMs >= cfg.songTakeMs

    private fun edgeBetween(prev: Take, cur: Take, teacher: List<TranscriptLine>): Edge? {
        val gap = cur.startMs - prev.endMs
        if (gap >= cfg.exerciseBreakMs) return Edge("no singing for ${gap / 1000} s", null)
        val said = teacher.filter { it.startMs >= prev.endMs - 500 && it.startMs < cur.startMs }
        for (line in said) {
            val text = line.text.lowercase()
            val m = MOVE_ON.find(text) ?: continue
            if (SAME_AGAIN.containsMatchIn(text)) continue
            return Edge("she said “${quote(line.text, m.range.first)}”", line.startMs)
        }
        return null
    }

    private fun quote(text: String, at: Int): String {
        val from = maxOf(0, at - 10)
        val s = text.substring(from).trim()
        return (if (from > 0) "…" else "") + if (s.length > 60) s.take(60).trimEnd() + "…" else s
    }

    companion object {
        /** Her demo this close before the lesson's first take sets up the first exercise. */
        const val INTRO_MS = 5_000L

        /** What she says when the lesson moves on to something new. */
        val MOVE_ON = Regex(
            "\\blet'?s\\s+(?:\\w+\\s+){0,2}?(?:sing|start|try|do|go|learn|move on|begin|work on)\\b" +
                "|\\bnext\\b" +
                "|\\bnow\\s+(?:i think\\s+)?(?:we|let'?s)\\b" +
                "|\\b(?:the|a|this|our|your|that)\\s+song\\b",
        )

        /** ...unless she is moving the same exercise along. */
        val SAME_AGAIN = Regex("\\b(?:again|one more|once more|lower|higher|same|up|down)\\b")
    }
}
