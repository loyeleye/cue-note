package com.cuenote.data

import android.content.Context
import com.cuenote.audio.Listening
import com.cuenote.audio.Output
import com.cuenote.audio.PracticeRecorder
import com.cuenote.pipeline.Pipeline
import com.cuenote.pipeline.audio.LessonFolder
import com.cuenote.pipeline.practice.BackingAudio
import com.cuenote.pipeline.practice.BackingPlan
import com.cuenote.pipeline.practice.BackingSpan
import com.cuenote.pipeline.practice.PitchCompare
import com.cuenote.pipeline.practice.PitchTrack
import com.cuenote.pipeline.practice.TakePitch
import com.cuenote.pipeline.practice.LoggedSession
import com.cuenote.pipeline.practice.LoggedTake
import com.cuenote.pipeline.practice.PracticeLog
import com.cuenote.pipeline.practice.PracticeWeek
import com.cuenote.pipeline.practice.Questions
import com.cuenote.data.sync.PracticeSyncWorker
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json
import com.cuenote.pipeline.practice.StartingNote
import com.cuenote.pipeline.practice.TimeStretch
import com.cuenote.signal.WavReader
import com.cuenote.signal.WavWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Practice between lessons, on the phone: the takes for each card, the
 * sittings they belong to, and the headphone timing from the timing check.
 */
class PracticeRepository(
    private val context: Context,
    private val dao: PracticeDao,
    private val cards: CardDao,
) {
    /** Practice takes live beside the lessons: practice/<take id>.wav. */
    private val root: File get() = File(context.getExternalFilesDir(null), "practice")

    val timing = PracticeTiming(context)

    fun card(cardId: String): Flow<SetlistRow?> = cards.observeRow(cardId)

    fun takes(cardId: String): Flow<List<PracticeTakeEntity>> = dao.observeTakes(cardId)

    /** A fresh file for the next take. */
    fun newTakeFile(): File = File(root, "${UUID.randomUUID()}.wav")

    /** Where posture tracking for a take goes (practice/<take id>/posture/), when it was on. */
    fun postureDir(takeFile: File): File = File(takeFile.parentFile, takeFile.nameWithoutExtension)

    /** Saves a finished take and adds it to this sitting of practice (or starts one). */
    suspend fun saveTake(
        card: CardEntity,
        take: PracticeRecorder.Take,
        startedAt: Long,
        output: Output,
        latencyMs: Int?,
    ): PracticeTakeEntity = withContext(Dispatchers.IO) {
        val endedAt = startedAt + take.durationMs
        val last = dao.lastSession()
        val session = if (last != null && startedAt - last.endedAt <= SESSION_GAP_MS) {
            last.copy(endedAt = maxOf(last.endedAt, endedAt))
        } else {
            PracticeSessionEntity(UUID.randomUUID().toString(), startedAt, endedAt)
        }
        dao.upsertSession(session)
        val row = PracticeTakeEntity(
            id = take.file.nameWithoutExtension,
            sessionId = session.id,
            cardId = card.id,
            lessonId = card.lessonId,
            file = take.file.absolutePath,
            recordedAt = startedAt,
            durationMs = take.durationMs,
            input = if (take.input == PracticeRecorder.Input.LESSON_MIC) "lesson_mic" else "phone",
            listening = output.listening.name.lowercase(),
            latencyMs = latencyMs,
            playbackStartFrame = take.playbackStartFrame,
            feelingNote = null,
            shared = false,
        )
        dao.upsertTake(row)
        PracticeSyncWorker.enqueue(context)   // the log summary
        row
    }

    /** A card's backing: tracks cut from the teacher's channel, and the take's first note. */
    data class Backing(val tracks: List<Track>, val startingNote: StartingNote.Note?) {
        val accompaniment: Track? get() = tracks.firstOrNull { it.span.kind == BackingSpan.Kind.ACCOMPANIMENT }
        val demos: List<Track> get() = tracks.filter { it.span.kind == BackingSpan.Kind.DEMO }
    }

    data class Track(val span: BackingSpan, val file: File)

    /**
     * Cuts (once) and returns the backing for [card], from the lesson
     * recording still on the phone. Null when the lesson's audio or its
     * processing isn't here (a card the phone didn't make, or a deleted lesson).
     */
    suspend fun backing(card: CardEntity): Backing? = withContext(Dispatchers.IO) {
        val local = card.localId ?: return@withContext null
        val folder = LessonFolder(File(File(context.getExternalFilesDir(null), "lessons"), card.lessonId))
        if (folder.chunks.isEmpty()) return@withContext null
        val out = Pipeline.readOutput(folder) ?: return@withContext null
        val dir = File(folder.dir, "practice/$local").apply { mkdirs() }
        val tracks = BackingPlan.forCard(out, local).map { span ->
            val f = File(dir, span.fileName)
            if (!f.isFile) writeWav(f, BackingAudio.cut(folder, span))
            Track(span, f)
        }
        val take = out.feedback.firstOrNull { it.id == local }?.takeId?.let { id -> out.takes.firstOrNull { it.id == id } }
        Backing(tracks, take?.let { StartingNote.of(it) })
    }

    /** The track at [rate] of its speed (same pitch), made once and kept beside it. */
    suspend fun slowed(track: Track, rate: Double): File = withContext(Dispatchers.IO) {
        if (rate == 1.0) return@withContext track.file
        val f = File(track.file.parentFile, track.file.nameWithoutExtension + "_" + (rate * 100).toInt() + ".wav")
        if (!f.isFile) writeWav(f, TimeStretch.stretch(pcm(track.file), rate))
        f
    }

    /** A WAV's samples, for playing along. */
    suspend fun pcm(file: File): ShortArray = withContext(Dispatchers.IO) { WavReader.read(file).samples }

    /**
     * The take's pitch, note by note, against the teacher's first sung demo
     * when the note has one. Worked out once after the take and kept beside
     * it as <take>.pitch.json.
     */
    suspend fun pitch(take: PracticeTakeEntity, backing: Backing?): TakePitch? = withContext(Dispatchers.Default) {
        val wav = File(take.file)
        if (!wav.isFile) return@withContext null
        val cache = File(wav.parentFile, wav.nameWithoutExtension + ".pitch.json")
        val demoFile = backing?.demos?.firstOrNull()?.file
        val stamp = demoFile?.name ?: "none"
        runCatching {
            val saved = pitchJson.decodeFromString(CachedPitch.serializer(), cache.readText())
            if (saved.demo == stamp) return@withContext saved.pitch
        }
        val contour = PitchTrack.of(WavReader.read(wav).samples)
        val demo = demoFile?.takeIf { it.isFile }?.let { PitchTrack.of(WavReader.read(it).samples) }
        val result = PitchCompare.compare(contour, demo)
        runCatching { cache.writeText(pitchJson.encodeToString(CachedPitch.serializer(), CachedPitch(stamp, result))) }
        result
    }

    /** The pitch worked out after the take, if it was. */
    fun cachedPitch(take: PracticeTakeEntity): TakePitch? = runCatching {
        val cache = File(take.file.removeSuffix(".wav") + ".pitch.json")
        pitchJson.decodeFromString(CachedPitch.serializer(), cache.readText()).pitch
    }.getOrNull()

    @kotlinx.serialization.Serializable
    private data class CachedPitch(val demo: String, val pitch: TakePitch)

    /** A short tone of the starting note, kept in the cache. */
    suspend fun toneFile(note: StartingNote.Note): File = withContext(Dispatchers.IO) {
        val f = File(context.cacheDir, "note_${note.midi}.wav")
        if (!f.isFile) writeWav(f, StartingNote.tone(note))
        f
    }

    private fun writeWav(f: File, pcm: ShortArray) {
        val tmp = File(f.parentFile, f.name + ".part")
        WavWriter(tmp, PracticeRecorder.SAMPLE_RATE, 1).use { it.write(pcm) }
        tmp.renameTo(f)
    }

    /**
     * Deletes a take from the phone. One the teacher has (or is about to
     * have) is taken out of the cloud at the next sync.
     */
    suspend fun deleteTake(id: String) = withContext(Dispatchers.IO) {
        val t = dao.take(id) ?: return@withContext
        File(t.file).delete(); File(t.file.removeSuffix(".wav") + ".pitch.json").delete()
        postureDir(File(t.file)).deleteRecursively()
        if (t.shared || t.cloudShared) dao.markDeleted(id) else dao.deleteTake(id)
        PracticeSyncWorker.enqueue(context)
    }

    // ------------------------------------------------------------ The next lesson

    /** Shared takes the teacher hasn't listened to yet. */
    fun waiting(): Flow<List<PracticeTakeEntity>> = dao.observeWaiting()

    /** Every take (not deleted) recorded since [since], newest first. */
    fun takesSince(since: Long): Flow<List<PracticeTakeEntity>> = dao.observeTakesSince(since)

    /** Shares a take with the student's teachers, or stops sharing it. Goes up at the next sync. */
    suspend fun share(take: PracticeTakeEntity, on: Boolean) = withContext(Dispatchers.IO) {
        dao.setShared(take.id, on)
        PracticeSyncWorker.enqueue(context)
    }

    /** The questions not dropped, newest first. */
    fun questions(): Flow<List<PracticeQuestionEntity>> = dao.observeQuestions()

    /** Asks the teacher a question, about [card] (and maybe a bar of it) or about nothing in particular. */
    suspend fun ask(card: CardEntity?, bar: Int?, body: String): PracticeQuestionEntity? = withContext(Dispatchers.IO) {
        val text = Questions.clean(body) ?: return@withContext null
        val q = PracticeQuestionEntity(
            id = UUID.randomUUID().toString(), cardId = card?.id, lessonId = card?.lessonId, bar = bar,
            body = text, status = "open", answer = null, answeredAt = null,
            createdAt = System.currentTimeMillis(), dirty = true,
        )
        dao.upsertQuestion(q)
        PracticeSyncWorker.enqueue(context)
        q
    }

    /** Takes a question back. */
    suspend fun drop(id: String) = withContext(Dispatchers.IO) {
        val q = dao.question(id) ?: return@withContext
        dao.upsertQuestion(q.copy(status = "dropped", dirty = true))
        PracticeSyncWorker.enqueue(context)
    }

    /** The practice of the last seven days. */
    fun week(now: Long = System.currentTimeMillis()): Flow<PracticeWeek> {
        val since = now - 7 * 86_400_000L
        return combine(dao.observeSessions(), dao.observeTakesSince(since)) { sessions, takes ->
            PracticeLog.week(
                sessions.map { LoggedSession(it.id, it.startedAt, it.endedAt) },
                takes.map { LoggedTake(it.cardId, it.sessionId, it.recordedAt, it.durationMs) },
                since,
            )
        }
    }

    /** Fetches answers, the teacher's listening and the lesson date now (when online). */
    fun refresh() = PracticeSyncWorker.enqueue(context)

    val agenda = PracticeAgenda(context)

    companion object {
        /** Takes closer together than this count as one sitting of practice. */
        const val SESSION_GAP_MS = 20 * 60_000L

        /** A take shorter than this is a mis-tap, and isn't kept. */
        const val MIN_TAKE_MS = 1_000L
    }
}

/** Pitch lines have gaps (NaN) where nothing was sung. */
private val pitchJson = Json { allowSpecialFloatingPointValues = true; ignoreUnknownKeys = true }

/** What the teachers set for the next lesson, kept from the last sync. */
class PracticeAgenda(context: Context) {
    private val prefs = context.getSharedPreferences("practice_agenda", Context.MODE_PRIVATE)
    private val _nextLessonAt = MutableStateFlow(prefs.getLong(KEY_NEXT, 0L).takeIf { it > 0 })
    /** Epoch ms of the next lesson, when a teacher has set one. */
    val nextLessonAt: StateFlow<Long?> = _nextLessonAt.asStateFlow()

    fun setNextLesson(at: Long?) {
        prefs.edit().apply { if (at == null) remove(KEY_NEXT) else putLong(KEY_NEXT, at) }.apply()
        _nextLessonAt.value = at
    }

    private val _skipped = MutableStateFlow(prefs.getStringSet(KEY_SKIPPED, emptySet())!!.toSet())
    /** Takes the student said "not this one" to when offered before a lesson, so they aren't offered again. */
    val skipped: StateFlow<Set<String>> = _skipped.asStateFlow()

    fun skip(takeIds: Collection<String>) {
        if (takeIds.isEmpty()) return
        // Only the last few hundred matter; older takes are long past their lesson.
        val all = (_skipped.value + takeIds).toList().takeLast(300).toSet()
        prefs.edit().putStringSet(KEY_SKIPPED, all).apply()
        _skipped.value = all
    }

    private companion object {
        const val KEY_NEXT = "next_lesson_at"
        const val KEY_SKIPPED = "offer_skipped"
    }
}

/**
 * The timing check's result for each pair of headphones the student has
 * used, so it's asked for once per pair, not every time.
 */
class PracticeTiming(context: Context) {
    private val prefs = context.getSharedPreferences("practice_timing", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)
    /** Bumps whenever a result is saved or forgotten, for screens to re-read. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /** The saved delay for [output] in ms, or null if it hasn't been checked. */
    fun latencyMs(output: Output): Int? =
        if (output.listening == Listening.SPEAKER) null
        else prefs.getInt(output.key, -1).takeIf { it >= 0 }

    fun save(output: Output, latencyMs: Int) {
        prefs.edit().putInt(output.key, latencyMs).apply()
        _changes.value++
    }

    fun forget(output: Output) {
        prefs.edit().remove(output.key).apply()
        _changes.value++
    }
}
