package com.cuenote.pipeline.reading

import kotlinx.serialization.Serializable

/** The clef a singer reads in. */
@Serializable
enum class Clef(
    /** The note on the bottom line of the staff, as a diatonic number (see [Staff.diatonic]). */
    val bottomLine: Int,
    val label: String,
) {
    /** Higher voices: E4 on the bottom line. */
    TREBLE(Staff.diatonic(64), "Treble"),
    /** Tenor parts and many men's lines: a treble clef with an 8 below, sounding an octave lower (E3 on the bottom line). */
    TREBLE_8VB(Staff.diatonic(52), "Treble, an octave down"),
    /** Low voices and the piano's left hand: G2 on the bottom line. */
    BASS(Staff.diatonic(43), "Bass"),
}

enum class Accidental { SHARP, FLAT }

/**
 * Where a note sits on a five-line staff. [step] 0 is the bottom line, 1 the
 * space above it, up to 8 for the top line; below 0 and above 8 need ledger
 * lines.
 */
data class Placed(val midi: Int, val step: Int, val accidental: Accidental?) {
    val onLine: Boolean get() = Math.floorMod(step, 2) == 0
    /** Short lines through or under/over the note, outside the staff (steps of those lines). */
    val ledgers: List<Int> get() = when {
        step < 0 -> (-2 downTo step step 2).toList()
        step > 8 -> (10..step step 2).toList()
        else -> emptyList()
    }
}

object Staff {
    private val LETTERS = listOf("C", "D", "E", "F", "G", "A", "B")
    /** Pitch class to (letter index, sharp?) with sharps; flats are spelled from the letter above. */
    private val SHARP_SPELL = listOf(0 to false, 0 to true, 1 to false, 1 to true, 2 to false, 3 to false, 3 to true, 4 to false, 4 to true, 5 to false, 5 to true, 6 to false)
    private val FLAT_SPELL = listOf(0 to false, 1 to true, 1 to false, 2 to true, 2 to false, 3 to false, 4 to true, 4 to false, 5 to true, 5 to false, 6 to true, 6 to false)

    /** Letter steps from C-1: C4 (MIDI 60) is 35. Only for natural notes; see [place]. */
    fun diatonic(midi: Int): Int {
        val (letter, _) = SHARP_SPELL[Math.floorMod(midi, 12)]
        return letter + 7 * (Math.floorDiv(midi, 12) - 1)
    }

    fun isNatural(midi: Int): Boolean = !SHARP_SPELL[Math.floorMod(midi, 12)].second

    fun place(midi: Int, clef: Clef, flats: Boolean = false): Placed {
        val (letter, altered) = (if (flats) FLAT_SPELL else SHARP_SPELL)[Math.floorMod(midi, 12)]
        // A flat is spelled from the letter above, so the octave can move with it (C-flat aside, not used here).
        val octave = Math.floorDiv(midi, 12) - 1
        val d = letter + 7 * octave
        val acc = if (!altered) null else if (flats) Accidental.FLAT else Accidental.SHARP
        return Placed(midi, d - clef.bottomLine, acc)
    }

    /** The letter alone ("G"), for Name it's buttons. */
    fun letter(midi: Int): String = LETTERS[SHARP_SPELL[Math.floorMod(midi, 12)].first]

    /** A name with its octave, "G3" or "F♯4", as in the starting-note button. */
    fun name(midi: Int): String {
        val (l, sharp) = SHARP_SPELL[Math.floorMod(midi, 12)]
        return LETTERS[l] + (if (sharp) "♯" else "") + (Math.floorDiv(midi, 12) - 1)
    }

    /** The seven letters in order, for the answer buttons. */
    val letters: List<String> get() = LETTERS

    /**
     * Where a note sits, said the way a beginner learns it: "the second line",
     * "the space above the top line", "one line below the staff".
     */
    fun describe(p: Placed): String {
        val ord = listOf("bottom", "second", "middle", "fourth", "top")
        val spaceOrd = listOf("first", "second", "third", "fourth")
        return when {
            p.step in 0..8 && p.onLine -> "on the ${ord[p.step / 2]} line"
            p.step in 1..7 -> "in the ${spaceOrd[p.step / 2]} space"
            p.step == -1 -> "in the space under the staff"
            p.step == 9 -> "in the space above the staff"
            p.step < 0 -> "below the staff, " + ledger(p.onLine, if (p.onLine) -p.step / 2 else (-p.step - 1) / 2)
            else -> "above the staff, " + ledger(p.onLine, if (p.onLine) (p.step - 8) / 2 else (p.step - 9) / 2)
        }
    }

    /** On or beyond [n] short (ledger) lines. */
    private fun ledger(onLine: Boolean, n: Int): String {
        val lines = if (n == 1) "1 short line" else "$n short lines"
        return if (onLine) "on the ${ORDINAL[n.coerceIn(1, 4)]} short line" else "past $lines"
    }

    private val ORDINAL = listOf("", "first", "second", "third", "fourth")
}
