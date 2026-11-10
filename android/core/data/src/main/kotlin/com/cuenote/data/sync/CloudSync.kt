package com.cuenote.data.sync

import com.cuenote.data.AuthRepository
import com.cuenote.data.CardEntity
import com.cuenote.data.LessonEntity
import com.cuenote.data.TakeEntity
import com.cuenote.pipeline.cards.ClipPlan
import com.cuenote.pipeline.cards.CloudIds
import com.cuenote.pipeline.cards.CueRequest
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.storage.storage
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant

/**
 * The phone's side of M3 in the cloud: put a lesson, its takes and clips in
 * Supabase, ask extract-cues for cards, and read the cards back. The
 * database's security rules decide what the student may write; this class
 * only asks.
 */
class CloudSync(private val auth: AuthRepository) {
    private val client: SupabaseClient get() = requireNotNull(auth.client) { "Sign-in isn't set up in this build" }
    private val json = Json { ignoreUnknownKeys = true }

    /** Waits for the saved session to load; null when nobody is signed in on this phone. */
    suspend fun studentId(): String? {
        val c = auth.client ?: return null
        c.auth.awaitInitialization()
        return c.auth.currentUserOrNull()?.id
    }

    /** clips/<student>/<lesson>/<file>: the student's own folder, as the storage rules require. */
    fun clipPath(studentId: String, lessonId: String, fileName: String) = "$studentId/$lessonId/$fileName"

    @Serializable
    private data class LessonRow(
        val id: String,
        @SerialName("student_id") val studentId: String,
        @SerialName("recorded_at") val recordedAt: String,
        @SerialName("duration_ms") val durationMs: Long,
        @SerialName("pipeline_state") val pipelineState: String,
        @SerialName("consent_at") val consentAt: String,
        @SerialName("taught_by") val taughtBy: String? = null,
    )

    /**
     * Creates or updates the cloud lesson. [taughtBy] is the teacher picked
     * before recording; if they are no longer one of the student's teachers
     * the lesson goes up without one, and a teacher can claim it later.
     */
    suspend fun putLesson(studentId: String, lesson: LessonEntity, taughtBy: String?) {
        fun row(t: String?) = LessonRow(
            id = lesson.id, studentId = studentId,
            recordedAt = Instant.ofEpochMilli(lesson.recordedAt).toString(),
            durationMs = lesson.durationMs, pipelineState = "uploading",
            consentAt = Instant.ofEpochMilli(lesson.consentAt).toString(), taughtBy = t,
        )
        try {
            client.from("lesson").upsert(row(taughtBy)) { onConflict = "id" }
        } catch (e: Exception) {
            if (taughtBy == null || !(e.message ?: "").contains("teachers")) throw e
            client.from("lesson").upsert(row(null)) { onConflict = "id" }
        }
    }

    /** Uploads one clip, replacing any earlier copy. */
    suspend fun putClip(path: String, file: File) {
        client.storage.from("clips").upload(path, file.readBytes()) {
            upsert = true
            contentType = ContentType.parse(ClipPlan.MIME)
        }
    }

    @Serializable
    private data class TakeRow(
        val id: String,
        @SerialName("lesson_id") val lessonId: String,
        @SerialName("start_ms") val startMs: Long,
        @SerialName("end_ms") val endMs: Long,
        @SerialName("clip_path") val clipPath: String?,
        @SerialName("pitch_contour") val pitchContour: JsonElement?,
        @SerialName("pitch_confidence") val pitchConfidence: JsonElement?,
    )

    @Serializable
    private data class IdRow(val id: String)

    /**
     * Creates or updates the lesson's takes, and removes cloud takes the
     * phone no longer finds (after "Start over"). Only the phone's own
     * columns are sent, so a song or bars set on the dashboard stay.
     */
    suspend fun putTakes(lessonId: String, takes: List<TakeEntity>, clipPath: (localId: String) -> String?) {
        val rows = takes.map {
            TakeRow(
                id = CloudIds.take(it.id), lessonId = lessonId, startMs = it.startMs, endMs = it.endMs,
                clipPath = clipPath(it.id),
                pitchContour = runCatching { json.parseToJsonElement(it.pitchContour) }.getOrNull(),
                pitchConfidence = runCatching { json.parseToJsonElement(it.pitchConfidence) }.getOrNull(),
            )
        }
        if (rows.isNotEmpty()) client.from("take").upsert(rows) { onConflict = "id" }
        val keep = rows.map { it.id }.toSet()
        val stale = client.from("take").select(Columns.list("id")) { filter { eq("lesson_id", lessonId) } }
            .decodeList<IdRow>().map { it.id }.filter { it !in keep }
        if (stale.isNotEmpty()) client.from("take").delete { filter { isIn("id", stale) } }
    }

    /** What extract-cues said: how many cards, and what it cost. */
    data class CueResult(val cards: Int, val keptTeacher: Int, val costUsd: Double?, val model: String?)

    class CueError(message: String, val retryable: Boolean) : Exception(message)

    /** Asks extract-cues to turn the lesson's feedback into cards. */
    suspend fun extractCues(request: CueRequest): CueResult {
        val response = try {
            client.functions.invoke("extract-cues") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(CueRequest.serializer(), request))
            }
        } catch (e: Exception) {
            // The library throws for error statuses; keep its message.
            val msg = e.message ?: e.toString()
            throw CueError(msg, retryable = !msg.contains("400") && !msg.contains("404"))
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val error = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
            throw CueError(error ?: "extract-cues answered ${response.status.value}", retryable = response.status.value >= 500)
        }
        val o = json.parseToJsonElement(text).jsonObject
        return CueResult(
            cards = o["cards"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            keptTeacher = o["kept_teacher"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            costUsd = o["cost_usd"]?.jsonPrimitive?.content?.toDoubleOrNull(),
            model = o["model"]?.jsonPrimitive?.content,
        )
    }

    @Serializable
    private data class FeedbackRow(
        val id: String,
        @SerialName("lesson_id") val lessonId: String,
        @SerialName("local_id") val localId: String? = null,
        @SerialName("take_id") val takeId: String? = null,
        @SerialName("span_start_ms") val spanStartMs: Long,
        @SerialName("span_end_ms") val spanEndMs: Long,
        val cue: String = "",
        val category: String? = null,
        @SerialName("her_words") val herWords: String = "",
        @SerialName("practice_flag") val practiceFlag: String? = null,
        @SerialName("bar_start") val barStart: Int? = null,
        @SerialName("bar_end") val barEnd: Int? = null,
        val transcript: String = "",
        @SerialName("updated_at") val updatedAt: String = "",
    )

    @Serializable
    private data class VersionRow(@SerialName("feedback_id") val feedbackId: String)

    /** The lesson's cards as the cloud has them now, her edits included. */
    suspend fun cards(lessonId: String): List<CardEntity> {
        val rows = client.from("feedback").select { filter { eq("lesson_id", lessonId) } }.decodeList<FeedbackRow>()
        val edited = if (rows.isEmpty()) emptySet() else client.from("card_version")
            .select(Columns.list("feedback_id")) { filter { isIn("feedback_id", rows.map { it.id }); eq("author", "teacher") } }
            .decodeList<VersionRow>().map { it.feedbackId }.toSet()
        return rows.map {
            CardEntity(
                id = it.id, lessonId = it.lessonId, localId = it.localId, takeId = it.takeId,
                spanStartMs = it.spanStartMs, spanEndMs = it.spanEndMs, cue = it.cue, category = it.category,
                herWords = it.herWords, practiceFlag = it.practiceFlag, barStart = it.barStart, barEnd = it.barEnd,
                transcript = it.transcript, editedByTeacher = it.id in edited, updatedAt = it.updatedAt,
            )
        }
    }
}
