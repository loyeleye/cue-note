package com.cuenote.data

import com.cuenote.pipeline.cards.CloudIds

/**
 * A lesson as the student reads it: one section per exercise (a warmup, or a
 * section of the song), each with its tries and the few points that matter,
 * rather than a card for every take. Pure, so it is tested on the JVM.
 *
 * A singer practices exercises, not takes: half of a lesson's takes are
 * three-second tries nobody comments on, and her feedback runs across them.
 * So each section shows at most [MAIN_POINTS] points, the ones the card writer
 * judged most worth drilling (its weight; a long explanation of technique
 * beats a story, music theory or a quick "yes"), in lesson order, and folds
 * the rest away. When she makes the same point twice in an exercise the card
 * writer gives both cards the same cue, and they show as one.
 *
 * Before the card writer has run, each section shows a best guess instead:
 * the longest things she said that were clearly about a try and sound like
 * technique (the breath, the jaw, "reach", "vibrate") rather than theory or a
 * story (octaves, keys, the piano), cut to the sentence or two of her own
 * words that carry the most of it. Only the card writer can judge depth; this
 * is a stand-in until it has run.
 *
 * A try she praised ("Yes, that was it") is starred: the one to copy.
 */
object LessonOutline {
    const val MAIN_POINTS = 3

    /** A best guess before cards: at most this many per exercise. */
    const val MAX_GUESSES = 2

    /** Shorter than this, a block is rarely a point worth drilling ("Yes. So small,"). */
    const val GUESS_MIN_CHARS = 120

    /** One point as the student sees it: the card to show, and any later cards that say the same thing. */
    data class CueGroup(val card: CardEntity, val repeats: List<CardEntity> = emptyList()) {
        val all get() = listOf(card) + repeats
        val practice get() = all.any { it.practiceFlag == "practice_this" }
        val weight get() = all.mapNotNull { it.weight }.maxOrNull()
        /** The writer's summary, else her own line. */
        val text get() = card.summary.ifBlank { card.herWords }
        /** She explained what she meant, in words or a voice note. */
        val explained get() = !card.summaryNote.isNullOrBlank() || card.summaryNoteClip != null
        /** Where the summary stands with the teacher. */
        val check get() = when {
            card.summaryRejected != null && card.summary.isNotBlank() -> SummaryCheck.CORRECTED
            card.summaryRejected != null || card.summaryCheck == "not_said" -> SummaryCheck.FLAGGED
            card.summaryCheck == "close" && card.summary.isNotBlank() -> SummaryCheck.CLOSE
            card.summaryCheck == "said" && card.summary.isNotBlank() -> SummaryCheck.CONFIRMED
            else -> SummaryCheck.UNCHECKED
        }
    }

    /**
     * The teacher's answer to "Did you say this?" about a point's summary.
     * CLOSE: near enough; the summary stays, marked close.
     * FLAGGED: she said it was wrong, so the summary is off the card.
     * CORRECTED: she rewrote the summary herself after the app's was wrong.
     * Close and flagged points may carry her own explanation and voice note.
     */
    enum class SummaryCheck { UNCHECKED, CONFIRMED, CLOSE, FLAGGED, CORRECTED }

    /** Before cards: something she said at length about a try, as her own words cut short. */
    data class Guess(val feedback: FeedbackEntity, val excerpt: String)

    data class Section(
        /** Null for notes outside any exercise, or for a lesson processed before exercises existed. */
        val exercise: ExerciseEntity?,
        val name: String,
        /** Warmup or song, for the header's small label. */
        val kind: String?,
        val startMs: Long,
        val takes: List<TakeEntity>,
        /** The points to show, at most [MAIN_POINTS], in lesson order. */
        val cues: List<CueGroup>,
        /** The rest, behind "More notes". */
        val more: List<CueGroup>,
        /** Her feedback blocks in this exercise. */
        val feedbackCount: Int,
        /** Before cards only: the app's own guess at her main points. */
        val guesses: List<Guess> = emptyList(),
        /** Local ids of the tries she praised. */
        val starred: Set<String> = emptySet(),
    ) {
        /** "Try 3": a take's number within this exercise, by its local id. */
        fun tryNo(localTakeId: String?): Int? = takes.indexOfFirst { it.id == localTakeId }.takeIf { it >= 0 }?.plus(1)
    }

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
        val guessing = cards.isEmpty()

        // The tries she praised: an encouragement card on a take, or (before cards) a short "yes, that was it".
        val starred = if (!guessing) {
            notes.filter { it.category == "encouragement" }.mapNotNull { c -> c.takeId?.let { takeByCloud[it]?.id } }.toSet()
        } else {
            feedback.filter { it.takeId != null && praise(it.words) }.mapNotNull { it.takeId }.toSet()
        }

        fun make(e: ExerciseEntity?, name: String, kind: String?, start: Long, ts: List<TakeEntity>, cs: List<CardEntity>, fbs: List<FeedbackEntity>) =
            section(e, name, kind, start, ts, cs, fbs, guessing, starred)

        val sections = ex.map { e ->
            make(e, names.getValue(e.id), e.kind, e.startMs, takesBy[e.id].orEmpty(), cardsBy[e.id].orEmpty(), fbBy[e.id].orEmpty())
        }.toMutableList()
        // Whatever isn't in an exercise: everything, for a lesson from before exercises.
        val looseTakes = takesBy[null].orEmpty()
        val looseCards = cardsBy[null].orEmpty()
        val looseFb = fbBy[null].orEmpty()
        if (looseTakes.isNotEmpty() || looseCards.isNotEmpty() || (guessing && looseFb.any { guessable(it) })) {
            val start = listOfNotNull(looseTakes.minOfOrNull { it.startMs }, looseCards.minOfOrNull { it.spanStartMs }, looseFb.minOfOrNull { it.spanStartMs }).minOrNull() ?: 0
            val name = if (ex.isEmpty()) "This lesson" else "Other notes"
            sections += make(null, name, null, start, looseTakes, looseCards, looseFb)
        }
        return sections.sortedBy { it.startMs }
    }

    private fun section(
        e: ExerciseEntity?, name: String, kind: String?, start: Long,
        takes: List<TakeEntity>, cards: List<CardEntity>, feedback: List<FeedbackEntity>,
        guessing: Boolean, starred: Set<String>,
    ): Section {
        val groups = group(cards)
        // Praise marks a try; it isn't a point to drill unless the writer thought so.
        val (said, rest) = groups.partition { it.card.cue.isNotBlank() && !(it.card.category == "encouragement" && (it.weight ?: 0f) < 0.5f) }
        val ranked = said.sortedWith(
            compareByDescending<CueGroup> { it.weight ?: 0.5f }
                .thenByDescending { it.practice }
                .thenByDescending { it.all.sumOf { c -> c.transcript.length } },
        )
        val shown = ranked.take(MAIN_POINTS)
        val ts = takes.sortedBy { it.startMs }
        return Section(
            exercise = e, name = name, kind = kind, startMs = start,
            takes = ts,
            cues = shown.sortedBy { it.card.spanStartMs },
            more = (ranked.drop(MAIN_POINTS) + rest).sortedBy { it.card.spanStartMs },
            feedbackCount = feedback.size,
            guesses = if (guessing) guesses(feedback) else emptyList(),
            starred = ts.map { it.id }.filter { it in starred }.toSet(),
        )
    }

    /** Below this [guessScore] a block is more likely theory, a story or a passing remark than a point to drill. */
    const val GUESS_MIN_SCORE = 200.0

    /** Words that point at technique in the body or the sound. */
    private val TECH = Regex(
        "\\b(?:breath\\w*|air|lips?|jaw|tongue|throat|mouth|nose|face|chest|head|vibrat\\w*|buzz\\w*|tense|tension|relax\\w*|reach\\w*|" +
            "land\\w*|fall\\w*|open\\w*|space|room|tube|vowels?|sound|high|low|feel|imagine|support|push\\w*|squeez\\w*|" +
            "loud|soft|registers?|mix\\w*)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Words that point at theory, a story or the business of the lesson. */
    private val THEORY = Regex(
        "\\b(?:octaves?|piano|keys?|play|plays|played|playing|chords?|harmon\\w*|intervals?|semitones?|clef|transpose|opera|popular|america|character|called|culture|cultural|christmas\\w*|song|lesson|week|schedule)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Before cards: the blocks most likely to be technique about a try, best first, then shown in lesson order. */
    fun guesses(feedback: List<FeedbackEntity>): List<Guess> =
        feedback.filter(::guessable)
            .map { it to guessScore(it.words, it.linkConfidence) }
            .filter { it.second >= GUESS_MIN_SCORE }
            .sortedByDescending { it.second }
            .take(MAX_GUESSES)
            .map { it.first }
            .sortedBy { it.spanStartMs }
            .map { Guess(it, excerpt(it.words)) }

    private fun guessable(f: FeedbackEntity) = f.takeId != null && f.words.trim().length >= GUESS_MIN_CHARS

    /** Length, how surely it is about the try before it, and how much it sounds like technique rather than theory. */
    fun guessScore(words: String, linkConfidence: Float): Double {
        val tech = TECH.findAll(words).count()
        val theory = THEORY.findAll(words).count()
        val n = sentences(words).size.coerceAtLeast(1)
        return words.length * linkConfidence.coerceIn(0.3f, 1f).toDouble() *
            (0.5 + minOf(1.0, tech.toDouble() / n)) * (if (theory > tech / 2.0) 0.3 else 1.0)
    }

    private fun sentences(words: String) =
        words.replace(Regex("\\s+"), " ").trim().split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }

    /** The one to three sentences of hers, within [max] characters, that say the most about technique. */
    fun excerpt(words: String, max: Int = 220): String {
        val ss = sentences(words)
        var best: Pair<Double, String>? = null
        for (i in ss.indices) for (n in 1..3) {
            if (i + n > ss.size) break
            val text = ss.subList(i, i + n).joinToString(" ")
            if (text.length > max) break
            val score = TECH.findAll(text).count() - 2.0 * THEORY.findAll(text).count() + if (text.length >= 60) 0.5 else -1.0
            if (best == null || score > best.first) best = score to text
        }
        best?.let { return it.second }
        val first = ss.firstOrNull() ?: words.trim()
        return first.take(max).replace(Regex("\\s+\\S*$"), "") + "…"
    }

    private val PRAISE = Regex(
        "^\\W*(?:yes|yeah|good|great|beautiful|perfect|exactly|nice)\\b.{0,40}?(?:that was it|that's it|that's the one|exactly|perfect|beautiful|very close)",
        RegexOption.IGNORE_CASE,
    )

    /** A short "Yes, that was it" after a try. */
    fun praise(words: String): Boolean = words.trim().length <= 60 && PRAISE.containsMatchIn(words.trim())

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
