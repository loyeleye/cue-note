package com.cuenote.data.sketch

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cuenote.data.AuthRepository
import com.cuenote.data.LessonDatabase
import com.cuenote.data.PhotoDao
import com.cuenote.data.PhotoEntity
import com.cuenote.pipeline.cards.CloudIds
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.storage.storage
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sends a lesson's photos to the cloud once the lesson and its cards are
 * there, asks caption-sketch for a caption for each new one, and brings back
 * any move she made on the dashboard.
 *
 * Files: photos/<student>/<lesson>/<photo id>.jpg (the original) and
 * <photo id>.clean.jpg (straightened and whitened), in the private photos
 * bucket. A photo whose card is not in the cloud yet goes up without a card
 * and is linked on a later run.
 */
class PhotoUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val lessonId = inputData.getString(KEY_LESSON) ?: return Result.failure()
        val client = AuthRepository.get().client ?: return Result.success()   // this build has no cloud
        val student = client.auth.currentUserOrNull()?.id ?: return Result.success()  // signed out: photos stay on the phone
        val db = LessonDatabase.get(applicationContext)
        val lesson = db.lessons().get(lessonId) ?: return Result.success()
        val dao = db.photos()
        val dir = File(lesson.dirPath)

        return try {
            // The lesson row comes first (the cards' upload makes it); until then, wait.
            val inCloud = client.from("lesson").select(Columns.list("id")) { filter { eq("id", lessonId) } }
                .decodeList<IdRow>().isNotEmpty()
            if (!inCloud) return giveUpLater()

            val cloudCards = client.from("feedback").select(Columns.list("id")) { filter { eq("lesson_id", lessonId) } }
                .decodeList<IdRow>().map { it.id }.toSet()
            pull(client, dao, lessonId)

            var waitingForCards = false
            val bucket = client.storage.from("photos")
            for (p in dao.forLesson(lessonId)) {
                if (isStopped) return Result.retry()
                val cardId = p.feedbackId?.let(CloudIds::feedback)
                if (cardId != null && cardId !in cloudCards) waitingForCards = true
                if (!p.needsUpload && (cardId == null || cardId in cloudCards)) continue

                val base = "$student/$lessonId/${p.id}"
                val original = File(dir, "photos/${p.fileName}")
                if (!original.isFile) continue
                bucket.upload("$base.jpg", original.readBytes()) { upsert = true }
                val clean = p.cleanFileName?.let { File(dir, "photos/clean/$it") }?.takeIf { it.isFile }
                if (clean != null) bucket.upload("$base.clean.jpg", clean.readBytes()) { upsert = true }

                client.from("photo_mark").upsert(
                    PhotoRow(
                        id = p.id,
                        lessonId = lessonId,
                        takenAtMs = p.takenAtMs,
                        feedbackId = cardId?.takeIf { it in cloudCards },
                        autoFeedbackId = p.autoFeedbackId?.let(CloudIds::feedback)?.takeIf { it in cloudCards },
                        linkSource = p.linkSource,
                        imagePath = "$base.jpg",
                        cleanedPath = clean?.let { "$base.clean.jpg" },
                        corners = p.corners?.let { Json.parseToJsonElement(it) },
                        pageConfidence = p.pageConfidence,
                    ),
                )
                if (cardId == null || cardId in cloudCards) dao.uploaded(p.id)

                if (p.caption == null) caption(client, dao, p)
            }
            if (waitingForCards) giveUpLater() else Result.success()
        } catch (e: Exception) {
            Log.w("PhotoUpload", "upload failed for $lessonId", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    /** The cards are not in the cloud yet: try again later, for about a day. */
    private fun giveUpLater(): Result = if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()

    /** Asks caption-sketch for a one-line caption from the sketch and what was said around it. */
    private suspend fun caption(client: io.github.jan.supabase.SupabaseClient, dao: PhotoDao, p: PhotoEntity) {
        val lines = dao.linesBetween(p.lessonId, p.takenAtMs - WORDS_BEFORE_MS, p.takenAtMs + WORDS_AFTER_MS)
        val words = lines.joinToString("\n") { (if (it.speaker == "teacher") "Teacher: " else "Student: ") + it.text }
        runCatching {
            val res = client.functions.invoke("caption-sketch", body = CaptionRequest(p.id, words))
            val out = json.decodeFromString(CaptionResponse.serializer(), res.bodyAsText())
            if (out.caption != null) dao.setCaption(p.id, out.caption, out.confidence)
        }.onFailure { Log.w("PhotoUpload", "caption failed for ${p.id}", it) }
    }

    @Serializable private data class IdRow(val id: String)

    @Serializable
    private data class PhotoRow(
        val id: String,
        @SerialName("lesson_id") val lessonId: String,
        @SerialName("taken_at_ms") val takenAtMs: Long,
        @SerialName("feedback_id") val feedbackId: String?,
        @SerialName("auto_feedback_id") val autoFeedbackId: String?,
        @SerialName("link_source") val linkSource: String,
        @SerialName("image_path") val imagePath: String,
        @SerialName("cleaned_path") val cleanedPath: String?,
        val corners: JsonElement?,
        @SerialName("page_confidence") val pageConfidence: Float,
    )

    @Serializable
    private data class CloudPhoto(
        val id: String,
        @SerialName("feedback_id") val feedbackId: String? = null,
        @SerialName("link_source") val linkSource: String = PhotoEntity.LINK_AUTO,
        val caption: String? = null,
        val confidence: Float? = null,
    )

    @Serializable private data class CaptionRequest(@SerialName("photo_mark_id") val photoMarkId: String, val words: String)
    @Serializable private data class CaptionResponse(val caption: String? = null, val confidence: Float? = null)

    companion object {
        const val KEY_LESSON = "lessonId"
        private const val MAX_ATTEMPTS = 12
        /** The words around a photo that go to caption-sketch: she usually explains, then sketches. */
        const val WORDS_BEFORE_MS = 60_000L
        const val WORDS_AFTER_MS = 20_000L
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Brings her dashboard moves and the captions down to the phone. A move
         * she made wins over the phone's guess; the student's own move is kept
         * until it is uploaded.
         */
        suspend fun pull(client: io.github.jan.supabase.SupabaseClient, dao: PhotoDao, lessonId: String) {
            val rows = client.from("photo_mark").select(Columns.list("id", "feedback_id", "link_source", "caption", "confidence")) {
                filter { eq("lesson_id", lessonId) }
            }.decodeList<CloudPhoto>()
            val local = dao.forLesson(lessonId).associateBy { it.id }
            val byCloud = dao.feedbackIds(lessonId).associateBy(CloudIds::feedback)
            for (r in rows) {
                val p = local[r.id] ?: continue
                if (p.needsUpload) {
                    if (r.caption != null && p.caption == null) dao.setCaption(p.id, r.caption, r.confidence)
                    continue
                }
                val fb = if (r.linkSource == PhotoEntity.LINK_TEACHER) r.feedbackId?.let(byCloud::get) else p.feedbackId
                val source = if (r.linkSource == PhotoEntity.LINK_TEACHER) r.linkSource else p.linkSource
                dao.applyCloud(p.id, fb, source, r.caption ?: p.caption, r.confidence ?: p.captionConfidence)
            }
        }

        /**
         * Queues the upload on the lesson's own photo chain ("photos-<id>").
         * Until the lesson and its cards are in the cloud it waits and tries
         * again, and the card upload [kick]s it once they are. It used to join
         * the card chain, where a photo waiting for cards held up "Make my
         * notes" behind it for up to a day.
         */
        fun enqueue(context: Context, lessonId: String) = enqueue(context, lessonId, ExistingWorkPolicy.APPEND_OR_REPLACE)

        /** Runs the lesson's photo upload now, dropping one that is waiting out a retry. */
        fun kick(context: Context, lessonId: String) = enqueue(context, lessonId, ExistingWorkPolicy.REPLACE)

        private fun enqueue(context: Context, lessonId: String, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<PhotoUploadWorker>()
                .setInputData(workDataOf(KEY_LESSON to lessonId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .addTag("photo-upload")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("photos-$lessonId", policy, request)
        }
    }
}
