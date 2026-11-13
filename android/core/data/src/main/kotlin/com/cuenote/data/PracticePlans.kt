package com.cuenote.data

import com.cuenote.pipeline.practice.Plan
import com.cuenote.pipeline.practice.PlanNote
import com.cuenote.pipeline.practice.PlanStep
import com.cuenote.pipeline.practice.PracticePlan
import com.cuenote.pipeline.practice.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Suggested practice on the phone: turns the setlist into [PlanNote]s (with
 * each note's exercise, how often she made the point, whether she checked
 * the summary and when it was last sung), asks [PracticePlan] for today's
 * order, and keeps each sitting in `practice_plan`.
 */
class PracticePlans(
    private val dao: PracticeDao,
    private val cards: CardDao,
    private val pipeline: PipelineDao?,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val stepsSer = ListSerializer(PlanStep.serializer())
    private val intsSer = ListSerializer(Int.serializer())

    /** Today's latest sitting, for "Pick up at 4 of 6" and "Done today". */
    fun today(): Flow<PracticePlanEntity?> = dao.observeLatestPlan(day(System.currentTimeMillis()))

    fun steps(p: PracticePlanEntity): List<PlanStep> = runCatching { json.decodeFromString(stepsSer, p.steps) }.getOrDefault(emptyList())

    fun skipped(p: PracticePlanEntity): List<Int> = runCatching { json.decodeFromString(intsSer, p.skipped) }.getOrDefault(emptyList())

    suspend fun get(id: String): PracticePlanEntity? = dao.plan(id)

    /** Builds today's plan from [setlist] and starts a sitting. Returns its id. */
    suspend fun start(setlist: List<SetlistRow>, voice: Voice, minutes: Int, nextLessonAt: Long?): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val (notes, warmup) = notes(setlist)
        val tomorrow = nextLessonAt != null && nextLessonAt - now in 0..(36 * 3_600_000L)
        val plan = PracticePlan.build(notes, voice, minutes, now, warmup, tomorrow, ::weekday)
        val id = UUID.randomUUID().toString()
        dao.upsertPlan(entity(id, plan, now))
        id
    }

    private fun entity(id: String, plan: Plan, now: Long) = PracticePlanEntity(
        id = id, day = day(now), startedAt = now, updatedAt = now, endedAt = null,
        voice = plan.voice.name, minutes = plan.minutes, steps = json.encodeToString(stepsSer, plan.steps),
        stepIndex = 0, done = 0, skipped = "[]", easyChecked = false,
    )

    /** The student finished step [index] (Next) or skipped it. Ends the sitting after the last step. */
    suspend fun advance(id: String, index: Int, skip: Boolean): PracticePlanEntity? {
        val p = dao.plan(id) ?: return null
        val now = System.currentTimeMillis()
        val steps = steps(p)
        val skipped = if (skip) skipped(p) + index else skipped(p)
        val next = index + 1
        val updated = p.copy(
            stepIndex = next, done = if (skip) p.done else p.done + 1,
            skipped = json.encodeToString(intsSer, skipped), updatedAt = now,
            endedAt = if (next >= steps.size) now else null, synced = false,
        )
        dao.upsertPlan(updated)
        return updated
    }

    /** "Still feel easy?" was asked; [toCoolDown] drops the rest of the plan except the cool-down. */
    suspend fun easyChecked(id: String, toCoolDown: Boolean): PracticePlanEntity? {
        val p = dao.plan(id) ?: return null
        val steps = if (toCoolDown) PracticePlan.toCoolDown(steps(p), p.stepIndex) else steps(p)
        val updated = p.copy(easyChecked = true, steps = json.encodeToString(stepsSer, steps), updatedAt = System.currentTimeMillis(), synced = false)
        dao.upsertPlan(updated)
        return updated
    }

    /** Ends the sitting early, keeping what was done. */
    suspend fun end(id: String) {
        val p = dao.plan(id) ?: return
        val now = System.currentTimeMillis()
        if (p.endedAt == null) dao.upsertPlan(p.copy(endedAt = now, updatedAt = now, synced = false))
    }

    /** The setlist as the planner needs it, and a note from her latest warmup. */
    private suspend fun notes(setlist: List<SetlistRow>): Pair<List<PlanNote>, PlanNote?> {
        val last = dao.lastTakes().associate { it.cardId to it.at }
        val onSetlist = setlist.map { it.card.id }.toSet()
        val notes = mutableListOf<PlanNote>()
        var warmup: PlanNote? = null
        val lessons = setlist.groupBy { it.card.lessonId }
        // Newest lesson first, so the warmup is the latest one she ran.
        for ((lessonId, rows) in lessons.entries.sortedByDescending { e -> e.value.maxOf { it.recordedAt } }) {
            val at = rows.first().recordedAt
            val sections = pipeline?.let { pd ->
                LessonOutline.build(pd.exercises(lessonId), pd.takes(lessonId), pd.feedback(lessonId), cards.cards(lessonId))
            }.orEmpty()
            val placed = mutableSetOf<String>()
            for (s in sections) {
                for (g in s.cues + s.more) {
                    val ids = g.all.map { it.id }
                    val c = g.all.firstOrNull { it.id in onSetlist }
                    if (c == null) {
                        // Her warmup: hear how she ran it, even when no note in it is on the setlist.
                        if (warmup == null && s.kind == "warmup" && s.exercise != null) warmup = note(g.card, at, s, g, last)
                        continue
                    }
                    placed += ids
                    val n = note(c, at, s, g, last)
                    notes += n
                    if (warmup == null && s.kind == "warmup") warmup = n
                }
            }
            // Notes the outline didn't place (no processing on this phone): still on the plan, without an exercise.
            rows.filter { it.card.id !in placed }.forEach { r ->
                val c = r.card
                notes += PlanNote(
                    c.id, at, c.spanStartMs, null, null, null, c.weight, checked(c), 1, last[c.id],
                    text = listOf(c.herWords, c.cue, c.summary).joinToString(" "),
                )
            }
        }
        return notes to warmup
    }

    private fun note(c: CardEntity, at: Long, s: LessonOutline.Section, g: LessonOutline.CueGroup, last: Map<String, Long>) = PlanNote(
        cardId = c.id, lessonAt = at, spanStartMs = c.spanStartMs,
        exerciseId = s.exercise?.id, exerciseKind = s.kind, exerciseTitle = s.name,
        weight = g.weight, checked = g.all.any(::checked), repeats = g.all.size,
        lastPracticedAt = g.all.mapNotNull { last[it.id] }.maxOrNull(),
        text = g.all.joinToString(" ") { listOf(it.herWords, it.cue, it.summary).joinToString(" ") },
    )

    private fun checked(c: CardEntity) = c.summaryCheck == "said" || c.summaryCheck == "close" || c.editedByTeacher

    companion object {
        fun day(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
        fun weekday(ms: Long): String = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(ms))
    }
}
