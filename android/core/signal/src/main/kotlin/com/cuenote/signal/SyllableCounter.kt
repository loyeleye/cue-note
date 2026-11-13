package com.cuenote.signal

import kotlin.math.max

/**
 * Counts the syllables of "Do · Re · Mi" as they're sung or spoken, from
 * the 100 ms level blocks, so the sound check can light each one as it's
 * heard. It counts onsets (a rise in level after a dip, or a fresh start
 * after quiet) and never judges pitch: any note, or speaking, counts.
 *
 * Singing it as one smooth phrase can hide the dips between syllables, so
 * [LEGATO_MS] of voice since the first onset fills all three.
 *
 * [expected] is whose mic should carry it; null when that isn't settled yet
 * (the student step, before the check knows which input is the student's),
 * so whichever mic is louder counts. With an expected mic, sound that is
 * clearly on the other mic lights nothing and sets [wrongMic].
 */
class SyllableCounter(
    private val expected: Channel?,
    private val activeDb: Double = StepEar.ACTIVE_DB,
) {
    var heard = 0
        private set
    /** The other person's mic is the one hearing it. Cleared once the right mic is heard. */
    var wrongMic = false
        private set

    private var prev1 = SILENT
    private var prev2 = SILENT
    private var sinceOnsetMs = Long.MAX_VALUE / 2
    private var voicedMs = 0L
    private var otherVoicedMs = 0L

    /** Feeds one block; returns true when it lit a new syllable. */
    fun add(levels: StereoLevels, ms: Long = 100): Boolean {
        val own: Double
        val other: Double
        if (expected == null) {
            own = max(levels.student.rmsDb, levels.teacher.rmsDb)
            other = SILENT
        } else {
            own = levels[expected].rmsDb
            other = levels[expected.other].rmsDb
        }
        sinceOnsetMs += ms
        val before = heard
        if (expected != null && other >= activeDb && other - own >= OTHER_MIC_DB) {
            // The wrong person, or the right person on the wrong mic.
            otherVoicedMs += ms
            if (otherVoicedMs >= WRONG_MIC_MS && heard == 0) wrongMic = true
        } else if (own >= activeDb) {
            val dip = minOf(prev1, prev2)
            val fresh = prev1 < activeDb || own - dip >= RISE_DB
            if (fresh && sinceOnsetMs >= REFRACTORY_MS && heard < SYLLABLES) {
                heard++
                sinceOnsetMs = 0
                wrongMic = false
            }
            if (heard > 0) voicedMs += ms
            if (voicedMs >= LEGATO_MS) heard = SYLLABLES
        }
        prev2 = prev1
        prev1 = own
        return heard > before
    }

    val done get() = heard >= SYLLABLES

    companion object {
        const val SYLLABLES = 3
        /** A rise this big over the last two blocks starts a new syllable. */
        const val RISE_DB = 4.0
        /** Syllables come no closer together than this. */
        const val REFRACTORY_MS = 250L
        /** This much voice after the first syllable lights the rest (a smooth, legato phrase). */
        const val LEGATO_MS = 1_500L
        /** Louder on the other mic by this much is the other mic's sound. */
        const val OTHER_MIC_DB = 6.0
        const val WRONG_MIC_MS = 500L
        private const val SILENT = -120.0
    }
}
