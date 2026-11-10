package com.cuenote.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * A photo taken during a lesson, usually of her sketch. Mirrors the cloud
 * `photo_mark` table; [id] is the same uuid on the phone and in the cloud.
 *
 * Which card it belongs to starts as the pipeline's guess by time
 * ([autoFeedbackId], kept even after a manual move so we can count how often
 * the guess was right) and can be moved by the student or the teacher
 * ([linkSource]).
 */
@Entity(tableName = "photo", primaryKeys = ["id"])
data class PhotoEntity(
    val id: String,
    val lessonId: String,
    /** The original, in the lesson folder's photos/. */
    val fileName: String,
    /** Milliseconds from the start of the recording. */
    val takenAtMs: Long,
    /** The local feedback id ("<lesson>-fb-001") of the card it is on, or null. */
    val feedbackId: String? = null,
    /** The pipeline's own guess, whatever anyone did since. */
    val autoFeedbackId: String? = null,
    /** "auto" (the pipeline's guess), "student" or "teacher" (moved by hand). */
    val linkSource: String = LINK_AUTO,
    /** The straightened, whitened copy in photos/clean/, once made. */
    val cleanFileName: String? = null,
    /** The page's corners as 8 fractions of the photo (x, y for TL, TR, BR, BL), JSON; null if no page. */
    val corners: String? = null,
    /** How sure the page finder was, 0..1; 1 when the corners were set by hand. */
    val pageConfidence: Float = 0f,
    /** caption-sketch's one line, and how well the sketch matches what she said, 0..1. */
    val caption: String? = null,
    val captionConfidence: Float? = null,
    /** True when something changed that the cloud has not seen yet. */
    val needsUpload: Boolean = true,
) {
    companion object {
        const val LINK_AUTO = "auto"
        const val LINK_STUDENT = "student"
        const val LINK_TEACHER = "teacher"

        /** The photo's id: the same every time for the same lesson and file. */
        fun idFor(lessonId: String, fileName: String): String =
            UUID.nameUUIDFromBytes("cuenote:$lessonId/photos/$fileName".toByteArray()).toString()
    }
}

@Dao
interface PhotoDao {
    @Query("select * from photo where lessonId = :lessonId order by takenAtMs")
    fun observe(lessonId: String): Flow<List<PhotoEntity>>

    @Query("select * from photo where lessonId = :lessonId order by takenAtMs")
    suspend fun forLesson(lessonId: String): List<PhotoEntity>

    @Query("select * from photo where id = :id")
    suspend fun get(id: String): PhotoEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(photos: List<PhotoEntity>)

    @Query("update photo set feedbackId = :feedbackId, linkSource = :source, needsUpload = 1 where id = :id")
    suspend fun move(id: String, feedbackId: String?, source: String)

    /** The pipeline's guess: applied only while nobody has moved the photo by hand. */
    @Query(
        """update photo set autoFeedbackId = :feedbackId,
           feedbackId = case when linkSource = 'auto' then :feedbackId else feedbackId end,
           needsUpload = 1 where id = :id""",
    )
    suspend fun setGuess(id: String, feedbackId: String?)

    @Query("update photo set cleanFileName = :clean, corners = :corners, pageConfidence = :confidence, needsUpload = 1 where id = :id")
    suspend fun setClean(id: String, clean: String, corners: String?, confidence: Float)

    @Query("update photo set caption = :caption, captionConfidence = :confidence where id = :id")
    suspend fun setCaption(id: String, caption: String?, confidence: Float?)

    /** A link or caption that came from the cloud (her move on the dashboard): nothing new to send back. */
    @Query("update photo set feedbackId = :feedbackId, linkSource = :source, caption = :caption, captionConfidence = :confidence where id = :id")
    suspend fun applyCloud(id: String, feedbackId: String?, source: String, caption: String?, confidence: Float?)

    /** The phone's feedback ids for a lesson, to map her dashboard moves back. */
    @Query("select id from feedback where lessonId = :lessonId")
    suspend fun feedbackIds(lessonId: String): List<String>

    /** What was said around a photo, for its caption. */
    @Query("select * from transcript_line where lessonId = :lessonId and endMs >= :fromMs and startMs <= :toMs order by startMs")
    suspend fun linesBetween(lessonId: String, fromMs: Long, toMs: Long): List<TranscriptLineEntity>

    @Query("update photo set needsUpload = 0 where id = :id")
    suspend fun uploaded(id: String)

    @Query("delete from photo where lessonId = :lessonId")
    suspend fun clear(lessonId: String)
}
