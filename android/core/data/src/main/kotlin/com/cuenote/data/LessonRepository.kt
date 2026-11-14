package com.cuenote.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.cuenote.audio.RecordingSummary
import com.cuenote.data.pipeline.PipelineScheduler
import com.cuenote.data.sync.CloudSync
import com.cuenote.pipeline.Pipeline
import com.cuenote.pipeline.audio.LessonFolder
import com.cuenote.pipeline.cards.CueRequest
import com.cuenote.pipeline.cards.ModelTrials
import kotlinx.serialization.json.Json
import com.cuenote.data.sync.LessonUploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private val compareJson = Json { ignoreUnknownKeys = true }

class LessonRepository(
    private val context: Context,
    private val dao: LessonDao,
    val pipelineDao: PipelineDao? = null,
    val cardDao: CardDao? = null,
    private val photoDao: PhotoDao? = null,
    private val practiceDao: PracticeDao? = null,
) {

    val lessons = dao.observeAll()

    /** Who taught each lesson, and which lessons stay on this phone. */
    val teachers = LessonTeacherStore(context)

    /** The teacher asked that this lesson stays on the phone: nothing of it goes to the cloud or an AI. */
    fun isLocalOnly(id: String) = teachers.isLocalOnly(id)

    /** Card and practice counts per lesson, for the badges on the home screen. */
    val cardCounts: kotlinx.coroutines.flow.Flow<Map<String, CardCount>> =
        cardDao?.observeCounts()?.map { list -> list.associateBy { it.lessonId } }
            ?: kotlinx.coroutines.flow.flowOf(emptyMap())

    /** The student's own on/off switches for cards on the setlist. */
    val setlistStore = SetlistStore(context)

    /** What to practice: cards on the setlist, newest lesson first. */
    val setlist: kotlinx.coroutines.flow.Flow<List<SetlistRow>> =
        (cardDao?.observeWithLessons() ?: kotlinx.coroutines.flow.flowOf(emptyList()))
            .combine(setlistStore.overrides) { rows, o -> rows.filter { SetlistStore.isOn(it.card, o) } }

    /** Lesson folders live in the app's own storage: lessons/<id>/audio/chunk_*.wav, events.tsv, photos/. */
    private val root: File get() = File(context.getExternalFilesDir(null), "lessons")

    fun lessonDir(id: String) = File(root, id)

    suspend fun create(consentAt: Long): LessonEntity {
        val id = UUID.randomUUID().toString()
        val lesson = LessonEntity(
            id = id,
            recordedAt = System.currentTimeMillis(),
            consentAt = consentAt,
            dirPath = lessonDir(id).absolutePath,
        )
        lessonDir(id).mkdirs()
        dao.insert(lesson)
        return lesson
    }

    /** Saves a finished recording and queues it for processing the next time the phone charges. */
    suspend fun finish(s: RecordingSummary) {
        dao.finish(
            id = s.lessonId, state = "recorded", durationMs = s.durationMs, chunks = s.chunkCount,
            gaps = s.gapCount, marks = s.markCount, photos = s.photoCount, mic = s.micName,
        )
        if (s.chunkCount > 0) PipelineScheduler.enqueue(context, s.lessonId)
        if (s.photoCount > 0) com.cuenote.data.sketch.SketchWorker.enqueue(context, s.lessonId)
    }

    /** Runs the pipeline now instead of waiting for the charger; [again] starts it over. */
    fun process(id: String, again: Boolean = false) = PipelineScheduler.enqueue(context, id, now = true, again = again)

    fun progress(id: String) = PipelineScheduler.progress(context, id)

    /** Cuts the clips, uploads the lesson and asks for its cards (again, if it has them). */
    fun makeCards(id: String) = LessonUploadWorker.enqueue(context, id)

    fun cardProgress(id: String) = LessonUploadWorker.progress(context, id)

    /** Lessons that were processed but have no cards yet, for after signing in. */
    suspend fun makeMissingCards() = dao.withState("processed").forEach { makeCards(it.id) }

    /** Where the last model comparison for a lesson is kept. */
    fun comparisonFile(id: String) = File(lessonDir(id), "pipeline/model-compare.json")

    /** The last model comparison, if one was run. */
    fun lastComparison(id: String): ModelTrials? = comparisonFile(id).takeIf { it.isFile }
        ?.let { runCatching { compareJson.decodeFromString(ModelTrials.serializer(), it.readText()) }.getOrNull() }

    /**
     * Runs the lesson through each model and keeps the result next to the
     * lesson. No cards change. The lesson must already have its cards (so it
     * is in the cloud).
     */
    suspend fun compareModels(id: String, models: List<String> = ModelTrials.MODELS.keys.toList()): ModelTrials =
        withContext(Dispatchers.IO) {
            if (isLocalOnly(id)) throw LocalOnly.Refused(id)
            val out = Pipeline.readOutput(LessonFolder(lessonDir(id))) ?: error("This lesson isn't processed yet")
            val cloud = CloudSync(AuthRepository.get())
            requireNotNull(cloud.studentId()) { "Sign in first" }
            val (trials, text) = cloud.compareModels(CueRequest.from(id, out) { null }, models)
            comparisonFile(id).writeText(text)
            trials
        }

    /** Copies the lesson's cards from the cloud again, her latest edits included. */
    suspend fun refreshCards(id: String) = withContext(Dispatchers.IO) {
        if (isLocalOnly(id)) return@withContext
        val cloud = CloudSync(AuthRepository.get())
        if (cloud.studentId() == null) return@withContext
        val fresh = cloud.cards(id)
        // A lesson that was kept on the phone until now has only the phone's notes: keep them until the cloud has cards.
        val phoneOnly = cardDao?.cards(id)?.let { have -> have.isNotEmpty() && have.all { it.onPhone } } == true
        if (fresh.isNotEmpty() || !phoneOnly) cardDao?.replaceCards(id, fresh)
        refreshExerciseTitles(id, cloud)
        forgetRemovedClips(id, cloud)
    }

    /** The exercises' names, so the lesson reads "Lip trill on a 5-note scale" rather than "Warmup 2". */
    private suspend fun refreshExerciseTitles(id: String, cloud: CloudSync) {
        val dao = pipelineDao ?: return
        val titles = runCatching { cloud.exerciseTitles(id) }.getOrNull() ?: return
        for (e in dao.exercises(id)) {
            val title = titles[com.cuenote.pipeline.cards.CloudIds.exercise(e.id)]
            if (title != e.title) dao.setExerciseTitle(e.id, title)
        }
    }

    /** A teacher deleted a card or removed its audio: the phone's copy of that clip goes too. */
    private suspend fun forgetRemovedClips(id: String, cloud: CloudSync) {
        val dao = cardDao ?: return
        val removed = cloud.removedClipIds(id).toList()
        if (removed.isEmpty()) return
        dao.clipsById(removed).forEach { File(it.file).delete() }
        dao.deleteClips(removed)
    }

    /**
     * For a lesson kept on the phone: writes the phone's own notes as cards
     * (see [PhoneNotes]), so they can go on the setlist. Notes the student put
     * in their own words stay. Does nothing for any other lesson.
     */
    suspend fun writePhoneNotes(id: String) = withContext(Dispatchers.IO) {
        if (!isLocalOnly(id)) return@withContext
        val pd = pipelineDao ?: return@withContext
        val cd = cardDao ?: return@withContext
        writePhoneNotes(pd, cd, id)
    }

    /** The student's own words for one of the phone's notes; blank takes them off. */
    suspend fun setOwnWords(cardId: String, text: String) = withContext(Dispatchers.IO) {
        cardDao?.setOwnWords(cardId, PhoneNotes.ownWords(text))
    }

    /** True once the speech models are on the phone. */
    fun modelsReady() = com.cuenote.ml.ModelFiles.missing(PipelineScheduler.modelsDir(context), PipelineScheduler.MODELS).isEmpty()

    fun downloadModels() = com.cuenote.data.pipeline.ModelDownloadWorker.start(context)

    fun modelDownload() = com.cuenote.data.pipeline.ModelDownloadWorker.progress(context)

    suspend fun failed(id: String) = dao.finish(id, "failed", 0, 0, 0, 0, 0, "")

    /**
     * Deletes a lesson everywhere: online first (when signed in), then on the
     * phone. If the online part fails, the phone keeps its copy and this
     * throws, so nothing is half-deleted without the student knowing.
     */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        // A local-only lesson was never sent, so there's nothing to delete online.
        if (!isLocalOnly(id)) {
            val cloud = CloudSync(AuthRepository.get())
            cloud.studentId()?.let { student -> cloud.deleteLesson(student, id) }
        }
        lessonDir(id).deleteRecursively()
        pipelineDao?.clearAll(id)
        photoDao?.clear(id)
        cardDao?.clearAll(id)
        // Practice takes of the lesson's notes go with it.
        practiceDao?.takesForLesson(id)?.forEach {
            File(it.file).delete(); File(it.file.removeSuffix(".wav") + ".pitch.json").delete()
            File(it.file.removeSuffix(".wav")).deleteRecursively()   // posture, when it was on
        }
        practiceDao?.deleteLessonTakes(id)
        practiceDao?.deleteLessonQuestions(id)
        dao.delete(id)
        teachers.forget(id)
    }

    /**
     * Copies a lesson's files to Downloads/CueNote/<id>/ so they can be moved
     * to a laptop over USB. M2 uses these recordings as its test fixtures.
     * Returns the number of files copied.
     */
    suspend fun exportToDownloads(id: String): Int = withContext(Dispatchers.IO) {
        val dir = lessonDir(id)
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        val resolver = context.contentResolver
        files.forEach { f ->
            val rel = f.parentFile!!.relativeTo(dir).path.let { if (it.isEmpty()) "" else "/$it" }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/CueNote/$id$rel")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("could not create ${f.name} in Downloads")
            resolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        files.size
    }
}

/** The phone's own notes for [lessonId], written from what the pipeline found (see [PhoneNotes]). */
suspend fun writePhoneNotes(pipeline: PipelineDao, cards: CardDao, lessonId: String) {
    // Cards from the cloud are left alone; a lesson that has them doesn't need the phone's.
    if (cards.cards(lessonId).any { !it.onPhone }) return
    val fresh = PhoneNotes.fromGuesses(lessonId, pipeline.exercises(lessonId), pipeline.takes(lessonId), pipeline.feedback(lessonId))
    cards.replacePhoneCards(lessonId, fresh)
}
