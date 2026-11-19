package com.cuenote.pipeline.reading

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Match the note: one target note, and the student's voice coming in frame
 * by frame. Says which way to go in plain words and settles once the note
 * has been held close enough for about a second. Nothing here is shown to
 * the student as a number.
 *
 * Singing the right note an octave away counts: a man answering a piano
 * note an octave down has matched it. The octave is reported so the screen
 * can say so kindly.
 */
class NoteMatch(
    val target: Int,
    /** Within this (cents) the note is on. Wider than the pitch strip's 35 to start a beginner off. */
    private val tolerance: Double = BEGINNER_CENTS,
    private val holdFrames: Int = HOLD_MS / LivePitch.FRAME_MS,
) {
    /** Which way to go, from where the voice is now. */
    enum class Hint {
        /** Nothing sung yet, or a pause. */
        WAITING,
        /** Sound, but no clear pitch (breathy, or the room is noisy). */
        UNCLEAR,
        /** Well below: slide up to it. */
        WAY_LOW,
        LOW,
        /** Just under: a touch higher. */
        A_LITTLE_LOW,
        ON,
        A_LITTLE_HIGH,
        HIGH,
        WAY_HIGH,
    }

    data class State(
        val hint: Hint,
        /** Off by this much (cents, folded into the nearest octave), for placing the marker; null without a clear note. */
        val cents: Double?,
        /** Whole octaves between the voice and the target, when sung clearly. */
        val octave: Int,
        /** 0..1 of the hold, for the ring that fills while the note is held. */
        val held: Float,
        /** True once matched; it stays true. */
        val settled: Boolean,
    )

    private var run = 0
    private var gap = 0
    private var settled = false
    private var lastOctave = 0
    private var firstSettledOctave: Int? = null

    /** The octave the match was made in; null before it settles. */
    val matchedOctave: Int? get() = firstSettledOctave

    fun push(f: LivePitch.Frame): State {
        if (!f.clear) {
            // Short dropouts (a consonant, a breath catch) don't reset the hold.
            gap++
            if (gap > MAX_GAP_FRAMES) run = 0
            val hint = if (f.db < SILENT_DB) Hint.WAITING else Hint.UNCLEAR
            return State(hint, null, lastOctave, held(), settled)
        }
        gap = 0
        val diff = f.semis - target
        // Fold whole octaves only when the voice is near one; a voice far off
        // in between keeps its real direction, so "go up" never flips to "go down".
        val near = (diff / 12f).roundToInt()
        val octave = if (abs(diff - 12f * near) <= OCTAVE_SNAP_SEMIS) near else 0
        lastOctave = octave
        val cents = (diff - 12f * octave) * 100.0
        val hint = hintFor(cents)
        if (abs(cents) <= tolerance) run++ else run = 0
        if (!settled && run >= holdFrames) { settled = true; firstSettledOctave = octave }
        return State(hint, cents, octave, held(), settled)
    }

    private fun held() = if (settled) 1f else (run.toFloat() / holdFrames).coerceIn(0f, 1f)

    private fun hintFor(c: Double): Hint = when {
        abs(c) <= tolerance -> Hint.ON
        c < -SLIDE_CENTS -> Hint.WAY_LOW
        c < -NEAR_CENTS -> Hint.LOW
        c < 0 -> Hint.A_LITTLE_LOW
        c > SLIDE_CENTS -> Hint.WAY_HIGH
        c > NEAR_CENTS -> Hint.HIGH
        else -> Hint.A_LITTLE_HIGH
    }

    companion object {
        /** A beginner's "on": half of a half step either way. */
        const val BEGINNER_CENTS = 50.0
        /** The pitch strip's "in tune", for later stages. */
        const val STEADY_CENTS = 35.0
        /** Within a half step is "a little". */
        const val NEAR_CENTS = 100.0
        /** Further than a minor third: slide to it. */
        const val SLIDE_CENTS = 300.0
        const val HOLD_MS = 960
        const val MAX_GAP_FRAMES = 3
        /** Within this of a whole octave away, the voice is taken as aiming at the note in its own octave. */
        const val OCTAVE_SNAP_SEMIS = 3f
        /** Quieter than this (dBFS) is a pause rather than unclear singing. */
        const val SILENT_DB = -45.0

        /** The words for a hint, as the student reads them. */
        fun words(h: Hint): String = when (h) {
            Hint.WAITING -> "Sing when you're ready"
            Hint.UNCLEAR -> "Can't quite hear a note. Try an open \"ah\""
            Hint.WAY_LOW -> "Lower than the note. Slide up to it"
            Hint.LOW -> "A bit low. Go up"
            Hint.A_LITTLE_LOW -> "Just under. A touch higher"
            Hint.ON -> "Right there. Hold it"
            Hint.A_LITTLE_HIGH -> "Just over. A touch lower"
            Hint.HIGH -> "A bit high. Come down"
            Hint.WAY_HIGH -> "Higher than the note. Slide down to it"
        }
    }
}
