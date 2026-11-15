package com.cartutor.engineglish.english.learn

import com.cartutor.engineglish.english.core.CommuteSlot
import com.cartutor.engineglish.english.core.DailyGoal
import com.cartutor.engineglish.english.core.LearnerSettings
import com.cartutor.engineglish.english.core.LearnerState
import com.cartutor.engineglish.english.core.Level
import com.cartutor.engineglish.english.core.PlanRequest
import com.cartutor.engineglish.english.core.ProgressEvent
import com.cartutor.engineglish.english.core.PronIssueType
import com.cartutor.engineglish.english.core.SlotMode
import com.cartutor.engineglish.english.core.Track
import com.cartutor.engineglish.english.core.UnitKey
import com.cartutor.engineglish.english.core.Verdict
import com.cartutor.engineglish.english.learn.LearnFixtures.HOUR
import com.cartutor.engineglish.english.learn.LearnFixtures.NOW
import com.cartutor.engineglish.english.learn.LearnFixtures.TODAY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PT 데모용 가상 학습 기록 — 시연 흐름의 숫자가 실제 엔진으로 나오는지 */
class DemoLearnerSeedTest {
    private val content = LearnFixtures.content()
    private val scheduler = SrsScheduler()
    private val progression = UnitProgression(scheduler)
    private val planner = CommuteSessionPlanner(scheduler, progression)

    private fun seeded(level: Level = Level.INTERMEDIATE) = DemoLearnerSeed.seed(
        LearnerState(profile = LearnFixtures.profile(level = level), settings = LearnerSettings(hudHint = false)),
        content, NOW,
    )

    @Test fun seededLearnerGivesTheScriptedMorningAndEveningPlans() {
        val s = seeded()
        val morning = planner.plan(PlanRequest(s, CommuteSlot.MORNING, SlotMode.NEW_EXPRESSIONS, Track.BUSINESS, Level.INTERMEDIATE, 1920, false, NOW), content)
        assertEquals("출근길 32분 → 새 표현 6개 + 복습 10개", morning.titleKo)
        val evening = planner.plan(PlanRequest(s, CommuteSlot.EVENING, SlotMode.REVIEW, Track.BUSINESS, Level.INTERMEDIATE, 1920, false, NOW + 10 * HOUR), content)
        assertEquals("퇴근길 32분 → 복습 18개 + 새 표현 2개", evening.titleKo)
    }

    @Test fun keepsProfileAndSettingsAndFillsTheReport() {
        val s = seeded()
        assertEquals(Track.BUSINESS, s.profile!!.track)
        assertEquals(Level.INTERMEDIATE, s.profile!!.level)
        assertTrue(s.profile!!.placementDone)
        assertFalse(s.settings.hudHint)
        val snap = progression.snapshot(s, content, NOW)
        assertEquals(5, snap.streakDays)
        assertTrue(snap.dueReviews >= 10)
        assertEquals(5, snap.week.count { it.answered > 0 }) // 최근 7일 창: 어제까지 5일 연속
        assertEquals(PronIssueType.R_L, snap.pronTop.first().type)
        val f = snap.fluency!!
        assertTrue(f.latencyTrendMs < 0) // 지난주보다 빨라짐
        assertTrue(snap.xp > 0)
        assertEquals(6, s.sessions.size)
        assertEquals(3, s.saved.size)
        assertTrue(s.mistakes.none { it.day == TODAY }) // '오늘 틀린 표현'은 시연 중에 생긴다
        assertTrue(DemoLearnerSeed.isSeeded(s))
        assertFalse(DemoLearnerSeed.isSeeded(LearnerState()))
    }

    @Test fun firstAnswerTodayExtendsTheStreakToSix() {
        val s = seeded()
        val item = content.unitItems(UnitKey(Track.BUSINESS, Level.INTERMEDIATE, 1))[5]
        val u = progression.record(s, LearnFixtures.outcome(item, Verdict.CORRECT), content, NOW)
        assertTrue(u.events.contains(ProgressEvent.StreakExtended(6)))
    }

    @Test fun beginnerAndMissingProfileStillGetEnoughReviews() {
        val b = seeded(Level.BEGINNER)
        assertEquals(14, b.cards.size)
        val plan = planner.plan(PlanRequest(b, CommuteSlot.MORNING, SlotMode.NEW_EXPRESSIONS, Track.BUSINESS, Level.BEGINNER, 1920, false, NOW), content)
        assertEquals("출근길 32분 → 새 표현 6개 + 복습 10개", plan.titleKo)

        val none = DemoLearnerSeed.seed(LearnerState(), content, NOW)
        assertNotNull(none.profile)
        assertEquals(DailyGoal.STEADY, none.profile!!.goal)
        assertEquals(Level.INTERMEDIATE, none.profile!!.level)
    }

    @Test fun seededStateSurvivesTheCodec() {
        val s = seeded()
        assertEquals(s, LearnerStateCodec.decode(LearnerStateCodec.encode(s)))
    }
}
