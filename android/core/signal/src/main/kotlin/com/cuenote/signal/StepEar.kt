package com.cuenote.signal

/**
 * Listens along with one soundcheck step, 100 ms block by block, and says
 * when it has heard enough to judge the step, so the step can end before
 * its countdown runs out instead of making everyone wait.
 */
class StepEar(private val need: Need, private val activeDb: Double = ACTIVE_DB) {

    /** What a step needs to hear before it can end early. */
    data class Need(
        /** Sound on both mics at once (piano and voice together), or on either. */
        val both: Boolean = false,
        /** How much sound, in total. */
        val soundMs: Long,
        /** Wait for a short pause first, so a spoken or sung line isn't cut off mid-word. */
        val waitForPause: Boolean = false,
    )

    var heardMs = 0L
        private set
    private var quietRunMs = 0L

    fun add(levels: StereoLevels, ms: Long = 100) {
        val s = levels.student.rmsDb >= activeDb
        val t = levels.teacher.rmsDb >= activeDb
        if (if (need.both) s && t else s || t) {
            heardMs += ms
            quietRunMs = 0
        } else {
            quietRunMs += ms
        }
    }

    /**
     * True once there's enough sound to judge: [Need.soundMs] of it and, for
     * a line, either a pause after it or twice as much sound (someone who
     * just keeps going).
     */
    fun enough(): Boolean {
        if (heardMs < need.soundMs) return false
        if (!need.waitForPause) return true
        return quietRunMs >= PAUSE_MS || heardMs >= need.soundMs * 2
    }

    companion object {
        /** Louder than this on a lav mic is someone singing, speaking or playing, not the room. */
        const val ACTIVE_DB = -42.0
        const val PAUSE_MS = 500L
    }
}
