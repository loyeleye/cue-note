package com.cuenote.pipeline.audio

import java.io.File
import kotlin.math.log10
import kotlin.math.max

/**
 * Turns compressed chunk files (chunk_00000.m4a ...) back into 48 kHz
 * interleaved stereo. The phone supplies one built on Android's decoder; the
 * laptop runner uses ffmpeg. Set [LessonFolder.decoder] before reading a
 * lesson whose audio has been compressed.
 */
fun interface ChunkDecoder {
    fun decode(file: File): ShortArray
}

/**
 * Bookkeeping for a lesson's compressed audio. A lossy encoder adds a short
 * delay at the start and pads the end, so audio/frames.tsv records, for each
 * compressed chunk, how many frames the original had and how many frames of
 * delay to skip when decoding. Times stay exactly where they were.
 */
object CompressedAudio {
    const val INDEX = "frames.tsv"
    const val EXTENSION = "m4a"

    /** Longest encoder delay we look for, in frames (AAC's is about 2112). */
    const val MAX_LAG = 4_096

    /**
     * The compressed copy is trusted when it is this close to the original
     * (signal-to-noise, dB; real lesson chunks at 96 kbps come out at 17 to
     * 23 dB) or, for a near-silent chunk, when the difference itself is
     * inaudibly small ([MAX_QUIET_ERROR_RMS], in 16-bit steps, about -50 dBFS).
     */
    const val MIN_SNR_DB = 12.0
    const val MAX_QUIET_ERROR_RMS = 100.0

    data class Entry(val name: String, val frames: Long, val lag: Int)

    fun readIndex(audioDir: File): Map<String, Entry> =
        File(audioDir, INDEX).takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 3) null else Entry(p[0], p[1].toLongOrNull() ?: return@mapNotNull null, p[2].toIntOrNull() ?: 0)
        }.associateBy { it.name }

    /** Adds or replaces one chunk's line; written to a temporary file first so a crash never leaves half an index. */
    fun writeEntry(audioDir: File, entry: Entry) {
        val all = readIndex(audioDir) + (entry.name to entry)
        val tmp = File(audioDir, "$INDEX.part")
        tmp.writeText(all.values.sortedBy { it.name }.joinToString("") { "${it.name}\t${it.frames}\t${it.lag}\n" })
        check(tmp.renameTo(File(audioDir, INDEX)) || run { File(audioDir, INDEX).delete(); tmp.renameTo(File(audioDir, INDEX)) }) {
            "could not save the audio index"
        }
    }

    /** The original's frames out of a decoded chunk: skip the encoder's delay, pad or cut to length. */
    fun trim(decoded: ShortArray, frames: Long, lag: Int): ShortArray {
        val out = ShortArray((frames * 2).toInt())
        val from = lag * 2
        val n = minOf(out.size, max(0, decoded.size - from))
        if (n > 0) System.arraycopy(decoded, from, out, 0, n)
        return out
    }

    data class Check(val lag: Int, val snrDb: Double, val errorRms: Double = 0.0) {
        val faithful get() = snrDb >= MIN_SNR_DB || errorRms <= MAX_QUIET_ERROR_RMS
    }

    /**
     * Finds the encoder's delay by lining the decoded copy up with the original
     * (on the loudest second, both channels), and measures how close the copy
     * is once lined up. Silence counts as a perfect copy.
     */
    fun check(original: ShortArray, decoded: ShortArray): Check {
        val frames = original.size / 2
        if (frames == 0) return Check(0, Double.POSITIVE_INFINITY)
        // the loudest second is where a misalignment shows most clearly
        val win = minOf(frames, 48_000)
        var best = 0; var bestE = -1.0
        var start = 0
        while (start + win <= frames) {
            var e = 0.0
            var k = start
            while (k < start + win) { val s = original[2 * k].toDouble(); e += s * s; k += 8 }
            if (e > bestE) { bestE = e; best = start }
            start += win / 2
        }
        if (bestE <= 0.0) return Check(0, Double.POSITIVE_INFINITY)
        val step = 4   // coarse search, then refine
        fun corr(lag: Int, stride: Int): Double {
            var c = 0.0
            var k = best
            while (k < best + win) {
                val j = k + lag
                if (2 * j + 1 >= decoded.size) break
                c += original[2 * k] * decoded[2 * j].toDouble() + original[2 * k + 1] * decoded[2 * j + 1].toDouble()
                k += stride
            }
            return c
        }
        var lag = (0..MAX_LAG step step).maxBy { corr(it, 4) }
        lag = (maxOf(0, lag - step)..minOf(MAX_LAG, lag + step)).maxBy { corr(it, 1) }
        // how close is the copy, over the whole chunk
        var sig = 0.0; var err = 0.0
        for (k in 0 until frames) for (c in 0..1) {
            val o = original[2 * k + c].toDouble()
            val j = 2 * (k + lag) + c
            val d = if (j < decoded.size) decoded[j].toDouble() else 0.0
            sig += o * o; err += (o - d) * (o - d)
        }
        val snr = if (err == 0.0) Double.POSITIVE_INFINITY else 10 * log10(max(sig, 1.0) / err)
        return Check(lag, snr, kotlin.math.sqrt(err / (2.0 * frames)))
    }
}
