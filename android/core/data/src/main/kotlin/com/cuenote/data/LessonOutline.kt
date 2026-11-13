package com.cuenote.data

import com.cuenote.pipeline.cards.CloudIds

/**
 * A lesson as the student reads it: one section per exercise (a warmup, or a
 * section of the song), each with its tries and the few cues that matter,
 * rather than a card for every take. Pure, so it is tested on the JVM.
 *
 * A singer practises exercises, not takes: half of a lesson's takes are
 * three-second tries nobody comments on, and her feedback runs across them.
 * So each section leads with at most [MAIN_CUES] cues, "Practice this" first,
 * and folds the rest away. When she makes the same point twice in an
 * exercise the card writer gives both cards the same cue, and they show as
 * one card that says how often she came back to it.
 */
object LessonOutline {
    const val MAIN_CUES = 3

    /** One cue as the student sees it: the card to show, and any later cards that say the same thing. */
    data class CueGroup(val card: CardEntity, val repeats: List<CardEntity> = emptyList()) {
        val all get() = listOf(card) + repeats
        val practice get() = all.any { it.practiceFlag == "practice_this" }
    }

    data class Section(
        /** Null for notes outside any exercise, or for a lesson processed before exercises existed. */
        val exercise: ExerciseEntity?,
        val name: String,
        /** Warmup or song, for the header's small label. */
        val kind: String?,
        val startMs: Long,
        val takes: List<TakeEntity>,
        /** The cues to show, at most [MAIN_CUES]. */
        val cues: List<CueGroup>,
        /** The rest, behind "More notes". */
        val more: List<CueGroup>,
        /** Her feedback blocks in this exercise, for the view before cards. */
        val feedbackCount: Int,
    )

    fun build(
        exercises: List<ExerciseEntity>,
        takes: List<TakeEntity>,
        feedback: List<FeedbackEntity>,
        cards: List<CardEntity>,
    ): List<Section> {
        val ex = exercises.sortedBy { it.startMs }
        val known = ex.map { it.id }.toSet()
        val fbExercise = feedback.associate { it.id to it.exerciseId }
        val takeByCloud = takes.associateBy { CloudIds.take(it.id) }

        fun exerciseOfTake(t: TakeEntity) = t.exerciseId?.takeIf { it in known }
        fun exerciseOfCard(c: CardEntity): String? {
            c.localId?.let { fbExercise[it] }?.takeIf { it in known }?.let { return it }
            c.takeId?.let { takeByCloud[it] }?.let(::exerciseOfTake)?.let { return it }
            // A card a teacher added, or a lesson processed again: the exercise it falls in.
            return ex.lastOrNull { it.startMs <= c.spanStartMs && c.spanStartMs < it.endMs }?.id
        }

        val notes = cards.filter { it.category != "chat" }
        val cardsBy = notes.groupBy { exerciseOfCard(it) }
        val takesBy = takes.groupBy { exerciseOfTake(it) }
        val fbBy = feedback.groupBy { it.exerciseId?.takeIf { e -> e in known } }
        val names = names(ex)

        val sections = ex.map { e ->
            section(e, names.getValue(e.id), e.kind, e.startMs, takesBy[e.id].orEmpty(), cardsBy[e.id].orEmpty(), fbBy[e.id].orEmpty().size)
        }.toMutableList()
        // Whatever isn't in an exercise: everything, for a lesson from before exercises.
        val looseTakes = takesBy[null].orEmpty()
        val looseCards = cardsBy[null].orEmpty()
        if (looseTakes.isNotEmpty() || looseCards.isNotEmpty()) {
            val start = minOf(looseTakes.minOfOrNull { it.startMs } ?: Long.MAX_VALUE, looseCards.minOfOrNull { it.spanStartMs } ?: Long.MAX_VALUE)
            val name = if (ex.isEmpty()) "This lesson" else "Other notes"
            sections += section(null, name, null, start, looseTakes, looseCards, fbBy[null].orEmpty().size)
        }
        return sections.sortedBy { it.startMs }
    }

    private fun section(
        e: ExerciseEntity?, name: String, kind: String?, start: Long,
        takes: List<TakeEntity>, cards: List<CardEntity>, feedbackCount: Int,
    ): Section {
        val groups = group(cards)
        val (said, blank) = groups.partition { it.card.cue.isNotBlank() }
        val ranked = said.sortedWith(compareByDescending<CueGroup> { it.practice }.thenBy { it.card.spanStartMs })
        return Section(
            exercise = e, name = name, kind = kind, startMs = start,
            takes = takes.sortedBy { it.startMs },
            cues = ranked.take(MAIN_CUES),
            more = ranked.drop(MAIN_CUES) + blank,
            feedbackCount = feedbackCount,
        )
    }

    /** Cards with the same cue become one; the card shown is her edit, else a "Practice this" one, else the first. */
    fun group(cards: List<CardEntity>): List<CueGroup> {
        val out = mutableListOf<CueGroup>()
        val byKey = linkedMapOf<String, MutableList<CardEntity>>()
        for (c in cards.sortedBy { it.spanStartMs }) {
            val k = key(c.cue)
            if (k.isEmpty()) out += CueGroup(c) else byKey.getOrPut(k) { mutableListOf() } += c
        }
        for (same in byKey.values) {
            val lead = same.firstOrNull { it.editedByTeacher } ?: same.firstOrNull { it.practiceFlag == "practice_this" } ?: same.first()
            out += CueGroup(lead, same - lead)
        }
        return out.sortedBy { it.card.spanStartMs }
    }

    private fun key(cue: String) = cue.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** "Lip trill on a 5-note scale" when named; otherwise "Warmup 2", or "Song" ("Song, part 2" when it came up again). */
    fun names(exercises: List<ExerciseEntity>): Map<String, String> {
        val warmups = exercises.count { it.kind != "song" }
        val songs = exercises.count { it.kind == "song" }
        var w = 0
        var s = 0
        return exercises.sortedBy { it.startMs }.associate { e ->
            val fallback = if (e.kind == "song") {
                s++; if (songs > 1) "Song, part $s" else "Song"
            } else {
                w++; if (warmups > 1) "Warmup $w" else "Warmup"
            }
            e.id to (e.title?.takeIf { it.isNotBlank() } ?: fallback)
        }
    }
}
