package com.cuenote.data

import android.content.Context

/**
 * Who taught a lesson: one of the student's teachers on Cue Note ([teacherId]),
 * or just a name for someone who isn't ([name]: a guest teacher, or a teacher
 * not invited yet). At most one of the two is set; neither means "not set".
 *
 * [localOnly]: the teacher asked that the lesson stays on this phone. Nothing
 * from it is sent to Cue Note's servers or to an AI (see [LocalOnly]). Only a
 * named teacher can ask for it, since a teacher on Cue Note has a dashboard.
 */
data class LessonTeacher(val teacherId: String? = null, val name: String? = null, val localOnly: Boolean = false) {
    val isSet: Boolean get() = teacherId != null || name != null

    companion object {
        val NONE = LessonTeacher()

        /** A typed name, tidied; blank means no teacher (and so not local-only). */
        fun named(name: String, localOnly: Boolean = false): LessonTeacher =
            tidy(name)?.let { LessonTeacher(name = it, localOnly = localOnly) } ?: NONE

        fun tidy(name: String): String? =
            name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).takeIf { it.isNotEmpty() }

        /** Matches the database's limit on lesson.teacher_name. */
        const val MAX_NAME = 80
    }
}

/**
 * Who taught each lesson, picked before recording ("Who's teaching today?")
 * or changed later from the lesson. Kept on the phone next to the lesson,
 * and sent with it as lesson.taught_by or lesson.teacher_name, unless the
 * lesson is local-only: then neither the lesson nor the name goes anywhere.
 * A lesson with no entry has no teacher set; a teacher can claim it from the
 * dashboard later.
 */
class LessonTeacherStore(context: Context) {
    private val prefs = context.getSharedPreferences("lesson_teacher", Context.MODE_PRIVATE)

    fun set(lessonId: String, teacher: LessonTeacher) {
        prefs.edit().apply {
            if (teacher.teacherId == null) remove(lessonId) else putString(lessonId, teacher.teacherId)
            if (teacher.teacherId != null || teacher.name == null) remove(NAME + lessonId) else putString(NAME + lessonId, teacher.name)
            if (teacher.localOnly && teacher.teacherId == null && teacher.name != null) putBoolean(LOCAL + lessonId, true)
            else remove(LOCAL + lessonId)
        }.commit() // synchronous: an upload starting right after must see it
    }

    fun get(lessonId: String): LessonTeacher =
        LessonTeacher(prefs.getString(lessonId, null), prefs.getString(NAME + lessonId, null), isLocalOnly(lessonId))

    /** The lesson stays on this phone: nothing from it may go to the cloud or an AI. */
    fun isLocalOnly(lessonId: String): Boolean = prefs.getBoolean(LOCAL + lessonId, false)

    /** Forgets a deleted lesson. */
    fun forget(lessonId: String) {
        prefs.edit().remove(lessonId).remove(NAME + lessonId).remove(LOCAL + lessonId).apply()
        markPending(lessonId, false)
    }

    /** The teacher picked last time, offered first next time. */
    var lastPicked: String?
        get() = prefs.getString(LAST, null)
        set(v) = prefs.edit().putString(LAST, v).apply()

    /** The last name typed for someone not on Cue Note, offered again next time. */
    var lastName: String?
        get() = prefs.getString(LAST_NAME, null)
        set(v) = prefs.edit().putString(LAST_NAME, v).apply()

    /**
     * Teachers who keep their lessons on this phone, by name: added under
     * "Your teachers", or remembered from a lesson. Kept on the phone only;
     * they get no invite and no account.
     */
    var localOnlyTeachers: List<String>
        get() = prefs.getString(LOCAL_TEACHERS, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        set(v) = prefs.edit().putString(LOCAL_TEACHERS, LocalOnly.tidyList(v).joinToString("\n")).apply()

    fun addLocalOnlyTeacher(name: String) { localOnlyTeachers = localOnlyTeachers + name }

    fun removeLocalOnlyTeacher(name: String) {
        localOnlyTeachers = localOnlyTeachers.filterNot { LocalOnly.sameName(it, name) }
    }

    /** True when [name] is one of the saved local-only teachers. */
    fun isLocalOnlyTeacher(name: String?): Boolean = name != null && localOnlyTeachers.any { LocalOnly.sameName(it, name) }

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
        const val LOCAL_TEACHERS = "__local_only_teachers"
        const val NAME = "name:"
        const val LOCAL = "local:"
    }
}

/**
 * The rules for a local-only lesson, kept free of Android so they can be
 * tested. A teacher can ask that their lessons stay on the student's phone:
 * they're recorded, turned into text and sorted into tries and the phone's
 * best guess on the phone as usual, but the lesson row, takes, clips, words,
 * sketches, practice takes and questions about it never go to Cue Note's
 * servers, and nothing about it goes to an AI.
 */
object LocalOnly {
    /** What a change of teacher means for the copy online. */
    enum class Change {
        /** Nothing to do online. */
        NONE,
        /** The lesson just became local-only: delete anything of it online. */
        DELETE_ONLINE,
        /** It stopped being local-only. Nothing goes up until the student taps Make my notes. */
        NOW_SHAREABLE,
        /** An ordinary change of teacher, sent to the cloud as before. */
        SEND,
    }

    fun change(before: LessonTeacher, after: LessonTeacher): Change = when {
        after.localOnly && !before.localOnly -> Change.DELETE_ONLINE
        after.localOnly -> Change.NONE   // still local-only: the new name stays on the phone too
        before.localOnly -> Change.NOW_SHAREABLE
        else -> Change.SEND
    }

    fun sameName(a: String, b: String): Boolean =
        LessonTeacher.tidy(a)?.lowercase() == LessonTeacher.tidy(b)?.lowercase()

    /** Tidied, without blanks or repeats (first spelling wins). */
    fun tidyList(names: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (n in names) {
            val t = LessonTeacher.tidy(n.replace('\n', ' ')) ?: continue
            if (out.none { sameName(it, t) }) out += t
        }
        return out
    }

    /** Thrown by anything that would send a local-only lesson off the phone. */
    class Refused(lessonId: String) : IllegalStateException("Lesson $lessonId is kept on this phone; it isn't sent anywhere")
}
