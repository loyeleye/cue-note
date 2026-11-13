package com.cuenote.pipeline.timeline

import com.cuenote.pipeline.Features
import com.cuenote.pipeline.Pipeline
import com.cuenote.pipeline.audio.LessonFolder
import com.cuenote.pipeline.golden.GoldenLabels
import com.cuenote.pipeline.golden.Score
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Writes the debug timeline: one self-contained HTML page that shows what the
 * pipeline heard and decided, lane by lane, and plays the lesson's audio from
 * the chunk files next to it. It doubles as the labelling tool for the golden
 * set: mark the true takes, feedback and links, then save labels.json.
 *
 * It opens in any browser from an exported lesson folder, and in the app.
 */
object TimelineWriter {
    private val json = Json { encodeDefaults = true }

    fun write(
        file: File,
        lesson: LessonFolder,
        out: Pipeline.Output,
        f: Features,
        labels: GoldenLabels?,
        score: Score?,
        state: Pipeline.State,
    ) {
        val data = buildJsonObject {
            put("lessonId", out.lessonId)
            put("durationMs", out.durationMs)
            put("vad", out.vad)
            put("transcriber", out.transcriber ?: "none")
            put("frameMs", 32.0)
            put("chunks", json.encodeToJsonElement(lesson.chunks.map { "../audio/${it.name}" }))
            put("chunkMs", json.encodeToJsonElement(lesson.chunkFrames.map { it * 1000 / 48_000 }))
            put("segments", json.encodeToJsonElement(out.segments))
            put("takes", json.encodeToJsonElement(out.takes.map { it.copy(pitchHz = emptyList(), pitchConfidence = emptyList()) }))
            put("feedback", json.encodeToJsonElement(out.feedback))
            put("exercises", json.encodeToJsonElement(out.exercises))
            put("transcript", json.encodeToJsonElement(out.transcript))
            put("events", json.encodeToJsonElement(out.events))
            put("gaps", json.encodeToJsonElement(lesson.events.filterIsInstance<com.cuenote.signal.LessonEvent.Gap>().map { listOf(it.atMs, it.missingMs) }))
            put("labels", labels?.let { json.encodeToJsonElement(it.copy(segments = emptyList())) } ?: JsonNull)
            put("score", score?.let { json.encodeToJsonElement(it) } ?: JsonNull)
            put("state", json.encodeToJsonElement(state))
            for (ch in 0..1) {
                val who = if (ch == 0) "s" else "t"
                put("${who}Db", b64(ByteArray(f.frames) { (f.db[ch][it] + 120).roundToInt().coerceIn(0, 255).toByte() }))
                put("${who}Conf", b64(ByteArray(f.frames) { (f.pitchConf[ch][it] * 100).roundToInt().coerceIn(0, 100).toByte() }))
                put("${who}Vad", b64(ByteArray(f.frames) { (f.vad[ch][it] * 100).roundToInt().coerceIn(0, 100).toByte() }))
                put("${who}Pitch", b64(ByteArray(f.frames) { i ->
                    val hz = f.pitchHz[ch][i]
                    // half-semitone steps above MIDI 24, 0 for none
                    if (hz <= 0f) 0.toByte() else ((69 + 12 * ln(hz / 440.0) / ln(2.0) - 24) * 2).roundToInt().coerceIn(1, 255).toByte()
                }))
            }
        }
        val template = TimelineWriter::class.java.getResource("/timeline/timeline.html")!!.readText()
        file.parentFile.mkdirs()
        file.writeText(template.replace("/*__DATA__*/null", data.toString().replace("</", "<\\/")))
    }

    private fun b64(bytes: ByteArray): JsonElement = kotlinx.serialization.json.JsonPrimitive(Base64.getEncoder().encodeToString(bytes))
}
