package com.cuenote.signal

/**
 * What the soundcheck decided about the two mics: which input is the
 * student's, and how sure it is. The lesson that follows uses it instead of
 * asking again, and shows it so it can still be changed.
 *
 * The margins are how much louder each voice was on its own mic than on the
 * other one, in dB, measured once the student's mic was settled. Both steps
 * only pass at [SetupThresholds.separationDb] or more, so a low margin still
 * means the right answer, just with more bleed between the mics.
 */
data class MicVerdict(
    val studentOnRight: Boolean,
    val confidence: Confidence,
    /** The smaller of the two margins, or null when only the student step was heard. */
    val separationDb: Double?,
) {
    enum class Confidence(val label: String) {
        HIGH("Very sure"),
        MEDIUM("Fairly sure"),
        LOW("Not very sure"),
    }

    companion object {
        /** Both voices at least this far apart: very sure. */
        const val HIGH_DB = 20.0
        /** At least this far apart: fairly sure. Below it, the bleed makes it a closer call. */
        const val MEDIUM_DB = 14.0

        /**
         * [studentMarginDb]: the student step, student's mic minus teacher's.
         * [teacherMarginDb]: the teacher step, teacher's mic minus student's,
         * or null when that step wasn't heard. One step alone is never "very sure".
         */
        fun judge(studentOnRight: Boolean, studentMarginDb: Double, teacherMarginDb: Double?): MicVerdict {
            val margin = minOf(studentMarginDb, teacherMarginDb ?: studentMarginDb)
            val confidence = when {
                margin >= HIGH_DB && teacherMarginDb != null -> Confidence.HIGH
                margin >= MEDIUM_DB -> Confidence.MEDIUM
                else -> Confidence.LOW
            }
            return MicVerdict(studentOnRight, confidence, if (teacherMarginDb == null) studentMarginDb else margin)
        }

        /** How much louder [own] is than the other channel, in the levels as recorded. */
        fun margin(levels: StereoLevels, own: Channel): Double = levels[own].rmsDb - levels[own.other].rmsDb
    }
}
