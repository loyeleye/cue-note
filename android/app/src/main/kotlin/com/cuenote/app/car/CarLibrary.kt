package com.cuenote.app.car

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.cuenote.app.CueNoteApp
import com.cuenote.data.PracticePlanEntity
import com.cuenote.data.PracticePlans
import com.cuenote.data.PracticeRepository
import com.cuenote.data.SetlistRow
import com.cuenote.pipeline.audio.LessonFolder
import com.cuenote.pipeline.practice.CarNote
import com.cuenote.pipeline.practice.CarPart
import com.cuenote.pipeline.practice.CarPlaylist
import com.cuenote.pipeline.practice.Plan
import com.cuenote.pipeline.practice.PracticePlan
import com.cuenote.pipeline.practice.Voice
import com.cuenote.practice.fixedText
import com.cuenote.practice.stepTitle
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What Cue Note shows in the car: a short browse list (today's practice,
 * the notes to practice, backing tracks, recent lessons) and, for whatever
 * is picked, the queue of files to play. Everything comes from the phone's
 * own copies, so it works with no signal.
 */
class CarLibrary(private val app: CueNoteApp, private val sounds: CarSounds) {
    private val plans: PracticePlans get() = app.plans
    private val practice: PracticeRepository get() = app.practice

    // ------------------------------------------------------------ Browsing

    fun root(): MediaItem = folder(ROOT, "Cue Note")

    suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            folder(TODAY, "Today's practice", "Sing along, step by step"),
            folder(NOTES, "Practice this", "Your teacher's notes"),
            folder(BACKING, "Backing tracks", "The piano and demos from your lessons"),
            folder(LESSONS, "Lessons", "Listen back"),
        )
        parentId == TODAY -> today()
        parentId == NOTES -> setlist().map { r ->
            playable("$NOTE${r.card.id}", title(r), "${teacher(r.card.lessonId) ?: "Your teacher"} · ${date(r.recordedAt)}")
        }.ifEmpty { listOf(info("No notes yet", "They come from your lessons")) }
        parentId == BACKING -> setlist().filter { it.card.localId != null }.map { r ->
            folder("$BACKING_OF${r.card.id}", title(r), date(r.recordedAt))
        }.ifEmpty { listOf(info("No backing tracks yet", "They're cut from lessons recorded on this phone")) }
        parentId.startsWith(BACKING_OF) -> tracks(parentId.removePrefix(BACKING_OF))
        parentId == LESSONS -> app.lessons.lessons.first()
            .filter { LessonFolder(File(it.dirPath)).chunks.isNotEmpty() }
            .take(MAX_LESSONS)
            .map { l -> playable("$LESSON${l.id}", "Lesson, ${date(l.recordedAt)}", listOfNotNull(teacher(l.id), minutes(l.durationMs)).joinToString(" · ")) }
            .ifEmpty { listOf(info("No lessons on this phone yet", "Record one to listen back here")) }
        else -> emptyList()
    }

    /** The plan's ways in: pick up today's sitting, or start one for how the voice feels. */
    private suspend fun today(): List<MediaItem> = buildList {
        val latest = plans.today().first()
        if (latest != null && latest.endedAt == null) {
            val n = plans.steps(latest).size
            if (latest.stepIndex < n) add(playable("$RESUME${latest.id}", "Pick up at step ${latest.stepIndex + 1} of $n", "Today's practice"))
        }
        if (setlist().isEmpty()) { add(info("Nothing to practice yet", "Your teacher's notes come from your lessons")); return@buildList }
        val set = plans.settings?.settings?.value
        if (set?.resting(PracticePlans.day(System.currentTimeMillis())) == true) {
            add(playable("${START}HOARSE:0", "Just listen today", "Your teacher asked you to rest your voice"))
            return@buildList
        }
        val max = set?.maxMinutes ?: PracticePlan.DEFAULT_MAX
        PracticePlan.lengths(max).forEach { m -> add(playable("${START}FINE:$m", "$m minutes", "Voice feels fine")) }
        add(playable("${START}TIRED:${PracticePlan.TIRED_MINUTES}", "Voice a bit tired", "An easy ${PracticePlan.TIRED_MINUTES} minutes in the middle of your range"))
        add(playable("${START}HOARSE:0", "Hoarse or sore", "Just listen, no singing"))
    }

    private suspend fun tracks(cardId: String): List<MediaItem> {
        val r = practice.card(cardId).first() ?: return emptyList()
        val b = practice.backing(r.card) ?: return listOf(info("No backing for this note", "The lesson's audio isn't on this phone"))
        return buildList {
            b.accompaniment?.let {
                add(playable("$TRACK$cardId:acc", "With piano", title(r)))
                add(playable("$TRACK$cardId:slow", "With piano, slower", title(r)))
            }
            b.demos.forEachIndexed { i, _ -> add(playable("$TRACK$cardId:demo:${i + 1}", if (b.demos.size > 1) "Demo ${i + 1}" else "Demo", title(r))) }
        }.ifEmpty { listOf(info("No backing for this note", "Nothing was played or sung with it")) }
    }

    // ------------------------------------------------------------ Playing

    /** A sitting the car is playing, so the service can log each step as it goes by. */
    data class Queue(val items: List<MediaItem>, val planId: String? = null)

    /**
     * The queue for a picked item: a whole sitting, one note, a lesson, or a
     * single backing track. Null for anything that isn't playable.
     */
    suspend fun queueFor(mediaId: String): Queue? = when {
        mediaId.startsWith(START) -> {
            val (v, m) = mediaId.removePrefix(START).split(':').let { it[0] to it.getOrElse(1) { "0" }.toInt() }
            val voice = runCatching { Voice.valueOf(v) }.getOrDefault(Voice.FINE)
            val id = plans.start(setlist(), voice, m.coerceAtLeast(PracticePlan.LENGTHS.first()), practice.agenda.nextLessonAt.value)
            plans.get(id)?.let { Queue(sitting(it), it.id) }
        }
        mediaId.startsWith(RESUME) -> plans.get(mediaId.removePrefix(RESUME))?.takeIf { it.endedAt == null }?.let { Queue(sitting(it), it.id) }
        mediaId.startsWith(NOTE) -> {
            val r = practice.card(mediaId.removePrefix(NOTE)).first()
            r?.let { row ->
                val (note, backing) = note(row)
                val parts = CarPlaylist.forNote(row.card.id, title(row), note)
                Queue(items(parts, "note-${row.card.id}", mapOf(row.card.id to (row to backing)), album = "Practice this"))
            }
        }
        mediaId.startsWith(LESSON) -> {
            val l = app.lessons.lessons.first().firstOrNull { it.id == mediaId.removePrefix(LESSON) }
            l?.let { lesson ->
                val chunks = LessonFolder(File(lesson.dirPath)).chunks
                Queue(chunks.mapIndexed { i, f ->
                    item("lesson-${lesson.id}-$i", f, "Lesson, ${date(lesson.recordedAt)}", "Part ${i + 1} of ${chunks.size}", "Lessons")
                })
            }
        }
        mediaId.startsWith(TRACK) -> track(mediaId.removePrefix(TRACK))?.let { Queue(listOf(it)) }
        else -> null
    }

    /** Today's sitting from the step it's on, as a queue. */
    suspend fun sitting(p: PracticePlanEntity): List<MediaItem> {
        val steps = plans.steps(p)
        val voice = runCatching { Voice.valueOf(p.voice) }.getOrDefault(Voice.FINE)
        val check = if (p.easyChecked) null else PracticePlan.easyCheckBefore(Plan(voice, p.minutes, steps))
        val rows = steps.mapNotNull { it.cardId }.distinct().mapNotNull { id ->
            practice.card(id).first()?.let { r -> id to (r to note(r).second) }
        }.toMap()
        val notes = rows.mapValues { (_, v) -> carNote(v.first, v.second) }
        val titles = steps.map { s ->
            val r = s.cardId?.let { rows[it]?.first }
            val teacher = r?.let { teacher(it.card.lessonId) } ?: "your teacher"
            stepTitle(s, r?.card?.cue, teacher.replaceFirstChar { it.uppercase() })
        }
        val parts = CarPlaylist.build(steps, titles, notes, ::fixedText, check, from = p.stepIndex.coerceAtMost(steps.size), minutes = p.minutes)
        return items(parts, "plan-${p.id}", rows, album = "Today's practice", planId = p.id, total = steps.size)
    }

    private suspend fun items(
        parts: List<CarPart>,
        key: String,
        rows: Map<String, Pair<SetlistRow, PracticeRepository.Backing?>>,
        album: String,
        planId: String? = null,
        total: Int = 0,
    ): List<MediaItem> = buildList {
        parts.forEachIndexed { i, part ->
            val sub = if (planId != null && part.step < total) "Step ${part.step + 1} of $total" else album
            fun add(f: File?, n: Int = 0) {
                if (f == null || !f.isFile) return
                add(item("$key-$i-$n", f, part.title, sub, album, planId, part.step, part.main))
            }
            when (part) {
                is CarPart.Say -> add(sounds.speech(part.text))
                is CarPart.Quiet -> add(sounds.quiet(part.seconds))
                is CarPart.Words -> add(rows[part.cardId]?.first?.wordsFile?.let(::File))
                is CarPart.Demo -> add(rows[part.cardId]?.second?.demos?.getOrNull(part.number - 1)?.file)
                is CarPart.Accompaniment -> rows[part.cardId]?.second?.accompaniment?.file?.let { f -> repeat(part.repeats) { n -> add(f, n) } }
            }
        }
    }

    private suspend fun track(spec: String): MediaItem? {
        val parts = spec.split(':')
        val r = practice.card(parts[0]).first() ?: return null
        val b = practice.backing(r.card) ?: return null
        val (file, label) = when (parts.getOrNull(1)) {
            "acc" -> b.accompaniment?.file to "With piano"
            "slow" -> b.accompaniment?.let { practice.slowed(it, SLOWER) } to "With piano, slower"
            "demo" -> b.demos.getOrNull((parts.getOrNull(2)?.toIntOrNull() ?: 1) - 1)?.file to "Demo"
            else -> null to ""
        }
        return file?.let { item("track-$spec", it, label, title(r), "Backing tracks") }
    }

    private suspend fun note(r: SetlistRow): Pair<CarNote, PracticeRepository.Backing?> {
        val b = runCatching { practice.backing(r.card) }.getOrNull()
        return carNote(r, b) to b
    }

    private fun carNote(r: SetlistRow, b: PracticeRepository.Backing?) = CarNote(
        cue = r.card.cue, herWords = r.card.herWords, teacher = teacher(r.card.lessonId),
        hasWords = r.wordsFile?.let { File(it).isFile } == true,
        accompanimentMs = b?.accompaniment?.span?.durationMs, demos = b?.demos?.size ?: 0,
    )

    private suspend fun setlist(): List<SetlistRow> = app.lessons.setlist.first()

    private fun teacher(lessonId: String): String? = app.lessonTeachers.get(lessonId).name?.substringBefore(' ')?.takeIf { it.isNotBlank() }

    private fun title(r: SetlistRow) = r.card.cue.ifBlank { CarPlaylist.spoken(r.card.herWords).take(60) }.ifBlank { "Your teacher's note" }

    private fun date(ms: Long) = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))

    private fun minutes(ms: Long) = if (ms <= 0) null else "${(ms / 60_000).coerceAtLeast(1)} min"

    private fun folder(id: String, title: String, subtitle: String? = null) = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build(),
        ).build()

    private fun playable(id: String, title: String, subtitle: String?) = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setArtist(subtitle).setIsBrowsable(false).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST).build(),
        ).build()

    /** A line that's neither a folder nor playable: an empty state. */
    private fun info(title: String, subtitle: String) = MediaItem.Builder().setMediaId("info:$title")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setIsBrowsable(false).setIsPlayable(false).build())
        .build()

    private fun item(
        id: String, file: File, title: String, subtitle: String, album: String,
        planId: String? = null, step: Int = 0, main: Boolean = false,
    ) = MediaItem.Builder().setMediaId(id).setUri(Uri.fromFile(file))
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setArtist(subtitle).setAlbumTitle(album)
                .setIsBrowsable(false).setIsPlayable(true)
                .setExtras(Bundle().apply {
                    planId?.let { putString(EXTRA_PLAN, it) }
                    putInt(EXTRA_STEP, step)
                    putBoolean(EXTRA_MAIN, main)
                })
                .build(),
        ).build()

    companion object {
        const val ROOT = "root"
        const val TODAY = "today"
        const val NOTES = "notes"
        const val BACKING = "backing"
        const val LESSONS = "lessons"
        const val START = "plan:start:"
        const val RESUME = "plan:resume:"
        const val NOTE = "note:"
        const val BACKING_OF = "backing:"
        const val TRACK = "track:"
        const val LESSON = "lesson:"
        const val EXTRA_PLAN = "cuenote.plan"
        const val EXTRA_STEP = "cuenote.step"
        const val EXTRA_MAIN = "cuenote.main"
        const val MAX_LESSONS = 20
        const val SLOWER = 0.75
    }
}
