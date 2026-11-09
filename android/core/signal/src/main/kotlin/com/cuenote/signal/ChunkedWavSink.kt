package com.cuenote.signal

import java.io.File

/**
 * Splits a continuous stereo stream into fixed-length WAV chunks
 * (chunk_00000.wav, chunk_00001.wav, ...). If the app crashes or a transmitter
 * drops, at most the chunk being written is affected.
 */
class ChunkedWavSink(
    private val dir: File,
    private val framesPerChunk: Int = LessonAudio.FRAMES_PER_CHUNK,
    private val channels: Int = LessonAudio.CHANNELS,
    private val onChunkClosed: (ChunkInfo) -> Unit = {},
) : AutoCloseable {

    data class ChunkInfo(val index: Int, val file: File, val startFrame: Long, val frames: Long)

    private var writer: WavWriter? = null
    private var chunkIndex = 0
    private var chunkStartFrame = 0L

    var totalFrames = 0L
        private set

    init {
        dir.mkdirs()
    }

    fun write(samples: ShortArray, sampleCount: Int = samples.size) {
        var offset = 0
        while (offset < sampleCount) {
            val w = writer ?: openNext()
            val roomFrames = framesPerChunk - w.framesWritten
            val frames = minOf(roomFrames, ((sampleCount - offset) / channels).toLong()).toInt()
            w.write(samples, offset, frames * channels)
            offset += frames * channels
            totalFrames += frames
            if (w.framesWritten >= framesPerChunk) closeCurrent()
        }
    }

    override fun close() {
        closeCurrent()
    }

    private fun openNext(): WavWriter {
        val f = File(dir, "chunk_%05d.wav".format(chunkIndex))
        chunkStartFrame = totalFrames
        return WavWriter(f, channels = channels).also { writer = it }
    }

    private fun closeCurrent() {
        val w = writer ?: return
        w.close()
        writer = null
        onChunkClosed(ChunkInfo(chunkIndex, w.file, chunkStartFrame, w.framesWritten))
        chunkIndex++
    }
}
