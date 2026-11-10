package com.cuenote.data.sketch

import android.content.Context
import com.cuenote.data.PhotoDao
import com.cuenote.data.PhotoEntity
import com.cuenote.pipeline.EventLink
import com.cuenote.signal.LessonEvent
import com.cuenote.sketch.Quad
import com.cuenote.sketch.SketchCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Lesson photos on the phone: which exist, which card each is on, and their
 * cleaned copies. Files live in the lesson folder: photos/<name>.jpg (the
 * original, never changed) and photos/clean/<name>.<n>.jpg (straightened and
 * whitened).
 */
class SketchRepository(private val context: Context, val dao: PhotoDao) {

    fun observe(lessonId: String): Flow<List<PhotoEntity>> = dao.observe(lessonId)

    fun original(lessonDir: File, p: PhotoEntity) = File(lessonDir, "photos/${p.fileName}")
    fun cleaned(lessonDir: File, p: PhotoEntity): File? = p.cleanFileName?.let { File(lessonDir, "photos/clean/$it") }

    /** Moves a photo to another card (or off every card, with null) by hand. */
    suspend fun move(photo: PhotoEntity, feedbackId: String?) {
        dao.move(photo.id, feedbackId, PhotoEntity.LINK_STUDENT)
        PhotoUploadWorker.enqueue(context, photo.lessonId)
    }

    /** Cleans the photo again with corners the student placed by hand. */
    suspend fun reclean(lessonDir: File, photo: PhotoEntity, corners: Quad, width: Int, height: Int) {
        clean(lessonDir, photo, corners.normalized(width, height))
        PhotoUploadWorker.enqueue(context, photo.lessonId)
    }

    /** Starts cleaning (and then uploading) a lesson's photos in the background. */
    fun process(lessonId: String) = SketchWorker.enqueue(context, lessonId)

    /**
     * When a lesson opens: picks up photos not seen yet (for lessons recorded
     * before this update), cleans any that need it, and brings down her moves.
     */
    suspend fun refresh(lessonId: String, lessonDir: File) {
        register(dao, lessonId, lessonDir)
        if (dao.forLesson(lessonId).any { it.cleanFileName == null }) process(lessonId)
        val client = com.cuenote.data.AuthRepository.get().client ?: return
        runCatching { PhotoUploadWorker.pull(client, dao, lessonId) }
    }

    companion object {
        private val corners = ListSerializer(Double.serializer())

        fun cornersOf(p: PhotoEntity): List<Double>? = p.corners?.let { runCatching { Json.decodeFromString(corners, it) }.getOrNull() }

        /** Adds a row for every photo in the lesson's events.tsv that has no row yet. */
        suspend fun register(dao: PhotoDao, lessonId: String, lessonDir: File) {
            val events = File(lessonDir, "events.tsv").takeIf { it.isFile }?.readLines()
                ?.mapNotNull(LessonEvent::parse)?.filterIsInstance<LessonEvent.Photo>().orEmpty()
            dao.insertNew(events.filter { File(lessonDir, "photos/${it.fileName}").isFile }.map {
                PhotoEntity(PhotoEntity.idFor(lessonId, it.fileName), lessonId, it.fileName, it.atMs)
            })
        }

        /** The pipeline's guesses (photo → nearest feedback by time). Photos moved by hand stay where they are. */
        suspend fun importLinks(dao: PhotoDao, lessonId: String, lessonDir: File, events: List<EventLink>) {
            register(dao, lessonId, lessonDir)
            for (e in events) if (e.kind == "photo" && e.fileName != null) {
                dao.setGuess(PhotoEntity.idFor(lessonId, e.fileName!!), e.feedbackId)
            }
        }

        /**
         * Straightens and whitens one photo. [normCorners] (8 fractions) are
         * the student's own; without them the page is found automatically.
         */
        suspend fun clean(dao: PhotoDao, lessonDir: File, photo: PhotoEntity, normCorners: List<Double>? = null) =
            withContext(Dispatchers.Default) {
                val src = File(lessonDir, "photos/${photo.fileName}")
                val bmp = Bitmaps.loadUpright(src)
                val raster = Bitmaps.toRaster(bmp)
                bmp.recycle()
                val given = normCorners?.let { Quad.fromNormalized(it, raster.width, raster.height) }
                val result = SketchCleaner.clean(raster, given)
                val dir = File(lessonDir, "photos/clean")
                // a new name each time, so screens showing the old one notice the change
                val stem = photo.fileName.substringBeforeLast('.')
                val n = (photo.cleanFileName?.substringBeforeLast('.')?.substringAfterLast('.')?.toIntOrNull() ?: 0) + 1
                val name = "$stem.$n.jpg"
                val out = Bitmaps.fromRaster(result.image)
                Bitmaps.saveJpeg(out, File(dir, name))
                out.recycle()
                photo.cleanFileName?.let { File(dir, it).delete() }
                val json = result.corners?.let { Json.encodeToString(corners, it.normalized(raster.width, raster.height)) }
                dao.setClean(photo.id, name, json, result.confidence.toFloat())
            }
    }

    private suspend fun clean(lessonDir: File, photo: PhotoEntity, normCorners: List<Double>?) =
        Companion.clean(dao, lessonDir, photo, normCorners)
}
