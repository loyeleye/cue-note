package com.cuenote.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cuenote.data.AuthRepository
import com.cuenote.data.ClipEntity
import com.cuenote.data.LessonDatabase
import com.cuenote.data.LessonTeacherStore
import com.cuenote.data.LocalOnly
import com.cuenote.data.PipelineStageEntity
import com.cuenote.pipeline.Pipeline
import com.cuenote.pipeline.audio.LessonFolder
import com.cuenote.pipeline.cards.ClipPlan
import com.cuenote.pipeline.cards.CueRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Turns a processed lesson into cards, in three resumable steps that each
 * show up in the lesson's stage list:
 *
 *   clips   cut each take and feedback block into a small .m4a for replay
 *   upload  the lesson, its takes and the clips go to the student's cloud folder
 *   cards   extract-cues reads the teacher's words and writes the cards;
 *           the phone then copies them into Room
 *
 * It runs after processing, whenever there is a network, as the unique work
 * chain "upload-<lessonId>". Other uploads for the lesson (photos, M6) append
 * to the same chain, so they run once the lesson and its cards are in the cloud.
 */
class LessonUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val lessonId = inputData.getString(KEY_LESSON) ?: return@withContext Result.failure()
        val db = LessonDatabase.get(applicationContext)
        val lesson = db.lessons().get(lessonId) ?: return@withContext Result.failure()
        val folder = LessonFolder(File(lesson.dirPath))
        val stages = db.pipeline()
        val cardsDao = db.cards()
        val out = Pipeline.readOutput(folder) ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "not processed yet"))

        fun record(stage: String, status: String, started: Long, detail: String) =
            PipelineStageEntity(lessonId, stage, status, started, System.currentTimeMillis(), detail)

        val teachers = LessonTeacherStore(applicationContext)
        // A local-only lesson: cut the replay clips for the phone, and stop there.
        val localOnly = teachers.isLocalOnly(lessonId)
        val auth = AuthRepository.get()
        val cloud = CloudSync(auth)
        val student = if (localOnly) null else if (auth.isConfigured) cloud.studentId() else null
        if (student == null && !localOnly) {
            stages.upsertStage(record("upload", "waiting", System.currentTimeMillis(),
                if (auth.isConfigured) "Sign in on this phone to make cards" else "Sign-in isn't set up in this build"))
            // Nothing to do until someone signs in; the lesson screen offers "Make cards" then.
            return@withContext Result.failure(workDataOf(KEY_ERROR to "not signed in"))
        }
        runCatching { setForeground(foreground("Cutting replay clips")) }

        // ---------------------------------------------------------- 1. clips
        var started = System.currentTimeMillis()
        val clipsDir = File(lesson.dirPath, "clips").apply { mkdirs() }
        val plan = ClipPlan.plan(out.takes, out.feedback, out.durationMs)
        val have = cardsDao.clips(lessonId).associateBy { it.id }
        try {
            var bytes = 0L
            plan.forEachIndexed { i, span ->
                if (isStopped) return@withContext Result.retry()
                val file = File(clipsDir, span.fileName)
                val known = have[span.cloudId]
                val same = known != null && known.startMs == span.startMs && known.endMs == span.endMs && file.isFile
                if (!same) {
                    ClipEncoder.encode(folder, span, file)
                    cardsDao.upsertClip(ClipEntity(span.cloudId, lessonId, span.kind, span.localId, span.startMs, span.endMs,
                        file.absolutePath, storagePath = null))
                }
                bytes += file.length()
                setProgress(workDataOf(KEY_STAGE to "clips", KEY_PROGRESS to (i + 1) * 100 / plan.size.coerceAtLeast(1)))
            }
            stages.upsertStage(record("clips", "done", started, "${plan.size} clips, ${bytes / 1024} KB"))
        } catch (e: Exception) {
            stages.upsertStage(record("clips", "failed", started, e.toString()))
            return@withContext Result.failure(workDataOf(KEY_ERROR to e.toString()))
        }

        // Checked again here: the teacher may have been changed while the clips were cut.
        if (student == null || teachers.isLocalOnly(lessonId)) {
            stages.upsertStage(record("upload", "skipped", System.currentTimeMillis(), KEPT_ON_PHONE))
            stages.setLessonState(lessonId, "processed")
            return@withContext Result.success()
        }

        // ---------------------------------------------------------- 2. upload
        started = System.currentTimeMillis()
        runCatching { setForeground(foreground("Uploading clips")) }
        val clips = cardsDao.clips(lessonId).filter { c -> plan.any { it.cloudId == c.id } }
        val pathOf = clips.associate { it.localId to cloud.clipPath(student, lessonId, File(it.file).name) }
        try {
            cloud.putLesson(student, lesson, teachers.get(lessonId))
            teachers.markPending(lessonId, false) // the upload carried the latest pick
            // Clips a teacher deleted, or whose audio they removed, stay off the cloud.
            val removed = cloud.removedClipIds(lessonId)
            clips.forEachIndexed { i, c ->
                if (isStopped) return@withContext Result.retry()
                val path = pathOf.getValue(c.localId)
                if (c.id !in removed && c.storagePath != path) {
                    cloud.putClip(path, File(c.file))
                    cardsDao.setUploaded(c.id, path)
                }
                setProgress(workDataOf(KEY_STAGE to "upload", KEY_PROGRESS to (i + 1) * 100 / clips.size.coerceAtLeast(1)))
            }
            val takes = stages.takes(lessonId)
            // exercises first: takes point at them
            cloud.putExercises(lessonId, stages.exercises(lessonId))
            cloud.putTakes(lessonId, takes) { pathOf[it] }
            stages.upsertStage(record("upload", "done", started, "${clips.size} clips and ${takes.size} takes"))
        } catch (e: Exception) {
            stages.upsertStage(record("upload", "failed", started, e.message ?: e.toString()))
            return@withContext retryOrFail(e.toString())
        }

        // ---------------------------------------------------------- 3. cards
        started = System.currentTimeMillis()
        runCatching { setForeground(foreground("Writing the cards")) }
        setProgress(workDataOf(KEY_STAGE to "cards", KEY_PROGRESS to 0))
        try {
            if (teachers.isLocalOnly(lessonId)) throw LocalOnly.Refused(lessonId)
            val r = cloud.extractCues(CueRequest.from(lessonId, out) { pathOf[it] })
            cardsDao.replaceCards(lessonId, cloud.cards(lessonId))
            stages.setLessonState(lessonId, "cards")
            val cost = r.costUsd?.let { " for $%.3f".format(it) } ?: ""
            val kept = if (r.keptTeacher > 0) ", kept ${r.keptTeacher} your teacher had edited" else ""
            stages.upsertStage(record("cards", "done", started, "${r.cards} cards by ${r.model ?: "the model"}$cost$kept"))
            // Sketches waiting for the cards can go now.
            runCatching { com.cuenote.data.sketch.PhotoUploadWorker.kick(applicationContext, lessonId) }
            Result.success()
        } catch (e: LocalOnly.Refused) {
            stages.upsertStage(record("cards", "skipped", started, KEPT_ON_PHONE))
            Result.success()
        } catch (e: CloudSync.CueError) {
            stages.upsertStage(record("cards", "failed", started, e.message ?: "extract-cues failed"))
            if (e.retryable) retryOrFail(e.message ?: "") else Result.failure(workDataOf(KEY_ERROR to e.message))
        } catch (e: Exception) {
            stages.upsertStage(record("cards", "failed", started, e.message ?: e.toString()))
            retryOrFail(e.toString())
        }
    }

    private fun retryOrFail(error: String): Result =
        if (runAttemptCount < 5) Result.retry() else Result.failure(workDataOf(KEY_ERROR to error))

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Making cards")

    private fun foreground(text: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lesson cards", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Making the lesson's cards")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_LESSON = "lessonId"
        const val KEY_STAGE = "stage"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        private const val CHANNEL = "cards"
        private const val NOTIFICATION_ID = 4211
        const val KEPT_ON_PHONE = "Kept on this phone: nothing uploaded"

        val STAGE_NAMES = mapOf(
            "clips" to "Cutting replay clips",
            "upload" to "Uploading the lesson",
            "cards" to "Writing the cards",
        )

        /** The chain other per-lesson uploads (photos) append to. */
        fun workName(lessonId: String) = "upload-$lessonId"

        fun enqueue(context: Context, lessonId: String) {
            // A local-only lesson only cuts its clips, so it needn't wait for a network.
            val network = if (LessonTeacherStore(context).isLocalOnly(lessonId)) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED
            val request = OneTimeWorkRequestBuilder<LessonUploadWorker>()
                .setInputData(workDataOf(KEY_LESSON to lessonId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .addTag("upload")
                .build()
            // Replace, never queue behind: a tap on "Make my notes" must start now,
            // not wait out an earlier try's backoff (or, on older installs, a
            // sketch upload in this chain that was itself waiting for the cards).
            WorkManager.getInstance(context).enqueueUniqueWork(workName(lessonId), ExistingWorkPolicy.REPLACE, request)
        }

        /** [error] is why the last try failed, when it did. */
        data class Progress(val state: WorkInfo.State, val stage: String?, val percent: Int, val error: String? = null, val attempts: Int = 0)

        /** The upload worker's state; other workers appended to the chain are ignored. */
        fun progress(context: Context, lessonId: String): Flow<Progress?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(workName(lessonId)).map { infos ->
                infos.lastOrNull { "upload" in it.tags }?.let {
                    Progress(it.state, it.progress.getString(KEY_STAGE), it.progress.getInt(KEY_PROGRESS, 0), it.outputData.getString(KEY_ERROR), it.runAttemptCount)
                }
            }
    }
}
