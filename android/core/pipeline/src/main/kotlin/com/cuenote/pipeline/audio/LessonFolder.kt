package com.cuenote.pipeline.audio

import com.cuenote.signal.LessonAudio
import com.cuenote.signal.LessonEvent
import com.cuenote.signal.WavReader
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A lesson as the recorder leaves it (and as "Export to Downloads" copies it):
 *
 *     <id>/audio/chunk_00000.wav ...   48 kHz 16-bit stereo, 30 s each, L = student, R = teacher
 *                                      (or chunk_00000.m4a once compressed, see [CompressedAudio])
 *     <id>/events.tsv                  mark, photo, gap and mic lines, offsets in ms
 *     <id>/summary.tsv
 *
 * The pipeline writes its own files to <id>/pipeline/.
 */
class LessonFolder(val dir: File) {
    companion object {
        /** Decodes compressed chunks; the app and the laptop runner each set their own. */
        @Volatile var decoder: ChunkDecoder? = null
    }

    val id: String get() = dir.name
    val audioDir = File(dir, "audio")
    val pipelineDir = File(dir, "pipeline")
    val labelsFile = File(dir, "labels.json")

    /** Compressed chunks and what they need to decode back exactly in place. */
    private val index: Map<String, CompressedAudio.Entry> by lazy { CompressedAudio.readIndex(audioDir) }

    /**
     * One file per chunk, in order: the original WAV, or its compressed copy
     * once the WAV has been replaced. A compressed copy counts only once it is
     * in the index, so a half-finished compression is never read.
     */
    val chunks: List<File> by lazy {
        val files = audioDir.listFiles().orEmpty()
        val wav = files.filter { it.name.matches(Regex("""chunk_\d+\.wav""")) }.associateBy { it.nameWithoutExtension }
        val packed = files.filter { it.name.matches(Regex("""chunk_\d+\.${CompressedAudio.EXTENSION}""")) && it.name in index }
            .associateBy { it.nameWithoutExtension }
        (wav.keys + packed.keys).sorted().map { wav[it] ?: packed.getValue(it) }
    }

    fun isCompressed(chunk: File) = !chunk.name.endsWith(".wav")

    val events: List<LessonEvent> by lazy {
        File(dir, "events.tsv").takeIf { it.isFile }?.readLines()?.mapNotNull(LessonEvent::parse).orEmpty()
    }

    /** Frames of 48 kHz audio in each chunk, read from the WAV headers (or file sizes). */
    val chunkFrames: List<Long> by lazy { chunks.map(::framesIn) }

    val durationMs: Long get() = LessonAudio.framesToMs(chunkFrames.sum())

    /**
     * Streams the whole lesson as 16 kHz (student, teacher) blocks, one per
     * chunk, so an hour-long lesson never sits in memory at 48 kHz.
     */
    fun stream16k(): Sequence<Pair<FloatArray, FloatArray>> = sequence {
        val ds = StereoDownsampler()
        for (chunk in chunks) yield(ds.process(samplesOf(chunk)))
    }

    /** One channel (0 = student, 1 = teacher) between two times, at 16 kHz. */
    fun read16k(channel: Int, startMs: Long, endMs: Long): FloatArray {
        val margin = 10L
        val from = maxOf(0L, startMs - margin)
        val firstFrame = LessonAudio.msToFrames(from)
        val lastFrame = LessonAudio.msToFrames(endMs)
        val pcm = readFrames(firstFrame, (lastFrame - firstFrame).toInt())
        val ds = StereoDownsampler()
        val (s, t) = ds.process(pcm)
        val res = if (channel == 0) s else t
        // drop the margin and the filter's delay (31 taps at 48 kHz, about 10 output samples)
        val skip = ((startMs - from) * 16).toInt() + 10
        return if (skip >= res.size) FloatArray(0) else res.copyOfRange(skip, res.size)
    }

    /** Interleaved 48 kHz frames from anywhere in the lesson, across chunk edges. */
    fun readFrames(startFrame: Long, count: Int): ShortArray {
        val out = ShortArray(count * 2)
        var written = 0
        var chunkStart = 0L
        for ((i, frames) in chunkFrames.withIndex()) {
            val chunkEnd = chunkStart + frames
            val want0 = startFrame + written
            if (written < count && want0 < chunkEnd && want0 >= chunkStart) {
                val offset = want0 - chunkStart
                val n = minOf(count - written.toLong(), chunkEnd - want0).toInt()
                if (isCompressed(chunks[i])) {
                    val all = samplesOf(chunks[i])
                    System.arraycopy(all, (offset * 2).toInt(), out, written * 2, n * 2)
                } else RandomAccessFile(chunks[i], "r").use { raf ->
                    raf.seek(44 + offset * 4)
                    val bytes = ByteArray(n * 4)
                    raf.readFully(bytes)
                    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    for (k in 0 until n * 2) out[written * 2 + k] = buf.getShort()
                }
                written += n
            }
            chunkStart = chunkEnd
            if (written >= count) break
        }
        return out
    }

    /** The whole chunk as interleaved 48 kHz stereo, whichever form it is in. */
    fun samplesOf(chunk: File): ShortArray {
        if (!isCompressed(chunk)) {
            val wav = WavReader.read(chunk)
            require(wav.channels == 2 && wav.sampleRate == LessonAudio.SAMPLE_RATE) {
                "${chunk.name}: expected 48 kHz stereo, got ${wav.sampleRate} Hz × ${wav.channels}"
            }
            return wav.samples
        }
        lastDecoded?.let { (name, samples) -> if (name == chunk.path) return samples }
        val d = checkNotNull(decoder) { "${chunk.name} is compressed and no decoder is set (LessonFolder.decoder)" }
        val e = index.getValue(chunk.name)
        return CompressedAudio.trim(d.decode(chunk), e.frames, e.lag).also { lastDecoded = chunk.path to it }
    }

    /** The last chunk decoded, since reads of nearby times usually land in the same chunk. */
    private var lastDecoded: Pair<String, ShortArray>? = null

    private fun framesIn(f: File): Long {
        if (isCompressed(f)) return index.getValue(f.name).frames
        RandomAccessFile(f, "r").use { raf ->
            val head = ByteArray(44)
            raf.readFully(head)
            val declared = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(40).toLong()
            val data = if (declared > 0) minOf(declared, raf.length() - 44) else raf.length() - 44
            return data / 4
        }
    }
}
