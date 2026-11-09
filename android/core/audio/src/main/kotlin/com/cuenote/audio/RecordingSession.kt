package com.cuenote.audio

import android.content.Context
import android.os.SystemClock
import com.cuenote.signal.ChunkedWavSink
import com.cuenote.signal.LessonAudio
import com.cuenote.signal.LessonEvent
import com.cuenote.signal.StereoLevels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter

/** What a finished recording looks like, also saved as summary.tsv in the lesson folder. */
data class RecordingSummary(
    val lessonId: String,
    val durationMs: Long,
    val chunkCount: Int,
    val gapCount: Int,
    val markCount: Int,
    val photoCount: Int,
    val micName: String,
)

sealed interface RecordingState {
    data object Idle : RecordingState
    data class Recording(
        val lessonId: String,
        val startedAtElapsedMs: Long,
        val micName: String,
        val marks: Int = 0,
        val photos: Int = 0,
        val gaps: Int = 0,
    ) : RecordingState
    data class Finished(val summary: RecordingSummary) : RecordingState
    data class Failed(val lessonId: String, val message: String) : RecordingState
}

/**
 * The one recording in progress. The service starts and stops it; the
 * recording screen reads its state and adds marks and photos. Everything is
 * written to the lesson folder as it happens, so a crash keeps what was
 * recorded up to the last chunk.
 */
object RecordingSession {
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _levels = MutableStateFlow<StereoLevels?>(null)
    val levels: StateFlow<StereoLevels?> = _levels.asStateFlow()

    private var recorder: LessonRecorder? = null
    private var sink: ChunkedWavSink? = null
    private var events: FileWriter? = null
    private var dir: File? = null
    private var chunks = 0

    /** Milliseconds since recording started, for stamping marks and photos. */
    fun elapsedMs(): Long {
        val s = _state.value as? RecordingState.Recording ?: return 0
        return SystemClock.elapsedRealtime() - s.startedAtElapsedMs
    }

    @Synchronized
    internal fun begin(context: Context, lessonId: String, lessonDir: File) {
        if (_state.value is RecordingState.Recording) return
        dir = lessonDir.apply { mkdirs() }
        chunks = 0
        events = FileWriter(File(lessonDir, "events.tsv"), true)
        sink = ChunkedWavSink(File(lessonDir, "audio"), onChunkClosed = { chunks++ })
        val rec = LessonRecorder(context.applicationContext)
        recorder = rec
        try {
            val mic = rec.start(
                sink = { buf, n -> sink?.write(buf, n) },
                onGap = { gap ->
                    log(LessonEvent.Gap(gap.atMs, gap.missingMs))
                    update { it.copy(gaps = it.gaps + 1) }
                },
            )
            // Note a switched setup in the lesson's own record, for anyone checking the audio later.
            val micLabel = if (rec.switchChannels) "$mic (left and right switched)" else mic
            _state.value = RecordingState.Recording(lessonId, SystemClock.elapsedRealtime(), micLabel)
            log(LessonEvent.MicChanged(0, micLabel))
            Forwarder.start(rec)
        } catch (e: Exception) {
            cleanup()
            _state.value = RecordingState.Failed(lessonId, e.message ?: "could not start recording")
        }
    }

    fun mark() {
        if (_state.value !is RecordingState.Recording) return
        log(LessonEvent.Mark(elapsedMs()))
        update { it.copy(marks = it.marks + 1) }
    }

    /** Call when the shutter is pressed, with the time from [elapsedMs] at that moment. */
    fun photo(atMs: Long, fileName: String) {
        if (_state.value !is RecordingState.Recording) return
        log(LessonEvent.Photo(atMs, fileName))
        update { it.copy(photos = it.photos + 1) }
    }

    internal fun micChanged(name: String) {
        if (_state.value !is RecordingState.Recording) return
        log(LessonEvent.MicChanged(elapsedMs(), name))
        update { it.copy(micName = name) }
    }

    @Synchronized
    internal fun end() {
        val s = _state.value as? RecordingState.Recording ?: return
        val rec = recorder
        rec?.stop()
        Forwarder.stop()
        sink?.close()
        val summary = RecordingSummary(
            lessonId = s.lessonId,
            durationMs = LessonAudio.framesToMs(rec?.framesRead ?: 0),
            chunkCount = chunks,
            gapCount = s.gaps,
            markCount = s.marks,
            photoCount = s.photos,
            micName = s.micName,
        )
        dir?.let { File(it, "summary.tsv").writeText(summary.toTsv()) }
        cleanup()
        _state.value = RecordingState.Finished(summary)
    }

    /** Back to idle once the app has saved the finished lesson. */
    fun acknowledge() {
        if (_state.value !is RecordingState.Recording) _state.value = RecordingState.Idle
    }

    private fun cleanup() {
        runCatching { events?.close() }
        events = null; sink = null; recorder = null; dir = null
    }

    @Synchronized
    private fun log(e: LessonEvent) {
        events?.apply { write(e.toLine()); write("\n"); flush() }
    }

    private inline fun update(f: (RecordingState.Recording) -> RecordingState.Recording) {
        val s = _state.value as? RecordingState.Recording ?: return
        _state.value = f(s)
    }

    /** Copies the recorder's live levels into [levels] while recording. */
    private object Forwarder {
        private var job: Thread? = null
        @Volatile private var on = false
        fun start(rec: LessonRecorder) {
            on = true
            job = Thread {
                while (on) {
                    _levels.value = rec.levels.value
                    Thread.sleep(150)
                }
                _levels.value = null
            }.apply { isDaemon = true; start() }
        }
        fun stop() { on = false; job?.join(500); job = null }
    }
}

fun RecordingSummary.toTsv(): String = listOf(
    "lesson_id\t$lessonId",
    "duration_ms\t$durationMs",
    "chunks\t$chunkCount",
    "gaps\t$gapCount",
    "marks\t$markCount",
    "photos\t$photoCount",
    "mic\t$micName",
).joinToString("\n", postfix = "\n")
