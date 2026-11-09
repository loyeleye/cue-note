package com.cuenote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ShareRulesTest {
    private val invited = listOf(InvitedTeacher("s1", "Marigold@Example.com", active = true, teacherName = "Marigold Stewart"))

    @Test fun acceptsANewTeacher() {
        assertNull(inviteProblem(" second@example.com ", "me@example.com", invited))
    }

    @Test fun rejectsBadOwnAndRepeatedEmails() {
        assertNotNull(inviteProblem("not-an-email", "me@example.com", invited))
        assertNotNull(inviteProblem("ME@example.com", "me@example.com", invited))
        assertNotNull(inviteProblem("marigold@example.com", "me@example.com", invited))
    }

    @Test fun listsActiveFirstAndNamesOnlyAcceptedTeachers() {
        val rows = listOf(
            ShareRow("p", "new@example.com", null, "pending", "2026-11-09T10:00:00Z"),
            ShareRow("r", "old@example.com", "t2", "revoked", "2026-11-01T10:00:00Z"),
            ShareRow("a", "marigold@example.com", "t1", "active", "2026-11-02T10:00:00Z"),
        )
        val list = toInvitedTeachers(rows, mapOf("t1" to "Marigold Stewart"))
        assertEquals(listOf("a", "p"), list.map { it.shareId })
        assertEquals("Marigold Stewart", list[0].title)
        assertEquals("new@example.com", list[1].title)
    }

    @Test fun carriesTheTeacherAndTheStudentsLimit() {
        val rows = listOf(ShareRow("a", "m@example.com", "t1", "active", "2026-11-02T10:00:00Z", restrictedToTaught = true))
        val t = toInvitedTeachers(rows, emptyMap()).single()
        assertEquals("t1", t.teacherId)
        assertEquals(true, t.restrictedToTaught)
    }

    @Test fun studentsSeeTheAliasAndTheRealName() {
        assertEquals("Tiger (Marigold Stewart)", studentFacingName("Tiger", " Marigold Stewart "))
        assertEquals("Tiger", studentFacingName("Tiger", ""))
        assertEquals("Marigold Stewart", studentFacingName(null, "Marigold Stewart"))
        assertNull(studentFacingName(" ", null))
    }
}
