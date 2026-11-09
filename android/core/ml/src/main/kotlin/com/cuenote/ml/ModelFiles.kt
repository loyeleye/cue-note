package com.cuenote.ml

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * The model files the pipeline needs, where to get them, and a small
 * downloader. The phone fetches them once, on first processing, into its
 * own storage; the APK stays small. Both come from whisper.cpp's model pages
 * on Hugging Face.
 */
object ModelFiles {
    data class Model(val fileName: String, val url: String, val approxBytes: Long)

    val SILERO = Model(
        "ggml-silero-v5.1.2.bin",
        "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin",
        885_098,
    )

    /** base.en is the design doc's starting point; tiny.en is the fallback if it is too slow on the phone. */
    val WHISPER_BASE_EN = Model(
        "ggml-base.en.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin",
        147_964_211,
    )
    val WHISPER_TINY_EN = Model(
        "ggml-tiny.en.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin",
        77_704_715,
    )

    fun missing(dir: File, models: List<Model>) = models.filter { !File(dir, it.fileName).isFile }

    /**
     * Downloads [model] into [dir], resuming a partial download. The file only
     * gets its real name once complete, so a half-downloaded model is never used.
     */
    fun download(model: Model, dir: File, onProgress: (Long, Long) -> Unit = { _, _ -> }, isStopped: () -> Boolean = { false }): File {
        dir.mkdirs()
        val dest = File(dir, model.fileName)
        if (dest.isFile) return dest
        val part = File(dir, model.fileName + ".part")
        var url = URI(model.url).toURL()
        var redirects = 0
        while (true) {
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            if (part.length() > 0) conn.setRequestProperty("Range", "bytes=${part.length()}-")
            when (val code = conn.responseCode) {
                in 300..399 -> {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("redirect without location")
                    url = url.toURI().resolve(loc).toURL()
                    conn.disconnect()
                    if (++redirects > 8) throw IOException("too many redirects")
                    continue
                }
                200, 206 -> {
                    val append = code == 206
                    if (!append) part.delete()
                    val total = (if (append) part.length() else 0) + conn.contentLengthLong.coerceAtLeast(0)
                    conn.inputStream.use { input ->
                        java.io.FileOutputStream(part, append).use { out ->
                            val buf = ByteArray(1 shl 16)
                            var done = part.length()
                            while (true) {
                                if (isStopped()) throw IOException("download stopped")
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                onProgress(done, total)
                            }
                        }
                    }
                    if (!part.renameTo(dest)) throw IOException("could not save ${dest.name}")
                    return dest
                }
                416 -> { part.renameTo(dest); return dest }   // already complete
                else -> throw IOException("download of ${model.fileName} failed: HTTP $code")
            }
        }
    }
}
