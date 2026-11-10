package com.cuenote.pipeline

import com.cuenote.pipeline.cards.ClipAudio
import com.cuenote.pipeline.cards.ClipPlan
import com.cuenote.pipeline.cards.CloudIds
import com.cuenote.pipeline.cards.CueRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CloudIdsTest {
    @Test fun `the same local id always gives the same uuid, and different ids differ`() {
        val a = CloudIds.feedback("lesson-1-fb-001")
        assertEquals(a, CloudIds.feedback("lesson-1-fb-001"))
        assertNotEquals(a, CloudIds.feedback("lesson-1-fb-002"))
        assertEquals(a, UUID.fromString(a).toString())
        assertEquals(3, UUID.fromString(a).version())
    }
}

class ClipPlanTest {
    private val takes = listOf(Take("L-take-001", 200, 10_000), Take("L-take-002", 30_000, 45_000))
    private val feedback = listOf(
        FeedbackBlock("L-fb-001", 10_500, 25_000, takeId = "L-take-001"),
        FeedbackBlock("L-fb-002", 46_000, 46_000 + 9 * 60_000L, takeId = "L-take-002"),
    )

    @Test fun `every take and block gets a padded clip named by its cloud id`() {
        val plan = ClipPlan.plan(takes, feedback, lessonMs = 20 * 60_000L)
        assertEquals(listOf("take", "feedback", "take", "feedback"), plan.map { it.kind })
        val first = plan[0]
        assertEquals(0L, first.startMs)   // padding stops at the start of the lesson
        assertEquals(10_000 + ClipPlan.TAKE_PAD_MS, first.endMs)
        assertEquals(CloudIds.take("L-take-001") + ".m4a", first.fileName)
        assertEquals(ClipPlan.MAX_CLIP_MS, plan[3].durationMs)   // very long feedback is cut short
    }

    @Test fun `clips stop at the end of the lesson`() {
        val plan = ClipPlan.plan(takes.take(1), emptyList(), lessonMs = 10_100)
        assertEquals(10_100L, plan.single().endMs)
    }
}

class ClipAudioTest {
    @Test fun `stereo becomes the average of both mics`() {
        val mono = ClipAudio.mixToMono(shortArrayOf(1000, 3000, -2000, 0, 32767, 32767))
        assertEquals(listOf<Short>(2000, -1000, 32767), mono.toList())
    }

    @Test fun `quiet clips are lifted, at most four times, and silence is left alone`() {
        val quiet = shortArrayOf(1000, -2000, 500)
        assertEquals(4f, ClipAudio.normalizeGain(quiet))
        val loud = shortArrayOf(0, 16000)
        val g = ClipAudio.normalizeGain(loud)
        assertTrue(g in 1.7f..1.9f)
        ClipAudio.applyGain(loud, g)
        assertTrue(loud[1] in 29000..29500)
        assertEquals(1f, ClipAudio.normalizeGain(ShortArray(10)))
    }
}

class CueRequestTest {
    private val out = Pipeline.Output(
        lessonId = "L", durationMs = 60_000, vad = "energy", transcriber = "test",
        segments = emptyList(),
        takes = listOf(Take("L-take-001", 1000, 8000)),
        feedback = listOf(
            FeedbackBlock("L-fb-001", 0, 900),
            FeedbackBlock("L-fb-002", 8500, 20_000, takeId = "L-take-001", linkConfidence = 0.9f,
                demos = listOf(Demo(12_000, 14_000))),
        ),
        transcript = listOf(
            TranscriptLine(Speaker.TEACHER, 0, 800, " Hello! "),
            TranscriptLine(Speaker.TEACHER, 8600, 10_000, "Lovely."),
            TranscriptLine(Speaker.STUDENT, 10_100, 11_000, "was that flat?"),
            TranscriptLine(Speaker.TEACHER, 11_200, 19_000, "A little, keep the breath going."),
            TranscriptLine(Speaker.STUDENT, 30_000, 31_000, "   "),
        ),
        events = emptyList(),
    )

    @Test fun `blocks carry cloud ids, her words only, and the clip path`() {
        val r = CueRequest.from("lesson-uuid", out) { id -> if (id == "L-fb-002") "s/l/x.m4a" else null }
        assertEquals("lesson-uuid", r.lessonId)
        val b = r.blocks[1]
        assertEquals(CloudIds.feedback("L-fb-002"), b.id)
        assertEquals(CloudIds.take("L-take-001"), b.takeId)
        assertEquals("Lovely. A little, keep the breath going.", b.transcript)
        assertEquals("s/l/x.m4a", b.clipPath)
        assertEquals(1, b.demos)
        assertNull(r.blocks[0].takeId)
        assertEquals("Hello!", r.blocks[0].transcript)
        assertEquals(4, r.transcript.size)   // the blank line is left out
    }

    @Test fun `the JSON uses the function's field names`() {
        val r = CueRequest.from("lesson-uuid", out) { null }
        val j = Json.parseToJsonElement(Json.encodeToString(CueRequest.serializer(), r)).jsonObject
        val block = j["blocks"]!!.jsonArray[1].jsonObject
        assertEquals(setOf("id", "local_id", "take_id", "span_start_ms", "span_end_ms", "clip_path", "transcript", "link_confidence", "demos"), block.keys)
        assertEquals("teacher", j["transcript"]!!.jsonArray[0].jsonObject["speaker"]!!.jsonPrimitive.content)
        assertEquals(setOf("lesson_id", "blocks", "transcript"), j.keys)
    }
}
