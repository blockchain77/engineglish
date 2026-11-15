package com.cartutor.engineglish.english.learn

import com.cartutor.engineglish.english.core.CardState
import com.cartutor.engineglish.english.core.CommuteSlot
import com.cartutor.engineglish.english.core.DailyGoal
import com.cartutor.engineglish.english.core.DayStat
import com.cartutor.engineglish.english.core.FluencyDay
import com.cartutor.engineglish.english.core.Grade
import com.cartutor.engineglish.english.core.LearnerState
import com.cartutor.engineglish.english.core.Level
import com.cartutor.engineglish.english.core.PronIssue
import com.cartutor.engineglish.english.core.PronIssueType
import com.cartutor.engineglish.english.core.PronProfile
import com.cartutor.engineglish.english.core.PronStat
import com.cartutor.engineglish.english.core.ProgressEvent
import com.cartutor.engineglish.english.core.ProgressState
import com.cartutor.engineglish.english.core.ProgressUpdate
import com.cartutor.engineglish.english.core.SessionRecord
import com.cartutor.engineglish.english.core.SlotMode
import com.cartutor.engineglish.english.core.Track
import com.cartutor.engineglish.english.core.UnitKey
import com.cartutor.engineglish.english.core.UnitRecord
import com.cartutor.engineglish.english.core.Verdict
import com.cartutor.engineglish.english.learn.LearnFixtures.DAY
import com.cartutor.engineglish.english.learn.LearnFixtures.HOUR
import com.cartutor.engineglish.english.learn.LearnFixtures.MIN
import com.cartutor.engineglish.english.learn.LearnFixtures.NOW
import com.cartutor.engineglish.english.learn.LearnFixtures.TODAY
import com.cartutor.engineglish.english.learn.LearnFixtures.fluency
import com.cartutor.engineglish.english.learn.LearnFixtures.outcome
import com.cartutor.engineglish.english.learn.LearnFixtures.profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** XP · 스트릭 · 체크포인트 · 레벨 업 · 일일 목표 · 실수/발음/유창성 기록 · 스냅숏 (명세 §6.3) */
class UnitProgressionTest {
    private val content = LearnFixtures.content()
    private val p = UnitProgression(SrsScheduler())

    private val b1 = UnitKey(Track.BUSINESS, Level.BEGINNER, 1)
    private val b2 = UnitKey(Track.BUSINESS, Level.BEGINNER, 2)
    private val b3 = UnitKey(Track.BUSINESS, Level.BEGINNER, 3)

    /** 유닛 문항을 verdicts 순서대로 기록 (1분 간격) */
    private fun answerUnit(start: LearnerState, key: UnitKey, verdicts: List<Verdict>, t0: Long = NOW): Pair<LearnerState, ProgressUpdate> {
        var s = start
        var last: ProgressUpdate? = null
        content.unitItems(key).zip(verdicts).forEachIndexed { i, (item, v) ->
            last = p.record(s, outcome(item, v), content, t0 + i * MIN)
            s = last!!.state
        }
        return s to last!!
    }

    private fun checkpointEvents(u: ProgressUpdate) = u.events.filter { it is ProgressEvent.CheckpointPassed || it is ProgressEvent.UnitUnlocked }

    @Test fun checkpointPassesAtEightyPercentAndUnlocksNextUnit() {
        val start = LearnerState(profile = profile())
        val (s, last) = answerUnit(start, b1, List(8) { Verdict.CORRECT } + List(2) { Verdict.WRONG })
        assertTrue(s.progress.units[b1.id]!!.passed)
        assertEquals(NOW + 9 * MIN, s.progress.units[b1.id]!!.passedAtMs)
        assertEquals(listOf(ProgressEvent.CheckpointPassed(b1, 0.8f), ProgressEvent.UnitUnlocked(b2)), checkpointEvents(last))
        assertEquals(50, last.xpGained) // 오답 0 + 체크포인트 50
        assertEquals(8 * 10 + 50, s.progress.xp)
        assertEquals(listOf(b1, b2), p.unlockedUnits(s, content, Track.BUSINESS, Level.BEGINNER))
        assertEquals(b2, p.currentUnit(s, content))
    }

    @Test fun seventyPercentDoesNotPassUntilAMistakeIsFixed() {
        val start = LearnerState(profile = profile())
        val (s, last) = answerUnit(start, b1, List(7) { Verdict.CORRECT } + List(3) { Verdict.WRONG })
        assertFalse(s.progress.units[b1.id]!!.passed)
        assertTrue(checkpointEvents(last).isEmpty())
        assertEquals(listOf(b1), p.unlockedUnits(s, content, Track.BUSINESS, Level.BEGINNER))
        // 복습에서 틀린 문항 하나를 맞히면 (최근 점수로 바뀜) 8/10 → 통과
        val fixed = p.record(s, outcome(content.unitItems(b1)[9], Verdict.CORRECT, isNew = false), content, NOW + HOUR)
        assertTrue(fixed.state.progress.units[b1.id]!!.passed)
        assertEquals(listOf(ProgressEvent.CheckpointPassed(b1, 0.8f), ProgressEvent.UnitUnlocked(b2)), checkpointEvents(fixed))
    }

    @Test fun partialAnswersCountHalf() {
        val (s, _) = answerUnit(
            LearnerState(profile = profile()), b1,
            List(7) { Verdict.CORRECT } + List(2) { Verdict.PARTIAL } + Verdict.WRONG,
        )
        assertTrue(s.progress.units[b1.id]!!.passed) // (7 + 2×0.5) / 10 = 0.8
        val scores = s.progress.units[b1.id]!!.scores
        assertEquals(10, scores.size)
        assertEquals(0.5f, scores[content.unitItems(b1)[7].id]!!, 0f)
    }

    @Test fun unitNeedsEveryItemScoredBeforeCheckpoint() {
        val (s, _) = answerUnit(LearnerState(profile = profile()), b1, List(9) { Verdict.CORRECT })
        assertFalse(s.progress.units[b1.id]!!.passed) // 9/10 문항만 풂
    }

    @Test fun levelUpAfterThreeUnitsAtProfileLevel() {
        var s = LearnerState(profile = profile()) // 하루 목표 20 → 30번째 답에는 목표 이벤트 없음
        var last: ProgressUpdate? = null
        for ((i, key) in listOf(b1, b2, b3).withIndex()) {
            val r = answerUnit(s, key, List(10) { Verdict.CORRECT }, NOW + i * HOUR)
            s = r.first
            last = r.second
        }
        assertEquals(Level.INTERMEDIATE, s.profile!!.level)
        assertEquals(
            listOf(ProgressEvent.CheckpointPassed(b3, 1f), ProgressEvent.LevelUp(Track.BUSINESS, Level.BEGINNER, Level.INTERMEDIATE)),
            last!!.events,
        )
        assertEquals(10 + 50 + 100, last.xpGained)
        assertEquals(30 * 10 + 3 * 50 + 100, s.progress.xp)
        // 새 레벨의 첫 유닛이 지금 유닛
        assertEquals(UnitKey(Track.BUSINESS, Level.INTERMEDIATE, 1), p.currentUnit(s, content))
    }

    @Test fun levelWithTwoUnitsNeedsOnlyTwo() {
        val small = LearnFixtures.content(unitsOverride = mapOf((Track.BUSINESS to Level.BEGINNER) to 2))
        var s = LearnerState(profile = profile(goal = DailyGoal.INTENSIVE))
        var events = emptyList<ProgressEvent>()
        for (key in listOf(b1, b2)) {
            small.unitItems(key).forEachIndexed { i, item ->
                val u = p.record(s, outcome(item, Verdict.CORRECT), small, NOW + i * MIN)
                s = u.state
                events = u.events
            }
        }
        assertEquals(Level.INTERMEDIATE, s.profile!!.level)
        assertTrue(events.contains(ProgressEvent.LevelUp(Track.BUSINESS, Level.BEGINNER, Level.INTERMEDIATE)))
    }

    @Test fun passingUnitsOutsideProfileLevelDoesNotLevelUp() {
        var s = LearnerState(profile = profile(level = Level.INTERMEDIATE, goal = DailyGoal.INTENSIVE))
        for (key in listOf(b1, b2, b3)) s = answerUnit(s, key, List(10) { Verdict.CORRECT }).first
        assertEquals(Level.INTERMEDIATE, s.profile!!.level)
        assertTrue(listOf(b1, b2, b3).all { s.progress.units[it.id]!!.passed })
        // 고급(마지막 레벨)에서는 더 오를 곳이 없음
        var adv = LearnerState(profile = profile(level = Level.ADVANCED, goal = DailyGoal.INTENSIVE))
        for (u in 1..3) adv = answerUnit(adv, UnitKey(Track.BUSINESS, Level.ADVANCED, u), List(10) { Verdict.CORRECT }).first
        assertEquals(Level.ADVANCED, adv.profile!!.level)
    }

    @Test fun xpRules() {
        val item = content.unitItems(b1)[0]
        val s = LearnerState(profile = profile())
        assertEquals(10, p.record(s, outcome(item, Verdict.CORRECT, Grade.GOOD), content, NOW).xpGained)
        assertEquals(12, p.record(s, outcome(item, Verdict.CORRECT, Grade.EASY), content, NOW).xpGained)
        assertEquals(10, p.record(s, outcome(item, Verdict.CORRECT, Grade.HARD), content, NOW).xpGained) // 힌트·두 번째 시도
        assertEquals(5, p.record(s, outcome(item, Verdict.PARTIAL), content, NOW).xpGained)
        assertEquals(0, p.record(s, outcome(item, Verdict.WRONG), content, NOW).xpGained)
        assertEquals(0, p.record(s, outcome(item, Verdict.NO_ANSWER), content, NOW).xpGained)
    }

    @Test fun recordUpdatesSrsCardAndTotals() {
        val item = content.unitItems(b1)[3]
        val u = p.record(LearnerState(profile = profile()), outcome(item, Verdict.CORRECT, Grade.EASY), content, NOW)
        val card = u.state.cards[item.id]!!
        assertEquals(1, card.reps)
        assertEquals(NOW + 2 * DAY, card.dueAtMs)
        assertEquals(1, u.state.progress.totalAnswered)
        assertEquals(1, u.state.progress.totalCorrect)
    }

    @Test fun streakFollowsKstCalendarDays() {
        val item = content.unitItems(b1)[0]
        val t0 = NOW + 16 * HOUR + 10 * MIN // 11/16 23:50 KST
        var s = LearnerState(profile = profile())
        var u = p.record(s, outcome(item, Verdict.CORRECT), content, t0)
        s = u.state
        assertEquals(1, s.progress.streakDays)
        assertTrue(u.events.none { it is ProgressEvent.StreakExtended })

        val t1 = t0 + 20 * MIN // 11/17 00:10 KST — UTC 로는 같은 날이지만 KST 로 다음 날
        u = p.record(s, outcome(item, Verdict.CORRECT, isNew = false), content, t1)
        s = u.state
        assertEquals(2, s.progress.streakDays)
        assertTrue(u.events.contains(ProgressEvent.StreakExtended(2)))
        assertEquals(TODAY + 1, s.progress.lastActiveDay)

        u = p.record(s, outcome(item, Verdict.WRONG, isNew = false), content, t1 + 5 * HOUR)
        s = u.state
        assertEquals(2, s.progress.streakDays)
        assertTrue(u.events.none { it is ProgressEvent.StreakExtended })

        u = p.record(s, outcome(item, Verdict.CORRECT, isNew = false), content, t1 + 2 * DAY) // 하루 건너뜀
        s = u.state
        assertEquals(1, s.progress.streakDays)
        assertEquals(2, s.progress.bestStreak)

        // 이틀 쉬면 화면 스트릭은 0
        assertEquals(1, p.snapshot(s, content, t1 + 3 * DAY).streakDays)
        assertEquals(0, p.snapshot(s, content, t1 + 4 * DAY).streakDays)
    }

    @Test fun dailyGoalReachedOnlyWhenCrossing() {
        var s = LearnerState(profile = profile(goal = DailyGoal.LIGHT))
        val items = content.unitItems(b1) + content.unitItems(b2)
        val reachedAt = ArrayList<Int>()
        items.take(12).forEachIndexed { i, item ->
            val u = p.record(s, outcome(item, Verdict.WRONG), content, NOW + i * MIN)
            s = u.state
            if (u.events.contains(ProgressEvent.DailyGoalReached(10))) reachedAt += i + 1
        }
        assertEquals(listOf(10), reachedAt)
        // 다음 날은 다시 0부터
        val next = p.record(s, outcome(items[12], Verdict.CORRECT), content, NOW + DAY)
        assertEquals(1, next.state.progress.days.last().answered)
    }

    @Test fun todayStatsCountNewReviewedAndXpAndKeepThirtyDays() {
        val old = (1..40).map { DayStat(day = TODAY - it, answered = 1) }.sortedBy { it.day }
        var s = LearnerState(profile = profile(), progress = ProgressState(days = old, lastActiveDay = TODAY - 1, streakDays = 3))
        val items = content.unitItems(b1)
        s = p.record(s, outcome(items[0], Verdict.CORRECT, Grade.GOOD, isNew = true), content, NOW).state
        s = p.record(s, outcome(items[1], Verdict.WRONG, Grade.AGAIN, isNew = true), content, NOW + MIN).state
        s = p.record(s, outcome(items[2], Verdict.PARTIAL, Grade.HARD, isNew = false), content, NOW + 2 * MIN).state
        s = p.record(s, outcome(items[3], Verdict.CORRECT, Grade.EASY, isNew = false), content, NOW + 3 * MIN).state
        val today = s.progress.days.last()
        assertEquals(DayStat(day = TODAY, answered = 4, correct = 2, partial = 1, newLearned = 1, reviewed = 2, xp = 27), today)
        assertEquals(30, s.progress.days.size)
        assertEquals(TODAY - 29, s.progress.days.first().day)
        assertEquals(4, s.progress.streakDays) // 어제 활동 → +1
    }

    @Test fun mistakesOnePerItemPerDayKeptSevenDays() {
        val a = content.unitItems(b1)[0]
        val b = content.unitItems(b1)[1]
        var s = LearnerState(profile = profile())
        s = p.record(s, outcome(a, Verdict.WRONG), content, NOW).state
        s = p.record(s, outcome(a, Verdict.PARTIAL, isNew = false), content, NOW + MIN).state
        assertEquals(1, s.mistakes.size)
        assertEquals(Verdict.PARTIAL, s.mistakes.single().verdict)
        s = p.record(s, outcome(a, Verdict.CORRECT, isNew = false), content, NOW + 2 * MIN).state
        assertEquals(1, s.mistakes.size) // 정답은 실수로 남기지 않음
        s = p.record(s, outcome(a, Verdict.WRONG, isNew = false), content, NOW + DAY).state
        assertEquals(listOf(TODAY, TODAY + 1), s.mistakes.map { it.day })
        s = p.record(s, outcome(b, Verdict.NO_ANSWER), content, NOW + 8 * DAY).state
        assertEquals(listOf(b.id), s.mistakes.map { it.itemId }) // 7일 지난 기록은 버림
    }

    @Test fun pronunciationProfileCountsAndKeepsRecentTwenty() {
        val item = content.unitItems(b1)[9]
        fun issue(type: PronIssueType, e: String, h: String) = PronIssue(type, e, h, 0.8f, "팁")
        var s = LearnerState(profile = profile())
        s = p.record(
            s, outcome(item, Verdict.PARTIAL, pron = listOf(issue(PronIssueType.R_L, "right", "light"), issue(PronIssueType.F_P, "coffee", "copy"))),
            content, NOW,
        ).state
        assertEquals(mapOf(PronIssueType.R_L to 1, PronIssueType.F_P to 1), s.pron.counts)
        assertEquals("light", s.pron.recent.first().heard)
        repeat(25) { i ->
            s = p.record(s, outcome(item, Verdict.PARTIAL, isNew = false, pron = listOf(issue(PronIssueType.R_L, "road", "load"))), content, NOW + (i + 1) * MIN).state
        }
        assertEquals(26, s.pron.counts[PronIssueType.R_L])
        assertEquals(20, s.pron.recent.size)
        assertEquals(NOW + 25 * MIN, s.pron.recent.last().atMs)
    }

    @Test fun fluencyRunningAveragesWhenLatencyKnown() {
        val item = content.unitItems(b1)[5]
        var s = LearnerState(profile = profile())
        s = p.record(s, outcome(item, Verdict.CORRECT, fluency = fluency(1_000L, wpm = 100, pauses = 1)), content, NOW).state
        s = p.record(s, outcome(item, Verdict.CORRECT, isNew = false, fluency = fluency(2_000L, wpm = null, pauses = 3)), content, NOW + MIN).state
        s = p.record(s, outcome(item, Verdict.CORRECT, isNew = false, fluency = fluency(null)), content, NOW + 2 * MIN).state // 지연 모름 → 제외
        s = p.record(s, outcome(item, Verdict.CORRECT, isNew = false, fluency = null), content, NOW + 3 * MIN).state
        assertEquals(listOf(FluencyDay(TODAY, 2, 1_500L, 100, 2f)), s.fluency)
        val f = p.snapshot(s, content, NOW).fluency!!
        assertEquals(1_500L, f.avgLatencyMs)
        assertEquals(100, f.avgWpm)
        assertEquals(2, f.samples)
        assertEquals(0L, f.latencyTrendMs)
    }

    @Test fun fluencyTrendComparesWithLastWeek() {
        val s = LearnerState(
            profile = profile(),
            fluency = listOf(FluencyDay(TODAY - 9, 4, 3_000L, 90, 2f), FluencyDay(TODAY - 1, 2, 1_800L, 110, 1f), FluencyDay(TODAY, 2, 1_400L, 120, 1f)),
        )
        val f = p.snapshot(s, content, NOW).fluency!!
        assertEquals(1_600L, f.avgLatencyMs)
        assertEquals(115, f.avgWpm)
        assertEquals(1_600L - 3_000L, f.latencyTrendMs) // 빨라짐 = 음수
        assertEquals(4, f.samples)
        assertNull(p.snapshot(LearnerState(), content, NOW).fluency)
    }

    @Test fun recordSessionKeepsThirtyAndAddsDriveMinutesOnce() {
        var s = LearnerState(profile = profile())
        repeat(31) { i ->
            s = p.recordSession(s, session("s$i", NOW + i * MIN, NOW + i * MIN + 32 * MIN))
        }
        assertEquals(30, s.sessions.size)
        assertEquals("s1", s.sessions.first().id)
        assertEquals("s30", s.sessions.last().id)
        assertEquals(31 * 32, s.progress.days.single { it.day == TODAY }.driveMinutes)
        val again = p.recordSession(s, session("s30", NOW, NOW + 32 * MIN))
        assertEquals(31 * 32, again.progress.days.single { it.day == TODAY }.driveMinutes)
        assertEquals(30, again.sessions.size)
    }

    private fun session(id: String, start: Long, end: Long) = SessionRecord(
        id = id, startedMs = start, endedMs = end, slot = CommuteSlot.MORNING, mode = SlotMode.NEW_EXPRESSIONS,
        track = Track.BUSINESS, answered = 16, correct = 12, partial = 2, interrupted = 1, xp = 150,
    )

    @Test fun unlockedAndCurrentUnits() {
        val fresh = LearnerState(profile = profile())
        assertEquals(listOf(b1), p.unlockedUnits(fresh, content, Track.BUSINESS, Level.BEGINNER))
        assertEquals(b1, p.currentUnit(fresh, content))
        assertNull(p.currentUnit(LearnerState(), content))
        val passed12 = fresh.copy(
            progress = ProgressState(units = mapOf(b1.id to UnitRecord(b1.id, passed = true), b2.id to UnitRecord(b2.id, passed = true))),
        )
        assertEquals(listOf(b1, b2, b3), p.unlockedUnits(passed12, content, Track.BUSINESS, Level.BEGINNER))
        assertEquals(b3, p.currentUnit(passed12, content))
        val all = passed12.copy(progress = passed12.progress.copy(units = passed12.progress.units + (b3.id to UnitRecord(b3.id, passed = true))))
        assertEquals(b3, p.currentUnit(all, content)) // 모두 통과 → 마지막
        assertEquals(UnitKey(Track.TRAVEL, Level.ADVANCED, 1), p.currentUnitFor(fresh, content, Track.TRAVEL, Level.ADVANCED))
    }

    @Test fun snapshotFillsScreenSummary() {
        val due = LearnFixtures.dueCards(listOf("biz.b1.01", "biz.b1.02", "biz.b1.03"))
        val future = LearnFixtures.futureCards(listOf("biz.b1.04"))
        val zero = mapOf("biz.b1.05" to CardState(itemId = "biz.b1.05", reps = 0, dueAtMs = NOW + 10 * MIN))
        val s = LearnerState(
            profile = profile(goal = DailyGoal.LIGHT),
            progress = ProgressState(
                xp = 420, streakDays = 3, lastActiveDay = TODAY,
                days = listOf(DayStat(TODAY - 2, answered = 5, correct = 4), DayStat(TODAY, answered = 4, correct = 3, partial = 1)),
                units = mapOf(b1.id to UnitRecord(b1.id, scores = content.unitItems(b1).associate { it.id to 1f }, passed = true)),
            ),
            cards = due + future + zero,
            pron = PronProfile(counts = mapOf(PronIssueType.V_B to 1, PronIssueType.TH_S to 2, PronIssueType.R_L to 5, PronIssueType.F_P to 2)),
        )
        val snap = p.snapshot(s, content, NOW)
        assertEquals(Track.BUSINESS, snap.track)
        assertEquals(Level.BEGINNER, snap.level)
        assertEquals(420, snap.xp)
        assertEquals(3, snap.streakDays)
        assertEquals(4, snap.todayAnswered)
        assertEquals(10, snap.todayGoal)
        assertEquals(0.75f, snap.todayAccuracy!!, 1e-6f)
        assertEquals(3, snap.dueReviews)
        assertEquals(4, snap.learnedItems) // reps ≥ 1
        assertEquals(90, snap.totalItems) // 비즈니스 3레벨 × 3유닛 × 10문항
        assertEquals(3, snap.units.size)
        assertTrue(snap.units[0].passed && snap.units[0].unlocked)
        assertEquals(10, snap.units[0].answered)
        assertEquals(1f, snap.units[0].accuracy, 0f)
        assertTrue(snap.units[1].unlocked && !snap.units[1].passed)
        assertFalse(snap.units[2].unlocked)
        assertEquals(b2, snap.currentUnit!!.key)
        assertEquals(1f / 3f, snap.levelProgress, 1e-6f)
        assertEquals((TODAY - 6..TODAY).toList(), snap.week.map { it.day })
        assertEquals(listOf(0, 0, 0, 0, 5, 0, 4), snap.week.map { it.answered })
        assertEquals(
            listOf(PronStat(PronIssueType.R_L, 5), PronStat(PronIssueType.F_P, 2), PronStat(PronIssueType.TH_S, 2)),
            snap.pronTop,
        )
        assertNotNull(snap.goal)

        val empty = p.snapshot(LearnerState(), content, NOW)
        assertNull(empty.track)
        assertEquals(7, empty.week.size)
        assertTrue(empty.units.isEmpty())
        assertNull(empty.todayAccuracy)
    }
}
