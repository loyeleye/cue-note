package com.cuenote.pipeline

import kotlin.math.abs
import kotlin.math.ln

/**
 * Gives every 32 ms frame one of the five labels (or silence), then tidies
 * the frames into segments. In plain terms:
 *
 * 1. **Who is making sound.** Each person's mic hears them far louder than
 *    the other mic does, so the louder channel says whose voice it is. The
 *    voice detector (Silero) says whether it is a voice at all, which keeps
 *    the piano, loud on the teacher's mic, from counting as her talking.
 * 2. **Singing or speaking.** Singing holds notes: the pitch stays put for a
 *    quarter of a second or more. Speech slides around and stops and starts.
 * 3. **Piano only.** Sound on the teacher's channel that is not a voice, or a
 *    pitch steadier than any voice can hold.
 * 4. **Tidy.** Smooth out flickers, drop slivers, and join pieces of the same
 *    kind separated by a breath.
 */
class Segmenter(private val cfg: PipelineConfig = PipelineConfig()) {

    /** Per-frame decision, before tidying. Exposed for the debug timeline and tests. */
    fun frameLabels(f: Features): Array<Label> {
        val n = f.frames
        val active = Array(2) { ch -> BooleanArray(n) { f.db[ch][it] > cfg.activeFloorDb } }
        val notes = Array(2) { ch -> pitchedRuns(f, ch) }
        // A voice: the voice detector hears one, or a held note wobbles the way
        // only a voice does (Silero is trained on speech and often misses
        // sustained singing). A dead-steady note is the piano.
        val voice = Array(2) { ch ->
            BooleanArray(n) { i ->
                active[ch][i] && notes[ch][i] != NoteKind.INSTRUMENT &&
                    (f.vad[ch][i] >= cfg.vadThreshold || notes[ch][i] == NoteKind.SUNG)
            }
        }
        // whose voice: compare the two mics
        val student = BooleanArray(n) { i -> voice[0][i] && f.db[0][i] - f.db[1][i] >= cfg.studentDominanceDb }
        val teacher = BooleanArray(n) { i ->
            voice[1][i] && f.db[1][i] - f.db[0][i] >= cfg.teacherDominanceDb && !student[i]
        }
        // Nobody talks over the piano: a voice with piano notes around it is singing
        // along, even a song with words, which holds too few notes to count on its own.
        // Piano notes count only while the teacher is not talking, so a steady
        // syllable of hers is not taken for the piano.
        val accompanied = near(BooleanArray(n) { i ->
            (notes[0][i] == NoteKind.INSTRUMENT || notes[1][i] == NoteKind.INSTRUMENT) && f.vad[1][i] < cfg.vadThreshold
        }, cfg.accompanimentWindowFrames, cfg.minNoteFrames)
        val studentSings = singing(f, 0, notes[0], student).also { s -> for (i in 0 until n) if (student[i] && accompanied[i]) s[i] = true }
        val teacherSings = singing(f, 1, notes[1], teacher)
        return Array(n) { i ->
            when {
                student[i] && studentSings[i] -> Label.STUDENT_SING
                teacher[i] && teacherSings[i] -> Label.TEACHER_SING
                teacher[i] -> Label.TEACHER_SPEAK
                student[i] -> Label.STUDENT_SPEAK
                active[1][i] || active[0][i] -> Label.PIANO
                else -> Label.SILENCE
            }
        }
    }

    fun segment(f: Features): List<Segment> = tidy(frameLabels(f))

    /** Smooth, drop slivers, bridge breaths; returns segments without silence. */
    fun tidy(raw: Array<Label>): List<Segment> {
        val smooth = modeFilter(raw, cfg.smoothFrames)
        var runs = runs(smooth)
        // drop slivers: absorb short runs into the longer neighbour
        val minFrames = msToFrame(cfg.minSegmentMs).coerceAtLeast(1)
        var changed = true
        while (changed) {
            changed = false
            val shortest = runs.withIndex().filter { it.value.len < minFrames }.minByOrNull { it.value.len } ?: break
            val i = shortest.index
            val prev = runs.getOrNull(i - 1)
            val next = runs.getOrNull(i + 1)
            val into = when {
                prev == null && next == null -> null
                prev == null -> next
                next == null -> prev
                else -> if (prev.len >= next.len) prev else next
            } ?: break
            into.len += runs[i].len
            if (into === next) next.start = runs[i].start
            runs = runs.filterIndexed { k, _ -> k != i }.toMutableList().let(::merge)
            changed = true
        }
        // bridge breaths: X, short silence, X becomes one X
        val bridge = msToFrame(cfg.bridgeMs)
        val out = mutableListOf<Run>()
        for (r in runs) {
            val last = out.lastOrNull()
            val beforeLast = out.getOrNull(out.size - 2)
            if (last != null && beforeLast != null && last.label == Label.SILENCE && last.len <= bridge &&
                beforeLast.label == r.label
            ) {
                out.removeAt(out.size - 1)
                beforeLast.len += last.len + r.len
            } else out += Run(r.label, r.start, r.len)
        }
        return out.filter { it.label != Label.SILENCE }
            .map { Segment(it.label, frameToMs(it.start), frameToMs(it.start + it.len)) }
    }

    // ---------------------------------------------------------------------

    enum class NoteKind { NONE, SUNG, INSTRUMENT }

    /**
     * Finds held notes: runs of confident pitch that stay within the note
     * tolerance for at least [PipelineConfig.minNoteFrames] frames. Each run is
     * then called sung (the pitch wobbles, from vibrato or the small unsteadiness
     * every voice has) or instrument (dead steady, like a piano string).
     */
    fun pitchedRuns(f: Features, ch: Int): Array<NoteKind> {
        val n = f.frames
        val out = Array(n) { NoteKind.NONE }
        val hz = f.pitchHz[ch]; val conf = f.pitchConf[ch]; val db = f.db[ch]
        var i = 0
        while (i < n) {
            if (conf[i] < cfg.voicedConfidence || hz[i] <= 0f || db[i] <= cfg.activeFloorDb) { i++; continue }
            var j = i + 1
            var anchor = hz[i]
            var wobble = 0.0
            while (j < n && conf[j] >= cfg.voicedConfidence && hz[j] > 0f && db[j] > cfg.activeFloorDb &&
                abs(cents(hz[j], anchor)) <= cfg.noteToleranceSemitones * 100
            ) {
                wobble += abs(cents(hz[j], hz[j - 1]))
                // the anchor follows slowly, so vibrato stays inside one note but a melody step does not
                anchor = anchor * 0.8f + hz[j] * 0.2f
                j++
            }
            val len = j - i
            if (len >= cfg.minNoteFrames) {
                val w = wobble / (len - 1)
                // a struck string fades the moment it is hit; a held voice does not
                val fading = decaySlope(db, i, j) < cfg.instrumentDecayDbPerSec
                val instrument = w < cfg.instrumentSteadinessCents || (fading && w < cfg.instrumentFadingWobbleCents)
                val kind = if (instrument) NoteKind.INSTRUMENT else NoteKind.SUNG
                for (k in i until j) out[k] = kind
            }
            i = j
        }
        return out
    }

    /** Level change over a run in dB per second, by least squares, skipping the attack. */
    private fun decaySlope(db: FloatArray, from: Int, to: Int): Double {
        val a = minOf(from + 2, to - 2)
        val n = to - a
        if (n < 4) return 0.0
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (k in a until to) {
            val x = (k - a) * FRAME_MS / 1000.0; val y = db[k].toDouble()
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        val den = n * sxx - sx * sx
        return if (den == 0.0) 0.0 else (n * sxy - sx * sy) / den
    }

    /**
     * For each frame where [mask] says this person is making a voice, decides
     * whether the surrounding window is singing. Two signs, either is enough:
     * much of the voiced time is spent on held notes, or the voice runs on
     * without a break for longer than speech does (legato scales and runs,
     * where the notes are too quick to count as held).
     */
    private fun singing(f: Features, ch: Int, notes: Array<NoteKind>, mask: BooleanArray): BooleanArray {
        val n = notes.size
        val legato = legatoRuns(f, ch, mask)
        val activeSum = IntArray(n + 1)
        val heldSum = IntArray(n + 1)
        val legatoSum = IntArray(n + 1)
        for (i in 0 until n) {
            activeSum[i + 1] = activeSum[i] + if (mask[i]) 1 else 0
            heldSum[i + 1] = heldSum[i] + if (mask[i] && notes[i] == NoteKind.SUNG) 1 else 0
            legatoSum[i + 1] = legatoSum[i] + if (mask[i] && legato[i]) 1 else 0
        }
        val w = cfg.styleWindowFrames
        return BooleanArray(n) { i ->
            if (!mask[i]) return@BooleanArray false
            val a = maxOf(0, i - w); val b = minOf(n, i + w + 1)
            val act = (activeSum[b] - activeSum[a]).toDouble()
            act > 0 && ((heldSum[b] - heldSum[a]) / act >= cfg.singingSustainRatio ||
                (legatoSum[b] - legatoSum[a]) / act >= cfg.singingLegatoRatio)
        }
    }

    /** Frames inside unbroken voicing longer than [PipelineConfig.legatoFrames], with no pitch leaps. */
    private fun legatoRuns(f: Features, ch: Int, mask: BooleanArray): BooleanArray {
        val n = f.frames
        val out = BooleanArray(n)
        val hz = f.pitchHz[ch]; val conf = f.pitchConf[ch]
        var i = 0
        while (i < n) {
            if (!mask[i] || conf[i] < cfg.voicedConfidence) { i++; continue }
            var j = i + 1
            while (j < n && mask[j] && conf[j] >= cfg.voicedConfidence && abs(cents(hz[j], hz[j - 1])) < 250) j++
            if (j - i >= cfg.legatoFrames) for (k in i until j) out[k] = true
            i = j
        }
        return out
    }

    /** True where [mask] is true on at least [atLeast] frames within [width] frames either side. */
    private fun near(mask: BooleanArray, width: Int, atLeast: Int = 1): BooleanArray {
        val n = mask.size
        val sum = IntArray(n + 1)
        for (i in 0 until n) sum[i + 1] = sum[i] + if (mask[i]) 1 else 0
        return BooleanArray(n) { i -> sum[minOf(n, i + width + 1)] - sum[maxOf(0, i - width)] >= atLeast }
    }

    private class Run(var label: Label, var start: Int, var len: Int)

    private fun runs(labels: Array<Label>): MutableList<Run> {
        val out = mutableListOf<Run>()
        for ((i, l) in labels.withIndex()) {
            val last = out.lastOrNull()
            if (last != null && last.label == l) last.len++ else out += Run(l, i, 1)
        }
        return out
    }

    private fun merge(rs: MutableList<Run>): MutableList<Run> {
        val out = mutableListOf<Run>()
        for (r in rs) {
            val last = out.lastOrNull()
            if (last != null && last.label == r.label) last.len += r.len else out += r
        }
        return out
    }

    private fun modeFilter(raw: Array<Label>, width: Int): Array<Label> {
        if (width <= 1) return raw
        val half = width / 2
        val counts = IntArray(Label.entries.size)
        return Array(raw.size) { i ->
            counts.fill(0)
            for (k in maxOf(0, i - half)..minOf(raw.size - 1, i + half)) counts[raw[k].ordinal]++
            val best = counts.indices.maxBy { counts[it] }
            // keep the original on ties so edges stay put
            if (counts[best] > counts[raw[i].ordinal]) Label.entries[best] else raw[i]
        }
    }

    companion object {
        fun cents(a: Float, b: Float): Double =
            if (a <= 0f || b <= 0f) 0.0 else 1200.0 * ln(a.toDouble() / b) / ln(2.0)
    }
}
