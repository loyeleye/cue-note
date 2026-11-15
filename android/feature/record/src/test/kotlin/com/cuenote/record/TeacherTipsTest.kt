package com.cuenote.record

import java.io.File
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TeacherTipsTest {
    private val bundled: Set<String> by lazy {
        // Unit tests run from the module's folder; the diagrams ship in :feature:lesson.
        val index = File("../lesson/src/main/assets/diagrams/index.json").readText()
        Regex("\"id\":\\s*\"([^\"]+)\"").findAll(index).map { it.groupValues[1] }.toSet()
    }

    @Test fun allTenTipsFromTheCardInItsOrder() {
        assertEquals(10, TEACHER_TIPS.size)
        assertEquals("Jaw back", TEACHER_TIPS.first().tip)
        assertEquals("Round mouth", TEACHER_TIPS.last().tip)
    }

    @Test fun everyTipHasANoteAndADiagramThatShips() {
        for (t in TEACHER_TIPS) {
            assertTrue(t.tip, t.what.isNotBlank() && t.tryIt.isNotBlank() && t.diagrams.isNotEmpty())
            for (id in t.diagrams) {
                assertTrue("${t.tip}: $id is not in the diagram library", id in bundled)
                assertTrue("${t.tip}: $id.png is missing", File("../lesson/src/main/assets/diagrams/$id.png").exists())
            }
        }
    }

    @Test fun eachDayHasATipAndTheyRotate() {
        val day = Calendar.getInstance().apply { set(2026, Calendar.JANUARY, 1) }
        val seen = (0 until 10).map { tipFor(day).also { day.add(Calendar.DAY_OF_YEAR, 1) } }.toSet()
        assertEquals(10, seen.size)
    }
}
