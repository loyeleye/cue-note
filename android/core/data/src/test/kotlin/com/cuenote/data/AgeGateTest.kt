package com.cuenote.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AgeGateTest {
    private val today = LocalDate.of(2026, 11, 10)

    @Test fun thirteenthBirthdayToday_isOldEnough() =
        assertTrue(AgeGate.isOldEnough(LocalDate.of(2013, 11, 10), today))

    @Test fun thirteenthBirthdayTomorrow_isBlocked() =
        assertFalse(AgeGate.isOldEnough(LocalDate.of(2013, 11, 11), today))

    @Test fun adult_isOldEnough() =
        assertTrue(AgeGate.isOldEnough(LocalDate.of(1990, 3, 4), today))

    @Test fun young_child_isBlocked() =
        assertFalse(AgeGate.isOldEnough(LocalDate.of(2019, 1, 1), today))

    @Test fun leapDayBirthday_turnsThirteenOnFeb28() {
        // Born 29 Feb 2012; 2025 has no 29 Feb, so java.time counts 28 Feb as the birthday.
        assertFalse(AgeGate.isOldEnough(LocalDate.of(2012, 2, 29), LocalDate.of(2025, 2, 27)))
        assertTrue(AgeGate.isOldEnough(LocalDate.of(2012, 2, 29), LocalDate.of(2025, 2, 28)))
    }

    @Test fun futureDate_isNotOldEnough() =
        assertFalse(AgeGate.isOldEnough(LocalDate.of(2030, 1, 1), today))
}
