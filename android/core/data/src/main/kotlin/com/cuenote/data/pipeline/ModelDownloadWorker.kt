package com.cuenote.data.pipeline

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cuenote.ml.ModelFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Fetches the speech models ahead of time, so the first lesson after a
 * recording can be processed without a download (the home screen's
 * "Download speech models" button). Lesson processing would fetch them
 * itself otherwise.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val dir = PipelineScheduler.modelsDir(applicationContext)
        val missing = ModelFiles.missing(dir, PipelineScheduler.MODELS)
        val total = missing.sumOf { it.approxBytes }.coerceAtLeast(1)
        var before = 0L
        try {
            for (m in missing) {
                ModelFiles.download(m, dir, onProgress = { done, _ ->
                    runBlocking { setProgress(workDataOf(KEY_PERCENT to ((before + done) * 100 / total).toInt())) }
                }, isStopped = { isStopped })
                before += m.approxBytes
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_PERCENT = "percent"
        private const val NAME = "download-models"

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }

        /** null when nothing has been asked for; otherwise the download's state and percent. */
        fun progress(context: Context): Flow<Pair<WorkInfo.State, Int>?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NAME).map { infos ->
                infos.lastOrNull()?.let { it.state to it.progress.getInt(KEY_PERCENT, 0) }
            }
    }
}
