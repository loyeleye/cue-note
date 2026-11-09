package com.cuenote.pipeline

import com.cuenote.ml.Transcriber
import com.cuenote.pipeline.audio.LessonFolder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Speech to text, only where someone is talking: the teacher's words from her
 * channel and the student's from theirs, so the transcript reads as a
 * dialogue and whisper never spends time on an hour of singing and piano.
 *
 * Resumable: each finished utterance is appended to a partial file, so if
 * the phone stops the work (it unplugs, or Android ends the job), the next
 * run carries on from the next utterance.
 */
class Transcription(private val cfg: PipelineConfig = PipelineConfig()) {

    data class Utterance(val speaker: Speaker, val startMs: Long, val endMs: Long)

    /** Joins nearby speech segments of the same person into utterances of at most ~28 s. */
    fun utterances(segments: List<Segment>): List<Utterance> {
        val out = mutableListOf<Utterance>()
        for (s in segments.filter { it.label.isSpeech }.sortedBy { it.startMs }) {
            val speaker = if (s.label == Label.TEACHER_SPEAK) Speaker.TEACHER else Speaker.STUDENT
            val last = out.lastOrNull()
            if (last != null && last.speaker == speaker && s.startMs - last.endMs <= cfg.utteranceJoinMs &&
                s.endMs - last.startMs <= cfg.maxUtteranceMs
            ) {
                out[out.size - 1] = last.copy(endMs = s.endMs)
            } else {
                // split anything longer than whisper's window
                var a = s.startMs
                while (a < s.endMs) {
                    val b = minOf(s.endMs, a + cfg.maxUtteranceMs)
                    out += Utterance(speaker, a, b)
                    a = b
                }
            }
        }
        return out
    }

    /**
     * Utterances of one person packed into one ~28 s piece of audio, with short
     * silences between them. whisper always works on a 30 s window, so one call
     * per batch instead of one per utterance is many times faster on the phone.
     */
    data class Batch(val speaker: Speaker, val utterances: List<Utterance>)

    fun batches(utts: List<Utterance>): List<Batch> {
        val out = mutableListOf<Batch>()
        for (speaker in Speaker.entries) {
            var cur = mutableListOf<Utterance>()
            var len = 0L
            for (u in utts.filter { it.speaker == speaker }) {
                val ul = u.endMs - u.startMs + 2 * cfg.utterancePadMs + BATCH_GAP_MS
                if (cur.isNotEmpty() && len + ul > cfg.maxUtteranceMs) { out += Batch(speaker, cur); cur = mutableListOf(); len = 0 }
                cur += u; len += ul
            }
            if (cur.isNotEmpty()) out += Batch(speaker, cur)
        }
        return out.sortedBy { it.utterances.first().startMs }
    }

    fun run(
        lesson: LessonFolder,
        segments: List<Segment>,
        transcriber: Transcriber,
        partial: File,
        onProgress: (Double) -> Unit = {},
        isStopped: () -> Boolean = { false },
    ): List<TranscriptLine> {
        val json = Json { ignoreUnknownKeys = true }
        fun readDone() = (if (partial.isFile) partial.readLines() else emptyList()).filter { it.isNotBlank() }
            .map { json.decodeFromString<PartialEntry>(it) }
        val doneKeys = readDone().map { it.key }.toHashSet()
        val batches = batches(utterances(segments))
        partial.parentFile.mkdirs()
        for ((i, batch) in batches.withIndex()) {
            val key = "${batch.speaker}:${batch.utterances.first().startMs}"
            if (key in doneKeys) continue
            if (isStopped()) throw StoppedException()
            val channel = if (batch.speaker == Speaker.STUDENT) 0 else 1
            // build the batch audio and remember where each utterance sits in it
            val pieces = batch.utterances.map { u ->
                val from = maxOf(0L, u.startMs - cfg.utterancePadMs)
                from to lesson.read16k(channel, from, u.endMs + cfg.utterancePadMs)
            }
            val gap = (BATCH_GAP_MS * 16).toInt()
            val audio = FloatArray(pieces.sumOf { it.second.size + gap })
            val offsets = LongArray(pieces.size)
            var pos = 0
            for ((k, p) in pieces.withIndex()) {
                offsets[k] = pos / 16L
                System.arraycopy(p.second, 0, audio, pos, p.second.size)
                pos += p.second.size + gap
            }
            val lines = transcriber.transcribe(audio, cfg.transcriptPrompt).mapNotNull { piece ->
                val k = offsets.indexOfLast { it <= piece.startMs }.coerceAtLeast(0)
                val from = pieces[k].first
                val lenMs = pieces[k].second.size / 16L
                val start = from + (piece.startMs - offsets[k]).coerceIn(0, lenMs)
                val end = from + (piece.endMs - offsets[k]).coerceIn(0, lenMs)
                TranscriptLine(batch.speaker, start, maxOf(start, end), piece.text)
            }
            partial.appendText(json.encodeToString(PartialEntry(key, lines)) + "\n")
            onProgress((i + 1).toDouble() / batches.size)
        }
        return readDone().flatMap { it.lines }.sortedBy { it.startMs }
    }

    companion object {
        const val BATCH_GAP_MS = 400L
    }

    @kotlinx.serialization.Serializable
    data class PartialEntry(val key: String, val lines: List<TranscriptLine>)
}
