package com.cuenote.pipeline.practice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/*
 * M5: the practice log and what goes up for the next lesson. Pure Kotlin, so
 * the rules are tested on the JVM. What syncs is only what the student
 * shares: the log summary (no audio), the takes they pick, their questions.
 */

/** One practice take, as the log needs it. */
data class LoggedTake(val cardId: String, val sessionId: String, val recordedAt: Long, val durationMs: Long)

/** One sitting, as the log needs it. */
data class LoggedSession(val id: String, val startedAt: Long, val endedAt: Long)

/** One note practiced in a sitting: the card, and how many goes. */
@Serializable
data class SessionItem(@SerialName("feedback_id") val feedbackId: String, val takes: Int)

/** What goes up for one sitting. */
data class SessionSummary(val id: String, val startedAt: Long, val endedAt: Long, val minutes: Int, val items: List<SessionItem>)

/** A week (or any stretch) of practice, for the "before your next lesson" screen. */
data class PracticeWeek(val sittings: Int, val minutes: Int, val notes: Int, val takes: Int) {
    val empty: Boolean get() = sittings == 0
}

object PracticeLog {
    /** A student shares up to this many takes at a time; one frees up once the teacher listens. */
    const val MAX_WAITING = 3

    /** How far back the log is sent up. Older sittings are already there. */
    const val SYNC_DAYS = 56

    /** A sitting's length in whole minutes, never less than one. */
    fun minutes(s: LoggedSession): Int = maxOf(1, ((s.endedAt - s.startedAt) / 60_000.0).roundToInt())

    /** The summary of each sitting: its minutes and the notes in it, most-practiced first. */
    fun summarize(sessions: List<LoggedSession>, takes: List<LoggedTake>): List<SessionSummary> {
        val bySession = takes.groupBy { it.sessionId }
        return sessions.mapNotNull { s ->
            val mine = bySession[s.id].orEmpty()
            if (mine.isEmpty()) return@mapNotNull null          // every take deleted: nothing to log
            val items = mine.groupingBy { it.cardId }.eachCount()
                .map { (card, n) -> SessionItem(card, n) }
                .sortedWith(compareByDescending<SessionItem> { it.takes }.thenBy { it.feedbackId })
            SessionSummary(s.id, s.startedAt, s.endedAt, minutes(s), items)
        }
    }

    /** The practice since [since]: sittings, minutes, distinct notes and takes. */
    fun week(sessions: List<LoggedSession>, takes: List<LoggedTake>, since: Long): PracticeWeek {
        val t = takes.filter { it.recordedAt >= since }
        val ids = t.map { it.sessionId }.toSet()
        val s = sessions.filter { it.id in ids }
        return PracticeWeek(s.size, s.sumOf { minutes(it) }, t.map { it.cardId }.toSet().size, t.size)
    }

    /** True when another take can be shared, given how many are still waiting for the teacher. */
    fun canShare(waiting: Int): Boolean = waiting < MAX_WAITING
}

/** A take's pitch as it goes up: one row per note, no pitch line. */
@Serializable
data class SharedNote(val sung: Float, val target: Float? = null, val verdict: String)

object SharedPitch {
    fun of(p: TakePitch): List<SharedNote> = p.notes.map {
        SharedNote(round2(it.sung), it.target?.let(::round2), it.verdict.name)
    }

    private fun round2(x: Float) = (x * 100).roundToInt() / 100f
}

/** Questions for the teacher: what's worth sending. */
object Questions {
    const val MAX_LENGTH = 1000

    /** The question as it will be sent, or null when there's nothing to send. */
    fun clean(body: String): String? = body.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH).ifEmpty { null }

    /** A bar number typed by the student, or null when it isn't one. */
    fun bar(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..9999 }
}
