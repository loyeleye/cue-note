package com.cuenote.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cuenote.data.AuthRepository
import com.cuenote.data.LessonDatabase
import com.cuenote.data.PlanSettingsStore
import com.cuenote.data.PracticeDao
import com.cuenote.data.PracticePlans
import com.cuenote.data.TeacherPlanSetting
import com.cuenote.data.PracticeRepository
import com.cuenote.data.PracticeTakeEntity
import com.cuenote.pipeline.cards.ClipAudio
import com.cuenote.pipeline.cards.ClipPlan
import com.cuenote.pipeline.practice.LoggedSession
import com.cuenote.pipeline.practice.LoggedTake
import com.cuenote.pipeline.practice.PracticeLog
import com.cuenote.pipeline.practice.SessionItem
import com.cuenote.pipeline.practice.SharedNote
import com.cuenote.pipeline.practice.SharedPitch
import com.cuenote.signal.WavReader
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Brings the cloud up to date with what the student chose to share from
 * practice, and brings the teacher's side back:
 *
 *   takes      shared ones go up (an .m4a clip in the lesson's folder, the
 *              pitch per note, how it felt); unshared or deleted ones come down
 *   log        each sitting of the last few weeks: when, how long, which notes
 *   questions  new and changed ones go up; answers come back
 *   agenda     whether the teacher has listened, and the next lesson's date
 *   plans      each sitting of suggested practice goes up (how the voice
 *              felt, steps done, notes skipped); the teachers' settings
 *              for the plan come back
 *
 * Nothing goes up without a signed-in student. It runs as the unique work
 * "practice-sync" whenever there is a network, after any change.
 */
class PracticeSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val auth = AuthRepository.get()
        if (!auth.isConfigured) return@withContext Result.success()
        val student = CloudSync(auth).studentId() ?: return@withContext Result.success()
        val client = requireNotNull(auth.client)
        val db = LessonDatabase.get(applicationContext)
        val dao = db.practice()
        val repo = PracticeRepository(applicationContext, dao, db.cards())
        try {
            syncLog(client, student, dao)          // first: a shared take names its sitting
            syncTakes(client, student, dao, repo)
            syncQuestions(client, student, dao)
            syncAgenda(client, student, repo)
            syncPlans(client, student, dao)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    @Serializable
    private data class TakeRow(
        val id: String,
        @SerialName("student_id") val studentId: String,
        @SerialName("feedback_id") val feedbackId: String?,
        @SerialName("lesson_id") val lessonId: String,
        @SerialName("session_id") val sessionId: String?,
        @SerialName("recorded_at") val recordedAt: String,
        @SerialName("duration_ms") val durationMs: Long,
        @SerialName("clip_path") val clipPath: String,
        val pitch: List<SharedNote>?,
        @SerialName("feeling_note") val feelingNote: String?,
    )

    @Serializable
    private data class HeardRow(
        val id: String,
        @SerialName("heard_at") val heardAt: String? = null,
        val reply: String? = null,
        @SerialName("reply_clip_path") val replyClipPath: String? = null,
    )

    private fun clipPath(student: String, t: PracticeTakeEntity) = "$student/${t.lessonId}/practice-${t.id}.${ClipPlan.EXTENSION}"

    private suspend fun syncTakes(client: SupabaseClient, student: String, dao: PracticeDao, repo: PracticeRepository) {
        val clips = client.storage.from("clips")
        val table = client.from("practice_take")
        val teachers = com.cuenote.data.LessonTeacherStore(applicationContext)
        for (t in dao.takesToSync()) {
            // A take on a local-only lesson's note stays on the phone, like the lesson.
            if (t.shared && !t.cloudShared && !t.deleted && teachers.isLocalOnly(t.lessonId)) continue
            val path = clipPath(student, t)
            when {
                t.deleted || (!t.shared && t.cloudShared) -> {
                    if (t.cloudShared) {
                        runCatching { clips.delete(listOf(path)) }
                        table.delete { filter { eq("id", t.id) } }
                    }
                    if (t.deleted) dao.deleteTake(t.id) else dao.markSynced(t.id, cloudShared = false, heardAt = null)
                }
                t.shared && !t.cloudShared -> {
                    val wav = File(t.file)
                    if (!wav.isFile) { dao.setShared(t.id, false); continue }
                    val m4a = File(applicationContext.cacheDir, "practice-${t.id}.${ClipPlan.EXTENSION}")
                    try {
                        val mono = WavReader.read(wav).samples
                        ClipAudio.applyGain(mono, ClipAudio.normalizeGain(mono))
                        ClipEncoder.encodeMono(mono, m4a, t.id)
                        clips.upload(path, m4a.readBytes()) { upsert = true; contentType = ContentType.parse(ClipPlan.MIME) }
                    } finally {
                        m4a.delete()
                    }
                    val pitch = repo.cachedPitch(t)?.let(SharedPitch::of)
                    fun row(fb: String?, session: String?) = TakeRow(
                        t.id, student, fb, t.lessonId, session, Instant.ofEpochMilli(t.recordedAt).toString(),
                        t.durationMs, path, pitch, t.feelingNote,
                    )
                    try {
                        table.upsert(row(t.cardId, t.sessionId)) { onConflict = "id" }
                    } catch (e: Exception) {
                        val msg = e.message.orEmpty()
                        when {
                            msg.contains("up to three") -> {
                                // The teacher hasn't heard the others yet: keep it on the phone.
                                runCatching { clips.delete(listOf(path)) }
                                dao.setShared(t.id, false)
                                continue
                            }
                            // The card was deleted on the dashboard: share it with its lesson.
                            msg.contains("foreign key") -> table.upsert(row(null, t.sessionId)) { onConflict = "id" }
                            else -> throw e
                        }
                    }
                    dao.markSynced(t.id, cloudShared = true, heardAt = null)
                }
            }
        }
        // Whether the teacher has listened, and any reply.
        val heard = table.select(Columns.list("id", "heard_at", "reply", "reply_clip_path")) { filter { eq("student_id", student) } }
            .decodeList<HeardRow>().associateBy { it.id }
        for (t in dao.takesToSync()) {
            if (!t.shared || !t.cloudShared || t.deleted) continue
            val r = heard[t.id]
            if (r == null) dao.markSynced(t.id, cloudShared = false, heardAt = null)   // gone from the cloud (lesson deleted)
            else {
                dao.markSynced(t.id, cloudShared = true, heardAt = r.heardAt?.let { Instant.parse(it).toEpochMilli() })
                if (r.reply != t.reply || r.replyClipPath != t.replyClipPath) dao.setReply(t.id, r.reply, r.replyClipPath)
            }
        }
    }

    @Serializable
    private data class SessionRow(
        val id: String,
        @SerialName("student_id") val studentId: String,
        @SerialName("started_at") val startedAt: String,
        @SerialName("ended_at") val endedAt: String,
        val minutes: Int,
        val items: List<SessionItem>,
    )

    private suspend fun syncLog(client: SupabaseClient, student: String, dao: PracticeDao) {
        val since = System.currentTimeMillis() - PracticeLog.SYNC_DAYS * 86_400_000L
        val sessions = dao.sessionsSince(since)
        if (sessions.isEmpty()) return
        val takes = dao.takesInSessions(sessions.map { it.id })
        val summary = PracticeLog.summarize(
            sessions.map { LoggedSession(it.id, it.startedAt, it.endedAt) },
            takes.map { LoggedTake(it.cardId, it.sessionId, it.recordedAt, it.durationMs) },
        )
        val rows = summary.map {
            SessionRow(it.id, student, Instant.ofEpochMilli(it.startedAt).toString(), Instant.ofEpochMilli(it.endedAt).toString(), it.minutes, it.items)
        }
        if (rows.isNotEmpty()) client.from("practice_session").upsert(rows) { onConflict = "id" }
        // A sitting whose takes were all deleted leaves the log too.
        val kept = rows.map { it.id }.toSet()
        val gone = sessions.map { it.id }.filter { it !in kept }
        if (gone.isNotEmpty()) client.from("practice_session").delete { filter { isIn("id", gone) } }
    }

    @Serializable
    private data class NewQuestion(
        val id: String,
        @SerialName("student_id") val studentId: String,
        @SerialName("feedback_id") val feedbackId: String?,
        @SerialName("lesson_id") val lessonId: String?,
        val bar: Int?,
        val body: String,
    )

    @Serializable
    private data class QuestionEdit(val bar: Int?, val body: String, val status: String? = null)

    @Serializable
    private data class CloudQuestion(
        val id: String,
        val status: String,
        val answer: String? = null,
        @SerialName("answer_clip_path") val answerClipPath: String? = null,
        @SerialName("answered_at") val answeredAt: String? = null,
    )

    private suspend fun syncQuestions(client: SupabaseClient, student: String, dao: PracticeDao) {
        val table = client.from("student_question")
        val cloud = table.select(Columns.list("id", "status", "answer", "answer_clip_path", "answered_at")) { filter { eq("student_id", student) } }
            .decodeList<CloudQuestion>().associateBy { it.id }
        val teachers = com.cuenote.data.LessonTeacherStore(applicationContext)
        for (q in dao.questions().filter { it.dirty }) {
            // A question about a local-only lesson could quote the teacher: it stays on the phone.
            if (q.id !in cloud && q.lessonId?.let(teachers::isLocalOnly) == true) continue
            if (q.id !in cloud) {
                if (q.status == "dropped") { dao.upsertQuestion(q.copy(dirty = false)); continue }   // never went up
                try {
                    table.insert(NewQuestion(q.id, student, q.cardId, q.lessonId, q.bar, q.body))
                } catch (e: Exception) {
                    // The card was deleted on the dashboard: ask it about the lesson instead.
                    if (!e.message.orEmpty().contains("foreign key")) throw e
                    table.insert(NewQuestion(q.id, student, null, q.lessonId, q.bar, q.body))
                }
            } else {
                // Only a drop changes the status from the phone; the teacher's answer stays.
                table.update(QuestionEdit(q.bar, q.body, status = if (q.status == "dropped") "dropped" else null)) {
                    filter { eq("id", q.id) }
                }
            }
            dao.upsertQuestion(q.copy(dirty = false))
        }
        // Answers come back.
        for (q in dao.questions()) {
            val c = cloud[q.id] ?: continue
            if (q.dirty || q.status == "dropped") continue
            val at = c.answeredAt?.let { Instant.parse(it).toEpochMilli() }
            if (c.status != q.status || c.answer != q.answer || c.answerClipPath != q.answerClipPath || at != q.answeredAt) {
                dao.upsertQuestion(q.copy(status = c.status, answer = c.answer, answerClipPath = c.answerClipPath, answeredAt = at))
            }
        }
    }

    @Serializable
    private data class ShareDate(@SerialName("next_lesson_at") val nextLessonAt: String? = null)

    /** The soonest lesson date any teacher has set that hasn't passed. */
    private suspend fun syncAgenda(client: SupabaseClient, student: String, repo: PracticeRepository) {
        val now = System.currentTimeMillis()
        val next = client.from("teacher_student_share").select(Columns.list("next_lesson_at")) {
            filter { eq("student_id", student); eq("status", "active") }
        }.decodeList<ShareDate>().mapNotNull { it.nextLessonAt?.let { s -> Instant.parse(s).toEpochMilli() } }
            .filter { it > now - 3 * 3_600_000L }.minOrNull()
        repo.agenda.setNextLesson(next)
    }

    @Serializable
    private data class PlanRow(
        val id: String,
        @SerialName("student_id") val studentId: String,
        val day: String,
        @SerialName("started_at") val startedAt: String,
        @SerialName("ended_at") val endedAt: String,
        val voice: String,
        val minutes: Int,
        val steps: Int,
        val done: Int,
        val skipped: List<String>,
    )

    /** Finished sittings go up; the teachers' settings for the plan come back. */
    private suspend fun syncPlans(client: SupabaseClient, student: String, dao: PracticeDao) {
        val plans = PracticePlans(dao, LessonDatabase.get(applicationContext).cards(), null)
        val ready = dao.plansToSync()
        if (ready.isNotEmpty()) {
            val rows = ready.map { p ->
                val ended = p.endedAt ?: p.updatedAt
                PlanRow(
                    id = p.id, studentId = student, day = p.day,
                    startedAt = Instant.ofEpochMilli(p.startedAt).toString(), endedAt = Instant.ofEpochMilli(ended).toString(),
                    voice = p.voice.lowercase(),
                    minutes = (((ended - p.startedAt) / 60_000).toInt()).coerceIn(0, 120),
                    steps = plans.steps(p).size.coerceAtMost(20), done = p.done.coerceAtMost(20),
                    skipped = plans.skippedCards(p),
                )
            }
            client.from("practice_plan_log").upsert(rows) { onConflict = "id" }
            ready.forEach { dao.markPlanSynced(it.id, it.updatedAt) }
        }
        val settings = client.from("practice_plan_setting").select { filter { eq("student_id", student) } }
            .decodeList<TeacherPlanSetting>()
        PlanSettingsStore.get(applicationContext).set(settings)
    }

    companion object {
        private const val MAX_ATTEMPTS = 8

        /** Queues a sync; one already running finishes first, then this one runs. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PracticeSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .addTag("practice-sync")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("practice-sync", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
