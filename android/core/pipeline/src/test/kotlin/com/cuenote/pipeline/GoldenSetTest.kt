package com.cuenote.pipeline

import com.cuenote.ml.Transcriber
import com.cuenote.ml.TranscriptPiece
import com.cuenote.pipeline.audio.LessonFolder
import com.cuenote.pipeline.golden.GoldenLabels
import com.cuenote.pipeline.golden.Score
import com.cuenote.pipeline.golden.Scorer
import com.cuenote.pipeline.synthetic.SyntheticLesson
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Writes a readable report of every scored lesson to build/golden-report/. */
object Report {
    private val dir = File(System.getProperty("cuenote.reportDir") ?: "build/golden-report").apply { mkdirs() }

    fun write(name: String, title: String, scores: List<Score>, note: String) {
        val pct = { v: Double -> "${(v * 100).toInt()}%" }
        val text = buildString {
            appendLine("# $title"); appendLine(); appendLine(note); appendLine()
            appendLine("| Lesson | Links right | Takes found | Takes real | Edge error | Demos found | Demo to right take | Frames right |")
            appendLine("|---|---|---|---|---|---|---|---|")
            for (s in scores) appendLine("| ${s.lessonId} | ${pct(s.linkAccuracy)} (${s.linkCorrect}/${s.linkTotal}) | ${pct(s.takeRecall)} | " +
                "${pct(s.takePrecision)} | ${s.takeBoundaryErrorMs.toInt()} ms | ${pct(s.demoRecall)} | " +
                "${s.demoLinkAccuracy?.let(pct) ?: "–"} | ${s.labelAccuracy?.let(pct) ?: "–"} |")
            appendLine(); appendLine(GoldenReport.summary(scores)); appendLine()
            for (s in scores.filter { it.mistakes.isNotEmpty() }) {
                appendLine("**${s.lessonId}**"); s.mistakes.forEach { appendLine("- $it") }; appendLine()
            }
        }
        File(dir, "$name.md").writeText(text)
        println(text)
    }
}

/** Gives each utterance a placeholder line; whisper on made-up voices only produces noise. */
class PlaceholderTranscriber : Transcriber {
    override val name = "placeholder"
    override fun transcribe(samples: FloatArray, prompt: String?) =
        listOf(TranscriptPiece(0, samples.size / 16L, "(speech)"))
}

/**
 * Synthetic lessons: made-up voices and piano with known right answers. Runs
 * on every build. It proves the logic, not the ears: the real golden set below
 * is what the M2 gate is judged on.
 */
class SyntheticGoldenTest {
    private val root = File("build/synthetic")

    private fun lesson(seed: Int): LessonFolder {
        val dir = File(root, "synthetic-$seed")
        if (!File(dir, "labels.json").isFile) { dir.deleteRecursively(); dir.mkdirs(); SyntheticLesson(seed).write(dir) }
        return LessonFolder(dir)
    }

    @Test fun `synthetic lessons are split and linked correctly`() {
        val scores = mutableListOf<Score>()
        var vadName = ""
        for (seed in 1..3) {
            val lesson = lesson(seed)
            Models.fromEnvironment().use { m ->
                vadName = m.vad.name
                val p = Pipeline(lesson, m.vad, PlaceholderTranscriber())
                p.reset()
                val out = p.run()
                scores += Scorer.score(out, GoldenLabels.readOrNull(lesson.labelsFile)!!)
            }
        }
        Report.write("synthetic", "Synthetic golden set", scores, "Voice detector: $vadName.")
        val links = scores.sumOf { it.linkCorrect }.toDouble() / scores.sumOf { it.linkTotal }
        val takeF1 = scores.map { it.takeF1 }.average()
        // Silero is the real setup; the loudness-only stand-in is held to a lower bar.
        val (minLinks, minTakes) = if (vadName == "silero") 0.9 to 0.9 else 0.7 to 0.7
        assertTrue("link accuracy ${links} below $minLinks", links >= minLinks)
        assertTrue("take F1 $takeF1 below $minTakes", takeF1 >= minTakes)
    }

    @Test fun `a stopped run resumes where it left off and gives the same answer`() {
        val lesson = lesson(1)
        Models.fromEnvironment().use { m ->
            val p = Pipeline(lesson, m.vad, PlaceholderTranscriber())
            p.reset()
            val full = p.run()
            p.reset()
            var calls = 0
            val stopAfterFeatures = runCatching {
                p.run(onStage = { s, _ -> if (s == Pipeline.Stage.SEGMENT) calls++ }, isStopped = { calls > 0 })
            }
            // it either stopped in transcription or finished; both must resume to the same output
            if (stopAfterFeatures.isFailure) assertTrue(stopAfterFeatures.exceptionOrNull() is StoppedException)
            assertTrue(p.state().done(Pipeline.Stage.FEATURES))
            val resumed = p.run()
            assertEquals(full.copy(transcript = emptyList()), resumed.copy(transcript = emptyList()))
            assertEquals(full.transcript.size, resumed.transcript.size)
        }
    }
}

/** Pass/fail numbers agreed with the teacher, in <golden>/thresholds.json. */
@Serializable
data class Thresholds(val linkAccuracy: Double? = null, val takeRecall: Double? = null, val takePrecision: Double? = null)

/**
 * The real golden set: labelled lessons in the folder named by CUENOTE_GOLDEN
 * (each a lesson folder with labels.json). Skipped when it is not set. Until
 * thresholds.json exists it only reports; after, it fails below the bar.
 */
class RealGoldenTest {
    @Test fun `real lessons meet the agreed thresholds`() {
        val root = System.getProperty("cuenote.golden")?.let(::File)
        assumeTrue("set CUENOTE_GOLDEN to a folder of labelled lessons", root?.isDirectory == true)
        val lessons = root!!.listFiles()!!.filter { File(it, "labels.json").isFile }.sortedBy { it.name }
        assumeTrue("no labelled lessons in $root", lessons.isNotEmpty())
        val scores = Models.fromEnvironment().use { m ->
            lessons.map { dir ->
                val lesson = LessonFolder(dir)
                val out = Pipeline(lesson, m.vad, m.transcriber).run()
                Scorer.score(out, GoldenLabels.readOrNull(lesson.labelsFile)!!)
            }
        }
        val t = File(root, "thresholds.json").takeIf { it.isFile }
            ?.let { Pipeline.json.decodeFromString(Thresholds.serializer(), it.readText()) }
        Report.write("real", "Real golden set", scores,
            if (t == null) "No thresholds.json yet, so this run only reports." else "Thresholds: $t")
        if (t == null) return
        val links = scores.sumOf { it.linkCorrect }.toDouble() / scores.sumOf { it.linkTotal }.coerceAtLeast(1)
        t.linkAccuracy?.let { assertTrue("link accuracy $links below $it", links >= it) }
        t.takeRecall?.let { min -> assertTrue(scores.all { it.takeRecall >= min }) }
        t.takePrecision?.let { min -> assertTrue(scores.all { it.takePrecision >= min }) }
    }
}
