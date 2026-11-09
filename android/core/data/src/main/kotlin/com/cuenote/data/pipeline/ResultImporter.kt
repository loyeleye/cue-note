package com.cuenote.data.pipeline

import com.cuenote.data.FeedbackEntity
import com.cuenote.data.PipelineDao
import com.cuenote.data.PipelineStageEntity
import com.cuenote.data.SegmentEntity
import com.cuenote.data.TakeEntity
import com.cuenote.data.TranscriptLineEntity
import com.cuenote.pipeline.Demo
import com.cuenote.pipeline.Pipeline
import com.cuenote.pipeline.Speaker
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Copies a pipeline run's results into Room. */
object ResultImporter {
    private val json = Json { encodeDefaults = true }

    suspend fun import(dao: PipelineDao, lessonId: String, out: Pipeline.Output, state: Pipeline.State) {
        val floats = ListSerializer(Float.serializer())
        dao.replaceResults(
            lessonId,
            segments = out.segments.map { SegmentEntity(lessonId, it.startMs, it.endMs, it.label.short) },
            takes = out.takes.map {
                TakeEntity(it.id, lessonId, it.startMs, it.endMs, pitchContourJson(it.pitchHz),
                    json.encodeToString(floats, it.pitchConfidence), it.withPiano)
            },
            feedback = out.feedback.map { fb ->
                val words = out.transcript.filter { it.speaker == Speaker.TEACHER && it.startMs < fb.endMs && it.endMs > fb.startMs }
                    .joinToString(" ") { it.text }
                FeedbackEntity(fb.id, lessonId, fb.takeId, fb.startMs, fb.endMs, fb.linkConfidence, fb.linkReason,
                    json.encodeToString(ListSerializer(Demo.serializer()), fb.demos), words)
            },
            lines = out.transcript.map { TranscriptLineEntity(lessonId, it.startMs, it.endMs, it.speaker.name.lowercase(), it.text) },
        )
        importStages(dao, lessonId, state)
    }

    /**
     * The cloud take.pitch_contour shape the teacher dashboard plots:
     * {"hop_ms": 32, "hz": [220.1, null, ...]}, null where there is no pitch.
     * pitch_confidence is the plain per-frame array of 0..1.
     */
    fun pitchContourJson(hz: List<Float>): String = buildJsonObject {
        put("hop_ms", 32)
        put("hz", JsonArray(hz.map { if (it > 0f) JsonPrimitive(it) else JsonNull }))
    }.toString()

    suspend fun importStages(dao: PipelineDao, lessonId: String, state: Pipeline.State) {
        for (s in state.stages) dao.upsertStage(PipelineStageEntity(lessonId, s.stage, s.status, s.startedAt, s.finishedAt, s.detail))
    }
}
