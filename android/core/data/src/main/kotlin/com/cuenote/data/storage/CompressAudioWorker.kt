package com.cuenote.data.storage

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cuenote.data.LessonDatabase
import com.cuenote.data.PipelineStageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shrinks a lesson's audio once the lesson is processed (see [AudioCompressor]),
 * while the phone charges. It waits its turn: never while the same lesson is
 * being processed or uploaded, since those read the chunk files.
 */
class CompressAudioWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val lessonId = inputData.getString(KEY_LESSON) ?: return@withContext Result.failure()
        val db = LessonDatabase.get(applicationContext)
        val lesson = db.lessons().get(lessonId) ?: return@withContext Result.failure()
        if (busy(lessonId)) return@withContext Result.retry()
        val started = System.currentTimeMillis()
        val r = runCatching { AudioCompressor.compress(File(lesson.dirPath), isStopped = { isStopped }) }
            .getOrElse { e ->
                db.pipeline().upsertStage(PipelineStageEntity(lessonId, STAGE, "failed", started, System.currentTimeMillis(), e.toString()))
                return@withContext Result.failure()
            }
        if (isStopped) return@withContext Result.retry()
        val detail = if (r.compressed == 0 && r.skipped == 0) "already compact"
        else "${r.bytesBefore / 1_048_576} MB of audio now ${r.bytesAfter / 1_048_576} MB" +
            if (r.skipped > 0) ", ${r.skipped} chunks kept as recorded" else ""
        db.pipeline().upsertStage(
            PipelineStageEntity(lessonId, STAGE, if (r.skipped > 0) "partial" else "done", started, System.currentTimeMillis(), detail),
        )
        Result.success()
    }

    /** True while this lesson's processing or upload is queued or running. */
    private fun busy(lessonId: String): Boolean {
        val wm = WorkManager.getInstance(applicationContext)
        return listOf("pipeline-$lessonId", "upload-$lessonId").any { name ->
            wm.getWorkInfosForUniqueWork(name).get().any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        }
    }

    companion object {
        const val KEY_LESSON = "lessonId"
        const val STAGE = "audio"

        fun enqueue(context: Context, lessonId: String) {
            val request = OneTimeWorkRequestBuilder<CompressAudioWorker>()
                .setInputData(workDataOf(KEY_LESSON to lessonId))
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresStorageNotLow(false).build())
                // give the upload a head start; if it is still going, the worker waits and tries again
                .setInitialDelay(10, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                .addTag("compress")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("compress-$lessonId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
