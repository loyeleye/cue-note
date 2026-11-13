package com.cuenote.pipeline.golden

import com.cuenote.pipeline.Label
import com.cuenote.pipeline.Pipeline
import com.cuenote.pipeline.Segment
import com.cuenote.pipeline.frameToMs
import com.cuenote.pipeline.msToFrame
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The right answers for one lesson, written by a person in the debug timeline
 * (or by the generator for synthetic lessons). Saved as <lesson>/labels.json.
 */
@Serializable
data class GoldenLabels(
    val lessonId: String = "",
    val takes: List<Span> = emptyList(),
    /** Each feedback block with the index (into [takes]) of the take it is about, or null. */
    val feedback: List<FeedbackLabel> = emptyList(),
    val demos: List<Span> = emptyList(),
    /** Optional frame-level truth; synthetic lessons have it, hand labels usually do not. */
    val segments: List<Segment> = emptyList(),
    /** Warmups and song sections, each from her setting it up to moving on. */
    val exercises: List<ExerciseLabel> = emptyList(),
    val notes: String = "",
) {
    @Serializable data class ExerciseLabel(val startMs: Long, val endMs: Long, val kind: String? = null)
    /** A span; for a demo, [take] is the take whose phrase she was modelling, when known. */
    @Serializable data class Span(val startMs: Long, val endMs: Long, val take: Int? = null)
    @Serializable data class FeedbackLabel(val startMs: Long, val endMs: Long, val take: Int? = null)

    companion object {
        fun readOrNull(file: File): GoldenLabels? = file.takeIf { it.isFile }
            ?.let { Pipeline.json.decodeFromString(serializer(), it.readText()) }

        fun write(file: File, labels: GoldenLabels) =
            file.writeText(Pipeline.json.encodeToString(serializer(), labels))
    }
}

/** How well one run of the pipeline matched the right answers. */
@Serializable
data class Score(
    val lessonId: String,
    val takePrecision: Double,
    val takeRecall: Double,
    val takeF1: Double,
    /** Mean distance between true and found take edges, for matched takes. */
    val takeBoundaryErrorMs: Double,
    /** The M2 gate number: of the true feedback blocks, how many ended up linked to the right take. */
    val linkAccuracy: Double,
    val linkCorrect: Int,
    val linkTotal: Int,
    val demoPrecision: Double,
    val demoRecall: Double,
    /** Of the found demos whose true phrase is known, how many point at the right take. */
    val demoLinkAccuracy: Double? = null,
    /** Fraction of 32 ms frames given the right label, when the labels include segments. */
    val labelAccuracy: Double? = null,
    /** "truth -> found" frame counts, for the confusion table. */
    val confusion: Map<String, Int> = emptyMap(),
    val mistakes: List<String> = emptyList(),
    /** When exercises are labelled: of the true exercises, how many were found (half or more overlap). */
    val exerciseRecall: Double? = null,
    val exercisePrecision: Double? = null,
    /**
     * Of each pair of neighbouring true takes (both found), how often the
     * pipeline got "same exercise or not" right. The number that says whether
     * tries are grouped the way the lesson went.
     */
    val groupingAccuracy: Double? = null,
    /** Of the true feedback blocks, how many landed in the right exercise. */
    val exerciseLinkAccuracy: Double? = null,
)

object Scorer {
    private const val TAKE_IOU = 0.5

    fun score(out: Pipeline.Output, truth: GoldenLabels): Score {
        val mistakes = mutableListOf<String>()
        // --- takes: one-to-one matching by best overlap
        val takeMatch = match(truth.takes.map { it.startMs to it.endMs }, out.takes.map { it.startMs to it.endMs }, TAKE_IOU)
        val tp = takeMatch.size
        val precision = ratio(tp, out.takes.size)
        val recall = ratio(tp, truth.takes.size)
        val f1 = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
        val edgeErr = takeMatch.entries.flatMap { (t, p) ->
            listOf(abs(truth.takes[t].startMs - out.takes[p].startMs), abs(truth.takes[t].endMs - out.takes[p].endMs))
        }.average().takeIf { !it.isNaN() } ?: 0.0
        truth.takes.indices.filter { it !in takeMatch }.forEach {
            mistakes += "missed take ${it + 1} at ${clock(truth.takes[it].startMs)}"
        }
        val foundToTrue = takeMatch.entries.associate { (t, p) -> p to t }
        out.takes.indices.filter { it !in foundToTrue }.forEach {
            mistakes += "extra take at ${clock(out.takes[it].startMs)}–${clock(out.takes[it].endMs)}"
        }

        // --- links: for each true feedback block, the found block that overlaps it most
        val takeIndexById = out.takes.withIndex().associate { it.value.id to it.index }
        var correct = 0
        for ((i, fb) in truth.feedback.withIndex()) {
            val found = out.feedback.maxByOrNull { overlap(fb.startMs, fb.endMs, it.startMs, it.endMs) }
                ?.takeIf { overlap(fb.startMs, fb.endMs, it.startMs, it.endMs) > 0 }
            if (found == null) {
                mistakes += "feedback ${i + 1} at ${clock(fb.startMs)} not found"
                continue
            }
            val foundTrueTake = found.takeId?.let(takeIndexById::get)?.let(foundToTrue::get)
            val ok = if (fb.take == null) found.takeId == null else foundTrueTake == fb.take
            if (ok) correct++ else mistakes +=
                "feedback ${i + 1} at ${clock(fb.startMs)} linked to ${foundTrueTake?.let { "take ${it + 1}" } ?: found.takeId?.let { "an extra take" } ?: "nothing"}, should be ${fb.take?.let { "take ${it + 1}" } ?: "nothing"}"
        }
        val linkAcc = ratio(correct, truth.feedback.size)

        // --- demos
        val demoList = out.feedback.flatMap { it.demos }
        val foundDemos = demoList.map { it.startMs to it.endMs }
        val demoMatch = match(truth.demos.map { it.startMs to it.endMs }, foundDemos, 0.3)
        val judged = demoMatch.filter { (t, _) -> truth.demos[t].take != null }
        val demoLinkAcc = if (judged.isEmpty()) null else judged.count { (t, p) ->
            demoList[p].takeId?.let(takeIndexById::get)?.let(foundToTrue::get) == truth.demos[t].take
        }.toDouble() / judged.size

        // --- frame labels
        var labelAcc: Double? = null
        val confusion = mutableMapOf<String, Int>()
        if (truth.segments.isNotEmpty()) {
            val frames = msToFrame(out.durationMs)
            val t = frameArray(truth.segments, frames)
            val p = frameArray(out.segments, frames)
            var same = 0
            for (k in 0 until frames) {
                if (t[k] == p[k]) same++
                val key = "${t[k].short} -> ${p[k].short}"
                confusion[key] = (confusion[key] ?: 0) + 1
            }
            labelAcc = ratio(same, frames)
        }
        // --- exercises
        var exRecall: Double? = null; var exPrecision: Double? = null
        var grouping: Double? = null; var exLink: Double? = null
        if (truth.exercises.isNotEmpty()) {
            val ex = truth.exercises.sortedBy { it.startMs }
            val exMatch = match(ex.map { it.startMs to it.endMs }, out.exercises.map { it.startMs to it.endMs }, 0.5)
            exRecall = ratio(exMatch.size, ex.size)
            exPrecision = ratio(exMatch.size, out.exercises.size)
            ex.indices.filter { it !in exMatch }.forEach { mistakes += "exercise ${it + 1} at ${clock(ex[it].startMs)} not found as one piece" }
            fun trueEx(a: Long, b: Long): Int? = ex.indexOfFirst { (a + b) / 2 in it.startMs..it.endMs }.takeIf { it >= 0 }
            // neighbouring true takes, both found: same exercise or not?
            val pairs = (1 until truth.takes.size).filter { it in takeMatch && it - 1 in takeMatch }
            if (pairs.isNotEmpty()) {
                var right = 0
                for (i in pairs) {
                    val a = truth.takes[i - 1]; val b = truth.takes[i]
                    val same = trueEx(a.startMs, a.endMs).let { it != null && it == trueEx(b.startMs, b.endMs) }
                    val found = out.takes[takeMatch.getValue(i - 1)].exerciseId.let { it != null && it == out.takes[takeMatch.getValue(i)].exerciseId }
                    if (same == found) right++
                    else mistakes += if (same) "takes ${i} and ${i + 1} (${clock(b.startMs)}) are one exercise but were split"
                    else "takes ${i} and ${i + 1} (${clock(b.startMs)}) are separate exercises but were joined"
                }
                grouping = right.toDouble() / pairs.size
            }
            // feedback: the exercise its found block was put in, against the true one
            val foundExToTrue = exMatch.entries.associate { (t, p) -> out.exercises[p].id to t }
            val judged = truth.feedback.mapNotNull { fb ->
                val found = out.feedback.maxByOrNull { overlap(fb.startMs, fb.endMs, it.startMs, it.endMs) }
                    ?.takeIf { overlap(fb.startMs, fb.endMs, it.startMs, it.endMs) > 0 } ?: return@mapNotNull null
                trueEx(fb.startMs, fb.endMs) to found.exerciseId?.let(foundExToTrue::get)
            }
            if (judged.isNotEmpty()) exLink = judged.count { (t, f) -> t == f }.toDouble() / judged.size
        }
        return Score(
            exerciseRecall = exRecall, exercisePrecision = exPrecision,
            groupingAccuracy = grouping, exerciseLinkAccuracy = exLink,
            lessonId = out.lessonId,
            takePrecision = precision, takeRecall = recall, takeF1 = f1, takeBoundaryErrorMs = edgeErr,
            linkAccuracy = linkAcc, linkCorrect = correct, linkTotal = truth.feedback.size,
            demoPrecision = ratio(demoMatch.size, foundDemos.size), demoRecall = ratio(demoMatch.size, truth.demos.size),
            demoLinkAccuracy = demoLinkAcc,
            labelAccuracy = labelAcc, confusion = confusion.toSortedMap(), mistakes = mistakes,
        )
    }

    /** Greedy one-to-one matching by intersection-over-union; returns truth index -> found index. */
    fun match(truth: List<Pair<Long, Long>>, found: List<Pair<Long, Long>>, minIou: Double): Map<Int, Int> {
        val pairs = truth.indices.flatMap { t -> found.indices.map { p -> Triple(t, p, iou(truth[t], found[p])) } }
            .filter { it.third >= minIou }.sortedByDescending { it.third }
        val usedT = HashSet<Int>(); val usedP = HashSet<Int>()
        val out = mutableMapOf<Int, Int>()
        for ((t, p, _) in pairs) if (t !in usedT && p !in usedP) { out[t] = p; usedT += t; usedP += p }
        return out
    }

    private fun iou(a: Pair<Long, Long>, b: Pair<Long, Long>): Double {
        val inter = overlap(a.first, a.second, b.first, b.second)
        val union = max(a.second, b.second) - min(a.first, b.first)
        return if (union <= 0) 0.0 else inter.toDouble() / union
    }

    private fun overlap(a0: Long, a1: Long, b0: Long, b1: Long) = max(0L, min(a1, b1) - max(a0, b0))
    private fun ratio(a: Int, b: Int) = if (b == 0) 1.0 else a.toDouble() / b

    private fun frameArray(segs: List<Segment>, frames: Int): Array<Label> {
        val arr = Array(frames) { Label.SILENCE }
        for (s in segs) for (k in msToFrame(s.startMs).coerceAtLeast(0) until msToFrame(s.endMs).coerceAtMost(frames)) arr[k] = s.label
        return arr
    }

    fun clock(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

    @Suppress("unused")
    private fun frameMs(f: Int) = frameToMs(f)
}
