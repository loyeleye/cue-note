package com.cuenote.pipeline.practice

import kotlin.math.ceil

/*
 * Today's practice as a playlist for the car (Android Auto). It plays by
 * itself, like a music app: each step is a short spoken instruction, then
 * what to sing with (her words, her demo, the piano), then the next step.
 * Nothing waits for a tap and nothing needs reading, so the driver never
 * looks at the screen. Pure Kotlin, so the order is tested on the JVM; the
 * app turns each part into a file (speech is synthesized once and kept).
 */

/** What the car needs to know about the note a step practices. */
data class CarNote(
    /** Her short cue, as on the card. */
    val cue: String,
    /** What she said, as transcribed; used when there's no cue. */
    val herWords: String,
    /** "Marigold", or null when the phone doesn't know. */
    val teacher: String?,
    /** The clip of her words is on the phone. */
    val hasWords: Boolean,
    /** Length of the piano from the lesson, or null when there's none on the phone. */
    val accompanimentMs: Long?,
    /** How many of her sung demos are on the phone. */
    val demos: Int,
)

/** One item of the car's playlist. [step] is the plan step it belongs to; the closing words have step = steps.size. */
sealed interface CarPart {
    val step: Int
    /** The line the car shows while it plays. Short: it's read at a glance, if at all. */
    val title: String
    /** True for what the student sings or listens with, rather than the instructions around it. */
    val main: Boolean get() = false

    data class Say(override val step: Int, override val title: String, val text: String) : CarPart

    /** The clip of her words from the lesson. */
    data class Words(override val step: Int, override val title: String, val cardId: String) : CarPart {
        override val main get() = true
    }

    /** Her sung demo number [number]. */
    data class Demo(override val step: Int, override val title: String, val cardId: String, val number: Int) : CarPart {
        override val main get() = true
    }

    /** The piano from the lesson, played [repeats] times back to back to fill the step. */
    data class Accompaniment(override val step: Int, override val title: String, val cardId: String, val repeats: Int) : CarPart {
        override val main get() = true
    }

    /** Quiet time to do the step on your own, ending in a soft chime: a timer you can hear. */
    data class Quiet(override val step: Int, override val title: String, val seconds: Int) : CarPart {
        override val main get() = true
    }
}

object CarPlaylist {
    /** The piano loops at most this many times in one step, however short it is. */
    const val MAX_REPEATS = 6

    /** Her words are cut to about this many characters when read aloud, so a step starts quickly. */
    const val MAX_SPOKEN = 220

    /**
     * The playlist for [steps], starting at step [from] (to pick a sitting up
     * where it was left). [titles] are the steps' names, as the phone shows
     * them; [notes] are keyed by card id; [fixedText] is the words of a gentle
     * start or cool-down. [easyCheckBefore] is where the halfway check goes.
     */
    fun build(
        steps: List<PlanStep>,
        titles: List<String>,
        notes: Map<String, CarNote>,
        fixedText: (PlanStep) -> String,
        easyCheckBefore: Int?,
        from: Int = 0,
        minutes: Int = steps.sumOf { it.minutes },
    ): List<CarPart> = buildList {
        val n = steps.size
        if (n == 0) return@buildList
        if (from == 0) {
            add(CarPart.Say(0, "Today's practice", "Here's today's practice: $n ${if (n == 1) "step" else "steps"}, about $minutes minutes. Keep your eyes on the road; it all plays by itself."))
        } else if (from < n) {
            add(CarPart.Say(from, "Picking up", "Picking up today's practice at step ${from + 1} of $n."))
        }
        for (i in from until n) {
            val s = steps[i]
            val title = titles.getOrElse(i) { "Step ${i + 1}" }
            if (i == easyCheckBefore && i > from) {
                add(
                    CarPart.Say(
                        i, "Still feel easy?",
                        "Halfway. Still feel easy? If your voice feels tired, scratchy or tight, press Cool-down to finish gently.",
                    ),
                )
            }
            val head = "Step ${i + 1} of $n: $title. "
            val note = s.cardId?.let { notes[it] }
            if (s.cardId == null || note == null) {
                val what = if (s.cardId == null) fixedText(s) else "Sing it through on your own."
                add(CarPart.Say(i, title, head + "About ${minutesText(s.minutes)}. " + what))
                add(CarPart.Quiet(i, "$title · ${minutesText(s.minutes)}", s.minutes * 60))
                continue
            }
            addAll(noteParts(i, title, head, s, note, s.cardId))
        }
        add(CarPart.Say(n, "Done", "That's today's practice. Well done. Have some water when you can."))
    }

    /** One note on its own, outside a plan: her words, her demo, then the piano once. From "Practice this". */
    fun forNote(cardId: String, title: String, note: CarNote): List<CarPart> =
        noteParts(0, title, "", PlanStep(StepKind.DRILL, cardId, minutes = 0), note, cardId)

    private fun noteParts(i: Int, title: String, head: String, s: PlanStep, note: CarNote, cardId: String): List<CarPart> = buildList {
        val who = note.teacher ?: "Your teacher"
        val said = spoken(note.cue.ifBlank { note.herWords })
        val intro = buildString {
            append(head)
            if (s.listenOnly) append("Just listen to this one today. ")
            if (said.isNotEmpty()) append("$who said: $said ")
            s.hint?.takeIf { it.isNotBlank() }?.let { append(it.trim().let { h -> if (h.endsWith('.')) "$h " else "$h. " }) }
        }.trim()
        if (intro.isNotEmpty()) add(CarPart.Say(i, title, intro))
        if (note.hasWords) add(CarPart.Words(i, "$who's words", cardId))
        if (s.listenOnly) {
            for (d in 1..note.demos) add(CarPart.Demo(i, "$who's demo${if (note.demos > 1) " $d" else ""}", cardId, d))
            if (note.demos == 0 && !note.hasWords) add(CarPart.Say(i, title, "There's nothing to listen to on this phone for this one, so on to the next."))
            return@buildList
        }
        if (note.demos > 0) add(CarPart.Demo(i, "$who's demo", cardId, 1))
        val acc = note.accompanimentMs
        if (acc != null && acc > 0) {
            add(CarPart.Say(i, title, if (s.minutes > 0) "Now sing along with the piano." else "Now sing it with the piano."))
            add(CarPart.Accompaniment(i, "$title · with piano", cardId, repeats(s.minutes, acc)))
        } else if (s.minutes > 0) {
            add(CarPart.Say(i, title, "There's no piano for this one on the phone. Sing it through on your own for about ${minutesText(s.minutes)}."))
            add(CarPart.Quiet(i, "$title · ${minutesText(s.minutes)}", s.minutes * 60))
        }
    }

    /** How many times the piano plays back to back to fill [minutes]. */
    fun repeats(minutes: Int, accompanimentMs: Long): Int {
        if (minutes <= 0 || accompanimentMs <= 0) return 1
        return ceil(minutes * 60_000.0 / accompanimentMs).toInt().coerceIn(1, MAX_REPEATS)
    }

    /** Her words, cut at a sentence or word near [MAX_SPOKEN], ending in a full stop. */
    fun spoken(text: String): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return ""
        val cut = if (t.length <= MAX_SPOKEN) t else {
            val head = t.take(MAX_SPOKEN)
            val sentence = head.lastIndexOfAny(charArrayOf('.', '!', '?'))
            if (sentence >= MAX_SPOKEN / 2) head.take(sentence + 1) else head.substringBeforeLast(' ')
        }
        return if (cut.last() in ".!?") cut else "$cut."
    }

    private fun minutesText(m: Int) = if (m == 1) "1 minute" else "$m minutes"
}
