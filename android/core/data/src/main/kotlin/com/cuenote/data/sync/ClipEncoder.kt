package com.cuenote.data.sync

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.cuenote.pipeline.audio.LessonFolder
import com.cuenote.pipeline.cards.ClipAudio
import com.cuenote.pipeline.cards.ClipSpan
import com.cuenote.signal.LessonAudio
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cuts one stretch of a lesson into a small .m4a clip (AAC, 64 kbps, mono,
 * 48 kHz) with Android's own encoder. The full recording stays on the phone;
 * only these clips are uploaded.
 */
object ClipEncoder {
    private const val RATE = LessonAudio.SAMPLE_RATE
    private const val BIT_RATE = 64_000
    private const val TIMEOUT_US = 10_000L

    /** Writes [span] of [lesson] to [out], replacing it. Returns the file size in bytes. */
    fun encode(lesson: LessonFolder, span: ClipSpan, out: File): Long {
        val first = LessonAudio.msToFrames(span.startMs)
        val count = (LessonAudio.msToFrames(span.endMs) - first).toInt()
        require(count > 0) { "empty clip ${span.localId}" }
        // A clip is at most a few minutes, so its mono samples fit in memory
        // (5 minutes is about 29 MB), which lets the whole clip share one gain.
        val mono = ClipAudio.mixToMono(lesson.readFrames(first, count))
        ClipAudio.applyGain(mono, ClipAudio.normalizeGain(mono))
        return encodeMono(mono, out, span.localId)
    }

    /** Writes 48 kHz mono samples to [out] as an .m4a, replacing it. Returns the file size in bytes. */
    fun encodeMono(mono: ShortArray, out: File, label: String): Long = encodePcm(mono, 1, BIT_RATE, out, label)

    /**
     * Writes 48 kHz 16-bit samples ([channels] interleaved) to [out] as an AAC
     * .m4a, replacing it. Returns the file size in bytes.
     */
    fun encodePcm(pcm: ShortArray, channels: Int, bitRate: Int, out: File, label: String): Long {
        val mono = pcm
        require(mono.isNotEmpty()) { "empty clip $label" }
        val tmp = File(out.parentFile, out.name + ".part")
        tmp.delete()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var fed = 0            // samples handed to the encoder
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        buf.clear()
                        // whole frames only, so a buffer never splits left from right
                        val n = minOf(buf.capacity() / 2 / channels * channels, mono.size - fed)
                        val pts = fed / channels * 1_000_000L / RATE
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                            for (k in 0 until n) bytes.putShort(mono[fed + k])
                            buf.put(bytes.array())
                            codec.queueInputBuffer(i, 0, n * 2, pts, 0)
                            fed += n
                        }
                    }
                }
                when (val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (o >= 0) {
                        val buf = codec.getOutputBuffer(o)!!
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0 && muxing) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxing) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
        check(muxing && tmp.length() > 0) { "the encoder wrote nothing for $label" }
        out.delete()
        check(tmp.renameTo(out)) { "could not save ${out.name}" }
        return out.length()
    }
}
