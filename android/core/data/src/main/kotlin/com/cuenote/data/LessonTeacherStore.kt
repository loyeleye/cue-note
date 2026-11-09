package com.cuenote.data

import android.content.Context

/**
 * Who taught each lesson, picked before recording ("Who's teaching today?").
 * Kept on the phone next to the lesson until lessons are uploaded (M3), which
 * sends it as lesson.taught_by. A lesson with no entry has no teacher set; a
 * teacher can claim it from the dashboard later.
 */
class LessonTeacherStore(context: Context) {
    private val prefs = context.getSharedPreferences("lesson_teacher", Context.MODE_PRIVATE)

    fun set(lessonId: String, teacherId: String?) {
        prefs.edit().apply { if (teacherId == null) remove(lessonId) else putString(lessonId, teacherId) }.apply()
    }

    fun get(lessonId: String): String? = prefs.getString(lessonId, null)

    /** The teacher picked last time, offered first next time. */
    var lastPicked: String?
        get() = prefs.getString(LAST, null)
        set(v) = prefs.edit().putString(LAST, v).apply()

    private companion object {
        const val LAST = "__last_picked"
    }
}
