package com.cuenote.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One row of teacher_student_share, as the student sees it. */
@Serializable
data class ShareRow(
    val id: String,
    @SerialName("invite_email") val inviteEmail: String,
    @SerialName("teacher_id") val teacherId: String? = null,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("accepted_at") val acceptedAt: String? = null,
    @SerialName("restricted_to_taught") val restrictedToTaught: Boolean = false,
)

@Serializable
private data class NewShare(
    @SerialName("student_id") val studentId: String,
    @SerialName("invite_email") val inviteEmail: String,
)

@Serializable
private data class ProfileName(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val alias: String? = null,
)

@Serializable
private data class StatusChange(val status: String)

@Serializable
private data class RestrictChange(@SerialName("restricted_to_taught") val restrictedToTaught: Boolean)

/**
 * A teacher the student has invited, ready for the screen.
 *
 * [teacherName] is how the student sees the teacher once they've accepted:
 * their alias and real name, "Tiger (Marigold Stewart)". Other teachers may
 * see only the alias, but the student always sees both.
 *
 * [restrictedToTaught] is the student's choice to show this teacher only the
 * lessons they taught.
 */
data class InvitedTeacher(
    val shareId: String,
    val email: String,
    val active: Boolean,
    val teacherName: String?,
    val teacherId: String? = null,
    val restrictedToTaught: Boolean = false,
) {
    val title: String get() = teacherName?.takeIf { it.isNotBlank() } ?: email
}

/** "Tiger (Marigold Stewart)", or whichever of the two is known. */
internal fun studentFacingName(alias: String?, realName: String?): String? {
    val a = alias?.trim().orEmpty()
    val r = realName?.trim().orEmpty()
    return when {
        a.isNotEmpty() && r.isNotEmpty() -> "$a ($r)"
        a.isNotEmpty() -> a
        r.isNotEmpty() -> r
        else -> null
    }
}

/**
 * The student's side of sharing: invite a teacher by email, list the
 * invites, and take them back. The database's security rules decide what
 * is allowed; this class only asks.
 */
class ShareRepository(private val auth: AuthRepository) {
    private val client get() = requireNotNull(auth.client) { "Sign-in isn't set up in this build" }
    private fun myId(): String =
        requireNotNull(client.auth.currentUserOrNull()?.id) { "Sign in first" }

    /** Pending and active invites, active first, newest first within each. */
    suspend fun list(): List<InvitedTeacher> {
        val rows = client.from("teacher_student_share").select {
            filter {
                eq("student_id", myId())
                isIn("status", listOf("pending", "active"))
            }
        }.decodeList<ShareRow>()
        val teacherIds = rows.mapNotNull { it.teacherId }.distinct()
        val names = if (teacherIds.isEmpty()) emptyMap() else
            client.from("profile").select { filter { isIn("id", teacherIds) } }
                .decodeList<ProfileName>().associate { it.id to studentFacingName(it.alias, it.displayName).orEmpty() }
        return toInvitedTeachers(rows, names)
    }

    /** Sends an invite. Returns a message to show when the email can't be invited. */
    suspend fun invite(email: String, current: List<InvitedTeacher>): String? {
        val myEmail = client.auth.currentUserOrNull()?.email
        inviteProblem(email, myEmail, current)?.let { return it }
        client.from("teacher_student_share").insert(NewShare(myId(), normalizeEmail(email)))
        return null
    }

    /**
     * Takes an invite back. A pending invite is deleted, as if it was never
     * sent. An active share is marked revoked: the teacher loses access at
     * once, and the database keeps a record that they once had it.
     */
    suspend fun setRestricted(teacher: InvitedTeacher, on: Boolean) {
        client.from("teacher_student_share").update(RestrictChange(on)) { filter { eq("id", teacher.shareId) } }
    }

    suspend fun revoke(teacher: InvitedTeacher) {
        val table = client.from("teacher_student_share")
        if (teacher.active) {
            table.update(StatusChange("revoked")) { filter { eq("id", teacher.shareId) } }
        } else {
            table.delete { filter { eq("id", teacher.shareId) } }
        }
    }
}

internal fun normalizeEmail(email: String) = email.trim().lowercase()

/** Why this email can't be invited, or null if it can. */
fun inviteProblem(email: String, myEmail: String?, current: List<InvitedTeacher>): String? {
    val e = normalizeEmail(email)
    return when {
        !Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(e) -> "That doesn't look like an email address."
        myEmail != null && e == normalizeEmail(myEmail) -> "That's your own email. Enter your teacher's."
        current.any { normalizeEmail(it.email) == e } -> "You've already invited $e."
        else -> null
    }
}

internal fun toInvitedTeachers(rows: List<ShareRow>, names: Map<String, String>): List<InvitedTeacher> =
    rows.filter { it.status == "pending" || it.status == "active" }
        .sortedWith(compareBy<ShareRow> { it.status != "active" }.thenByDescending { it.createdAt })
        .map {
            InvitedTeacher(
                shareId = it.id,
                email = it.inviteEmail,
                active = it.status == "active",
                teacherName = it.teacherId?.let(names::get),
                teacherId = it.teacherId,
                restrictedToTaught = it.restrictedToTaught,
            )
        }
