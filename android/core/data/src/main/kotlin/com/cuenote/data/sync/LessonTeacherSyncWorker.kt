package com.cuenote.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cuenote.data.AuthRepository
import com.cuenote.data.LessonTeacher
import com.cuenote.data.LessonTeacherStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Sends the teacher picked for an older lesson ("Taught by" on the lesson)
 * to the cloud, once there's a network. A pick the database refuses (that
 * person no longer teaches the student) is dropped rather than retried.
 */
class LessonTeacherSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val auth = AuthRepository.get()
        if (!auth.isConfigured) return@withContext Result.success()
        val cloud = CloudSync(auth)
        cloud.studentId() ?: return@withContext Result.success() // sent with the lesson after sign-in
        val store = LessonTeacherStore(applicationContext)
        var retry = false
        for (id in store.pendingSync()) {
            val teacher = store.get(id)
            try {
                cloud.setLessonTeacher(id, teacher)
                store.markPending(id, false)
            } catch (e: Exception) {
                if ((e.message ?: "").contains("teachers")) {
                    // Not one of the student's teachers any more: the lesson stays as the cloud has it.
                    store.markPending(id, false)
                } else retry = true
            }
        }
        if (!retry) Result.success() else if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    companion object {
        private const val MAX_ATTEMPTS = 8

        /** Saves [teacher] for the lesson on the phone and queues sending it. */
        fun change(context: Context, lessonId: String, teacher: LessonTeacher) {
            LessonTeacherStore(context).apply {
                set(lessonId, teacher)
                markPending(lessonId, true)
            }
            val request = OneTimeWorkRequestBuilder<LessonTeacherSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("lesson-teacher-sync", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
