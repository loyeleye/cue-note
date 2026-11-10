package com.cuenote.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/*
 * M3: the replay clips the phone cuts from a lesson, and the cards that come
 * back from the cloud. Both are keyed by cloud ids (com.cuenote.pipeline
 * .cards.CloudIds), which are derived from the pipeline's own ids.
 */

/** A clip of one take or feedback block, cut for replay and upload. */
@Entity(tableName = "clip", primaryKeys = ["id"])
data class ClipEntity(
    /** The cloud id of the take or block; also the clip's file name. */
    val id: String,
    val lessonId: String,
    /** "take" or "feedback". */
    val kind: String,
    /** The pipeline's id of the take or block. */
    val localId: String,
    val startMs: Long,
    val endMs: Long,
    /** The .m4a file on the phone. */
    val file: String,
    /** clips/<student>/<lesson>/<file> once uploaded, else null. */
    val storagePath: String?,
)

/**
 * A card: one feedback block with her cue. Mirrors the cloud feedback row,
 * which extract-cues writes and the teacher edits; the phone only reads it.
 */
@Entity(tableName = "card", primaryKeys = ["id"])
data class CardEntity(
    /** feedback.id in the cloud. */
    val id: String,
    val lessonId: String,
    /** The pipeline's id of the block ("<lesson>-fb-001"), when the phone made it. */
    val localId: String?,
    /** The cloud id of the take the card is about, or null. */
    val takeId: String?,
    val spanStartMs: Long,
    val spanEndMs: Long,
    val cue: String,
    val category: String?,
    val herWords: String,
    /** "practice_this", "just_listen" or null. */
    val practiceFlag: String?,
    val barStart: Int?,
    val barEnd: Int?,
    /** Everything she said in the block, as transcribed. */
    val transcript: String,
    /** True once a teacher has saved a version of this card. */
    val editedByTeacher: Boolean,
    /** feedback.updated_at, as the server wrote it. */
    val updatedAt: String,
)

/** How many cards a lesson has, and how many she marked to practice. */
data class CardCount(val lessonId: String, val cards: Int, val practice: Int)

/** A card with its lesson's date and the two clips a setlist row plays. */
data class SetlistRow(
    @Embedded val card: CardEntity,
    val recordedAt: Long,
    /** The teacher's words: the clip of the feedback block, if cut. */
    val wordsFile: String?,
    /** The student's take the card is about, if cut. */
    val takeFile: String?,
)

@Dao
interface CardDao {
    /** Every card with a cue, newest lesson first; the setlist is picked from these. */
    @Query(
        """select card.*, lesson.recordedAt as recordedAt,
           (select file from clip where clip.id = card.id) as wordsFile,
           (select file from clip where clip.id = card.takeId) as takeFile
           from card join lesson on lesson.id = card.lessonId
           where card.cue != ''
           order by lesson.recordedAt desc, card.spanStartMs""",
    )
    fun observeWithLessons(): Flow<List<SetlistRow>>

    /** One card with its lesson's date and clips, for the practice screen. */
    @Query(
        """select card.*, lesson.recordedAt as recordedAt,
           (select file from clip where clip.id = card.id) as wordsFile,
           (select file from clip where clip.id = card.takeId) as takeFile
           from card join lesson on lesson.id = card.lessonId
           where card.id = :cardId""",
    )
    fun observeRow(cardId: String): Flow<SetlistRow?>

    @Query(
        "select lessonId, count(*) as cards, " +
            "sum(case when practiceFlag = 'practice_this' then 1 else 0 end) as practice " +
            "from card group by lessonId",
    )
    fun observeCounts(): Flow<List<CardCount>>

    @Query("select * from card where lessonId = :lessonId order by spanStartMs")
    fun observeCards(lessonId: String): Flow<List<CardEntity>>

    @Query("select * from clip where lessonId = :lessonId order by startMs")
    fun observeClips(lessonId: String): Flow<List<ClipEntity>>

    @Query("select * from clip where lessonId = :lessonId order by startMs")
    suspend fun clips(lessonId: String): List<ClipEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertClip(c: ClipEntity)

    @Query("update clip set storagePath = :path where id = :id") suspend fun setUploaded(id: String, path: String)

    @Query("delete from clip where lessonId = :lessonId") suspend fun clearClips(lessonId: String)

    @Query("select * from clip where id in (:ids)") suspend fun clipsById(ids: List<String>): List<ClipEntity>

    @Query("delete from clip where id in (:ids)") suspend fun deleteClips(ids: List<String>)

    @Query("delete from card where lessonId = :lessonId") suspend fun clearCards(lessonId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCards(c: List<CardEntity>)

    /** Replaces a lesson's cards with what the cloud has now. */
    @Transaction
    suspend fun replaceCards(lessonId: String, cards: List<CardEntity>) {
        clearCards(lessonId)
        insertCards(cards)
    }

    @Transaction
    suspend fun clearAll(lessonId: String) {
        clearClips(lessonId)
        clearCards(lessonId)
    }
}
