package com.cuenote.ml

import java.io.File

/**
 * The Kotlin side of libcuenote_ml (see src/main/cpp). Only this file talks to
 * native code; everything else goes through [Transcriber] and
 * [VoiceActivityDetector].
 */
object WhisperNative {
    @Volatile private var loaded = false

    /**
     * Loads the native library. On the phone it is packaged in the APK, so the
     * plain name works. On a laptop pass the folder host-build.sh wrote to.
     */
    @Synchronized
    fun load(dir: File? = null) {
        if (loaded) return
        if (dir != null) {
            val lib = dir.listFiles()?.firstOrNull { it.name.startsWith("libcuenote_ml.") }
                ?: error("libcuenote_ml not found in $dir; run core/ml/host-build.sh")
            System.load(lib.absolutePath)
        } else {
            System.loadLibrary("cuenote_ml")
        }
        loaded = true
    }

    /** True when the library can be loaded from [dir] (or the default path). */
    fun available(dir: File? = null): Boolean = runCatching { load(dir) }.isSuccess

    external fun initContext(modelPath: String): Long
    external fun freeContext(ptr: Long)
    external fun transcribe(ptr: Long, samples: FloatArray, threads: Int, language: String, prompt: String?): String
    external fun initVad(modelPath: String, threads: Int): Long
    external fun freeVad(ptr: Long)
    external fun vadProbabilities(ptr: Long, samples: FloatArray): FloatArray
}
