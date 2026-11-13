package com.cuenote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanSettingsTest {
    @Test fun twoTeachersTakeTheStricterOfEach() {
        val s = PlanSettings.of(
            listOf(
                TeacherPlanSetting("tia", pinnedFeedbackId = "hill", maxMinutes = 30, restUntil = "2026-11-14", coolDown = " "),
                TeacherPlanSetting("ola", pinnedFeedbackId = "ribs", maxMinutes = 20, restUntil = "2026-11-16", coolDown = "Slides down"),
            ),
        )
        assertEquals(setOf("hill", "ribs"), s.pinned)
        assertEquals(20, s.maxMinutes)
        assertEquals("2026-11-16", s.restUntil)
        assertEquals("Slides down", s.coolDown)
        assertNull(s.gentleStart)
    }

    @Test fun restIsThroughTheDaySet() {
        val s = PlanSettings(restUntil = "2026-11-16")
        assertTrue(s.resting("2026-11-16"))
        assertFalse(s.resting("2026-11-17"))
        assertFalse(PlanSettings().resting("2026-11-13"))
    }
}
