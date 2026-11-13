package com.cuenote.data.storage

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.cuenote.pipeline.audio.ChunkDecoder
import java.io.File
import java.nio.ByteOrder

/**
 * Decodes a compressed lesson chunk (.m4a, AAC) back to 48 kHz interleaved
 * 16-bit stereo with Android's own decoder. [com.cuenote.pipeline.audio.CompressedAudio]
 * then skips the encoder's delay so every sample lands where it was recorded.
 */
object AacChunkDecoder : ChunkDecoder {
    private const val TIMEOUT_US = 10_000L

    override fun decode(file: File): ShortArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        // a 30 s chunk is about 2.9 million samples; grow as needed
        var out = ShortArray(48_000 * 2 * 31)
        var size = 0
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val shorts = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val n = shorts.remaining()
                    if (size + n > out.size) out = out.copyOf(maxOf(out.size * 2, size + n))
                    shorts.get(out, size, n)
                    size += n
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        return out.copyOf(size)
    }
}
