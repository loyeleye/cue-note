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
        const val VERSION = 2
        val TEXT = """
            Cue Note records this lesson on two mics, one for each of you.

            • The full recording stays on the student's phone, and the phone turns it into text itself.
            • After the lesson, short clips of singing and feedback, photos of sketches and the text are saved online for the teacher's dashboard.
            • From the text and sketch photos, the app drafts cue cards with the help of AI. Your lessons aren't used to train it.
            • The teacher can fix any card, and their version always wins.
            • Nothing records unless you both say yes, before every lesson.
        """.trimIndent()

        /** The consent text for a lesson the teacher keeps on the phone. */
        fun localText(student: String) = """
            This lesson stays on $student's phone.

            • The phone records it on two mics, one for each of you, and turns it into text and notes itself.
            • Cue Note doesn't send any of it to its servers or to an AI: not the recording, clips, words or sketch photos.
            • It doesn't show on any teacher's dashboard, and no other teacher sees it.
            • If $student exports a copy, where that copy goes is up to $student.
            • If this phone is lost, the lesson goes with it.
            • Nothing records unless you both say yes, before every lesson.
        """.trimIndent()
    }
}
