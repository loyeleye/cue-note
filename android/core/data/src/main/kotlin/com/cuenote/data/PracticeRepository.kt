package com.cuenote.data

import android.content.Context
import com.cuenote.audio.Listening
import com.cuenote.audio.Output
import com.cuenote.audio.PracticeRecorder
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
        row
    }

    suspend fun deleteTake(id: String) = withContext(Dispatchers.IO) {
        dao.take(id)?.let { File(it.file).delete() }
        dao.deleteTake(id)
    }

    companion object {
        /** Takes closer together than this count as one sitting of practice. */
        const val SESSION_GAP_MS = 20 * 60_000L

        /** A take shorter than this is a mis-tap, and isn't kept. */
        const val MIN_TAKE_MS = 1_000L
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
