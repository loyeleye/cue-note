package com.cuenote.signal

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes one 16-bit PCM WAV file. The header is written with zero sizes first
 * and patched on [close], so a file cut short by a crash is still readable by
 * most tools (they fall back to the file length).
 */
class WavWriter(
    val file: File,
    private val sampleRate: Int = LessonAudio.SAMPLE_RATE,
    private val channels: Int = LessonAudio.CHANNELS,
) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw").apply { setLength(0) }
    private var dataBytes = 0L
    private var scratch = ByteBuffer.allocate(0)

    var framesWritten = 0L
        private set

    init {
        raf.write(header(0))
    }

    /** Writes [sampleCount] interleaved samples from [samples], starting at [offset]. */
    fun write(samples: ShortArray, offset: Int = 0, sampleCount: Int = samples.size - offset) {
        require(sampleCount % channels == 0) { "sampleCount must be a whole number of frames" }
        val bytes = sampleCount * 2
        if (scratch.capacity() < bytes) {
            scratch = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        }
        scratch.clear()
        for (i in offset until offset + sampleCount) scratch.putShort(samples[i])
        raf.write(scratch.array(), 0, bytes)
        dataBytes += bytes
        framesWritten += sampleCount / channels
    }

    override fun close() {
        raf.seek(0)
        raf.write(header(dataBytes))
        raf.close()
    }

    private fun header(dataLen: Long): ByteArray {
        val byteRate = sampleRate * channels * 2
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + dataLen).toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16)
            putShort(1)                              // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * 2).toShort())       // block align
            putShort(16)                             // bits per sample
            put("data".toByteArray()); putInt(dataLen.toInt())
        }.array()
    }
}

/** Reads a WAV written by [WavWriter]. Used by tests and, later, the pipeline. */
object WavReader {
    data class Wav(val sampleRate: Int, val channels: Int, val samples: ShortArray)

    fun read(file: File): Wav {
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
        val channels = buf.getShort(22).toInt()
        val sampleRate = buf.getInt(24)
        val declared = buf.getInt(40)
        val dataLen = if (declared > 0) declared else bytes.size - 44
        val samples = ShortArray(dataLen / 2) { buf.getShort(44 + it * 2) }
        return Wav(sampleRate, channels, samples)
    }
}
