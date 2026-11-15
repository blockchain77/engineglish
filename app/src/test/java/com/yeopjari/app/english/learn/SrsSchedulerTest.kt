package com.yeopjari.app.english.learn

import com.yeopjari.app.english.core.CardState
import com.yeopjari.app.english.core.Grade
import com.yeopjari.app.english.core.Verdict
import com.yeopjari.app.english.learn.LearnFixtures.DAY
import com.yeopjari.app.english.learn.LearnFixtures.MIN
import com.yeopjari.app.english.learn.LearnFixtures.NOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SM-2 간소판 간격 · ease · 등급 매핑 (명세 §6.1) */
class SrsSchedulerTest {
    private val s = SrsScheduler()

    @Test fun firstReviewIntervalsByGrade() {
        for ((grade, days) in listOf(Grade.HARD to 0.5, Grade.GOOD to 1.0, Grade.EASY to 2.0)) {
            val c = s.schedule(null, "biz.b1.01", grade, NOW)
            assertEquals(1, c.reps)
            assertEquals(0, c.lapses)
            assertEquals(days, c.intervalDays, 1e-9)
            assertEquals(NOW + (days * DAY).toLong(), c.dueAtMs)
            assertEquals(NOW, c.lastReviewedMs)
            assertEquals(NOW, c.introducedMs)
            assertEquals(grade, c.lastGrade)
            assertEquals("biz.b1.01", c.itemId)
        }
    }

    @Test fun secondReviewIntervalsByGrade() {
        for ((grade, days) in listOf(Grade.HARD to 2.0, Grade.GOOD to 3.0, Grade.EASY to 4.0)) {
            val first = s.schedule(null, "x", Grade.GOOD, NOW)
            val second = s.schedule(first, "x", grade, NOW + DAY)
            assertEquals(2, second.reps)
            assertEquals(days, second.intervalDays, 1e-9)
            assertEquals(NOW + DAY + (days * DAY).toLong(), second.dueAtMs)
            assertEquals(NOW, second.introducedMs) // 처음 본 시각 유지
        }
    }

    @Test fun matureIntervalsMultiplyByEaseAndFactor() {
        val card = CardState(itemId = "x", reps = 2, ease = 2.5, intervalDays = 3.0, dueAtMs = NOW, introducedMs = NOW - 4 * DAY)
        val good = s.schedule(card, "x", Grade.GOOD, NOW)
        assertEquals(2.5, good.ease, 1e-9) // GOOD: ease 그대로
        assertEquals(3.0 * 2.5 * 1.0, good.intervalDays, 1e-9)
        val easy = s.schedule(card, "x", Grade.EASY, NOW)
        assertEquals(2.6, easy.ease, 1e-9) // EASY: +0.1
        assertEquals(3.0 * 2.6 * 1.3, easy.intervalDays, 1e-9)
        val hard = s.schedule(card, "x", Grade.HARD, NOW)
        assertEquals(2.36, hard.ease, 1e-9) // HARD: −0.14
        assertEquals(3.0 * 2.36 * 0.6, hard.intervalDays, 1e-9)
        assertEquals(3, hard.reps)
    }

    @Test fun intervalIsCappedAt180DaysAndEaseAt3() {
        val card = CardState(itemId = "x", reps = 9, ease = 3.0, intervalDays = 150.0)
        val easy = s.schedule(card, "x", Grade.EASY, NOW)
        assertEquals(3.0, easy.ease, 1e-9)
        assertEquals(180.0, easy.intervalDays, 1e-9)
        assertEquals(NOW + 180 * DAY, easy.dueAtMs)
    }

    @Test fun againResetsRepsCountsLapseAndComesBackInTenMinutes() {
        val card = CardState(itemId = "x", reps = 4, lapses = 1, ease = 1.5, intervalDays = 20.0, introducedMs = NOW - 30 * DAY)
        val again = s.schedule(card, "x", Grade.AGAIN, NOW)
        assertEquals(0, again.reps)
        assertEquals(2, again.lapses)
        assertEquals(1.3, again.ease, 1e-9) // 1.5 − 0.8 → 하한 1.3
        assertEquals(NOW + 10 * MIN, again.dueAtMs)
        assertEquals(Grade.AGAIN, again.lastGrade)
        assertEquals(NOW - 30 * DAY, again.introducedMs)
        // 다시 맞히면 1회차 간격부터
        val relearn = s.schedule(again, "x", Grade.GOOD, NOW + 10 * MIN)
        assertEquals(1, relearn.reps)
        assertEquals(1.0, relearn.intervalDays, 1e-9)
    }

    @Test fun easeFormulaMatchesSpec() {
        // ease' = ease + 0.1 − (5−q)(0.08 + (5−q)·0.02)
        assertEquals(2.5 - 0.8, SrsScheduler.nextEase(2.5, Grade.AGAIN), 1e-9)
        assertEquals(2.5 - 0.14, SrsScheduler.nextEase(2.5, Grade.HARD), 1e-9)
        assertEquals(2.5, SrsScheduler.nextEase(2.5, Grade.GOOD), 1e-9)
        assertEquals(2.6, SrsScheduler.nextEase(2.5, Grade.EASY), 1e-9)
        assertEquals(1.3, SrsScheduler.nextEase(1.35, Grade.HARD), 1e-9)
    }

    @Test fun gradeForMapsVerdictAndLatency() {
        assertEquals(Grade.EASY, s.gradeFor(Verdict.CORRECT, 1_499L))
        assertEquals(Grade.GOOD, s.gradeFor(Verdict.CORRECT, 1_500L))
        assertEquals(Grade.GOOD, s.gradeFor(Verdict.CORRECT, null))
        assertEquals(Grade.HARD, s.gradeFor(Verdict.PARTIAL, 300L))
        assertEquals(Grade.AGAIN, s.gradeFor(Verdict.WRONG, 300L))
        assertEquals(Grade.AGAIN, s.gradeFor(Verdict.NO_ANSWER, null))
    }

    @Test fun dueListsOnlyDueCardsMostOverdueFirst() {
        val cards = listOf(
            CardState(itemId = "b", dueAtMs = NOW - 1 * DAY),
            CardState(itemId = "future", dueAtMs = NOW + 1),
            CardState(itemId = "a", dueAtMs = NOW - 3 * DAY),
            CardState(itemId = "now", dueAtMs = NOW),
            CardState(itemId = "c", dueAtMs = NOW - 1 * DAY),
        )
        assertEquals(listOf("a", "b", "c", "now"), s.due(cards, NOW).map { it.itemId })
        assertTrue(s.due(emptyList(), NOW).isEmpty())
    }

    @Test fun newCardStartsWithoutLastGrade() {
        assertNull(CardState(itemId = "x").lastGrade)
        val c = s.schedule(CardState(itemId = "x"), "x", Grade.GOOD, NOW) // 카드는 있지만 introducedMs 0 → 지금
        assertEquals(NOW, c.introducedMs)
    }
}
