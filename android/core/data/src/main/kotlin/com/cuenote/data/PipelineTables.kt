package com.cuenote.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/*
 * What the after-lesson pipeline produced, mirrored into Room so the app can
 * show it and, in M3, upload it. take and feedback match the cloud tables;
 * segment, transcript_line and pipeline_stage are phone-only, as in the
 * design doc's data model.
 */

/** One of the five labels over a stretch of the lesson. Phone only. */
@Entity(tableName = "segment", primaryKeys = ["lessonId", "startMs"])
data class SegmentEntity(
    val lessonId: String,
    val startMs: Long,
    val endMs: Long,
    val label: String,
)

/** The student singing. Mirrors the cloud `take` table (clip_path arrives in M3). */
@Entity(tableName = "take", primaryKeys = ["id"])
data class TakeEntity(
    val id: String,
    val lessonId: String,
    val startMs: Long,
    val endMs: Long,
    /** Same JSON shapes as the cloud columns: {"hop_ms":32,"hz":[...|null]} and a 0..1 array per frame. */
    val pitchContour: String,
    val pitchConfidence: String,
    val withPiano: Boolean,
)

/**
 * The teacher's feedback block. The cue, category and her_words columns of
 * the cloud table come from extract-cues in M3; here it carries the link and
 * the raw words.
 */
@Entity(tableName = "feedback", primaryKeys = ["id"])
data class FeedbackEntity(
    val id: String,
    val lessonId: String,
    val takeId: String?,
    val spanStartMs: Long,
    val spanEndMs: Long,
    val linkConfidence: Float,
    val linkReason: String,
    /** JSON list of demos: start, end, and the phrase of the take each one models. */
    val demos: String,
    /** The teacher's words in this block, as transcribed. */
    val words: String,
)

@Entity(tableName = "transcript_line", primaryKeys = ["lessonId", "startMs", "speaker"])
data class TranscriptLineEntity(
    val lessonId: String,
    val startMs: Long,
    val endMs: Long,
    val speaker: String,
    val text: String,
)

/** One row per pipeline stage per lesson: what ran, how long it took, what went wrong. */
@Entity(tableName = "pipeline_stage", primaryKeys = ["lessonId", "stage"])
data class PipelineStageEntity(
    val lessonId: String,
    val stage: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long,
    val detail: String,
)

@Dao
interface PipelineDao {
    @Query("select * from pipeline_stage where lessonId = :lessonId order by startedAt")
    fun observeStages(lessonId: String): Flow<List<PipelineStageEntity>>

    @Query("select * from take where lessonId = :lessonId order by startMs")
    fun observeTakes(lessonId: String): Flow<List<TakeEntity>>

    @Query("select * from feedback where lessonId = :lessonId order by spanStartMs")
    fun observeFeedback(lessonId: String): Flow<List<FeedbackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertStage(s: PipelineStageEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSegments(s: List<SegmentEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTakes(t: List<TakeEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertFeedback(f: List<FeedbackEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLines(l: List<TranscriptLineEntity>)

    @Query("delete from segment where lessonId = :id") suspend fun clearSegments(id: String)
    @Query("delete from take where lessonId = :id") suspend fun clearTakes(id: String)
    @Query("delete from feedback where lessonId = :id") suspend fun clearFeedback(id: String)
    @Query("delete from transcript_line where lessonId = :id") suspend fun clearLines(id: String)
    @Query("delete from pipeline_stage where lessonId = :id") suspend fun clearStages(id: String)

    @Query("update lesson set pipelineState = :state where id = :id")
    suspend fun setLessonState(id: String, state: String)

    /** Replaces a lesson's pipeline results in one go. */
    @Transaction
    suspend fun replaceResults(
        lessonId: String,
        segments: List<SegmentEntity>,
        takes: List<TakeEntity>,
        feedback: List<FeedbackEntity>,
        lines: List<TranscriptLineEntity>,
    ) {
        clearSegments(lessonId); clearTakes(lessonId); clearFeedback(lessonId); clearLines(lessonId)
        insertSegments(segments); insertTakes(takes); insertFeedback(feedback); insertLines(lines)
    }

    @Transaction
    suspend fun clearAll(lessonId: String) {
        clearSegments(lessonId); clearTakes(lessonId); clearFeedback(lessonId); clearLines(lessonId); clearStages(lessonId)
    }
}
