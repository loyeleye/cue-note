package com.cuenote.pipeline

import com.cuenote.pipeline.audio.LessonFolder
import com.cuenote.pipeline.golden.GoldenLabels
import com.cuenote.pipeline.golden.Score
import com.cuenote.pipeline.golden.Scorer
import com.cuenote.pipeline.synthetic.SyntheticLesson
import java.io.File
import kotlin.system.exitProcess

/**
 * Laptop runner, for exported lessons and the golden set:
 *
 *   process <lesson folder> [--again] [--whisper ggml-tiny.en.bin]
 *       Runs the pipeline and writes <lesson>/pipeline/timeline.html.
 *   golden <folder of lessons>
 *       Runs every lesson that has a labels.json and prints the scores.
 *   synth <folder> [seed] [rounds]
 *       Writes a synthetic lesson with its right answers.
 */
fun main(args: Array<String>) {
    val cmd = args.firstOrNull()
    val rest = args.drop(1)
    when (cmd) {
        "process" -> {
            val dir = File(expand(rest.firstOrNull() ?: usage()))
            val again = "--again" in rest
            val whisper = rest.indexOf("--whisper").takeIf { it >= 0 }?.let { rest.getOrNull(it + 1) }
            processOne(LessonFolder(dir), again, whisper)?.let(::printScore)
        }
        "golden" -> {
            val root = File(expand(rest.firstOrNull() ?: usage()))
            val lessons = root.listFiles()?.filter { File(it, "labels.json").isFile }?.sortedBy { it.name }.orEmpty()
            if (lessons.isEmpty()) { println("No lessons with labels.json under $root"); exitProcess(1) }
            val scores = lessons.mapNotNull { processOne(LessonFolder(it), again = "--again" in rest, whisper = null) }
            scores.forEach(::printScore)
            println(GoldenReport.summary(scores))
        }
        "synth" -> {
            val dir = File(expand(rest.firstOrNull() ?: usage()))
            val seed = rest.getOrNull(1)?.toInt() ?: 1
            val rounds = rest.getOrNull(2)?.toInt() ?: 6
            dir.mkdirs()
            val labels = SyntheticLesson(seed, rounds).write(dir)
            println("Wrote ${dir.absolutePath}: ${labels.takes.size} takes, ${labels.feedback.size} feedback blocks")
        }
        "diag" -> Diagnostics.print(LessonFolder(File(expand(rest.firstOrNull() ?: usage()))))
        else -> usage()
    }
}

private fun processOne(lesson: LessonFolder, again: Boolean, whisper: String?): Score? {
    require(lesson.chunks.isNotEmpty()) { "no audio/chunk_*.wav in ${lesson.dir}" }
    Models.fromEnvironment(whisper).use { m ->
        val p = Pipeline(lesson, m.vad, m.transcriber)
        if (again) p.reset()
        val t0 = System.currentTimeMillis()
        var last = ""
        val out = p.run(onStage = { s, prog ->
            val line = "${lesson.id}: ${s.key} ${(prog * 100).toInt()}%"
            if (line != last) { print("\r$line   "); last = line }
        })
        println("\r${lesson.id}: done in ${(System.currentTimeMillis() - t0) / 1000} s, vad=${m.vad.name}, " +
            "speech=${m.transcriber?.name ?: "none"} · ${out.takes.size} takes, ${out.feedback.size} feedback")
        println("  timeline: ${p.timelineFile.absolutePath}")
        return GoldenLabels.readOrNull(lesson.labelsFile)?.let { Scorer.score(out, it) }
    }
}

private fun printScore(s: Score) {
    println("  ${s.lessonId}: links ${pct(s.linkAccuracy)} (${s.linkCorrect}/${s.linkTotal}), " +
        "takes found ${pct(s.takeRecall)}, real ${pct(s.takePrecision)}, edges ±${s.takeBoundaryErrorMs.toInt()} ms" +
        (s.labelAccuracy?.let { ", frames ${pct(it)}" } ?: ""))
    s.mistakes.take(12).forEach { println("    - $it") }
}

private fun pct(v: Double) = "${(v * 100).toInt()}%"
private fun expand(p: String) = if (p.startsWith("~/")) System.getProperty("user.home") + p.substring(1) else p
private fun usage(): Nothing {
    println("usage: process <lesson> [--again] [--whisper <model file>] | golden <folder> | synth <folder> [seed] [rounds]")
    exitProcess(2)
}

object GoldenReport {
    fun summary(scores: List<Score>): String {
        if (scores.isEmpty()) return "no scores"
        val links = scores.sumOf { it.linkCorrect }.toDouble() / scores.sumOf { it.linkTotal }.coerceAtLeast(1)
        return "Overall: links right ${pct(links)} across ${scores.size} lessons; " +
            "takes found ${pct(scores.map { it.takeRecall }.average())}, real ${pct(scores.map { it.takePrecision }.average())}"
    }
}
