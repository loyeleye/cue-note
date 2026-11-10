package com.cuenote.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * A lesson as the phone knows it. Mirrors the cloud `lesson` table plus
 * local-only fields (the folder of WAV chunks never leaves the phone).
 */
@Entity(tableName = "lesson")
data class LessonEntity(
    @PrimaryKey val id: String,
    val recordedAt: Long,          // epoch ms
    val consentAt: Long,           // epoch ms, both people agreed
    val dirPath: String,
    val pipelineState: String = "recording",
    val durationMs: Long = 0,
    val chunkCount: Int = 0,
    val gapCount: Int = 0,
    val markCount: Int = 0,
    val photoCount: Int = 0,
    val micName: String = "",
)

@Dao
interface LessonDao {
    @Query("select * from lesson order by recordedAt desc")
    fun observeAll(): Flow<List<LessonEntity>>

    @Query("select * from lesson where id = :id")
    suspend fun get(id: String): LessonEntity?

    @Insert
    suspend fun insert(lesson: LessonEntity)

    @Query(
        """update lesson set pipelineState = :state, durationMs = :durationMs, chunkCount = :chunks,
           gapCount = :gaps, markCount = :marks, photoCount = :photos, micName = :mic where id = :id""",
    )
    suspend fun finish(
        id: String, state: String, durationMs: Long, chunks: Int,
        gaps: Int, marks: Int, photos: Int, mic: String,
    )

    @Query("delete from lesson where id = :id")
    suspend fun delete(id: String)
}

@Database(
    entities = [
        LessonEntity::class, SegmentEntity::class, TakeEntity::class, FeedbackEntity::class,
        TranscriptLineEntity::class, PipelineStageEntity::class, PhotoEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class LessonDatabase : RoomDatabase() {
    abstract fun lessons(): LessonDao
    abstract fun pipeline(): PipelineDao
    abstract fun photos(): PhotoDao

    companion object {
        @Volatile private var instance: LessonDatabase? = null

        /** One database for the app and its background workers. */
        fun get(context: Context): LessonDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LessonDatabase::class.java, "cuenote.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }

        /** M2 adds the pipeline's tables; existing lessons are kept. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_1_2_SQL.forEach(db::execSQL)
            }
        }

        /** M6 adds lesson photos (sketches); everything else is kept. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_3_SQL.forEach(db::execSQL)
            }
        }
    }
}

/** Copied from schemas/com.cuenote.data.LessonDatabase/2.json (Room checks they match). */
internal val MIGRATION_1_2_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `segment` (`lessonId` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, `label` TEXT NOT NULL, PRIMARY KEY(`lessonId`, `startMs`))",
    "CREATE TABLE IF NOT EXISTS `take` (`id` TEXT NOT NULL, `lessonId` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, `pitchContour` TEXT NOT NULL, `pitchConfidence` TEXT NOT NULL, `withPiano` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `feedback` (`id` TEXT NOT NULL, `lessonId` TEXT NOT NULL, `takeId` TEXT, `spanStartMs` INTEGER NOT NULL, `spanEndMs` INTEGER NOT NULL, `linkConfidence` REAL NOT NULL, `linkReason` TEXT NOT NULL, `demos` TEXT NOT NULL, `words` TEXT NOT NULL, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `transcript_line` (`lessonId` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, `speaker` TEXT NOT NULL, `text` TEXT NOT NULL, PRIMARY KEY(`lessonId`, `startMs`, `speaker`))",
    "CREATE TABLE IF NOT EXISTS `pipeline_stage` (`lessonId` TEXT NOT NULL, `stage` TEXT NOT NULL, `status` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER NOT NULL, `detail` TEXT NOT NULL, PRIMARY KEY(`lessonId`, `stage`))",
)

/** Copied from schemas/com.cuenote.data.LessonDatabase/3.json (Room checks they match). */
internal val MIGRATION_2_3_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `photo` (`id` TEXT NOT NULL, `lessonId` TEXT NOT NULL, `fileName` TEXT NOT NULL, `takenAtMs` INTEGER NOT NULL, `feedbackId` TEXT, `autoFeedbackId` TEXT, `linkSource` TEXT NOT NULL, `cleanFileName` TEXT, `corners` TEXT, `pageConfidence` REAL NOT NULL, `caption` TEXT, `captionConfidence` REAL, `needsUpload` INTEGER NOT NULL, PRIMARY KEY(`id`))",
)
