package com.cuenote.data.writer

import android.content.Context
import android.os.PowerManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cuenote.data.CardEntity
import com.cuenote.data.FeedbackEntity
import com.cuenote.data.LessonDatabase
import com.cuenote.data.LessonTeacherStore
import com.cuenote.data.PipelineStageEntity
import com.cuenote.pipeline.Pipeline
import com.cuenote.pipeline.audio.LessonFolder
import com.cuenote.pipeline.cards.CloudIds
import com.cuenote.pipeline.cards.PhoneCard
import com.cuenote.pipeline.cards.PhoneCards
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Writes a lesson's cards on the phone, for a lesson kept on the phone: the
 * vetted model (or Gemini Nano) reads each feedback block and writes a cue,
 * her words and a summary, checked as the cloud's are (PhoneCards). Nothing
 * leaves the phone. Runs only while the phone charges, and stops between
 * blocks when it's unplugged or gets warm; the next run starts over, which
 * at a few seconds a block costs little.
 *
 * The cards are `source = 'phone_writer'` ("Written on this phone"). Notes
 * the student put in their own words are kept as they are.
 */
class PhoneCardWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val lessonId = inputData.getString(KEY_LESSON) ?: return@withContext Result.failure()
        val db = LessonDatabase.get(applicationContext)
        val lesson = db.lessons().get(lessonId) ?: return@withContext Result.failure()
        // Only for lessons kept on the phone: the others get their cards from the cloud.
        if (!LessonTeacherStore(applicationContext).isLocalOnly(lessonId)) return@withContext Result.failure()
        val out = Pipeline.readOutput(LessonFolder(File(lesson.dirPath))) ?: return@withContext Result.failure()
        val started = System.currentTimeMillis()
        fun stage(status: String, detail: String) = runBlocking {
            db.pipeline().upsertStage(PipelineStageEntity(lessonId, STAGE, status, started, System.currentTimeMillis(), detail))
        }
        val model = PhoneWriter.open(applicationContext) ?: run {
            stage("waiting", "The note writer isn't on this phone yet")
            return@withContext Result.failure(workDataOf(KEY_ERROR to "no model"))
        }
        val power = applicationContext.getSystemService(PowerManager::class.java)
        val warm = { power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE }
        val cards = model.use { m ->
            PhoneCards.write(m, PhoneCards.blocks(out), isStopped = { isStopped || warm() }) { done, total, _ ->
                runBlocking { setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total)) }
            }
        }
        val todo = PhoneCards.blocks(out).count(PhoneCards::worthWriting)
        if (cards.size < todo) {
            stage("waiting", if (warm()) "Paused while the phone cools down" else "Paused when the phone came off the charger")
            return@withContext Result.retry()
        }
        val feedback = db.pipeline().feedback(lessonId).associateBy { it.id }
        val rows = cards.filter { !it.unreadable }.mapNotNull { c -> feedback[c.localId]?.let { entity(lessonId, it, c) } }
        db.cards().replacePhoneCards(lessonId, rows)
        val mins = (System.currentTimeMillis() - started) / 60_000.0
        stage("done", "${rows.size} cards by ${model.name} in ${"%.1f".format(mins)} min" +
            cards.count { it.repaired }.let { if (it > 0) ", $it quotes replaced with her own words" else "" })
        Result.success()
    }

    private fun entity(lessonId: String, f: FeedbackEntity, c: PhoneCard) = CardEntity(
        id = CloudIds.feedback(f.id),
        lessonId = lessonId,
        localId = f.id,
        takeId = f.takeId?.let(CloudIds::take),
        spanStartMs = f.spanStartMs,
        spanEndMs = f.spanEndMs,
        cue = c.cue,
        category = c.category,
        herWords = c.herWords,
        practiceFlag = null,
        barStart = null,
        barEnd = null,
        transcript = f.words.trim(),
        editedByTeacher = false,
        updatedAt = "",
        summary = c.summary,
        weight = c.weight,
        source = CardEntity.SOURCE_PHONE_WRITER,
    )

    companion object {
        const val KEY_LESSON = "lessonId"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        const val STAGE = "phone_cards"

        fun workName(lessonId: String) = "phone-cards-$lessonId"

        /**
         * Gets the note writer onto the phone if it isn't (over Wi-Fi), then
         * writes the lesson's cards the next time the phone charges.
         */
        fun enqueue(context: Context, lessonId: String) {
            val wm = WorkManager.getInstance(context)
            val write = OneTimeWorkRequestBuilder<PhoneCardWorker>()
                .setInputData(workDataOf(KEY_LESSON to lessonId))
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
                .build()
            val download = OneTimeWorkRequestBuilder<PhoneWriterDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresStorageNotLow(true).build())
                .build()
            wm.beginUniqueWork(workName(lessonId), ExistingWorkPolicy.REPLACE, download).then(write).enqueue()
        }

        fun cancel(context: Context, lessonId: String) = WorkManager.getInstance(context).cancelUniqueWork(workName(lessonId))

        data class Progress(val state: WorkInfo.State, val downloading: Boolean, val percent: Int, val done: Int, val total: Int)

        /** Where the writing stands: the download, then the cards; null when nothing was asked for. */
        fun progress(context: Context, lessonId: String): Flow<Progress?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(workName(lessonId)).map { infos ->
                val dl = infos.firstOrNull { it.tags.contains(PhoneWriterDownloadWorker::class.java.name) }
                val wr = infos.firstOrNull { it.tags.contains(PhoneCardWorker::class.java.name) }
                when {
                    dl != null && !dl.state.isFinished -> Progress(dl.state, true, dl.progress.getInt(PhoneWriterDownloadWorker.KEY_PERCENT, 0), 0, 0)
                    wr != null -> Progress(wr.state, false, 0, wr.progress.getInt(KEY_DONE, 0), wr.progress.getInt(KEY_TOTAL, 0))
                    else -> null
                }
            }
    }
}

/** Fetches the vetted note writer (about 2 GB) once, on Wi-Fi. Does nothing when it's here or Gemini Nano is. */
class PhoneWriterDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (PhoneWriter.hasWeights(applicationContext) || PhoneWriter.nanoStatus() == "available") return@withContext Result.success()
        try {
            PhoneWriter.download(applicationContext, onProgress = { done, total ->
                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                runBlocking { setProgress(workDataOf(KEY_PERCENT to pct)) }
            }, isStopped = { isStopped })
            Result.success()
        } catch (e: Exception) {
            if (isStopped || runAttemptCount < 5) Result.retry() else Result.failure(workDataOf("error" to e.toString()))
        }
    }

    companion object { const val KEY_PERCENT = "percent" }
}
