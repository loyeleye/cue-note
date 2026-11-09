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

@Database(entities = [LessonEntity::class], version = 1, exportSchema = false)
abstract class LessonDatabase : RoomDatabase() {
    abstract fun lessons(): LessonDao

    companion object {
        fun create(context: Context): LessonDatabase =
            Room.databaseBuilder(context, LessonDatabase::class.java, "cuenote.db").build()
    }
}
