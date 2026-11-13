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

    @Query("select * from lesson where pipelineState = :state")
    suspend fun withState(state: String): List<LessonEntity>

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
        TranscriptLineEntity::class, PipelineStageEntity::class, ClipEntity::class, CardEntity::class,
        PhotoEntity::class, PracticeSessionEntity::class, PracticeTakeEntity::class,
        PracticeQuestionEntity::class, ExerciseEntity::class,
    ],
    version = 11,
    exportSchema = true,
)
abstract class LessonDatabase : RoomDatabase() {
    abstract fun lessons(): LessonDao
    abstract fun pipeline(): PipelineDao
    abstract fun photos(): PhotoDao
    abstract fun cards(): CardDao
    abstract fun practice(): PracticeDao

    companion object {
        @Volatile private var instance: LessonDatabase? = null

        /** One database for the app and its background workers. */
        fun get(context: Context): LessonDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LessonDatabase::class.java, "cuenote.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                .build().also { instance = it }
        }

        /** M2 adds the pipeline's tables; existing lessons are kept. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_1_2_SQL.forEach(db::execSQL)
            }
        }

        /** M3 adds replay clips and cards; existing lessons and results are kept. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_3_SQL.forEach(db::execSQL)
            }
        }

        /** M6 adds lesson photos (sketches); everything else is kept. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_3_4_SQL.forEach(db::execSQL)
            }
        }

        /** M5 adds practice sessions and takes; everything else is kept. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_4_5_SQL.forEach(db::execSQL)
            }
        }

        /** M5 adds sharing practice takes and questions for the teacher; everything else is kept. */
        /** Exercises: warmups and song sections grouping takes and feedback. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_6_7_SQL.forEach(db::execSQL)
            }
        }

        /** The teacher's replies to shared takes and questions, in words or a voice note. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_7_8_SQL.forEach(db::execSQL)
            }
        }

        /** Exercises get the names the card writer (or the dashboard) gives them. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_8_9_SQL.forEach(db::execSQL)
            }
        }

        /** Cards get the writer's one- or two-sentence summary and how much each point is worth drilling. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_9_10_SQL.forEach(db::execSQL)
            }
        }

        /** Cards get the teacher's answer about the summary, and the one she said she didn't say. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_10_11_SQL.forEach(db::execSQL)
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_5_6_SQL.forEach(db::execSQL)
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
    "CREATE TABLE IF NOT EXISTS `clip` (`id` TEXT NOT NULL, `lessonId` TEXT NOT NULL, `kind` TEXT NOT NULL, `localId` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, `file` TEXT NOT NULL, `storagePath` TEXT, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `card` (`id` TEXT NOT NULL, `lessonId` TEXT NOT NULL, `localId` TEXT, `takeId` TEXT, `spanStartMs` INTEGER NOT NULL, `spanEndMs` INTEGER NOT NULL, `cue` TEXT NOT NULL, `category` TEXT, `herWords` TEXT NOT NULL, `practiceFlag` TEXT, `barStart` INTEGER, `barEnd` INTEGER, `transcript` TEXT NOT NULL, `editedByTeacher` INTEGER NOT NULL, `updatedAt` TEXT NOT NULL, PRIMARY KEY(`id`))",
)

/** Copied from schemas/com.cuenote.data.LessonDatabase/4.json (Room checks they match). */
internal val MIGRATION_3_4_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `photo` (`id` TEXT NOT NULL, `lessonId` TEXT NOT NULL, `fileName` TEXT NOT NULL, `takenAtMs` INTEGER NOT NULL, `feedbackId` TEXT, `autoFeedbackId` TEXT, `linkSource` TEXT NOT NULL, `cleanFileName` TEXT, `corners` TEXT, `pageConfidence` REAL NOT NULL, `caption` TEXT, `captionConfidence` REAL, `needsUpload` INTEGER NOT NULL, PRIMARY KEY(`id`))",
)

/** Copied from schemas/com.cuenote.data.LessonDatabase/5.json (Room checks they match). */
internal val MIGRATION_4_5_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `practice_session` (`id` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `practice_take` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `cardId` TEXT NOT NULL, `lessonId` TEXT NOT NULL, `file` TEXT NOT NULL, `recordedAt` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `input` TEXT NOT NULL, `listening` TEXT NOT NULL, `latencyMs` INTEGER, `playbackStartFrame` INTEGER, `feelingNote` TEXT, `shared` INTEGER NOT NULL, PRIMARY KEY(`id`))",
)

/** Copied from schemas/com.cuenote.data.LessonDatabase/6.json (Room checks they match). */
internal val MIGRATION_5_6_ADDED_COLUMNS = mapOf(
    "practice_take" to listOf(
        "`cloudShared` INTEGER NOT NULL DEFAULT 0",
        "`heardAt` INTEGER",
        "`deleted` INTEGER NOT NULL DEFAULT 0",
    ),
)

internal val MIGRATION_5_6_SQL = MIGRATION_5_6_ADDED_COLUMNS.flatMap { (table, cols) ->
    cols.map { "ALTER TABLE `$table` ADD COLUMN $it" }
} + listOf(
    "CREATE TABLE IF NOT EXISTS `practice_question` (`id` TEXT NOT NULL, `cardId` TEXT, `lessonId` TEXT, `bar` INTEGER, `body` TEXT NOT NULL, `status` TEXT NOT NULL, `answer` TEXT, `answeredAt` INTEGER, `createdAt` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, PRIMARY KEY(`id`))",
)

/** Copied from schemas/com.cuenote.data.LessonDatabase/7.json (Room checks they match). */
internal val MIGRATION_6_7_ADDED_COLUMNS = mapOf(
    "take" to listOf("`exerciseId` TEXT"),
    "feedback" to listOf("`exerciseId` TEXT"),
)

internal val MIGRATION_6_7_SQL = MIGRATION_6_7_ADDED_COLUMNS.flatMap { (table, cols) ->
    cols.map { "ALTER TABLE `$table` ADD COLUMN $it" }
} + listOf(
    "CREATE TABLE IF NOT EXISTS `exercise` (`id` TEXT NOT NULL, `lessonId` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, `kind` TEXT NOT NULL, `reason` TEXT NOT NULL, PRIMARY KEY(`id`))",
)

/** Copied from schemas/com.cuenote.data.LessonDatabase/8.json (Room checks they match). */
internal val MIGRATION_7_8_ADDED_COLUMNS = mapOf(
    "practice_take" to listOf("`reply` TEXT", "`replyClipPath` TEXT"),
    "practice_question" to listOf("`answerClipPath` TEXT"),
)

internal val MIGRATION_7_8_SQL = MIGRATION_7_8_ADDED_COLUMNS.flatMap { (table, cols) ->
    cols.map { "ALTER TABLE `$table` ADD COLUMN $it" }
}

/** Copied from schemas/com.cuenote.data.LessonDatabase/9.json (Room checks they match). */
internal val MIGRATION_8_9_ADDED_COLUMNS = mapOf(
    "exercise" to listOf("`title` TEXT"),
)

internal val MIGRATION_8_9_SQL = MIGRATION_8_9_ADDED_COLUMNS.flatMap { (table, cols) ->
    cols.map { "ALTER TABLE `$table` ADD COLUMN $it" }
}

/** Copied from schemas/com.cuenote.data.LessonDatabase/10.json (Room checks they match). */
internal val MIGRATION_9_10_ADDED_COLUMNS = mapOf(
    "card" to listOf("`summary` TEXT NOT NULL DEFAULT ''", "`weight` REAL"),
)

internal val MIGRATION_9_10_SQL = MIGRATION_9_10_ADDED_COLUMNS.flatMap { (table, cols) ->
    cols.map { "ALTER TABLE `$table` ADD COLUMN $it" }
}

/** Copied from schemas/com.cuenote.data.LessonDatabase/11.json (Room checks they match). */
internal val MIGRATION_10_11_ADDED_COLUMNS = mapOf(
    "card" to listOf("`summaryCheck` TEXT", "`summaryRejected` TEXT"),
)

internal val MIGRATION_10_11_SQL = MIGRATION_10_11_ADDED_COLUMNS.flatMap { (table, cols) ->
    cols.map { "ALTER TABLE `$table` ADD COLUMN $it" }
}
