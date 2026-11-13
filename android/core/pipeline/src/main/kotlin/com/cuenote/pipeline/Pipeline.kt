package com.cuenote.pipeline

import com.cuenote.ml.Transcriber
import com.cuenote.ml.VoiceActivityDetector
import com.cuenote.pipeline.audio.LessonFolder
import com.cuenote.pipeline.golden.GoldenLabels
import com.cuenote.pipeline.golden.Scorer
import com.cuenote.pipeline.timeline.TimelineWriter
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The after-lesson pipeline, one resumable stage at a time. Each stage writes
 * its result into <lesson>/pipeline/ and records itself in state.json, so a
 * run that stops halfway picks up at the first unfinished stage, and the
 * debug timeline can show exactly where a lesson went wrong.
 *
 *   features   loudness, pitch (YIN) and voice probability for every 32 ms
 *   segment    the five labels, then takes and feedback blocks
 *   transcribe speech to text, only where someone is talking
 *   link       takes into exercises, feedback to takes, demos to phrases, photos and marks to cards
 *   timeline   the debug timeline page
 *
 * Cutting Opus clips and calling extract-cues come in M3.
 */
class Pipeline(
    private val lesson: LessonFolder,
    private val vad: VoiceActivityDetector,
    private val transcriber: Transcriber?,
    private val cfg: PipelineConfig = loadConfig(lesson),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Stage(val key: String) {
        FEATURES("features"), SEGMENT("segment"), TRANSCRIBE("transcribe"), LINK("link"), TIMELINE("timeline")
    }

    @Serializable
    data class StageRecord(
        val stage: String,
        val status: String,          // "done", "failed", "skipped"
        val startedAt: Long,
        val finishedAt: Long,
        val detail: String = "",
    )

    @Serializable
    data class State(val stages: List<StageRecord> = emptyList()) {
        fun done(s: Stage) = stages.any { it.stage == s.key && (it.status == "done" || it.status == "skipped") }
        val failed get() = stages.lastOrNull { it.status == "failed" }
    }

    /** Everything the pipeline produced, as the app and the tests read it. */
    @Serializable
    data class Output(
        val lessonId: String,
        val durationMs: Long,
        val vad: String,
        val transcriber: String?,
        val segments: List<Segment>,
        val takes: List<Take>,
        val feedback: List<FeedbackBlock>,
        val transcript: List<TranscriptLine>,
        val events: List<EventLink>,
        /** Warmups and song sections, each grouping its takes and feedback. */
        val exercises: List<Exercise> = emptyList(),
    )

    private val dir = lesson.pipelineDir
    private val stateFile = File(dir, "state.json")
    private val featuresFile = File(dir, "features.bin")
    private val segmentsFile = File(dir, "segments.json")
    private val structureFile = File(dir, "structure.json")
    private val transcriptPartial = File(dir, "transcript.partial.jsonl")
    private val transcriptFile = File(dir, "transcript.json")
    val outputFile = File(dir, "output.json")
    val timelineFile = File(dir, "timeline.html")

    fun state(): State = if (stateFile.isFile) json.decodeFromString(stateFile.readText()) else State()

    /** Forget earlier results and start over (after changing thresholds, say). */
    fun reset() {
        dir.listFiles()?.filter { it.name != "config.json" }?.forEach { it.delete() }
    }

    /**
     * Runs every unfinished stage. [onStage] hears the stage and its progress
     * (0..1); [isStopped] is checked often so the phone can pause the work.
     */
    fun run(
        onStage: (Stage, Double) -> Unit = { _, _ -> },
        isStopped: () -> Boolean = { false },
    ): Output {
        dir.mkdirs()
        lateinit var features: Features
        lateinit var structure: StructureFile

        stage(Stage.FEATURES) {
            Features.measure(lesson, vad, onProgress = { onStage(Stage.FEATURES, it) }, isStopped = isStopped)
                .also { it.write(featuresFile) }
            "${lesson.chunks.size} chunks, vad=${vad.name}"
        }
        features = Features.read(featuresFile)

        stage(Stage.SEGMENT) {
            onStage(Stage.SEGMENT, 0.0)
            val segments = Segmenter(cfg).segment(features)
            val s = Structure(cfg).build(segments, features, lesson.id)
            segmentsFile.writeText(json.encodeToString(ListSerializer(Segment.serializer()), segments))
            structureFile.writeText(json.encodeToString(StructureFile.serializer(), StructureFile(s.takes, s.feedback)))
            "${segments.size} segments, ${s.takes.size} takes, ${s.feedback.size} feedback blocks"
        }
        val segments = json.decodeFromString(ListSerializer(Segment.serializer()), segmentsFile.readText())
        structure = json.decodeFromString(StructureFile.serializer(), structureFile.readText())

        if (transcriber == null) {
            if (!state().done(Stage.TRANSCRIBE)) record(StageRecord(Stage.TRANSCRIBE.key, "skipped", clock(), clock(), "no speech model"))
            if (!transcriptFile.isFile) transcriptFile.writeText("[]")
        } else stage(Stage.TRANSCRIBE) {
            val lines = Transcription(cfg).run(
                lesson, segments, structure.takes, features, transcriber, transcriptPartial,
                onProgress = { onStage(Stage.TRANSCRIBE, it) }, isStopped = isStopped,
            )
            transcriptFile.writeText(json.encodeToString(ListSerializer(TranscriptLine.serializer()), lines))
            "${lines.size} lines, ${transcriber.name}"
        }
        val transcript = json.decodeFromString(ListSerializer(TranscriptLine.serializer()), transcriptFile.readText())

        val grouped = Exercises(cfg).group(structure.takes, structure.feedback, transcript, lesson.id)
        val linker = Linker(cfg).apply { demoPitch = FeaturePitch(features, channel = 1) }
        val links = linker.link(grouped.takes, grouped.feedback, lesson.events)
        val output = Output(
            lessonId = lesson.id,
            durationMs = lesson.durationMs,
            vad = features.vadName,
            transcriber = transcriber?.name,
            segments = segments,
            takes = grouped.takes,
            feedback = links.feedback,
            transcript = transcript,
            events = links.events,
            exercises = grouped.exercises,
        )
        stage(Stage.LINK) {
            outputFile.writeText(json.encodeToString(Output.serializer(), output))
            val linked = output.feedback.count { it.takeId != null }
            "${output.exercises.size} exercises, $linked of ${output.feedback.size} feedback blocks linked, ${output.events.size} photos and marks"
        }

        // The timeline is cheap, so it is always rewritten (labels may have changed).
        val labels = GoldenLabels.readOrNull(lesson.labelsFile)
        val score = labels?.let { Scorer.score(output, it) }
        TimelineWriter.write(timelineFile, lesson, output, features, labels, score, state())
        record(StageRecord(Stage.TIMELINE.key, "done", clock(), clock(), timelineFile.name))
        return output
    }

    @Serializable
    data class StructureFile(val takes: List<Take>, val feedback: List<FeedbackBlock>)

    private inline fun stage(s: Stage, block: () -> String) {
        if (state().done(s)) return
        val started = clock()
        val detail = try {
            block()
        } catch (e: StoppedException) {
            throw e
        } catch (e: Throwable) {
            record(StageRecord(s.key, "failed", started, clock(), e.toString()))
            throw e
        }
        record(StageRecord(s.key, "done", started, clock(), detail))
    }

    private fun record(r: StageRecord) {
        val st = state()
        val kept = st.stages.filterNot { it.stage == r.stage }
        stateFile.writeText(json.encodeToString(State.serializer(), State(kept + r)))
    }

    private class FeaturePitch(private val f: Features, private val channel: Int) : Linker.PitchSource {
        private fun range(a: Long, b: Long) = msToFrame(a).coerceIn(0, f.frames) to msToFrame(b).coerceIn(0, f.frames)
        override fun hz(startMs: Long, endMs: Long) = range(startMs, endMs).let { (a, b) -> f.pitchHz[channel].copyOfRange(a, maxOf(a, b)) }
        override fun conf(startMs: Long, endMs: Long) = range(startMs, endMs).let { (a, b) -> f.pitchConf[channel].copyOfRange(a, maxOf(a, b)) }
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

        fun loadConfig(lesson: LessonFolder): PipelineConfig =
            File(lesson.pipelineDir, "config.json").takeIf { it.isFile }
                ?.let { json.decodeFromString(PipelineConfig.serializer(), it.readText()) } ?: PipelineConfig()

        fun readOutput(lesson: LessonFolder): Output? =
            File(lesson.pipelineDir, "output.json").takeIf { it.isFile }
                ?.let { json.decodeFromString(Output.serializer(), it.readText()) }
    }
}
