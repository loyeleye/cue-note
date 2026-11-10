package com.cuenote.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.cuenote.audio.RecordingSummary
import com.cuenote.data.pipeline.PipelineScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class LessonRepository(
    private val context: Context,
    private val dao: LessonDao,
    val pipelineDao: PipelineDao? = null,
    private val photoDao: PhotoDao? = null,
) {

    val lessons = dao.observeAll()

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

    /** True once the speech models are on the phone. */
    fun modelsReady() = com.cuenote.ml.ModelFiles.missing(PipelineScheduler.modelsDir(context), PipelineScheduler.MODELS).isEmpty()

    fun downloadModels() = com.cuenote.data.pipeline.ModelDownloadWorker.start(context)

    fun modelDownload() = com.cuenote.data.pipeline.ModelDownloadWorker.progress(context)

    suspend fun failed(id: String) = dao.finish(id, "failed", 0, 0, 0, 0, 0, "")

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lessonDir(id).deleteRecursively()
        pipelineDao?.clearAll(id)
        photoDao?.clear(id)
        dao.delete(id)
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
