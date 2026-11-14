package com.cuenote.data

import com.cuenote.data.LocalOnly.Change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalOnlyTest {
    private val ben = LessonTeacher.named("Ben", localOnly = true)
    private val guest = LessonTeacher.named("Ben")
    private val onCueNote = LessonTeacher(teacherId = "t-1")

    @Test fun onlyANamedTeacherCanKeepALessonOnThePhone() {
        assertTrue(ben.localOnly)
        assertEquals("Ben", ben.name)
        // A blank name is no teacher, and so not local-only either.
        assertEquals(LessonTeacher.NONE, LessonTeacher.named("   ", localOnly = true))
        assertFalse(LessonTeacher.named("   ", localOnly = true).localOnly)
    }

    @Test fun becomingLocalOnlyDeletesTheCopyOnline() {
        assertEquals(Change.DELETE_ONLINE, LocalOnly.change(onCueNote, ben))
        assertEquals(Change.DELETE_ONLINE, LocalOnly.change(guest, ben))
        assertEquals(Change.DELETE_ONLINE, LocalOnly.change(LessonTeacher.NONE, ben))
    }

    @Test fun aLocalOnlyLessonNeverSendsItsTeacher() {
        // Renaming a local-only teacher stays on the phone.
        assertEquals(Change.NONE, LocalOnly.change(ben, LessonTeacher.named("Benjamin", localOnly = true)))
    }

    @Test fun leavingLocalOnlySendsNothingByItself() {
        assertEquals(Change.NOW_SHAREABLE, LocalOnly.change(ben, onCueNote))
        assertEquals(Change.NOW_SHAREABLE, LocalOnly.change(ben, guest))
        assertEquals(Change.NOW_SHAREABLE, LocalOnly.change(ben, LessonTeacher.NONE))
    }

    @Test fun ordinaryChangesGoToTheCloudAsBefore() {
        assertEquals(Change.SEND, LocalOnly.change(guest, onCueNote))
        assertEquals(Change.SEND, LocalOnly.change(LessonTeacher.NONE, guest))
    }

    @Test fun savedNamesAreTidiedAndKeptOnce() {
        assertEquals(listOf("Ben Alvarez", "Mia"), LocalOnly.tidyList(listOf("  Ben   Alvarez ", "ben alvarez", "", "Mia", "MIA")))
        assertTrue(LocalOnly.sameName("ben", " Ben "))
        // A name can't smuggle in the list separator.
        assertEquals(listOf("Ben Alvarez"), LocalOnly.tidyList(listOf("Ben\nAlvarez")))
    }

    @Test fun localConsentSaysNothingIsSent() {
        val text = ConsentStore.localText("Sam")
        assertTrue(text.contains("stays on Sam's phone"))
        assertTrue(text.contains("doesn't send any of it to its servers or to an AI"))
    }

    @Test fun lessonsGroupByTeacherNameOnce() {
        assertEquals(listOf("Ben", "Marigold", LessonGroups.NO_TEACHER), LessonGroups.of(listOf("Ben", null, "Marigold", " ben ", "")))
        assertEquals(listOf("Ben"), LessonGroups.of(listOf("Ben", "BEN")))
    }
}
