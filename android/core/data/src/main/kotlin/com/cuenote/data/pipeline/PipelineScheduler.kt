package com.cuenote.data.pipeline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.cuenote.ml.ModelFiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.concurrent.TimeUnit

/** Queues lesson processing. One job per lesson; a newer request replaces a waiting one. */
object PipelineScheduler {
    val WHISPER = ModelFiles.WHISPER_BASE_EN
    val MODELS = listOf(ModelFiles.SILERO, WHISPER)

    fun modelsDir(context: Context) = File(context.filesDir, "models")

    private fun workName(lessonId: String) = "pipeline-$lessonId"

    /**
     * After a recording: wait until the phone is charging, which also means
     * the mic receiver is out of the only USB-C port (the design doc's rule).
     * [now] skips that wait, for the "Process now" button. [again] throws
     * away earlier results first.
     */
    fun enqueue(context: Context, lessonId: String, now: Boolean = false, again: Boolean = false) {
        val needsDownload = ModelFiles.missing(modelsDir(context), MODELS).isNotEmpty()
        val constraints = Constraints.Builder()
            .setRequiresCharging(!now)
            .setRequiredNetworkType(if (needsDownload) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED)
            .build()
        val request = OneTimeWorkRequestBuilder<LessonPipelineWorker>()
            .setInputData(workDataOf(LessonPipelineWorker.KEY_LESSON to lessonId, LessonPipelineWorker.KEY_AGAIN to again))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .addTag("pipeline")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(lessonId), ExistingWorkPolicy.REPLACE, request)
    }

    data class Progress(val state: WorkInfo.State, val stage: String?, val percent: Int)

    fun progress(context: Context, lessonId: String): Flow<Progress?> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(workName(lessonId)).map { infos ->
            infos.lastOrNull()?.let {
                Progress(it.state, it.progress.getString(LessonPipelineWorker.KEY_STAGE), it.progress.getInt(LessonPipelineWorker.KEY_PROGRESS, 0))
            }
        }
}
