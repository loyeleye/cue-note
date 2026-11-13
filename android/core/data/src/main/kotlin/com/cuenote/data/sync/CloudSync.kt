package com.cuenote.data.sync

import com.cuenote.data.AuthRepository
import com.cuenote.data.CardEntity
import com.cuenote.data.ExerciseEntity
import com.cuenote.data.LessonEntity
import com.cuenote.data.LessonTeacher
import com.cuenote.data.TakeEntity
import com.cuenote.pipeline.cards.ClipPlan
import com.cuenote.pipeline.cards.CloudIds
import com.cuenote.pipeline.cards.CueRequest
import com.cuenote.pipeline.cards.ModelTrials
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
        @SerialName("teacher_name") val teacherName: String? = null,
    )

    /**
     * Creates or updates the cloud lesson. [teacher] is who was picked before
     * recording (or since); if a picked teacher is no longer one of the
     * student's teachers the lesson goes up without one, and a teacher can
     * claim it later.
     */
    suspend fun putLesson(studentId: String, lesson: LessonEntity, teacher: LessonTeacher) {
        fun row(t: LessonTeacher) = LessonRow(
            id = lesson.id, studentId = studentId,
            recordedAt = Instant.ofEpochMilli(lesson.recordedAt).toString(),
            durationMs = lesson.durationMs, pipelineState = "uploading",
            consentAt = Instant.ofEpochMilli(lesson.consentAt).toString(),
            taughtBy = t.teacherId, teacherName = t.name,
        )
        try {
            client.from("lesson").upsert(row(teacher)) { onConflict = "id" }
        } catch (e: Exception) {
            if (teacher.teacherId == null || !(e.message ?: "").contains("teachers")) throw e
            client.from("lesson").upsert(row(LessonTeacher.NONE)) { onConflict = "id" }
        }
    }

    @Serializable
    private data class TeacherChange(
        @SerialName("taught_by") val taughtBy: String?,
        @SerialName("teacher_name") val teacherName: String?,
    )

    /**
     * Changes who taught a lesson that's already up. A lesson not uploaded
     * yet matches nothing here, and takes the phone's pick when it goes up.
     */
    suspend fun setLessonTeacher(lessonId: String, teacher: LessonTeacher) {
        client.from("lesson").update(TeacherChange(teacher.teacherId, teacher.name)) { filter { eq("id", lessonId) } }
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
        @SerialName("exercise_id") val exerciseId: String? = null,
    )

    @Serializable
    private data class ExerciseRow(
        val id: String,
        @SerialName("lesson_id") val lessonId: String,
        @SerialName("start_ms") val startMs: Long,
        @SerialName("end_ms") val endMs: Long,
        val kind: String,
        val reason: String,
    )

    /**
     * Creates or updates the lesson's exercises and removes ones the phone no
     * longer finds. A title given on the dashboard stays, since it isn't sent.
     */
    suspend fun putExercises(lessonId: String, exercises: List<ExerciseEntity>) {
        val rows = exercises.filter { it.endMs > it.startMs }.map {
            ExerciseRow(CloudIds.exercise(it.id), lessonId, it.startMs, it.endMs, it.kind, it.reason)
        }
        if (rows.isNotEmpty()) client.from("exercise").upsert(rows) { onConflict = "id" }
        val keep = rows.map { it.id }.toSet()
        val stale = client.from("exercise").select(Columns.list("id")) { filter { eq("lesson_id", lessonId) } }
            .decodeList<IdRow>().map { it.id }.filter { it !in keep }
        if (stale.isNotEmpty()) client.from("exercise").delete { filter { isIn("id", stale) } }
    }

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
                exerciseId = it.exerciseId?.let(CloudIds::exercise),
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
        val o = json.parseToJsonElement(callExtractCues(request)).jsonObject
        return CueResult(
            cards = o["cards"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            keptTeacher = o["kept_teacher"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            costUsd = o["cost_usd"]?.jsonPrimitive?.content?.toDoubleOrNull(),
            model = o["model"]?.jsonPrimitive?.content,
        )
    }

    /** Runs the lesson through each of [models] and returns their cards; writes no cards. Returns the raw JSON too. */
    suspend fun compareModels(request: CueRequest, models: List<String>): Pair<ModelTrials, String> {
        val text = callExtractCues(request.copy(trialModels = models))
        return json.decodeFromString(ModelTrials.serializer(), text) to text
    }

    private suspend fun callExtractCues(request: CueRequest): String {
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
        return text
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
        @SerialName("audio_removed_at") val audioRemovedAt: String? = null,
        val summary: String = "",
        val weight: Float? = null,
    )

    @Serializable
    private data class VersionRow(@SerialName("feedback_id") val feedbackId: String)

    @Serializable
    private data class RemovedRow(@SerialName("feedback_id") val feedbackId: String)

    @Serializable
    private data class MutedRow(val id: String, @SerialName("audio_removed_at") val audioRemovedAt: String? = null)

    @Serializable
    private data class ClipRow(@SerialName("clip_path") val clipPath: String? = null)

    /**
     * Cards whose clip must not go up again: ones a teacher deleted, and ones
     * whose audio a teacher removed. Matters when a lesson is processed again.
     */
    suspend fun removedClipIds(lessonId: String): Set<String> {
        val gone = client.from("feedback_removed").select(Columns.list("feedback_id")) { filter { eq("lesson_id", lessonId) } }
            .decodeList<RemovedRow>().map { it.feedbackId }
        val muted = client.from("feedback").select(Columns.list("id", "audio_removed_at")) { filter { eq("lesson_id", lessonId) } }
            .decodeList<MutedRow>().filter { it.audioRemovedAt != null }.map { it.id }
        return (gone + muted).toSet()
    }

    /**
     * Deletes a lesson online: its clips and photos, the teacher voice notes
     * left on it, then the lesson row, which takes its takes, cards, versions
     * and notes with it. Throws if any step fails, so the caller can keep the
     * phone's copy and say so.
     */
    suspend fun deleteLesson(studentId: String, lessonId: String) {
        val exists = client.from("lesson").select(Columns.list("id")) { filter { eq("id", lessonId) } }
            .decodeList<IdRow>().isNotEmpty()
        if (!exists) return
        val folder = "$studentId/$lessonId"
        for (name in listOf("clips", "photos")) {
            val bucket = client.storage.from(name)
            while (true) {
                val page = bucket.list(folder) { limit = 500 }.map { "$folder/${it.name}" }
                if (page.isEmpty()) break
                bucket.delete(page)
                if (page.size < 500) break
            }
        }
        val notes = client.from("comment").select(Columns.list("clip_path")) { filter { eq("lesson_id", lessonId) } }
            .decodeList<ClipRow>().mapNotNull { it.clipPath }
        if (notes.isNotEmpty()) client.storage.from("clips").delete(notes)
        client.from("lesson").delete { filter { eq("id", lessonId) } }
    }

    @Serializable
    private data class TitleRow(val id: String, val title: String? = null)

    /** The names the card writer or the dashboard gave the lesson's exercises, by cloud id. */
    suspend fun exerciseTitles(lessonId: String): Map<String, String> =
        client.from("exercise").select(Columns.list("id", "title")) { filter { eq("lesson_id", lessonId) } }
            .decodeList<TitleRow>().mapNotNull { r -> r.title?.takeIf { it.isNotBlank() }?.let { r.id to it } }.toMap()

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
                summary = it.summary, weight = it.weight,
            )
        }
    }
}
