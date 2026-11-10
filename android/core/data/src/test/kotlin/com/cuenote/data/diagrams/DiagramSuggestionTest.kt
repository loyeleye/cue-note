package com.cuenote.data.diagrams

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagramSuggestionTest {
    private fun s(teacher: String, student: String) =
        DiagramSuggestion("s1", "fb1", "vowels-jaw", 0.8f, "cues", teacher, student)

    @Test fun `a teacher's dismissal hides it for the student`() {
        assertFalse(s("dismissed", "open").visibleToStudent)
        assertFalse(s("dismissed", "accepted").visibleToStudent)
    }

    @Test fun `the student's own dismissal hides it, and either side can keep it`() {
        assertFalse(s("open", "dismissed").visibleToStudent)
        assertTrue(s("open", "open").visibleToStudent)
        assertTrue(s("accepted", "open").accepted)
        assertTrue(s("open", "accepted").accepted)
        assertFalse(s("open", "open").accepted)
    }

    @Test fun `a cloud row with the lesson filter decodes`() {
        val row = """{"id":"s1","feedback_id":"fb1","diagram_id":"vowels-jaw","confidence":0.82,"source":"sketch",
            "teacher_status":"open","student_status":"open","feedback":{"lesson_id":"l1"}}"""
        val d = Json.decodeFromString<DiagramSuggestion>(row)
        assertEquals("sketch", d.source)
        assertEquals(0.82f, d.confidence)
    }
}
