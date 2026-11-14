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

/**
 * One sitting of suggested practice: how the voice felt, the plan the phone
 * made from the teacher's notes, and how far the student got. Kept on the
 * phone; [synced] marks whether the teacher's log has it yet.
 */
@Entity(tableName = "practice_plan", primaryKeys = ["id"])
data class PracticePlanEntity(
    val id: String,
    /** The student's local day, yyyy-MM-dd, for "Done today" and picking up where they left off. */
    val day: String,
    val startedAt: Long,
    val updatedAt: Long,
    /** Set once the sitting ended, finished or not. */
    val endedAt: Long?,
    /** "FINE", "TIRED" or "HOARSE". */
    val voice: String,
    /** The length picked. */
    val minutes: Int,
    /** The plan's steps, as JSON (List<PlanStep>). */
    val steps: String,
    /** The step the student is on. */
    val stepIndex: Int,
    /** Steps done (Next), and the indexes skipped, as a JSON array. */
    val done: Int,
    val skipped: String,
    /** "Still feel easy?" has been asked. */
    val easyChecked: Boolean,
    val synced: Boolean = false,
)

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
    /** The teacher's reply in words, from the cloud. */
    val reply: String? = null,
    /** The teacher's voice reply: its path in the clips bucket (…/teacher-notes/…). */
    val replyClipPath: String? = null,
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
    /** The teacher's voice answer: its path in the clips bucket (…/teacher-notes/…). */
    val answerClipPath: String? = null,
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

    @Query("update practice_take set reply = :reply, replyClipPath = :clip where id = :id")
    suspend fun setReply(id: String, reply: String?, clip: String?)

    /** Takes the teacher replied to, newest first. */
    @Query("select * from practice_take where deleted = 0 and (reply is not null or replyClipPath is not null) order by recordedAt desc limit :limit")
    fun observeReplied(limit: Int = 10): Flow<List<PracticeTakeEntity>>

    @Query("update practice_take set shared = :shared where id = :id")
    suspend fun setShared(id: String, shared: Boolean)

    @Query("update practice_take set deleted = 1 where id = :id")
    suspend fun markDeleted(id: String)

    @Query("select * from practice_take where id = :id")
    suspend fun take(id: String): PracticeTakeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTake(t: PracticeTakeEntity)

    @Query("delete from practice_take where id = :id") suspend fun deleteTake(id: String)

    @Query("select * from practice_take where lessonId = :lessonId") suspend fun takesForLesson(lessonId: String): List<PracticeTakeEntity>

    @Query("select * from practice_take where cardId in (:cardIds) and deleted = 0") suspend fun takesForCards(cardIds: List<String>): List<PracticeTakeEntity>

    @Query("delete from practice_take where lessonId = :lessonId") suspend fun deleteLessonTakes(lessonId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPlan(p: PracticePlanEntity)

    @Query("select * from practice_plan where id = :id") suspend fun plan(id: String): PracticePlanEntity?

    /** Today's latest sitting of suggested practice, if any. */
    @Query("select * from practice_plan where day = :day order by startedAt desc limit 1")
    fun observeLatestPlan(day: String): Flow<PracticePlanEntity?>

    @Query("select * from practice_plan where startedAt >= :since order by startedAt")
    suspend fun plansSince(since: Long): List<PracticePlanEntity>

    @Query("select * from practice_plan where synced = 0 and endedAt is not null")
    suspend fun plansToSync(): List<PracticePlanEntity>

    @Query("update practice_plan set synced = 1 where id = :id and updatedAt = :updatedAt")
    suspend fun markPlanSynced(id: String, updatedAt: Long)

    /** When each card last had a practice take, for spacing the plan. */
    @Query("select cardId, max(recordedAt) as at from practice_take where deleted = 0 group by cardId")
    suspend fun lastTakes(): List<LastTake>

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

/** The latest practice take of a card. */
data class LastTake(val cardId: String, val at: Long)
