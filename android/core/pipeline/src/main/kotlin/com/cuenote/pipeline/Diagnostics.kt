package com.cuenote.pipeline

import com.cuenote.pipeline.audio.LessonFolder
import com.cuenote.pipeline.golden.GoldenLabels
import java.io.File
import kotlin.math.abs

/**
 * For tuning: given a processed lesson with labels.json that includes
 * segments, prints what the measurements look like under each true label.
 */
object Diagnostics {
    fun print(lesson: LessonFolder) {
        val f = Features.read(File(lesson.pipelineDir, "features.bin"))
        val labels = GoldenLabels.readOrNull(lesson.labelsFile) ?: error("no labels.json")
        val cfg = Pipeline.loadConfig(lesson)
        val seg = Segmenter(cfg)
        val notes = Array(2) { seg.pitchedRuns(f, it) }
        val truth = Array(f.frames) { Label.SILENCE }
        for (s in labels.segments) for (k in msToFrame(s.startMs).coerceAtLeast(0) until msToFrame(s.endMs).coerceAtMost(f.frames)) truth[k] = s.label
        println("label           frames  sDb   tDb  sVad tVad  sSung sInst tSung tInst")
        for (l in Label.entries) {
            val idx = truth.indices.filter { truth[it] == l }
            if (idx.isEmpty()) continue
            fun avg(a: FloatArray) = idx.map { a[it].toDouble() }.average()
            fun frac(ch: Int, k: Segmenter.NoteKind) = idx.count { notes[ch][it] == k }.toDouble() / idx.size
            println("%-15s %6d %5.1f %5.1f %4.2f %4.2f  %4.2f  %4.2f  %4.2f  %4.2f".format(
                l.short, idx.size, avg(f.db[0]), avg(f.db[1]), avg(f.vad[0]), avg(f.vad[1]),
                frac(0, Segmenter.NoteKind.SUNG), frac(0, Segmenter.NoteKind.INSTRUMENT),
                frac(1, Segmenter.NoteKind.SUNG), frac(1, Segmenter.NoteKind.INSTRUMENT)))
        }
        // wobble of each held run, by the true label at its middle
        println("\nheld-note wobble (cents per frame), by true label: count p10 p50 p90")
        for (ch in 0..1) {
            val byLabel = mutableMapOf<Label, MutableList<Double>>()
            var i = 0
            val hz = f.pitchHz[ch]
            while (i < f.frames) {
                if (notes[ch][i] == Segmenter.NoteKind.NONE) { i++; continue }
                var j = i + 1
                while (j < f.frames && notes[ch][j] == notes[ch][i] && abs(Segmenter.cents(hz[j], hz[j - 1])) < cfg.noteToleranceSemitones * 100) j++
                val w = (i + 1 until j).map { abs(Segmenter.cents(hz[it], hz[it - 1])) }.average()
                byLabel.getOrPut(truth[(i + j) / 2]) { mutableListOf() } += w
                i = j
            }
            for ((l, ws) in byLabel) {
                val s = ws.sorted()
                println("  ${if (ch == 0) "student" else "teacher"} ch, %-14s %4d  %5.1f %5.1f %5.1f".format(
                    l.short, s.size, s[s.size / 10], s[s.size / 2], s[s.size * 9 / 10]))
            }
        }
    }
}
