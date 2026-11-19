package com.cuenote.pipeline.reading

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Where the student's voice lives. Exercises start where they talk, as the
 * voice teacher advised: about A3 to C4 for most women and children, an
 * octave lower for most men, and never up at C5.
 */
@Serializable
data class VoiceHome(
    /** The note nearest the speaking voice (MIDI). */
    val speaking: Int,
    val clef: Clef,
) {
    /** Exercise notes for the first stages: natural notes from a step under the speaking note to a sixth above it. */
    val easyNotes: List<Int> get() = (speaking - 2..speaking + 9).filter { Staff.isNatural(it) }

    /** Stage 1's five notes: five natural notes in a row, starting at or just under the speaking note. */
    val firstFive: List<Int> get() {
        var start = speaking
        while (!Staff.isNatural(start)) start--
        return generateSequence(start) { n -> generateSequence(n + 1) { it + 1 }.first { Staff.isNatural(it) } }.take(5).toList()
    }

    companion object {
        /**
         * A low voice (baritone or bass) reads bass clef, as their parts are
         * usually written; others read treble. Tenors can switch to treble
         * with an 8 below on the reading screen.
         */
        const val LOW_VOICE_BELOW = 52

        /** Women's and children's default before a voice check, around A3. */
        val DEFAULT = VoiceHome(57, Clef.TREBLE)

        /**
         * The speaking note from a few seconds of talking: the median of the
         * clear frames, rounded. Null with too little clear voice to tell
         * (under about a second).
         */
        fun fromSpeech(frames: List<LivePitch.Frame>): VoiceHome? {
            val s = frames.filter { it.clear }.map { it.semis }.sorted()
            if (s.size < MIN_CLEAR_FRAMES) return null
            val mid = s[s.size / 2].roundToInt()
            // Speech sits low in the singing range; lift the home note a little so exercises aren't growled.
            val home = (mid + SPEECH_TO_HOME).coerceIn(40, 69)
            return VoiceHome(home, if (home < LOW_VOICE_BELOW) Clef.BASS else Clef.TREBLE)
        }

        const val MIN_CLEAR_FRAMES = 30
        const val SPEECH_TO_HOME = 2
    }
}

/**
 * Name it's review deck: each note sits in a box from 1 (new or missed) to
 * 5 (known). Lower boxes come up more often; a miss sends a note back to 1.
 */
@Serializable
data class NameDeck(val boxes: Map<Int, Int> = emptyMap()) {
    fun box(midi: Int): Int = boxes[midi] ?: 1

    fun answer(midi: Int, right: Boolean): NameDeck =
        copy(boxes = boxes + (midi to if (right) (box(midi) + 1).coerceAtMost(MAX_BOX) else 1))

    /** The next note to show from [pool], never [last] twice in a row when there is a choice. */
    fun next(pool: List<Int>, last: Int?, random: Random = Random.Default): Int {
        val choices = pool.filter { it != last }.ifEmpty { pool }
        val weights = choices.map { 1 shl (MAX_BOX - box(it)) }
        var r = random.nextInt(weights.sum())
        for ((i, w) in weights.withIndex()) { if (r < w) return choices[i]; r -= w }
        return choices.last()
    }

    /** True when every note in [pool] has reached box 4. */
    fun knows(pool: List<Int>): Boolean = pool.all { box(it) >= KNOWN_BOX }

    companion object {
        const val MAX_BOX = 5
        const val KNOWN_BOX = 4
    }
}

/** One stage of the reading and pitch track, as the ladder shows it. */
enum class ReadingStage(val title: String, val about: String) {
    FIND_YOUR_VOICE("Find your voice", "Match a note you hear, and hold it"),
    THE_STAFF("The staff", "Five notes on the staff: name them and sing them"),
    STEPS_SKIPS_LEAPS("Steps, skips and leaps", "How far a melody moves, by ear and on the page"),
    SHORT_MELODIES("Short melodies", "Sing a few bars from just the first note"),
    THE_SCORE("The piano-vocal score", "Find your line, follow the piano, come in on time"),
}

/** What the student has done in this track, kept on the phone. */
@Serializable
data class ReadingProgress(
    val home: VoiceHome? = null,
    /** Notes matched in Match the note, by day (days since the epoch). */
    val matchedDays: Map<Long, Int> = emptyMap(),
    /** Written notes sung in Sing what you see, by MIDI note. */
    val sungFromPage: Map<Int, Int> = emptyMap(),
    val deck: NameDeck = NameDeck(),
    /** Stage 3: steps, skips and leaps. */
    val motions: MotionProgress = MotionProgress(),
    /** Stage 4: short melodies. */
    val melodies: MelodyProgress = MelodyProgress(),
) {
    val voice: VoiceHome get() = home ?: VoiceHome.DEFAULT

    fun matched(day: Long, n: Int = 1) = copy(matchedDays = matchedDays + (day to (matchedDays[day] ?: 0) + n))
    fun sang(midi: Int) = copy(sungFromPage = sungFromPage + (midi to (sungFromPage[midi] ?: 0) + 1))

    val totalMatched: Int get() = matchedDays.values.sum()
}

/**
 * Which stage the student is on. The first four are built so far; the
 * last shows on the ladder as what comes next.
 */
object ReadingLadder {
    /** Matched notes, over at least two days, before the staff opens up. */
    const val MATCHES_TO_MOVE_ON = 12
    const val DAYS_TO_MOVE_ON = 2
    /** Times each of the five notes is sung from the page before Stage 1 is done. */
    const val SUNG_EACH = 2

    fun stage(p: ReadingProgress): ReadingStage = when {
        !foundVoice(p) -> ReadingStage.FIND_YOUR_VOICE
        !knowsStaff(p) -> ReadingStage.THE_STAFF
        !p.motions.done -> ReadingStage.STEPS_SKIPS_LEAPS
        !p.melodies.done -> ReadingStage.SHORT_MELODIES
        else -> ReadingStage.THE_SCORE
    }

    fun foundVoice(p: ReadingProgress): Boolean =
        p.totalMatched >= MATCHES_TO_MOVE_ON && p.matchedDays.count { it.value > 0 } >= DAYS_TO_MOVE_ON

    fun knowsStaff(p: ReadingProgress): Boolean {
        val five = p.voice.firstFive
        return p.deck.knows(five) && five.all { (p.sungFromPage[it] ?: 0) >= SUNG_EACH }
    }

    /** A line on how it's going, in words, never a score. */
    fun progressWords(p: ReadingProgress): String = when (stage(p)) {
        ReadingStage.FIND_YOUR_VOICE -> when {
            p.home == null -> "Start with a quick voice check, so the notes sit where you talk."
            p.totalMatched == 0 -> "Your voice is set. Next, match a few notes."
            p.totalMatched < MATCHES_TO_MOVE_ON -> "You've matched ${p.totalMatched} notes. Keep going over a couple of days."
            else -> "Nearly there. Match a few more on another day."
        }
        ReadingStage.THE_STAFF -> {
            val five = p.voice.firstFive
            val named = five.count { p.deck.box(it) >= NameDeck.KNOWN_BOX }
            val sung = five.count { (p.sungFromPage[it] ?: 0) >= SUNG_EACH }
            when {
                named == 0 && sung == 0 -> "You can match notes. Now learn where they sit on the staff."
                named < five.size -> "You know $named of your five notes by name."
                else -> "You know all five by name. Sing each one from the page ($sung of 5 so far)."
            }
        }
        ReadingStage.STEPS_SKIPS_LEAPS -> {
            val m = p.motions
            val left = Motion.PRACTICED.filter {
                (m.heard[it] ?: 0) < MotionProgress.BY_EAR || (m.seen[it] ?: 0) < MotionProgress.BY_EYE || (m.sung[it] ?: 0) < MotionProgress.SUNG
            }
            if (left.size == Motion.PRACTICED.size && m.heard.isEmpty() && m.seen.isEmpty() && m.sung.isEmpty())
                "You can name and sing your five notes. Now hear, spot and sing steps, skips and leaps."
            else "Still to settle: ${left.joinToString(", ") { it.word + "s" }}. Hear them, spot them and sing them."
        }
        ReadingStage.SHORT_MELODIES -> {
            val m = p.melodies
            val left = buildList {
                if (m.tunesSung < MelodyProgress.SUNG_TUNES) add("sing " + more(MelodyProgress.SUNG_TUNES - m.tunesSung, "tune") + " from the page")
                if (m.tunesTapped < MelodyProgress.TAPPED_TUNES) add("tap " + more(MelodyProgress.TAPPED_TUNES - m.tunesTapped, "rhythm"))
                if (!m.anchorsKnown) add("learn the songs for each leap")
            }
            if (m.sung.isEmpty() && m.tapped.isEmpty() && m.anchors.isEmpty())
                "Steps, skips and leaps feel solid. Now sing a few bars from just the first note."
            else "Still to do: ${left.joinToString(", ")}."
        }
        ReadingStage.THE_SCORE -> "Short melodies feel solid. The piano-vocal score is next."
    }

    private fun more(n: Int, thing: String) = if (n == 1) "1 more $thing" else "$n more ${thing}s"

    /** Days since the epoch in local time, for [ReadingProgress.matchedDays]. */
    fun day(epochMs: Long, offsetMs: Int): Long = Math.floorDiv(epochMs + offsetMs, 86_400_000L)
}
