package com.cuenote.pipeline

/**
 * Groups segments into takes (the student singing) and feedback blocks (the
 * teacher talking and demonstrating between takes).
 *
 * - A take is student singing, kept together across breaths, short piano
 *   interludes and silences, and ended by anyone talking or the teacher singing.
 * - A feedback block is the teacher talking and singing, kept together across
 *   short pauses and across a student question, so a back-and-forth about one
 *   take stays one block.
 * - The student talking is never a take and is never pitch-scored.
 */
class Structure(private val cfg: PipelineConfig = PipelineConfig()) {

    data class Result(val takes: List<Take>, val feedback: List<FeedbackBlock>)

    fun build(segments: List<Segment>, features: Features? = null, lessonId: String = "lesson"): Result {
        val takes = takes(segments).mapIndexed { i, (start, end) ->
            val withPiano = segments.any { it.label == Label.PIANO && it.startMs < end && it.endMs > start } ||
                features?.let { pianoUnder(it, start, end) } == true
            Take(
                id = "$lessonId-take-%03d".format(i + 1),
                startMs = start, endMs = end,
                pitchHz = features?.let { slice(it.pitchHz[0], start, end) }.orEmpty(),
                pitchConfidence = features?.let { slice(it.pitchConf[0], start, end) }.orEmpty(),
                withPiano = withPiano,
            )
        }
        val feedback = feedback(segments).mapIndexed { i, (start, end) ->
            val demos = segments.filter {
                it.label == Label.TEACHER_SING && it.startMs >= start && it.endMs <= end && it.durationMs >= cfg.minDemoMs
            }.map { Demo(it.startMs, it.endMs) }
            FeedbackBlock(id = "$lessonId-fb-%03d".format(i + 1), startMs = start, endMs = end, demos = demos)
        }
        return Result(takes, feedback)
    }

    private fun takes(segments: List<Segment>): List<Pair<Long, Long>> {
        val out = mutableListOf<LongArray>()
        var lastBreaker = Long.MIN_VALUE   // end of the last speech or teacher singing
        for (s in segments) {
            when (s.label) {
                Label.STUDENT_SING -> {
                    val cur = out.lastOrNull()
                    if (cur != null && s.startMs - cur[1] <= cfg.takeJoinMs && lastBreaker <= cur[1]) {
                        cur[1] = s.endMs
                    } else out += longArrayOf(s.startMs, s.endMs)
                }
                Label.PIANO, Label.SILENCE -> Unit
                else -> lastBreaker = maxOf(lastBreaker, s.endMs)
            }
        }
        return out.filter { it[1] - it[0] >= cfg.minTakeMs }.map { it[0] to it[1] }
    }

    private fun feedback(segments: List<Segment>): List<Pair<Long, Long>> {
        val out = mutableListOf<LongArray>()
        var studentSangSince = false
        for (s in segments) {
            when {
                s.label.isTeacher -> {
                    val cur = out.lastOrNull()
                    if (cur != null && !studentSangSince && s.startMs - cur[1] <= cfg.feedbackJoinMs) cur[1] = s.endMs
                    else out += longArrayOf(s.startMs, s.endMs)
                    studentSangSince = false
                }
                s.label == Label.STUDENT_SING && s.durationMs >= cfg.minTakeMs -> studentSangSince = true
                else -> Unit
            }
        }
        return out.filter { it[1] - it[0] >= cfg.minFeedbackMs }.map { it[0] to it[1] }
    }

    private fun slice(arr: FloatArray, startMs: Long, endMs: Long): List<Float> {
        val a = msToFrame(startMs).coerceIn(0, arr.size)
        val b = msToFrame(endMs).coerceIn(a, arr.size)
        return arr.copyOfRange(a, b).map { (it * 10).toInt() / 10f }
    }

    /** Piano under a take shows as steady loud sound on the teacher channel while she is not talking. */
    private fun pianoUnder(f: Features, startMs: Long, endMs: Long): Boolean {
        val a = msToFrame(startMs); val b = minOf(msToFrame(endMs), f.frames)
        if (b <= a) return false
        var loud = 0
        for (i in a until b) if (f.db[1][i] > cfg.activeFloorDb + 10 && f.vad[1][i] < cfg.vadThreshold) loud++
        return loud > (b - a) / 3
    }
}
