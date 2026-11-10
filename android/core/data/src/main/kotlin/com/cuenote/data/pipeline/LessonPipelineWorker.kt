package com.cuenote.data.pipeline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cuenote.data.LessonDatabase
import com.cuenote.data.PipelineStageEntity
import com.cuenote.data.sketch.SketchRepository
import com.cuenote.data.sketch.SketchWorker
import com.cuenote.ml.ModelFiles
import com.cuenote.ml.WhisperNative
import com.cuenote.pipeline.Models
import com.cuenote.pipeline.Pipeline
import com.cuenote.pipeline.StoppedException
import com.cuenote.pipeline.audio.LessonFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs the after-lesson pipeline in the background (see :core:pipeline).
 * Each stage saves its result, so when Android stops the work (the phone is
 * unplugged, or the job runs too long) the next attempt carries on from the
 * stage it reached, and from the last utterance within transcription.
 */
class LessonPipelineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val lessonId = inputData.getString(KEY_LESSON) ?: return@withContext Result.failure()
        val db = LessonDatabase.get(applicationContext)
        val lesson = db.lessons().get(lessonId) ?: return@withContext Result.failure()
        val folder = LessonFolder(File(lesson.dirPath))
        val dao = db.pipeline()
        if (inputData.getBoolean(KEY_AGAIN, false) && runAttemptCount == 0) {
            dao.clearAll(lessonId)
            Pipeline(folder, com.cuenote.ml.EnergyVad(), null).reset()
        }

        runCatching { setForeground(foreground("Getting ready", 0)) }
        dao.setLessonState(lessonId, "processing")

        // 1. models: downloaded once, kept in the app's storage
        val modelsDir = PipelineScheduler.modelsDir(applicationContext)
        try {
            for (m in ModelFiles.missing(modelsDir, PipelineScheduler.MODELS)) {
                ModelFiles.download(m, modelsDir, onProgress = { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    runBlocking { setProgress(workDataOf(KEY_STAGE to "download", KEY_PROGRESS to pct)) }
                }, isStopped = { isStopped })
            }
        } catch (e: Exception) {
            if (isStopped) return@withContext Result.retry()
            dao.upsertStage(PipelineStageEntity(lessonId, "download", "failed", now(), now(), e.toString()))
            return@withContext if (runAttemptCount < 5) Result.retry() else Result.failure()
        }

        // 2. the pipeline itself
        WhisperNative.load()
        val result = Models.load(modelsDir, nativeDir = null, whisperModel = PipelineScheduler.WHISPER.fileName).use { models ->
            val pipeline = Pipeline(folder, models.vad, models.transcriber)
            var lastStage = ""
            var lastPct = -1
            try {
                val out = pipeline.run(
                    onStage = { stage, p ->
                        val pct = (p * 100).toInt()
                        if (stage.key != lastStage || pct != lastPct) {
                            lastStage = stage.key; lastPct = pct
                            runBlocking {
                                setProgress(workDataOf(KEY_STAGE to stage.key, KEY_PROGRESS to pct))
                                runCatching { setForeground(foreground(STAGE_NAMES[stage.key] ?: stage.key, pct)) }
                            }
                        }
                    },
                    isStopped = { isStopped },
                )
                ResultImporter.import(dao, lessonId, out, pipeline.state())
                // photos onto the cards they follow, then clean and upload them
                SketchRepository.importLinks(db.photos(), lessonId, File(lesson.dirPath), out.events)
                SketchWorker.enqueue(applicationContext, lessonId)
                dao.setLessonState(lessonId, "processed")
                Result.success()
            } catch (e: StoppedException) {
                ResultImporter.importStages(dao, lessonId, pipeline.state())
                Result.retry()
            } catch (e: Throwable) {
                ResultImporter.importStages(dao, lessonId, pipeline.state())
                dao.setLessonState(lessonId, "failed")
                Result.failure(workDataOf("error" to e.toString()))
            }
        }
        result
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Getting ready", 0)

    private fun foreground(text: String, pct: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lesson processing", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Processing a lesson")
            .setContentText(text)
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, n)
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        const val KEY_LESSON = "lessonId"
        const val KEY_AGAIN = "again"
        const val KEY_STAGE = "stage"
        const val KEY_PROGRESS = "progress"
        private const val CHANNEL = "pipeline"
        private const val NOTIFICATION_ID = 4210
        val STAGE_NAMES = mapOf(
            "download" to "Downloading the speech models (once)",
            "features" to "Listening to the recording",
            "segment" to "Finding takes and feedback",
            "transcribe" to "Writing down what was said",
            "link" to "Linking feedback to takes",
            "timeline" to "Drawing the timeline",
        )
    }
}
