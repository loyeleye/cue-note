package com.cuenote.pipeline.practice

import kotlinx.serialization.Serializable
import kotlin.math.min

/*
 * Suggested practice: today's sitting, put in order from the notes the
 * teacher already marked "practice this". Pure Kotlin, so the rules are
 * tested on the JVM.
 *
 * The teacher sets the homework. The plan only orders it and sizes it for
 * the time the student has and how their voice feels. It never adds a drill,
 * and it never invents an exercise: the gentle start and the cool-down are
 * fixed items whose words the teacher can change.
 */

/** How the student's voice feels before practice. Nothing is picked for them. */
enum class Voice { FINE, TIRED, HOARSE }

/** One note on the setlist, as the plan needs it. */
data class PlanNote(
    val cardId: String,
    /** When the note's lesson was recorded (epoch ms). */
    val lessonAt: Long,
    /** Where in the lesson, for "her order". */
    val spanStartMs: Long,
    /** The warmup or song section the note belongs to, if known. */
    val exerciseId: String?,
    /** "warmup" or "song", if known. */
    val exerciseKind: String?,
    val exerciseTitle: String?,
    /** 0..1 from the card writer: how much the point is worth drilling. */
    val weight: Float?,
    /** True when the teacher said Yes or Close to the summary, or wrote it herself. */
    val checked: Boolean,
    /** How many times she made this point in the lesson (same cue). */
    val repeats: Int,
    /** When the student last sang a practice take of it, if ever. */
    val lastPracticedAt: Long?,
    /** The teacher pinned it as her main point. */
    val pinned: Boolean = false,
    /** Her words, cue and category, to tell whether it's about high or loud singing. */
    val text: String = "",
)

/** What a step is. */
@Serializable
enum class StepKind { GENTLE_START, WARMUP, DRILL, SONG, COOL_DOWN }

/**
 * One step of today's plan. [cardId] is the note it practices (null for the
 * gentle start and cool-down). [listenOnly] steps are heard, not sung.
 * [why] is the app's own reason, shown small and labeled as the app's.
 */
@Serializable
data class PlanStep(
    val kind: StepKind,
    val cardId: String? = null,
    val minutes: Int,
    val listenOnly: Boolean = false,
    val title: String? = null,
    val why: String? = null,
    /** The app's note on how to do it today ("Lower half only"). */
    val hint: String? = null,
    val pinned: Boolean = false,
)

data class Plan(val voice: Voice, val minutes: Int, val steps: List<PlanStep>) {
    val total: Int get() = steps.sumOf { it.minutes }
}

object PracticePlan {
    /** The lengths a student can pick, shortest first. */
    val LENGTHS = listOf(10, 20, 30)

    /** Without a teacher's setting, 30 minutes stays hidden (the voice teacher's cap for beginners). */
    const val DEFAULT_MAX = 20

    /** A tired voice gets about this long, whatever was picked. */
    const val TIRED_MINUTES = 12

    const val DAY_MS = 86_400_000L

    /** Lengths offered, up to the teacher's cap. */
    fun lengths(max: Int = DEFAULT_MAX): List<Int> = LENGTHS.filter { it <= max }.ifEmpty { listOf(LENGTHS.first()) }

    /** How many drills fit: one in 10 minutes, two in 20, three in 30. */
    fun drills(minutes: Int): Int = when {
        minutes >= 30 -> 3
        minutes >= 20 -> 2
        else -> 1
    }

    private val HIGH_OR_LOUD = listOf(
        "high", "top note", "top of", "belt", "loud", "forte", "fortissimo", "climax", "up to the", "reach up",
        "passaggio", "head voice", "high note", "big note",
    )

    /** True when her words are about high or loud singing, which a tired voice should only listen to. */
    fun highOrLoud(text: String): Boolean {
        val t = text.lowercase()
        return HIGH_OR_LOUD.any { t.contains(it) }
    }

    /**
     * How strongly the app suggests a note today. Only checked summaries
     * count their weight: an unchecked one is the app's guess, so it counts
     * zero. Points she made again and again rise, and so do notes not sung
     * for a few days (spacing); one sung today drops.
     */
    fun score(n: PlanNote, now: Long): Double {
        val weight = if (n.checked) (n.weight ?: 0f).toDouble() else 0.0
        val repeats = min(n.repeats - 1, 3).coerceAtLeast(0) * 0.15
        val days = n.lastPracticedAt?.let { (now - it).toDouble() / DAY_MS } ?: 7.0
        val spacing = when {
            days < 0.5 -> -0.5
            else -> min(days, 7.0) * 0.05
        }
        return weight + repeats + spacing
    }

    /** The app's reason for picking a note, in a few words, or null when there's nothing worth saying. */
    fun why(n: PlanNote, now: Long, weekday: (Long) -> String): String? = when {
        n.pinned -> null
        n.repeats >= 3 -> "your teacher came back to this ${n.repeats} times"
        n.lastPracticedAt == null -> "not practiced yet"
        now - n.lastPracticedAt >= 2 * DAY_MS -> "not sung since ${weekday(n.lastPracticedAt)}"
        else -> null
    }

    /**
     * Today's plan. [notes] are the notes on the setlist; [warmup] is a note
     * from her latest warmup, if any, so the student can hear how she ran it.
     * [lessonTomorrow] leans the plan toward the song.
     */
    fun build(
        notes: List<PlanNote>,
        voice: Voice,
        minutes: Int,
        now: Long,
        warmup: PlanNote? = null,
        lessonTomorrow: Boolean = false,
        weekday: (Long) -> String = { "last week" },
    ): Plan {
        val ranked = notes
            .sortedWith(
                compareByDescending<PlanNote> { it.pinned }
                    .thenByDescending { score(it, now) }
                    .thenByDescending { it.lessonAt }
                    .thenBy { it.spanStartMs },
            )
        return when (voice) {
            Voice.HOARSE -> listening(ranked)
            Voice.TIRED -> tired(ranked, warmup, now, weekday)
            Voice.FINE -> fine(ranked, minutes, warmup, lessonTomorrow, now, weekday)
        }
    }

    private fun fine(
        ranked: List<PlanNote>,
        minutes: Int,
        warmup: PlanNote?,
        lessonTomorrow: Boolean,
        now: Long,
        weekday: (Long) -> String,
    ): Plan {
        val warmMin = when { minutes >= 30 -> 5; minutes >= 20 -> 4; else -> 3 }
        val drillMin = if (minutes >= 30) 5 else if (minutes >= 20) 4 else 3
        var count = drills(minutes)
        if (lessonTomorrow && count > 1) count--
        val picked = ranked.filter { it.cardId != warmup?.cardId }.take(count)
        // The song section the picked drills belong to: the one most of them share.
        val song = picked.filter { it.exerciseKind == "song" && it.exerciseId != null }
            .groupBy { it.exerciseId }.maxByOrNull { it.value.size }?.value?.first()
        val steps = buildList {
            add(PlanStep(StepKind.GENTLE_START, minutes = 2))
            warmup?.let { add(PlanStep(StepKind.WARMUP, it.cardId, warmMin, title = it.exerciseTitle)) }
            picked.forEach { n ->
                add(PlanStep(StepKind.DRILL, n.cardId, drillMin, why = why(n, now, weekday), pinned = n.pinned))
            }
            // In 10 minutes the song comes only if a drill lives in it; it's that drill, sung in context.
            if (song != null && (minutes >= 20 || picked.size == 1)) {
                val songMin = if (lessonTomorrow) drillMin + 2 else drillMin
                add(PlanStep(StepKind.SONG, song.cardId, songMin, title = song.exerciseTitle, hint = "Sing it through once with the piano."))
            }
            add(PlanStep(StepKind.COOL_DOWN, minutes = 2))
        }
        return Plan(Voice.FINE, minutes, fit(steps, minutes))
    }

    /** A bit tired: shorter, middle range only. High or loud notes are heard, not sung. No song. */
    private fun tired(ranked: List<PlanNote>, warmup: PlanNote?, now: Long, weekday: (Long) -> String): Plan {
        val (high, easy) = ranked.filter { it.cardId != warmup?.cardId }.partition { highOrLoud(it.text) }
        val steps = buildList {
            add(PlanStep(StepKind.GENTLE_START, minutes = 2))
            warmup?.let { add(PlanStep(StepKind.WARMUP, it.cardId, 3, title = it.exerciseTitle, hint = "Lower half only, nothing loud.")) }
            easy.take(1).forEach { add(PlanStep(StepKind.DRILL, it.cardId, 3, why = why(it, now, weekday), pinned = it.pinned)) }
            // Her main point (or the top one) still gets heard, even when it goes high.
            (high.firstOrNull { it.pinned } ?: high.firstOrNull() ?: easy.getOrNull(1))?.let {
                val listen = highOrLoud(it.text)
                add(
                    PlanStep(
                        StepKind.DRILL, it.cardId, if (listen) 2 else 3, listenOnly = listen, pinned = it.pinned,
                        hint = if (listen) "Listen only today: this one is about high or loud singing." else null,
                        why = if (listen) null else why(it, now, weekday),
                    ),
                )
            }
            add(PlanStep(StepKind.COOL_DOWN, minutes = 2))
        }
        return Plan(Voice.TIRED, steps.sumOf { it.minutes }, steps)
    }

    /** Hoarse or sore: no singing. Her words and demos only, then the song to follow along. */
    private fun listening(ranked: List<PlanNote>): Plan {
        val picked = ranked.take(2)
        val song = ranked.firstOrNull { it.exerciseKind == "song" && it.cardId !in picked.map { p -> p.cardId } }
            ?: picked.firstOrNull { it.exerciseKind == "song" }
        val steps = buildList {
            picked.forEach { add(PlanStep(StepKind.DRILL, it.cardId, 2, listenOnly = true, pinned = it.pinned)) }
            song?.let { add(PlanStep(StepKind.SONG, it.cardId, 3, listenOnly = true, title = it.exerciseTitle, hint = "Follow along with her demo.")) }
        }
        return Plan(Voice.HOARSE, steps.sumOf { it.minutes }, steps)
    }

    /** Trims drill time when the plan runs over the minutes picked; the gentle start and cool-down are never cut. */
    private fun fit(steps: List<PlanStep>, minutes: Int): List<PlanStep> {
        var over = steps.sumOf { it.minutes } - minutes
        if (over <= 0) return steps
        return steps.map { s ->
            if (over > 0 && s.kind in setOf(StepKind.DRILL, StepKind.SONG, StepKind.WARMUP) && s.minutes > 2) {
                val cut = min(over, s.minutes - 2)
                over -= cut
                s.copy(minutes = s.minutes - cut)
            } else s
        }
    }

    /** Where the "Still feel easy?" check goes: before this step index, once, about halfway. Null for short plans. */
    fun easyCheckBefore(plan: Plan): Int? {
        val sung = plan.steps.indices.filter { plan.steps[it].kind != StepKind.GENTLE_START && plan.steps[it].kind != StepKind.COOL_DOWN }
        if (plan.voice == Voice.HOARSE || sung.size < 2) return null
        return sung[sung.size / 2]
    }

    /**
     * After "A bit tired" at the halfway check: everything left is dropped
     * except the cool-down. Never to the song.
     */
    fun toCoolDown(steps: List<PlanStep>, from: Int): List<PlanStep> =
        steps.take(from) + steps.drop(from).filter { it.kind == StepKind.COOL_DOWN }.ifEmpty { listOf(PlanStep(StepKind.COOL_DOWN, minutes = 2)) }
}
