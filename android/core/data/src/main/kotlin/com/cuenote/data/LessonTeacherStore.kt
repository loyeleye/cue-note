package com.cuenote.data

import android.content.Context

/**
 * Who taught a lesson: one of the student's teachers on Cue Note ([teacherId]),
 * or just a name for someone who isn't ([name]: a guest teacher, or a teacher
 * not invited yet). At most one of the two is set; neither means "not set".
 */
data class LessonTeacher(val teacherId: String? = null, val name: String? = null) {
    val isSet: Boolean get() = teacherId != null || name != null

    companion object {
        val NONE = LessonTeacher()

        /** A typed name, tidied; blank means no teacher. */
        fun named(name: String): LessonTeacher =
            name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).takeIf { it.isNotEmpty() }
                ?.let { LessonTeacher(name = it) } ?: NONE

        /** Matches the database's limit on lesson.teacher_name. */
        const val MAX_NAME = 80
    }
}

/**
 * Who taught each lesson, picked before recording ("Who's teaching today?")
 * or changed later from the lesson. Kept on the phone next to the lesson,
 * and sent with it as lesson.taught_by or lesson.teacher_name. A lesson with
 * no entry has no teacher set; a teacher can claim it from the dashboard later.
 */
class LessonTeacherStore(context: Context) {
    private val prefs = context.getSharedPreferences("lesson_teacher", Context.MODE_PRIVATE)

    fun set(lessonId: String, teacher: LessonTeacher) {
        prefs.edit().apply {
            if (teacher.teacherId == null) remove(lessonId) else putString(lessonId, teacher.teacherId)
            if (teacher.teacherId != null || teacher.name == null) remove(NAME + lessonId) else putString(NAME + lessonId, teacher.name)
        }.apply()
    }

    fun get(lessonId: String): LessonTeacher =
        LessonTeacher(prefs.getString(lessonId, null), prefs.getString(NAME + lessonId, null))

    /** The teacher picked last time, offered first next time. */
    var lastPicked: String?
        get() = prefs.getString(LAST, null)
        set(v) = prefs.edit().putString(LAST, v).apply()

    /** The last name typed for someone not on Cue Note, offered again next time. */
    var lastName: String?
        get() = prefs.getString(LAST_NAME, null)
        set(v) = prefs.edit().putString(LAST_NAME, v).apply()

    /** Lessons whose teacher changed after they went up, still to be sent. */
    fun pendingSync(): Set<String> = prefs.getStringSet(PENDING, emptySet()).orEmpty()

    fun markPending(lessonId: String, pending: Boolean) {
        val now = pendingSync().toMutableSet().apply { if (pending) add(lessonId) else remove(lessonId) }
        prefs.edit().putStringSet(PENDING, now).apply()
    }

    private companion object {
        const val LAST = "__last_picked"
        const val LAST_NAME = "__last_name"
        const val PENDING = "__pending_sync"
        const val NAME = "name:"
    }
}
