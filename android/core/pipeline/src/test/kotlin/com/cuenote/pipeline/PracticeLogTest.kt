package com.cuenote.pipeline

import com.cuenote.pipeline.practice.Contour
import com.cuenote.pipeline.practice.LoggedSession
import com.cuenote.pipeline.practice.LoggedTake
import com.cuenote.pipeline.practice.NoteResult
import com.cuenote.pipeline.practice.OfferTake
import com.cuenote.pipeline.practice.PracticeLog
import com.cuenote.pipeline.practice.Questions
import com.cuenote.pipeline.practice.SessionItem
import com.cuenote.pipeline.practice.SharedPitch
import com.cuenote.pipeline.practice.TakePitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeLogTest {
    private val min = 60_000L
    private val s1 = LoggedSession("s1", 0, 18 * min)
    private val s2 = LoggedSession("s2", 1000 * min, 1000 * min + 8_000)
    private val takes = listOf(
        LoggedTake("lift", "s1", 0, 8000),
        LoggedTake("breathe", "s1", 5 * min, 9000),
        LoggedTake("lift", "s1", 10 * min, 7000),
        LoggedTake("lift", "s2", 1000 * min, 8000),
    )

    @Test fun `a sitting is summed up by its notes, most practiced first`() {
        val s = PracticeLog.summarize(listOf(s1, s2), takes)
        assertEquals(2, s.size)
        assertEquals(18, s[0].minutes)
        assertEquals(listOf(SessionItem("lift", 2), SessionItem("breathe", 1)), s[0].items)
        assertEquals(1, s[1].minutes)   // an eight-second sitting still counts as a minute
    }

    @Test fun `a sitting whose takes were all deleted isn't logged`() {
        assertEquals(listOf("s1"), PracticeLog.summarize(listOf(s1, s2), takes.filter { it.sessionId == "s1" }).map { it.id })
    }

    @Test fun `the week counts sittings, minutes, notes and takes since the last lesson`() {
        val all = PracticeLog.week(listOf(s1, s2), takes, since = 0)
        assertEquals(2, all.sittings); assertEquals(19, all.minutes); assertEquals(2, all.notes); assertEquals(4, all.takes)
        val recent = PracticeLog.week(listOf(s1, s2), takes, since = 500 * min)
        assertEquals(1, recent.sittings); assertEquals(1, recent.notes)
        assertTrue(PracticeLog.week(listOf(s1), emptyList(), 0).empty)
    }

    @Test fun `up to three takes wait for the teacher at a time`() {
        assertTrue(PracticeLog.canShare(2))
        assertFalse(PracticeLog.canShare(3))
    }

    @Test fun `shared pitch keeps the notes, not the pitch line`() {
        val p = TakePitch(
            Contour(List(50) { 60f }, List(50) { 0.9f }),
            listOf(
                NoteResult(0, 10, 60.0371f, 60f, NoteResult.Verdict.IN_TUNE),
                NoteResult(12, 20, 61.4f, null, NoteResult.Verdict.UNCLEAR),
            ),
        )
        val s = SharedPitch.of(p)
        assertEquals(60.04f, s[0].sung, 1e-4f)
        assertEquals("IN_TUNE", s[0].verdict)
        assertNull(s[1].target)
    }

    @Test fun `questions are trimmed, and bars must be numbers`() {
        assertEquals("Bar 12 still cracks", Questions.clean("  Bar 12\n still   cracks "))
        assertNull(Questions.clean("   "))
        assertEquals(12, Questions.bar(" 12 "))
        assertNull(Questions.bar("twelve"))
        assertNull(Questions.bar("0"))
    }

    // ------------------------------------------------ the pre-lesson offer
    private fun t(id: String, card: String, at: Long, shared: Boolean = false) = OfferTake(id, card, at, shared)

    @Test fun `offers the latest take on each practice-this note since the last lesson`() {
        val takes = listOf(t("a1", "lift", 10), t("a2", "lift", 30), t("b1", "breathe", 20), t("old", "vowel", 1), t("x", "free", 40))
        val got = PracticeLog.toOffer(setOf("lift", "breathe", "vowel"), takes, waiting = 0, since = 5)
        assertEquals(listOf("a2", "b1"), got.map { it.id })   // newest first; not the old one, not a note the teacher didn't mark
    }

    @Test fun `a note already shared since the lesson isn't offered again`() {
        val takes = listOf(t("a1", "lift", 10, shared = true), t("a2", "lift", 30), t("b1", "breathe", 20))
        assertEquals(listOf("b1"), PracticeLog.toOffer(setOf("lift", "breathe"), takes, 1, 0).map { it.id })
    }

    @Test fun `the offer fits the free places and leaves out skipped takes`() {
        val takes = listOf(t("a", "n1", 1), t("b", "n2", 2), t("c", "n3", 3), t("d", "n4", 4))
        val all = setOf("n1", "n2", "n3", "n4")
        assertEquals(listOf("d", "c", "b"), PracticeLog.toOffer(all, takes, 0, 0).map { it.id })
        assertEquals(listOf("d"), PracticeLog.toOffer(all, takes, 2, 0).map { it.id })
        assertEquals(emptyList<String>(), PracticeLog.toOffer(all, takes, 3, 0).map { it.id })
        assertEquals(listOf("c", "b", "a"), PracticeLog.toOffer(all, takes, 0, 0, skipped = setOf("d")).map { it.id })
    }
}
