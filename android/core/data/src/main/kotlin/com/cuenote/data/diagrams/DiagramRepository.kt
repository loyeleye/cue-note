package com.cuenote.data.diagrams

import com.cuenote.data.AuthRepository
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * "Does this diagram help?" on the student's cards. A suggestion is a diagram
 * from the app's library that the AI matched to a card (cloud table
 * diagram_suggestion). The student sees it unless their teacher dismissed it
 * or they did; their own dismissal hides it only for them.
 *
 * Suggestions live in the cloud only; this keeps the lesson that is open in
 * memory and fetches again each time a lesson opens.
 */
class DiagramRepository(private val auth: AuthRepository = AuthRepository.get()) {

    private val byLesson = MutableStateFlow<Map<String, List<DiagramSuggestion>>>(emptyMap())

    /** The suggestions the student should see on a lesson's cards. */
    fun observe(lessonId: String): Flow<List<DiagramSuggestion>> =
        byLesson.map { all -> all[lessonId].orEmpty().filter { it.visibleToStudent } }

    suspend fun refresh(lessonId: String) = withContext(Dispatchers.IO) {
        val client = auth.client ?: return@withContext
        val rows = client.from("diagram_suggestion").select(Columns.raw("$COLUMNS, feedback!inner(lesson_id)")) {
            filter { eq("feedback.lesson_id", lessonId) }
        }.decodeList<DiagramSuggestion>()
        byLesson.update { it + (lessonId to rows) }
    }

    /** The student's answer: "accepted" keeps it on the card, "dismissed" hides it for them. */
    suspend fun decide(lessonId: String, suggestion: DiagramSuggestion, decision: String) = withContext(Dispatchers.IO) {
        require(decision in DECISIONS) { "unknown decision $decision" }
        val client = auth.client ?: error("Sign in to answer this.")
        client.from("diagram_suggestion").update({ set("student_status", decision) }) {
            filter { eq("id", suggestion.id) }
        }
        byLesson.update { all ->
            all + (lessonId to all[lessonId].orEmpty().map { if (it.id == suggestion.id) it.copy(studentStatus = decision) else it })
        }
    }

    companion object {
        private const val COLUMNS = "id, feedback_id, diagram_id, confidence, source, teacher_status, student_status"
        val DECISIONS = setOf("open", "accepted", "dismissed")
    }
}

@Serializable
data class DiagramSuggestion(
    val id: String,
    /** The card's cloud id (CardEntity.id). */
    @SerialName("feedback_id") val feedbackId: String,
    @SerialName("diagram_id") val diagramId: String,
    val confidence: Float,
    /** "cues" (her words) or "sketch" (her sketch). */
    val source: String,
    @SerialName("teacher_status") val teacherStatus: String,
    @SerialName("student_status") val studentStatus: String,
    /** Only there for the lesson filter. */
    val feedback: JsonElement? = null,
) {
    val visibleToStudent: Boolean get() = teacherStatus != "dismissed" && studentStatus != "dismissed"
    val accepted: Boolean get() = studentStatus == "accepted" || teacherStatus == "accepted"
}
