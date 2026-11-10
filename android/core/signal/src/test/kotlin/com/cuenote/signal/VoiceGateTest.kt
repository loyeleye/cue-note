package com.cuenote.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceGateTest {
    @Test fun turnsOnAboveTheOnLevel() {
        val g = VoiceGate()
        assertFalse(g.update(-58.0, 0))
        assertTrue(g.update(-40.0, 1000))
    }

    @Test fun staysOnBetweenTheTwoLevels() {
        val g = VoiceGate()
        g.update(-40.0, 0)
        assertTrue(g.update(-58.0, 1000)) // below "on" but above "off"
        assertFalse(g.update(-65.0, 2000))
    }

    @Test fun holdsAfterSwitchingSoTheLabelDoesNotFlicker() {
        val g = VoiceGate(holdMs = 400)
        g.update(-40.0, 1000)
        assertTrue(g.update(-80.0, 1200)) // a consonant gap inside the hold
        assertFalse(g.update(-80.0, 1500))
    }

    @Test fun silenceIsQuiet() {
        assertFalse(VoiceGate().update(null, 0))
    }

    @Test fun followerRisesFastAndFallsSlowly() {
        val f = LevelFollower(attackMs = 50.0, releaseMs = 300.0)
        val up = f.step(1.0, 50.0)
        assertTrue("rises most of the way in one attack time", up > 0.6)
        f.step(1.0, 500.0)
        val down = f.step(0.0, 50.0)
        assertTrue("falls only a little in the same time", down > 0.8)
    }

    @Test fun mapsDbOntoTheBar() {
        assertEquals(0.0, LevelFollower.fraction(null), 1e-9)
        assertEquals(0.5, LevelFollower.fraction(-30.0), 1e-9)
        assertEquals(1.0, LevelFollower.fraction(3.0), 1e-9)
    }
}
