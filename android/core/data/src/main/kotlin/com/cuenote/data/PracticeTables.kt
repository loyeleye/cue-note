package com.cuenote.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/*
 * M5: practice between lessons. A session is one sitting of practice; each
 * take is one go at a card. Both stay on the phone (the design doc's
 * "practice takes and logs stay on the phone by default"); sharing comes
 * with the practice log and the next lesson's agenda.
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
)

@Dao
interface PracticeDao {
    @Query("select * from practice_take where cardId = :cardId order by recordedAt desc")
    fun observeTakes(cardId: String): Flow<List<PracticeTakeEntity>>

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
}
