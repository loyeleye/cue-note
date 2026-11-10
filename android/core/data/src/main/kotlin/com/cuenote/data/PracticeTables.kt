package com.cuenote.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/*
 * M5: practice between lessons. A session is one sitting of practice; each
 * take is one go at a card. Both stay on the phone (the design doc's
 * "practice takes and logs stay on the phone by default"). Only what the
 * student shares goes up: the log summary, takes they pick for their
 * teacher, and their questions (PracticeSyncWorker).
 */

/** One sitting of practice. A new take within [PracticeRepository.SESSION_GAP_MS] of the last joins it. */
@Entity(tableName = "practice_session", primaryKeys = ["id"])
data class PracticeSessionEntity(
    val id: String,
    /** Epoch ms of the first take. */
    val startedAt: Long,
    /** Epoch ms the last take ended. */
    val endedAt: Long,
)

/** One practice take of a card, saved as a 48 kHz mono WAV on the phone. */
@Entity(tableName = "practice_take", primaryKeys = ["id"])
data class PracticeTakeEntity(
    val id: String,
    val sessionId: String,
    /** The card (feedback.id) this take works on. */
    val cardId: String,
    val lessonId: String,
    val file: String,
    /** Epoch ms the take started. */
    val recordedAt: Long,
    val durationMs: Long,
    /** "phone" or "lesson_mic". */
    val input: String,
    /** "wired", "bluetooth" or "speaker". */
    val listening: String,
    /** The headphone delay from the timing check, when one applied. */
    val latencyMs: Int?,
    /** The take's frame where any backing started playing, if one did. */
    val playbackStartFrame: Long?,
    /** How it felt, in the student's words. */
    val feelingNote: String?,
    /** True once the student shares it with their teacher. */
    val shared: Boolean,
    /** True while the cloud holds it as shared; differs from [shared] until the next sync. */
    @ColumnInfo(defaultValue = "0") val cloudShared: Boolean = false,
    /** Epoch ms a teacher listened to the shared take, from the cloud. */
    val heardAt: Long? = null,
    /** Deleted on the phone, still to be taken out of the cloud. Its file is already gone. */
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

/**
 * A question for the teacher, pinned to a card (and maybe a bar), or about
 * nothing in particular. Asked on the phone, answered on the dashboard.
 */
@Entity(tableName = "practice_question", primaryKeys = ["id"])
data class PracticeQuestionEntity(
    /** Also its id in the cloud. */
    val id: String,
    val cardId: String?,
    val lessonId: String?,
    val bar: Int?,
    val body: String,
    /** "open", "answered" or "dropped". */
    val status: String,
    /** The teacher's answer in words, if they wrote one. */
    val answer: String?,
    val answeredAt: Long?,
    val createdAt: Long,
    /** Changed on the phone since the last sync. */
    val dirty: Boolean,
) {
    val open: Boolean get() = status == "open"
    val answered: Boolean get() = status == "answered"
}

@Dao
interface PracticeDao {
    @Query("select * from practice_take where cardId = :cardId and deleted = 0 order by recordedAt desc")
    fun observeTakes(cardId: String): Flow<List<PracticeTakeEntity>>

    /** Shared takes the teacher hasn't heard yet, across every card. */
    @Query("select * from practice_take where shared = 1 and deleted = 0 and heardAt is null order by recordedAt desc")
    fun observeWaiting(): Flow<List<PracticeTakeEntity>>

    @Query("select * from practice_take where deleted = 0 and recordedAt >= :since order by recordedAt desc")
    fun observeTakesSince(since: Long): Flow<List<PracticeTakeEntity>>

    @Query("select * from practice_take where deleted = 0 and sessionId in (:sessions)")
    suspend fun takesInSessions(sessions: List<String>): List<PracticeTakeEntity>

    /** Takes whose sharing the cloud doesn't know about yet. */
    @Query("select * from practice_take where deleted = 1 or shared != cloudShared or shared = 1")
    suspend fun takesToSync(): List<PracticeTakeEntity>

    @Query("update practice_take set cloudShared = :cloudShared, heardAt = :heardAt where id = :id")
    suspend fun markSynced(id: String, cloudShared: Boolean, heardAt: Long?)

    @Query("update practice_take set shared = :shared where id = :id")
    suspend fun setShared(id: String, shared: Boolean)

    @Query("update practice_take set deleted = 1 where id = :id")
    suspend fun markDeleted(id: String)

    @Query("select * from practice_take where id = :id")
    suspend fun take(id: String): PracticeTakeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTake(t: PracticeTakeEntity)

    @Query("delete from practice_take where id = :id") suspend fun deleteTake(id: String)

    @Query("select * from practice_take where lessonId = :lessonId") suspend fun takesForLesson(lessonId: String): List<PracticeTakeEntity>

    @Query("delete from practice_take where lessonId = :lessonId") suspend fun deleteLessonTakes(lessonId: String)

    @Query("select * from practice_session order by endedAt desc limit 1")
    suspend fun lastSession(): PracticeSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSession(s: PracticeSessionEntity)

    @Query("select * from practice_session order by startedAt desc")
    fun observeSessions(): Flow<List<PracticeSessionEntity>>

    @Query("select * from practice_session where startedAt >= :since")
    suspend fun sessionsSince(since: Long): List<PracticeSessionEntity>

    @Query("select * from practice_question where status != 'dropped' order by createdAt desc")
    fun observeQuestions(): Flow<List<PracticeQuestionEntity>>

    @Query("select * from practice_question where id = :id") suspend fun question(id: String): PracticeQuestionEntity?

    @Query("select * from practice_question") suspend fun questions(): List<PracticeQuestionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertQuestion(q: PracticeQuestionEntity)

    @Query("delete from practice_question where lessonId = :lessonId") suspend fun deleteLessonQuestions(lessonId: String)
}
