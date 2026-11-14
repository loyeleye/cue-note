package com.cuenote.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * A photo taken during a lesson: a sketch of the teacher's, or a photo of the
 * student themself ([kind] "me"), say of the mouth shape the teacher asked for.
 * A photo of me is kept as taken (no clean-up, no AI caption) and stays on the
 * phone unless the student [shared] it. Mirrors the cloud
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
    /** "sketch" or "me". */
    @ColumnInfo(defaultValue = "sketch") val kind: String = KIND_SKETCH,
    /** A photo of me the student chose to share with their teacher. Sketches are always shared. */
    @ColumnInfo(defaultValue = "0") val shared: Boolean = false,
    /** The student's own line on what to notice in a photo of me. */
    val note: String? = null,
    /** Deleted by the student: hidden now, gone once any cloud copy is removed. */
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
) {
    val isMe: Boolean get() = kind == KIND_ME
    /** Whether the cloud should have this photo: every sketch, and a photo of me only when shared. */
    val goesToCloud: Boolean get() = !deleted && (!isMe || shared)

    companion object {
        const val LINK_AUTO = "auto"
        const val LINK_STUDENT = "student"
        const val LINK_TEACHER = "teacher"
        const val KIND_SKETCH = "sketch"
        const val KIND_ME = "me"

        /** The photo's id: the same every time for the same lesson and file. */
        fun idFor(lessonId: String, fileName: String): String =
            UUID.nameUUIDFromBytes("cuenote:$lessonId/photos/$fileName".toByteArray()).toString()
    }
}

@Dao
interface PhotoDao {
    @Query("select * from photo where lessonId = :lessonId and deleted = 0 order by takenAtMs")
    fun observe(lessonId: String): Flow<List<PhotoEntity>>

    /** Photos of me on the cards with these local feedback ids, for practice. */
    @Query("select * from photo where feedbackId in (:feedbackIds) and kind = 'me' and deleted = 0 order by takenAtMs")
    fun observeMeOn(feedbackIds: List<String>): Flow<List<PhotoEntity>>

    /** Sketch or me. Changing it means the cloud has to catch up (upload, or take it down). */
    @Query("update photo set kind = :kind, shared = 0, needsUpload = 1 where id = :id")
    suspend fun setKind(id: String, kind: String)

    @Query("update photo set shared = :shared, needsUpload = 1 where id = :id")
    suspend fun setShared(id: String, shared: Boolean)

    @Query("update photo set note = :note, needsUpload = 1 where id = :id")
    suspend fun setNote(id: String, note: String?)

    @Query("update photo set deleted = 1, needsUpload = 1 where id = :id")
    suspend fun markDeleted(id: String)

    @Query("delete from photo where id = :id")
    suspend fun remove(id: String)

    /** The student deleted the part of the lesson these blocks were in: the photos stay, on no card. */
    @Query("update photo set feedbackId = null where lessonId = :lessonId and feedbackId in (:feedbackIds)")
    suspend fun unlinkFeedback(lessonId: String, feedbackIds: List<String>)

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

    /** The lesson's copy online is gone: its photos count as not uploaded, and lose the AI's captions. */
    @Query("update photo set needsUpload = 1, caption = null, captionConfidence = null where lessonId = :lessonId")
    suspend fun forgetCloud(lessonId: String)

    @Query("delete from photo where lessonId = :lessonId")
    suspend fun clear(lessonId: String)
}
