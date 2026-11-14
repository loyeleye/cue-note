package com.cuenote.data.writer

import android.content.Context
import com.cuenote.ml.ModelFiles
import com.cuenote.pipeline.cards.CardModel
import com.cuenote.pipeline.cards.LlamaCardModel
import com.cuenote.pipeline.cards.LlamaNative
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The models the card writer on the phone can run (see PhoneCards in
 * :core:pipeline), and how it picks one.
 *
 * Gemini Nano, when the phone offers it to apps (ML Kit's Prompt API; Google
 * lists the Pixel 9 and 10, so a Pixel 8 Pro most likely says no), needs no
 * download. Otherwise the vetted open model is downloaded once, over Wi-Fi,
 * and checked against its SHA-256, so the phone runs exactly the weights
 * that were vetted on the laptop (`vet` in Cli.kt).
 */
object PhoneWriter {
    /** A GGUF file and the hash of the copy that was vetted. */
    data class Weights(val name: String, val label: String, val model: ModelFiles.Model, val sha256: String, val license: String)

    val LLAMA_3_2_3B = Weights(
        "llama3.2-3b", "Llama 3.2 3B",
        ModelFiles.Model(
            "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/5ab33fa94d1d04e903623ae72c95d1696f09f9e8/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            2_019_377_696,
        ),
        "6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff",
        "Llama 3.2 Community License",
    )

    val GEMMA_3_4B = Weights(
        "gemma3-4b", "Gemma 3 4B",
        ModelFiles.Model(
            "gemma-3-4b-it-Q4_K_M.gguf",
            "https://huggingface.co/ggml-org/gemma-3-4b-it-GGUF/resolve/d0976223747697cb51e056d85c532013931fe52e/gemma-3-4b-it-Q4_K_M.gguf",
            2_489_757_856,
        ),
        "882e8d2db44dc554fb0ea5077cb7e4bc49e7342a1f0da57901c0802ea21a0863",
        "Gemma Terms of Use",
    )

    val QWEN_2_5_3B = Weights(
        "qwen2.5-3b", "Qwen2.5 3B",
        ModelFiles.Model(
            "qwen2.5-3b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/7dabda4d13d513e3e842b20f0d435c732f172cbe/qwen2.5-3b-instruct-q4_k_m.gguf",
            2_104_932_768,
        ),
        "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d",
        "Qwen Research License (non-commercial)",
    )

    /** The one the phone downloads, from the first vetting (README, "Vetting a model for it"). */
    val VETTED = LLAMA_3_2_3B

    fun dir(context: Context) = File(context.filesDir, "models")

    fun weightsFile(context: Context, w: Weights = VETTED) = File(dir(context), w.model.fileName)

    fun hasWeights(context: Context, w: Weights = VETTED) = weightsFile(context, w).isFile

    /** Downloads the weights and checks their hash; a file that doesn't match is deleted. */
    fun download(context: Context, w: Weights = VETTED, onProgress: (Long, Long) -> Unit, isStopped: () -> Boolean): File {
        val f = ModelFiles.download(w.model, dir(context), onProgress, isStopped)
        val sum = sha256(f)
        if (!sum.equals(w.sha256, ignoreCase = true)) {
            f.delete()
            throw IOException("${w.label} didn't match the vetted copy (sha256 $sum)")
        }
        return f
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Whether this phone lets apps use Gemini Nano: "available", "download" (it can fetch it), or null. */
    suspend fun nanoStatus(): String? = runCatching {
        Generation.getClient().use { m ->
            when (m.checkStatus()) {
                FeatureStatus.AVAILABLE -> "available"
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> "download"
                else -> null
            }
        }
    }.getOrNull()

    private inline fun <T> GenerativeModel.use(block: (GenerativeModel) -> T): T = try { block(this) } finally { close() }

    /** The model to write with: Gemini Nano when it's ready, else the downloaded weights; null when neither is here. */
    suspend fun open(context: Context): CardModel? {
        if (nanoStatus() == "available") return NanoCardModel()
        val f = weightsFile(context).takeIf { it.isFile } ?: return null
        LlamaNative.load()
        // Four threads: the Pixel's big cores, leaving the small ones for the rest of the phone.
        return LlamaCardModel(f, VETTED.name, threads = 4)
    }
}

/** Gemini Nano through ML Kit's Prompt API. It can't hold an answer to a schema, so PhoneCards' checks matter more. */
class NanoCardModel : CardModel {
    private val model = Generation.getClient()
    override val name = "gemini-nano"

    override fun complete(system: String, user: String, schema: String): String = runBlocking {
        // This version of the Prompt API takes no separate system turn, so the instructions lead the prompt.
        val request = generateContentRequest(TextPart("$system\n\n$user\n\nAnswer with the JSON object only.")) {
            temperature = 0.2f
            maxOutputTokens = 300
        }
        model.generateContent(request).candidates.firstOrNull()?.text.orEmpty()
    }

    override fun close() = model.close()
}
