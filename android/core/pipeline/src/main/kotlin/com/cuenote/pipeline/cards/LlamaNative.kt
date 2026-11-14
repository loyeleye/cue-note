package com.cuenote.pipeline.cards

import java.io.File

/**
 * The Kotlin side of libcuenote_llm (llama.cpp, built by :core:llm-android
 * for the phone and by core/llm-android/host-build.sh for a laptop). Only
 * this file talks to it; the card writer goes through [LlamaCardModel].
 */
object LlamaNative {
    @Volatile private var loaded = false

    /** On the phone the library is in the APK; on a laptop pass the folder host-build.sh wrote to. */
    @Synchronized
    fun load(dir: File? = null) {
        if (loaded) return
        if (dir != null) {
            val lib = dir.listFiles()?.firstOrNull { it.name.startsWith("libcuenote_llm.") }
                ?: error("libcuenote_llm not found in $dir; run core/llm-android/host-build.sh")
            System.load(lib.absolutePath)
        } else {
            System.loadLibrary("cuenote_llm")
        }
        loaded = true
    }

    external fun load(modelPath: String, nCtx: Int, threads: Int): Long
    external fun free(ptr: Long)
    external fun complete(ptr: Long, system: String, user: String, schema: String, maxTokens: Int, temperature: Float, seed: Int): ByteArray
}

/**
 * A GGUF model run by llama.cpp on this device, held to the answer's JSON
 * schema by a grammar. One block's prompt is well under [CONTEXT] tokens.
 */
class LlamaCardModel(
    file: File,
    override val name: String,
    threads: Int = 4,
    private val maxTokens: Int = 300,
    private val temperature: Float = 0.2f,
) : CardModel {
    private var ptr: Long = LlamaNative.load(file.absolutePath, CONTEXT, threads).also {
        require(it != 0L) { "couldn't load ${file.name}" }
    }

    @Synchronized
    override fun complete(system: String, user: String, schema: String): String {
        check(ptr != 0L) { "closed" }
        return LlamaNative.complete(ptr, system, user, schema, maxTokens, temperature, 1234).decodeToString()
    }

    @Synchronized
    override fun close() {
        if (ptr != 0L) LlamaNative.free(ptr)
        ptr = 0
    }

    companion object {
        const val CONTEXT = 4096
    }
}
