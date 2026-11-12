package com.cuenote.pipeline

import com.cuenote.ml.Transcriber
import com.cuenote.ml.TranscriptPiece
import com.cuenote.pipeline.audio.LessonFolder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Speech to text for everything said between takes. In plain terms:
 *
 * 1. **Where to listen.** Every stretch where someone talks (or the teacher
 *    sings a demo), with a little room either side, outside the takes. A
 *    song is never transcribed.
 * 2. **Both mics, whole stretches.** Each stretch goes to whisper as one
 *    piece of continuous audio, once from each mic. Continuous audio keeps
 *    the context whisper needs for short replies like "yes" or "that was
 *    flat?", and keeps its word times honest.
 * 3. **Whose word is it.** In a small room each mic also hears the other
 *    person, so a word often comes back from both mics. The pipeline learns
 *    each person's speaking pitch from the moments that are clearly theirs,
 *    then gives each word to the person whose pitch it matches (or, when the
 *    two pitches are close, to whoever was louder), and keeps one copy.
 * 4. **Lines.** Words of one person with no long pause between become a line.
 *
 * Resumable: each finished piece is appended to a partial file, so if the
 * phone stops the work, the next run carries on from the next piece.
 */
class Transcription(private val cfg: PipelineConfig = PipelineConfig()) {

    data class Window(val startMs: Long, val endMs: Long)

    /** A word whisper heard, on the lesson's clock, and the mic it came from. */
    data class Word(val channel: Int, val startMs: Long, val endMs: Long, val text: String)

    /**
     * Stretches to transcribe: talking and teacher singing, padded, joined when
     * close, with the takes cut out, then cut into pieces whisper can take at once.
     */
    fun windows(segments: List<Segment>, takes: List<Take>, durationMs: Long): List<Window> {
        val pad = cfg.transcriptPadMs
        val spans = segments
            .filter { it.label.isSpeech || it.label == Label.TEACHER_SING }
            .map { maxOf(0L, it.startMs - pad) to minOf(durationMs, it.endMs + pad) }
            .sortedBy { it.first }
        val merged = mutableListOf<LongArray>()
        for ((a, b) in spans) {
            val last = merged.lastOrNull()
            if (last != null && a - last[1] <= cfg.transcriptJoinMs) last[1] = maxOf(last[1], b)
            else merged += longArrayOf(a, b)
        }
        // cut out the takes
        var pieces: List<LongArray> = merged
        for (t in takes) {
            pieces = pieces.flatMap { p ->
                if (t.endMs <= p[0] || t.startMs >= p[1]) listOf(p)
                else listOfNotNull(
                    longArrayOf(p[0], t.startMs).takeIf { it[1] > it[0] },
                    longArrayOf(t.endMs, p[1]).takeIf { it[1] > it[0] },
                )
            }
        }
        // whisper's window is 30 s: split longer stretches evenly
        return pieces.filter { it[1] - it[0] >= MIN_WINDOW_MS }.flatMap { p ->
            val len = p[1] - p[0]
            val n = ((len + cfg.maxUtteranceMs - 1) / cfg.maxUtteranceMs).toInt()
            (0 until n).map { k -> Window(p[0] + len * k / n, p[0] + len * (k + 1) / n) }
        }
    }

    /** Each person's usual speaking pitch in this lesson, in Hz, learned from the moments that are clearly theirs. */
    data class Voices(val studentHz: Double?, val teacherHz: Double?) {
        /** True when the two speak far enough apart in pitch that pitch can tell them apart. */
        val distinct get() = studentHz != null && teacherHz != null &&
            kotlin.math.abs(Segmenter.cents(studentHz.toFloat(), teacherHz.toFloat())) >= MIN_VOICE_GAP_CENTS
    }

    fun voices(f: Features, takes: List<Take>): Voices {
        val inTake = BooleanArray(f.frames)
        for (t in takes) for (i in msToFrame(t.startMs).coerceIn(0, f.frames) until msToFrame(t.endMs).coerceIn(0, f.frames)) inTake[i] = true
        fun median(ch: Int, clear: (Double) -> Boolean): Double? {
            val hz = (0 until f.frames).filter { i ->
                !inTake[i] && clear((f.db[0][i] - f.db[1][i]).toDouble()) && f.vad[ch][i] >= cfg.vadThreshold &&
                    f.pitchConf[ch][i] >= cfg.voicedConfidence && f.pitchHz[ch][i] > 0f
            }.map { f.pitchHz[ch][it].toDouble() }.sorted()
            return if (hz.size < MIN_VOICE_FRAMES) null else hz[hz.size / 2]
        }
        return Voices(
            studentHz = median(0) { it >= cfg.studentDominanceDb + CLEAR_MARGIN_DB },
            teacherHz = median(1) { it <= -cfg.teacherDominanceDb - CLEAR_MARGIN_DB },
        )
    }

    /**
     * Decides whose each word is, and keeps one copy of it.
     *
     * Loudness decides, except in the band where a quiet student and the
     * teacher's bleed look alike: there a word at the student's own speaking
     * pitch is theirs (only a close match counts, since pitch trackers sometimes
     * read a voice an octave low). Each word's call is then checked against its
     * neighbours in the same phrase, so one odd word in the middle of someone's
     * sentence stays theirs. When both mics caught a word, the copy from the
     * speaker's own mic is kept.
     */
    fun assign(words: List<Word>, f: Features, voices: Voices = Voices(null, null)): List<TranscriptLine> {
        class Heard(val w: Word, var student: Boolean, val voiced: Boolean) { var keep = false }
        val heard = mutableListOf<Heard>()
        for (ch in 0..1) {
            val mine = words.filter { it.channel == ch }.sortedBy { it.startMs }.map { w ->
                val a = msToFrame(w.startMs).coerceIn(0, f.frames - 1)
                val b = msToFrame(w.endMs).coerceIn(a + 1, f.frames)
                var d = 0.0; var vad = 0.0
                val hz = mutableListOf<Double>()
                for (i in a until b) {
                    d += f.db[0][i] - f.db[1][i]
                    vad += maxOf(f.vad[0][i], f.vad[1][i])
                    val c = if (f.db[0][i] >= f.db[1][i]) 0 else 1
                    if (f.pitchConf[c][i] >= cfg.voicedConfidence && f.pitchHz[c][i] > 0f) hz += f.pitchHz[c][i].toDouble()
                }
                d /= (b - a); vad /= (b - a)
                // the voice detector often misses a quiet voice, so a word with a voice's pitch counts too
                val voiced = vad >= cfg.transcriptVadThreshold || hz.size >= MIN_WORD_PITCH_FRAMES
                val margin = d - cfg.studentDominanceDb
                val ambiguous = margin >= -AMBIGUOUS_BAND_DB && margin < CLEAR_MARGIN_DB
                val student = if (ambiguous && voices.distinct && hz.size >= MIN_WORD_PITCH_FRAMES) {
                    val p = hz.sorted()[hz.size / 2].toFloat()
                    kotlin.math.abs(Segmenter.cents(p, voices.studentHz!!.toFloat())) <= STUDENT_PITCH_MATCH_CENTS
                } else margin >= 0
                Heard(w, student, voiced)
            }
            // smooth each word's call over its neighbours in the same phrase
            val calls = mine.map { it.student }
            for ((k, h) in mine.withIndex()) {
                var votes = 0; var n = 0
                var j = k
                while (j > 0 && k - j < SMOOTH_WORDS && mine[j].w.startMs - mine[j - 1].w.endMs <= PHRASE_GAP_MS) j--
                var e = k
                while (e < mine.size - 1 && e - k < SMOOTH_WORDS && mine[e + 1].w.startMs - mine[e].w.endMs <= PHRASE_GAP_MS) e++
                for (i in j..e) { n++; if (calls[i]) votes++ }
                if (votes * 2 != n) h.student = votes * 2 > n
                // a word whose neighbours were clearly a voice is part of what was said
                h.keep = h.voiced || (j..e).count { mine[it].voiced } * 2 > (e - j + 1)
            }
            heard += mine.filter { it.keep }
        }
        fun Heard.onOwnMic() = w.channel == (if (student) 0 else 1)
        val own = heard.filter { it.onOwnMic() }
        val kept = own + heard.filter { o ->
            !o.onOwnMic() && own.none { m ->
                m.student == o.student && m.w.startMs < o.w.endMs + COPY_NEAR_MS && o.w.startMs < m.w.endMs + COPY_NEAR_MS
            }
        }
        val lines = mutableListOf<MutableList<Heard>>()
        for (h in kept.sortedBy { it.w.startMs }) {
            val cur = lines.lastOrNull()
            val last = cur?.last()
            if (cur != null && last!!.student == h.student && h.w.startMs - last.w.endMs <= cfg.utteranceJoinMs &&
                h.w.endMs - cur.first().w.startMs <= cfg.maxUtteranceMs
            ) cur += h else lines += mutableListOf(h)
        }
        return lines.map { hs ->
            TranscriptLine(
                if (hs.first().student) Speaker.STUDENT else Speaker.TEACHER,
                hs.first().w.startMs, hs.maxOf { it.w.endMs }, joinWords(hs.map { it.w.text }),
            )
        }.filter { it.text.isNotBlank() }
    }

    fun run(
        lesson: LessonFolder,
        segments: List<Segment>,
        takes: List<Take>,
        features: Features,
        transcriber: Transcriber,
        partial: File,
        onProgress: (Double) -> Unit = {},
        isStopped: () -> Boolean = { false },
    ): List<TranscriptLine> {
        val json = Json { ignoreUnknownKeys = true }
        fun readDone() = (if (partial.isFile) partial.readLines() else emptyList()).filter { it.isNotBlank() }
            .mapNotNull { runCatching { json.decodeFromString<PartialEntry>(it) }.getOrNull() }
        val doneKeys = readDone().map { it.key }.toHashSet()
        val jobs = windows(segments, takes, lesson.durationMs).flatMap { w -> listOf(0, 1).map { ch -> w to ch } }
        partial.parentFile.mkdirs()
        for ((i, job) in jobs.withIndex()) {
            val (w, ch) = job
            val key = "$ch:${w.startMs}:${w.endMs}"
            if (key in doneKeys) continue
            if (isStopped()) throw StoppedException()
            val audio = lesson.read16k(ch, w.startMs, w.endMs)
            val words = transcriber.transcribe(audio, cfg.transcriptPrompt).map { p ->
                // whisper sometimes stretches a word over the silence after it
                val end = minOf(w.endMs, w.startMs + p.endMs, w.startMs + p.startMs + MAX_WORD_MS)
                Word(ch, w.startMs + p.startMs, end, p.text)
            }
            partial.appendText(json.encodeToString(PartialEntry(key, words.map { PartialWord(it.channel, it.startMs, it.endMs, it.text) })) + "\n")
            onProgress((i + 1).toDouble() / jobs.size)
        }
        val all = readDone().flatMap { e -> e.words.map { Word(it.channel, it.startMs, minOf(it.endMs, it.startMs + MAX_WORD_MS), it.text) } }
        return assign(all, features, voices(features, takes))
    }

    companion object {
        /** Stretches shorter than this are too short for whisper to hear a word in. */
        const val MIN_WINDOW_MS = 400L
        /** Voices closer than this (cents, 4 semitones) are told apart by loudness only. */
        const val MIN_VOICE_GAP_CENTS = 400.0
        /** Frames of clearly-one-person speech needed to learn their pitch. */
        const val MIN_VOICE_FRAMES = 100
        /** Beyond the usual loudness rule, how much louder a mic must be for "clearly theirs". */
        const val CLEAR_MARGIN_DB = 10.0
        /** Voiced frames a word needs before its pitch is trusted. */
        const val MIN_WORD_PITCH_FRAMES = 3
        /** How far below the student loudness rule a word can be and still be theirs by pitch, in dB. */
        const val AMBIGUOUS_BAND_DB = 14.0
        /** A word this close to the student's speaking pitch (cents) is theirs. */
        const val STUDENT_PITCH_MATCH_CENTS = 350.0
        /** Words closer than this are one phrase. */
        const val PHRASE_GAP_MS = 300L
        /** Neighbours either side that a word's call is checked against. */
        const val SMOOTH_WORDS = 2
        /** Another mic's copy of a word is dropped when the speaker's own mic heard a word this close. */
        const val COPY_NEAR_MS = 600L
        /** No word lasts longer than this. */
        const val MAX_WORD_MS = 1_500L

        /** Joins whisper's words; punctuation that whisper split off sticks to the word before it. */
        fun joinWords(words: List<String>): String = buildString {
            for (w in words.map { it.trim() }.filter { it.isNotEmpty() }) {
                if (isNotEmpty() && w.first() !in ",.!?;:'%)") append(' ')
                append(w)
            }
        }
    }

    @kotlinx.serialization.Serializable
    data class PartialWord(val channel: Int, val startMs: Long, val endMs: Long, val text: String)

    @kotlinx.serialization.Serializable
    data class PartialEntry(val key: String, val words: List<PartialWord> = emptyList())
}
