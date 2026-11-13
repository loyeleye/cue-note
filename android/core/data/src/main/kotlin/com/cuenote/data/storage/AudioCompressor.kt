package com.cuenote.data.storage

import com.cuenote.data.sync.ClipEncoder
import com.cuenote.pipeline.audio.CompressedAudio
import com.cuenote.pipeline.audio.LessonFolder
import java.io.File

/**
 * Shrinks a processed lesson's audio about fifteen-fold: each 30 s WAV chunk
 * (5.8 MB) becomes an AAC .m4a (about 0.4 MB at 96 kbps stereo), which still
 * plays, clips and processes the same.
 *
 * Safe by construction, one chunk at a time:
 * 1. encode the chunk to chunk_NNNNN.m4a,
 * 2. decode it back and line it up with the original; if it doesn't sound
 *    like the original (a broken encoder, a full disk), keep the WAV and stop,
 * 3. record the chunk in audio/frames.tsv, and only then
 * 4. delete the WAV.
 * Stopping at any point leaves every chunk readable, and running it again
 * carries on where it stopped.
 */
object AudioCompressor {
    const val BIT_RATE = 96_000

    data class Result(val compressed: Int, val skipped: Int, val bytesBefore: Long, val bytesAfter: Long)

    fun compress(dir: File, isStopped: () -> Boolean = { false }): Result {
        val lesson = LessonFolder(dir)
        var done = 0; var kept = 0; var before = 0L; var after = 0L
        for (chunk in lesson.chunks) {
            if (lesson.isCompressed(chunk)) continue
            if (isStopped()) break
            val pcm = lesson.samplesOf(chunk)
            val packed = File(chunk.parentFile, chunk.nameWithoutExtension + "." + CompressedAudio.EXTENSION)
            if (pcm.isEmpty()) { kept++; continue }
            ClipEncoder.encodePcm(pcm, 2, BIT_RATE, packed, chunk.name)
            val check = CompressedAudio.check(pcm, AacChunkDecoder.decode(packed))
            if (!check.faithful) {
                // not a faithful copy: keep the original, and stop rather than repeat the failure
                packed.delete()
                kept++
                break
            }
            CompressedAudio.writeEntry(chunk.parentFile, CompressedAudio.Entry(packed.name, pcm.size / 2L, check.lag))
            before += chunk.length(); after += packed.length()
            chunk.delete()
            done++
        }
        return Result(done, kept, before, after)
    }
}
