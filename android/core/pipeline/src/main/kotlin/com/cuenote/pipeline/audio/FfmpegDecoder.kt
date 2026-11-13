package com.cuenote.pipeline.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes compressed chunks on a laptop with ffmpeg, for the pipeline runner
 * and the golden set. ffmpeg honours the file's own delay information where
 * Android's decoder may not, so a chunk can come out up to an encoder delay
 * (about 44 ms) early here; fine for analysis, not used on the phone.
 */
object FfmpegDecoder : ChunkDecoder {
    fun available(): Boolean = runCatching {
        ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().apply { inputStream.readBytes() }.waitFor() == 0
    }.getOrDefault(false)

    override fun decode(file: File): ShortArray {
        val p = ProcessBuilder("ffmpeg", "-v", "error", "-i", file.absolutePath, "-f", "s16le", "-ac", "2", "-ar", "48000", "-")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val bytes = p.inputStream.readBytes()
        check(p.waitFor() == 0) { "ffmpeg could not decode ${file.name}" }
        val out = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }
}
