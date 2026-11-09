package com.cuenote.data

import android.content.Context

/**
 * California requires everyone recorded to consent. Both people read and
 * accept the full consent text once at first setup; after that each lesson
 * is re-confirmed with one tap, and that time is stored on the lesson.
 */
class ConsentStore(context: Context) {
    private val prefs = context.getSharedPreferences("consent", Context.MODE_PRIVATE)

    data class FirstSetup(val studentName: String, val teacherName: String, val acceptedAt: Long)

    fun firstSetup(): FirstSetup? {
        val at = prefs.getLong("accepted_at", 0)
        if (at == 0L) return null
        return FirstSetup(prefs.getString("student", "")!!, prefs.getString("teacher", "")!!, at)
    }

    fun saveFirstSetup(studentName: String, teacherName: String) {
        prefs.edit()
            .putString("student", studentName)
            .putString("teacher", teacherName)
            .putLong("accepted_at", System.currentTimeMillis())
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()

    companion object {
        const val VERSION = 1
        val TEXT = """
            Cue Note records this lesson on two microphones: one for the student and one for the teacher.

            • The full recording stays on the student's phone. Only short clips of singing and feedback, photos of sketches, and text are uploaded, and only after the lesson.
            • Text and sketch photos may be sent to an AI service to turn feedback into cards. Audio is never sent to it.
            • The teacher can see, correct and delete anything from her lessons, and either person can delete clips at any time.
            • Nothing is recorded unless both of you agree, before every lesson.
        """.trimIndent()
    }
}
