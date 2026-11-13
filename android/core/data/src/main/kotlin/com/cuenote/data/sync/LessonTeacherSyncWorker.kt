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
import com.cuenote.data.LessonDatabase
import com.cuenote.data.LessonTeacher
import com.cuenote.data.LessonTeacherStore
import com.cuenote.data.LocalOnly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Sends the teacher picked for an older lesson ("Taught by" on the lesson)
 * to the cloud, once there's a network. A pick the database refuses (that
 * person no longer teaches the student) is dropped rather than retried.
 *
 * A lesson moved to a local-only teacher is deleted online instead (clips,
 * photos, cards and the lesson row), and their name is never sent.
 */
class LessonTeacherSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val auth = AuthRepository.get()
        if (!auth.isConfigured) return@withContext Result.success()
        val cloud = CloudSync(auth)
        val student = cloud.studentId() ?: return@withContext Result.success() // sent with the lesson after sign-in
        val store = LessonTeacherStore(applicationContext)
        var retry = false
        for (id in store.pendingSync()) {
            val teacher = store.get(id)
            try {
                if (teacher.localOnly) cloud.deleteLesson(student, id) else cloud.setLessonTeacher(id, teacher)
                store.markPending(id, false)
            } catch (e: Exception) {
                if (!teacher.localOnly && (e.message ?: "").contains("teachers")) {
                    // Not one of the student's teachers any more: the lesson stays as the cloud has it.
                    store.markPending(id, false)
                } else retry = true
            }
        }
        if (!retry) Result.success() else if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    companion object {
        private const val MAX_ATTEMPTS = 8

        /**
         * Saves [teacher] for the lesson on the phone and queues sending it.
         * Moving a lesson to a local-only teacher stops its uploads, clears
         * what came back from the cloud (the AI's cards and sketch captions)
         * and queues deleting it online. Moving it away from one sends
         * nothing: it goes up only when the student taps Make my notes.
         */
        suspend fun change(context: Context, lessonId: String, teacher: LessonTeacher) {
            val store = LessonTeacherStore(context)
            val what = LocalOnly.change(store.get(lessonId), teacher)
            store.set(lessonId, teacher)
            when (what) {
                LocalOnly.Change.NONE, LocalOnly.Change.NOW_SHAREABLE -> return
                LocalOnly.Change.DELETE_ONLINE -> forgetCloudCopy(context, lessonId)
                LocalOnly.Change.SEND -> Unit
            }
            store.markPending(lessonId, true)
            val request = OneTimeWorkRequestBuilder<LessonTeacherSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("lesson-teacher-sync", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /** Stops the lesson's uploads and forgets, on the phone, what the cloud and its AI made of it. */
        private suspend fun forgetCloudCopy(context: Context, lessonId: String) {
          withContext(Dispatchers.IO) {
            val work = WorkManager.getInstance(context)
            work.cancelUniqueWork(LessonUploadWorker.workName(lessonId))
            work.cancelUniqueWork("photos-$lessonId")
            val db = LessonDatabase.get(context)
            db.cards().clearCards(lessonId)
            db.cards().forgetUploads(lessonId)
            db.photos().forgetCloud(lessonId)
            if (db.lessons().get(lessonId)?.pipelineState == "cards") db.pipeline().setLessonState(lessonId, "processed")
          }
        }
    }
}
