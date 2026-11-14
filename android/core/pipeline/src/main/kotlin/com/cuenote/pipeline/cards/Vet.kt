package com.cuenote.pipeline.cards

import com.cuenote.pipeline.Pipeline
import com.cuenote.pipeline.Speaker
import com.cuenote.pipeline.audio.LessonFolder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/*
 * Vetting a small model for the phone's card writer, on the laptop: runs
 * the phone's prompt and checks ([PhoneCards]) over lessons that already have
 * a model comparison from the app (Details → Compare AI models, saved as
 * pipeline/model-compare.json and copied by Export to Downloads), and sets
 * each card beside Opus's for the same block.
 *
 *   ./gradlew :core:pipeline:run --args="vet ~/lessons --endpoint http://127.0.0.1:8080 --name qwen2.5-3b"
 *
 * The endpoint is llama.cpp's llama-server with the GGUF the phone would
 * download, so the vetting runs the same weights and the same JSON schema.
 */

/** A model behind an OpenAI-style chat endpoint, such as llama-server. Laptop only. */
class ServerCardModel(private val endpoint: String, override val name: String, private val maxTokens: Int = 300) : CardModel {
    override fun complete(system: String, user: String, schema: String): String {
        val body = buildJsonObject {
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
            put("temperature", 0.2)
            put("max_tokens", maxTokens)
            put("response_format", buildJsonObject {
                put("type", "json_object")
                put("schema", Json.parseToJsonElement(schema))
            })
        }.toString()
        val c = URI("${endpoint.trimEnd('/')}/v1/chat/completions").toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 10_000
        c.readTimeout = 600_000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
        val msg = Json.parseToJsonElement(text).jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        return msg ?: error("no answer: ${text.take(200)}")
    }
}

object Vet {
    /** Opus's card for a block, from the lesson's model comparison. */
    data class Reference(val cue: String, val herWords: String, val category: String?)

    data class Lesson(val id: String, val blocks: List<PhoneBlock>, val reference: Map<String, Reference>)

    private val json = Json { ignoreUnknownKeys = true }

    /** A lesson folder with pipeline output and, ideally, pipeline/model-compare.json. */
    fun load(dir: File, referenceModel: String = "claude-opus-5-5"): Lesson? {
        val out = Pipeline.readOutput(LessonFolder(dir)) ?: return null
        val blocks = PhoneCards.blocks(out)
        val compare = File(dir, "pipeline/model-compare.json").takeIf { it.isFile }
            ?.let { runCatching { json.decodeFromString(ModelTrials.serializer(), it.readText()) }.getOrNull() }
        val byCloud = blocks.associateBy { CloudIds.feedback(it.localId) }
        val ref = compare?.trials?.firstOrNull { it.model == referenceModel }?.cards.orEmpty()
            .mapNotNull { c -> byCloud[c.id]?.let { it.localId to Reference(c.cue, c.herWords, c.category) } }.toMap()
        return Lesson(out.lessonId, blocks, ref)
    }

    /** extract-cues' sample lesson (a request with no reference), for a quick check that a model answers in shape. */
    fun loadFixture(file: File): Lesson {
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val req = root["request"]!!.jsonObject
        val lines = req["transcript"]!!.jsonArray.map { it.jsonObject }.map {
            Triple(if (it["speaker"]!!.jsonPrimitive.content == "teacher") Speaker.TEACHER else Speaker.STUDENT,
                it["start_ms"]!!.jsonPrimitive.long to it["end_ms"]!!.jsonPrimitive.long, it["text"]!!.jsonPrimitive.content)
        }
        val blocks = req["blocks"]!!.jsonArray.map { it.jsonObject }.map { b ->
            val a = b["span_start_ms"]!!.jsonPrimitive.long
            val z = b["span_end_ms"]!!.jsonPrimitive.long
            val said = lines.filter { it.second.first < z && it.second.second > a }.map { it.first to it.third }
            PhoneBlock(
                localId = b["local_id"]!!.jsonPrimitive.content, exerciseId = null, exercise = null, sung = "",
                lines = said, words = b["transcript"]!!.jsonPrimitive.content,
                demos = b["demos"]?.jsonPrimitive?.int ?: 0, afterTake = b["take_id"]?.jsonPrimitive?.contentOrNull != null,
            )
        }
        return Lesson(file.nameWithoutExtension, blocks, emptyMap())
    }

    data class Row(val lesson: String, val block: PhoneBlock, val card: PhoneCard, val ref: Reference?)

    fun run(model: CardModel, lessons: List<Lesson>, log: (String) -> Unit = {}): List<Row> = lessons.flatMap { l ->
        val cards = PhoneCards.write(model, l.blocks) { i, n, c -> log("${l.id}: $i/$n in ${c.ms / 1000.0} s  “${c.cue}”") }
        val byId = l.blocks.associateBy { it.localId }
        cards.map { Row(l.id, byId.getValue(it.localId), it, l.reference[it.localId]) }
    }

    private val STOP = setOf("the", "a", "an", "and", "or", "to", "of", "on", "in", "your", "you", "it", "that", "this", "for", "with", "don't", "do", "not", "is", "be")

    private fun content(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]"), " ").split(Regex("\\s+")).filter { it.length > 1 && it !in STOP }.toSet()

    /** Shared content words over all content words of the two cues: 1 is the same words, 0 nothing in common. */
    fun overlap(a: String, b: String): Double {
        val x = content(a); val y = content(b)
        if (x.isEmpty() && y.isEmpty()) return 1.0
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return (x intersect y).size.toDouble() / (x union y).size
    }

    data class Summary(
        val model: String, val cards: Int, val secondsPerCard: Double, val p90Seconds: Double,
        val unreadable: Double, val repaired: Double, val cueCut: Double, val ungrounded: Double,
        val compared: Int, val chatAgrees: Double?, val categoryAgrees: Double?, val cueOverlap: Double?,
    )

    fun summarize(model: String, rows: List<Row>): Summary {
        fun share(p: (Row) -> Boolean, of: List<Row> = rows) = if (of.isEmpty()) 0.0 else of.count(p).toDouble() / of.size
        val secs = rows.map { it.card.ms / 1000.0 }.sorted()
        val withRef = rows.filter { it.ref != null }
        val bothSaid = withRef.filter { it.ref!!.category != "chat" && it.card.category != "chat" }
        return Summary(
            model = model, cards = rows.size,
            secondsPerCard = secs.average().takeIf { !it.isNaN() } ?: 0.0,
            p90Seconds = secs.getOrNull(((secs.size - 1) * 0.9).toInt()) ?: 0.0,
            unreadable = share({ it.card.unreadable }), repaired = share({ it.card.repaired }), cueCut = share({ it.card.cueCut }), ungrounded = share({ it.card.ungrounded }),
            compared = withRef.size,
            chatAgrees = withRef.takeIf { it.isNotEmpty() }?.let { share({ r -> (r.ref!!.category == "chat") == (r.card.category == "chat") }, it) },
            categoryAgrees = withRef.takeIf { it.isNotEmpty() }?.let { share({ r -> r.ref!!.category == r.card.category }, it) },
            cueOverlap = bothSaid.takeIf { it.isNotEmpty() }?.map { overlap(it.card.cue, it.ref!!.cue) }?.average(),
        )
    }

    fun report(summaries: List<Summary>, rows: Map<String, List<Row>>): String = buildString {
        fun pct(v: Double?) = v?.let { "${(it * 100).toInt()}%" } ?: "–"
        appendLine("# On-phone card writer: vetting")
        appendLine()
        appendLine("| Model | Cards | s/card (p90) | Not JSON | Quote repaired | Cue over 8 words | Cue not from her words | vs Opus: blocks | chat agrees | category agrees | cue words shared |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        for (s in summaries) appendLine(
            "| ${s.model} | ${s.cards} | ${"%.1f".format(s.secondsPerCard)} (${"%.1f".format(s.p90Seconds)}) | ${pct(s.unreadable)} | ${pct(s.repaired)} | " +
                "${pct(s.cueCut)} | ${pct(s.ungrounded)} | ${s.compared} | ${pct(s.chatAgrees)} | ${pct(s.categoryAgrees)} | ${pct(s.cueOverlap)} |",
        )
        for ((model, rs) in rows) {
            appendLine()
            appendLine("## $model")
            appendLine()
            appendLine("| Block | Her words (teacher) | Cue on the phone | Opus's cue | Category (phone / Opus) |")
            appendLine("|---|---|---|---|---|")
            for (r in rs) {
                fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ").take(160)
                val flags = listOfNotNull("repaired".takeIf { r.card.repaired }, "cut".takeIf { r.card.cueCut }, "not JSON".takeIf { r.card.unreadable }, "not from her words, dropped".takeIf { r.card.ungrounded })
                appendLine("| ${r.block.localId.substringAfterLast('-')} | ${cell(r.card.herWords)} | ${cell(r.card.cue)}${if (flags.isEmpty()) "" else " (${flags.joinToString()})"} | ${cell(r.ref?.cue ?: "–")} | ${r.card.category ?: "–"} / ${r.ref?.category ?: "–"} |")
            }
        }
    }
}
