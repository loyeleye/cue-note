package com.cuenote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopicTest {
    @Test fun readsTheCategoriesCardsHaveToday() {
        assertEquals(Topic.REGISTERS, Topic.of("registration"))
        assertEquals(Topic.REGISTERS, Topic.of("Passaggio"))
        assertEquals(Topic.BREATH, Topic.of("breath support"))
        assertEquals(Topic.RESONANCE, Topic.of("resonance"))
        assertEquals(Topic.VOWELS, Topic.of("vowel shape"))
        assertEquals(Topic.POSTURE, Topic.of("posture"))
        assertEquals(Topic.MUSICALITY, Topic.of("phrasing"))
    }

    @Test fun everyCategoryExtractCuesWrites() {
        // supabase/functions/extract-cues/cues.ts CATEGORIES
        val expected = mapOf(
            "breath" to Topic.BREATH, "resonance" to Topic.RESONANCE, "vowel" to Topic.VOWELS,
            "registration" to Topic.REGISTERS, "phrasing" to Topic.MUSICALITY, "pitch" to Topic.MUSICALITY,
            "diction" to Topic.DICTION, "posture" to Topic.POSTURE,
            "warm-up" to null, "encouragement" to null, "chat" to null,
        )
        for ((c, t) in expected) assertEquals(c, t, Topic.of(c))
    }

    @Test fun readsItsOwnNamesAndLabels() {
        assertEquals(Topic.DICTION, Topic.of("DICTION"))
        assertEquals(Topic.ONSET, Topic.of("Onset and phonation"))
    }

    @Test fun chatAndUnknownHaveNoTopic() {
        assertNull(Topic.of("chat"))
        assertNull(Topic.of(null))
        assertNull(Topic.of("  "))
        assertNull(Topic.of("general"))
    }
}
