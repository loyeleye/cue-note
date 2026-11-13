package com.cuenote.data

import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * One teacher's way into the whole of one lesson. By default a teacher hears
 * only the takes on "Practice this" cards and what the student shares; a pass
 * opens the rest for 14 days, once both people say yes.
 */
@Serializable
data class FullLessonPass(
    val id: String,
    @SerialName("lesson_id") val lessonId: String,
    @SerialName("teacher_id") val teacherId: String,
    /** "student" or "teacher": who asked first. The other one answers. */
    @SerialName("asked_by") val askedBy: String,
    /** "pending", "active", "declined" or "ended". */
    val status: String,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("ends_at") val endsAt: String? = null,
) {
    val endsAtMs: Long? get() = endsAt?.let { Instant.parse(it).toEpochMilli() }

    /** Where it stands now: an active pass past its end has ended. */
    fun state(now: Long = System.currentTimeMillis()): String =
        if (status == "active" && (endsAtMs ?: 0) <= now) "ended" else status

    /** Waiting for an answer, or open now. */
    fun current(now: Long = System.currentTimeMillis()): Boolean = state(now).let { it == "pending" || it == "active" }
}

@Serializable
private data class AskParams(val lesson: String, val teacher: String, val note: String?)

@Serializable
private data class AnswerParams(val pass: String, val yes: Boolean)

@Serializable
private data class EndParams(val pass: String)

/** The student's side of full-lesson passes. The database decides what's allowed. */
class FullLessonRepository(private val auth: AuthRepository) {
    private val client get() = requireNotNull(auth.client) { "Sign-in isn't set up in this build" }

    /** Every ask and pass for this lesson, newest first. */
    suspend fun forLesson(lessonId: String): List<FullLessonPass> =
        client.from("full_lesson_pass").select { filter { eq("lesson_id", lessonId) } }
            .decodeList<FullLessonPass>().sortedByDescending { it.createdAt }

    /** Invites a teacher to hear the whole lesson. If they'd already asked, this is the yes. */
    suspend fun invite(lessonId: String, teacherId: String, note: String?) {
        client.postgrest.rpc("ask_full_lesson", AskParams(lessonId, teacherId, note?.trim()?.ifEmpty { null }))
    }

    /** Yes, or "not this time", to a teacher's ask. */
    suspend fun answer(passId: String, yes: Boolean) {
        client.postgrest.rpc("answer_full_lesson", AnswerParams(passId, yes))
    }

    /** Ends a pass early, or takes back an invite. */
    suspend fun end(passId: String) {
        client.postgrest.rpc("end_full_lesson", EndParams(passId))
    }
}
