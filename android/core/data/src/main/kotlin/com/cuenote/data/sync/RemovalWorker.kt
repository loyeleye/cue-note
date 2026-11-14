package com.cuenote.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cuenote.data.AuthRepository
import com.cuenote.data.LessonDatabase
import com.cuenote.data.LessonParts
import com.cuenote.data.LessonRemovals
import com.cuenote.data.LessonTeacherStore
import com.cuenote.data.SetlistStore
import com.cuenote.data.pipeline.ResultImporter
import com.cuenote.pipeline.Pipeline
import com.cuenote.pipeline.Removal
import com.cuenote.pipeline.TranscriptLine
import com.cuenote.pipeline.audio.AudioEraser
import com.cuenote.pipeline.audio.LessonFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Deletes the parts of a lesson the student deleted, once Undo has passed
 * (see [LessonRemovals]). Two steps, chained as the unique work
 * "remove-<lessonId>":
 *
 *   [Local]  on the phone: the clips, tries, feedback and cards in the part,
 *            the setlist switches and practice takes of those cards, what was
 *            said in it (the transcript), and the audio itself, silenced
 *   [Cloud]  online, once there's a network: the same rows, leaving
 *            tombstones so making the notes again doesn't bring the cards back,
 *            and their clips. Nothing for a lesson kept on the phone.
 *
 * Each step is safe to run again, and only marks a deletion done once it is.
 */
object RemovalWorker {
    const val KEY_LESSON = "lessonId"

    fun workName(lessonId: String) = "remove-$lessonId"

    /** Starts (or restarts, after another delete) the countdown to deleting. */
    fun enqueue(context: Context, lessonId: String) {
        val data = workDataOf(KEY_LESSON to lessonId)
        val local = OneTimeWorkRequestBuilder<Local>()
            .setInputData(data)
            .setInitialDelay(LessonRemovals.UNDO_MS + 2_000, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .addTag("remove")
            .build()
        val cloud = OneTimeWorkRequestBuilder<Cloud>()
            .setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag("remove")
            .build()
        WorkManager.getInstance(context).beginUniqueWork(workName(lessonId), ExistingWorkPolicy.REPLACE, local).then(cloud).enqueue()
    }

    /** Deletes the phone's copies. Waits while the lesson's audio is being read or rewritten. */
    class Local(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            val lessonId = inputData.getString(KEY_LESSON) ?: return@withContext Result.failure()
            val db = LessonDatabase.get(applicationContext)
            val lesson = db.lessons().get(lessonId) ?: return@withContext Result.success()   // deleted meanwhile
            val dir = File(lesson.dirPath)
            if (busy(applicationContext, lessonId)) return@withContext Result.retry()

            // Past the undo window: committed, so the pipeline's output leaves them out from now on.
            val now = System.currentTimeMillis()
            val all = LessonRemovals.update(dir) { list ->
                list.map { if (!it.committed && now >= it.at + LessonRemovals.UNDO_MS) it.copy(committed = true) else it }
            }
            val todo = all.filter { it.committed && !it.localDone }
            if (todo.isEmpty()) return@withContext Result.success()
            try {
                deleteOnPhone(applicationContext, db, lessonId, dir, todo)
            } catch (e: Exception) {
                return@withContext if (runAttemptCount < 5) Result.retry() else Result.failure(workDataOf("error" to e.toString()))
            }
            val done = todo.map { it.id }.toSet()
            LessonRemovals.update(dir) { list -> list.map { if (it.id in done) it.copy(localDone = true) else it } }
            Result.success()
        }
    }

    /** Deletes the copies online. A lesson kept on the phone, or never uploaded, has none. */
    class Cloud(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            val lessonId = inputData.getString(KEY_LESSON) ?: return@withContext Result.failure()
            val lesson = LessonDatabase.get(applicationContext).lessons().get(lessonId) ?: return@withContext Result.success()
            try {
                deleteOnline(applicationContext, lessonId, File(lesson.dirPath))
                Result.success()
            } catch (e: Exception) {
                if (runAttemptCount < 8) Result.retry() else Result.failure(workDataOf("error" to e.toString()))
            }
        }
    }

    /**
     * Deletes online the committed parts not deleted there yet. Signed out, it
     * waits: the next upload of the lesson calls this first.
     */
    suspend fun deleteOnline(context: Context, lessonId: String, dir: File) {
        val todo = LessonRemovals.read(dir).filter { it.committed && !it.cloudDone }
        if (todo.isEmpty()) return
        val done = if (LessonTeacherStore(context).isLocalOnly(lessonId)) {
            // Kept on the phone: nothing of it is online (an earlier copy is deleted whole when it was made local-only).
            todo
        } else {
            val auth = AuthRepository.get()
            val cloud = CloudSync(auth)
            if (!auth.isConfigured || cloud.studentId() == null) return
            todo.onEach { cloud.removeLessonPart(lessonId, it.startMs, it.endMs, it.whole) }
        }
        val ids = done.map { it.id }.toSet()
        LessonRemovals.update(dir) { list -> list.map { if (it.id in ids) it.copy(cloudDone = true) else it } }
    }

    /** True while the lesson is being processed, uploaded or compressed: all of them read its audio. */
    private fun busy(context: Context, lessonId: String): Boolean {
        val wm = WorkManager.getInstance(context)
        return listOf("pipeline-$lessonId", "upload-$lessonId", "compress-$lessonId").any { name ->
            wm.getWorkInfosForUniqueWork(name).get().any { it.state == WorkInfo.State.RUNNING }
        }
    }

    private val lines = ListSerializer(TranscriptLine.serializer())

    /** Everything of [todo] on the phone. Safe to run again. */
    private suspend fun deleteOnPhone(context: Context, db: LessonDatabase, lessonId: String, dir: File, todo: List<Removal>) {
        val pipeline = db.pipeline()
        val cards = db.cards()
        val exercises = pipeline.exercises(lessonId)
        val takes = pipeline.takes(lessonId)
        val feedback = pipeline.feedback(lessonId)
        val cut = LessonParts.cut(todo, exercises)
        val goneTakes = takes.filter { cut.takeGone(it.id, it.startMs, it.endMs, it.exerciseId) }.map { it.id }.toSet()
        val goneFeedback = feedback.filter { cut.feedbackGone(it.spanStartMs, it.spanEndMs, it.exerciseId) }.map { it.id }.toSet()

        // 1. replay clips
        val goneClips = cards.clips(lessonId).filter {
            (it.kind == "take" && it.localId in goneTakes) || (it.kind == "feedback" && it.localId in goneFeedback)
        }
        goneClips.forEach { File(it.file).delete() }
        if (goneClips.isNotEmpty()) cards.deleteClips(goneClips.map { it.id })

        // 2. cards, and what hangs off them on the phone
        val goneCards = cards.cards(lessonId).filter { LessonParts.cardGone(it, cut, goneFeedback) }.map { it.id }
        if (goneCards.isNotEmpty()) {
            cards.deleteCards(goneCards)
            SetlistStore(context).forget(goneCards)
            val practice = db.practice()
            for (t in practice.takesForCards(goneCards)) {
                File(t.file).delete(); File(t.file.removeSuffix(".wav") + ".pitch.json").delete()
                File(t.file.removeSuffix(".wav")).deleteRecursively()   // posture, when it was on
                // one the teacher has must also go from the cloud: the practice sync does that
                if (t.shared || t.cloudShared) practice.markDeleted(t.id) else practice.deleteTake(t.id)
            }
            for (q in practice.questions().filter { it.cardId in goneCards && it.status != "dropped" }) {
                practice.upsertQuestion(q.copy(status = "dropped", dirty = true))
            }
            runCatching { PracticeSyncWorker.enqueue(context) }
        }
        if (goneFeedback.isNotEmpty()) db.photos().unlinkFeedback(lessonId, goneFeedback.toList())

        // 3. what the pipeline found, and what was said, kept without the parts (the output is read less them now)
        val folder = LessonFolder(dir)
        val pipelineDir = folder.pipelineDir
        Pipeline.readOutput(folder)?.let { out ->
            val titles = exercises.associate { it.id to it.title }
            ResultImporter.import(pipeline, lessonId, out, Pipeline(folder, com.cuenote.ml.EnergyVad(), null).state())
            for (e in pipeline.exercises(lessonId)) titles[e.id]?.let { pipeline.setExerciseTitle(e.id, it) }
            File(pipelineDir, "output.json").writeText(Pipeline.json.encodeToString(Pipeline.Output.serializer(), out))
        }
        val transcript = File(pipelineDir, "transcript.json")
        if (transcript.isFile) {
            val kept = Pipeline.json.decodeFromString(lines, transcript.readText()).filterNot { l ->
                todo.any { r -> r.covers(l.startMs, l.endMs) && (r.whole || l.speaker == com.cuenote.pipeline.Speaker.STUDENT) }
            }
            transcript.writeText(Pipeline.json.encodeToString(lines, kept))
        }
        // the half-written transcript and the debug pages have the words in them; both are made again when needed
        File(pipelineDir, "transcript.partial.jsonl").delete()
        File(pipelineDir, "timeline.html").delete()
        if (todo.any { it.whole }) File(pipelineDir, "model-compare.json").delete()

        // 4. the recording itself
        AudioEraser.silence(dir, todo.map { it.startMs..it.endMs })

        // 5. a lesson kept on the phone: its notes again, from what is left
        if (LessonTeacherStore(context).isLocalOnly(lessonId)) com.cuenote.data.writePhoneNotes(pipeline, cards, lessonId)
    }
}
