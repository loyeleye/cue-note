package com.cuenote.pipeline.reading

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.random.Random

/**
 * How far a melody moves from one note to the next, the way a voice teacher
 * says it: a step goes to the very next letter (line to space), a skip jumps
 * over one (line to line, or space to space), and a leap is anything wider.
 * Counted on the staff, by letters, so it reads the same in any clef.
 */
@Serializable
enum class Motion(val word: String) {
    REPEAT("same note"),
    STEP("step"),
    SKIP("skip"),
    LEAP("leap");

    companion object {
        /** The motion from [from] to [to] (natural notes). */
        fun of(from: Int, to: Int): Motion = when (abs(Staff.diatonic(to) - Staff.diatonic(from))) {
            0 -> REPEAT
            1 -> STEP
            2 -> SKIP
            else -> LEAP
        }

        /** The three a beginner practices; a repeated note is easy enough to leave out. */
        val PRACTICED = listOf(STEP, SKIP, LEAP)
    }
}

/** One pair of notes to hear, read or sing. */
data class MotionPair(val from: Int, val to: Int) {
    val motion: Motion get() = Motion.of(from, to)
    val up: Boolean get() = to > from
    /** Letter steps between the two notes (1 for a step, 2 for a skip...). */
    val size: Int get() = abs(Staff.diatonic(to) - Staff.diatonic(from))

    /** "A skip up, from G to B" and how it looks: "line to line". */
    fun say(clef: Clef): String {
        val dir = if (up) "up" else "down"
        val a = Staff.place(from, clef)
        val b = Staff.place(to, clef)
        val look = "${if (a.onLine) "line" else "space"} to ${if (b.onLine) "line" else "space"}"
        val name = when (motion) {
            Motion.LEAP -> "a leap $dir (${INTERVAL[size]})"
            else -> "a ${motion.word} $dir"
        }
        return "${name.replaceFirstChar { it.uppercase() }}, from ${Staff.letter(from)} to ${Staff.letter(to)}: $look."
    }

    companion object {
        /** Interval names by letter steps, for leaps. */
        private val INTERVAL = listOf("unison", "a second", "a third", "a fourth", "a fifth", "a sixth", "a seventh", "an octave")
    }
}

/**
 * Picks pairs for the steps, skips and leaps drills: natural notes (no sharps
 * or flats yet), both inside the student's easy range, leaps no wider than a
 * fifth to start with, and the three motions coming up about equally often.
 */
object MotionDrill {
    /** Widest leap in Stage 3, in letter steps (4 = a fifth). */
    const val WIDEST = 4

    fun pair(home: VoiceHome, random: Random = Random.Default, last: MotionPair? = null): MotionPair {
        val pool = home.easyNotes
        repeat(200) {
            val motion = Motion.PRACTICED.random(random)
            val size = when (motion) {
                Motion.STEP -> 1
                Motion.SKIP -> 2
                else -> random.nextInt(3, WIDEST + 1)
            }
            val from = pool.random(random)
            val up = random.nextBoolean()
            val to = pool.firstOrNull { Staff.diatonic(it) - Staff.diatonic(from) == if (up) size else -size }
            if (to != null && (last == null || MotionPair(from, to) != last)) return MotionPair(from, to)
        }
        // A pool too small for anything else: the plainest step.
        return MotionPair(pool.first(), pool[1])
    }
}

/** Stage 3 progress: right answers by ear and by eye, and motions sung, per motion. */
@Serializable
data class MotionProgress(
    val heard: Map<Motion, Int> = emptyMap(),
    val seen: Map<Motion, Int> = emptyMap(),
    val sung: Map<Motion, Int> = emptyMap(),
) {
    fun heardRight(m: Motion) = copy(heard = heard + (m to (heard[m] ?: 0) + 1))
    fun seenRight(m: Motion) = copy(seen = seen + (m to (seen[m] ?: 0) + 1))
    fun sang(m: Motion) = copy(sung = sung + (m to (sung[m] ?: 0) + 1))

    /** Steps, skips and leaps each told apart by ear and by eye often enough, and each sung. */
    val done: Boolean get() = Motion.PRACTICED.all {
        (heard[it] ?: 0) >= BY_EAR && (seen[it] ?: 0) >= BY_EYE && (sung[it] ?: 0) >= SUNG
    }

    companion object {
        const val BY_EAR = 8
        const val BY_EYE = 6
        const val SUNG = 4
    }
}
