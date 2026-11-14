package com.cuenote.pipeline.audio

import com.cuenote.signal.LessonAudio
import com.cuenote.signal.WavWriter
import java.io.File
import java.io.RandomAccessFile

/**
 * Silences stretches of a lesson's recording, for parts the student deleted
 * (see [com.cuenote.pipeline.Removals]). Times stay where they were, so the
 * rest of the lesson, its clips and its cards still line up.
 *
 * A WAV chunk is zeroed in place. A compressed chunk is decoded, zeroed and
 * written back as a WAV chunk, which replaces it (the compressor shrinks it
 * again later); the WAV is in place before the compressed copy goes, so a
 * crash never loses audio that wasn't deleted.
 */
object AudioEraser {
    /** Returns how many chunks changed. */
    fun silence(lessonDir: File, ranges: List<LongRange>): Int {
        if (ranges.isEmpty()) return 0
        val folder = LessonFolder(lessonDir)
        var changed = 0
        var chunkStart = 0L
        for ((i, chunk) in folder.chunks.withIndex()) {
            val frames = folder.chunkFrames[i]
            val chunkEnd = chunkStart + frames
            // frame ranges within this chunk
            val hits = ranges.mapNotNull { r ->
                val a = maxOf(LessonAudio.msToFrames(r.first), chunkStart)
                val b = minOf(LessonAudio.msToFrames(r.last), chunkEnd)
                if (b > a) (a - chunkStart) until (b - chunkStart) else null
            }
            if (hits.isNotEmpty()) {
                if (folder.isCompressed(chunk)) rewriteAsWav(folder, chunk, hits) else zeroWav(chunk, hits)
                changed++
            }
            chunkStart = chunkEnd
        }
        return changed
    }

    private fun zeroWav(chunk: File, hits: List<LongRange>) = RandomAccessFile(chunk, "rw").use { raf ->
        val dataEnd = raf.length()
        for (h in hits) {
            val from = 44 + h.first * 4
            val to = minOf(dataEnd, 44 + (h.last + 1) * 4)
            if (to <= from) continue
            raf.seek(from)
            val zeros = ByteArray(64 * 1024)
            var left = to - from
            while (left > 0) {
                val n = minOf(left, zeros.size.toLong()).toInt()
                raf.write(zeros, 0, n)
                left -= n
            }
        }
    }

    private fun rewriteAsWav(folder: LessonFolder, chunk: File, hits: List<LongRange>) {
        val samples = folder.samplesOf(chunk).copyOf()
        for (h in hits) {
            val from = (h.first * 2).toInt().coerceIn(0, samples.size)
            val to = ((h.last + 1) * 2).toInt().coerceIn(0, samples.size)
            samples.fill(0, from, to)
        }
        val name = chunk.nameWithoutExtension
        val part = File(folder.audioDir, "$name.wav.part")
        WavWriter(part).use { it.write(samples) }
        val wav = File(folder.audioDir, "$name.wav")
        check(part.renameTo(wav)) { "could not save ${wav.name}" }
        chunk.delete()
        CompressedAudio.removeEntry(folder.audioDir, chunk.name)
    }
}
