package com.cuenote.data.sketch

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cuenote.data.LessonDatabase
import java.io.File

/**
 * After a lesson: makes a row for every photo, cleans each sketch that has no
 * cleaned copy yet (photos of me are left as taken), then queues the upload. Cleaning a photo takes about a
 * second, so this runs straight away, without waiting for the charger.
 */
class SketchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val lessonId = inputData.getString(KEY_LESSON) ?: return Result.failure()
        val db = LessonDatabase.get(applicationContext)
        val lesson = db.lessons().get(lessonId) ?: return Result.success()
        val dir = File(lesson.dirPath)
        val dao = db.photos()
        SketchRepository.register(dao, lessonId, dir)
        for (p in dao.forLesson(lessonId)) {
            if (isStopped) return Result.retry()
            // a photo of me is kept as taken: cleaning would ruin a face
            if (p.cleanFileName != null || p.isMe || p.deleted) continue
            try {
                SketchRepository.clean(dao, dir, p, SketchRepository.cornersOf(p))
            } catch (e: Exception) {
                // a photo that can't be read keeps only its original; the rest go on
                Log.w("SketchWorker", "could not clean ${p.fileName}", e)
            }
        }
        PhotoUploadWorker.enqueue(applicationContext, lessonId)
        return Result.success()
    }

    companion object {
        const val KEY_LESSON = "lessonId"

        fun enqueue(context: Context, lessonId: String) {
            val request = OneTimeWorkRequestBuilder<SketchWorker>()
                .setInputData(workDataOf(KEY_LESSON to lessonId))
                .addTag("sketches")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("sketches-$lessonId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
