package com.cartutor.engineglish.english.learn

import com.cartutor.engineglish.english.core.CardState
import com.cartutor.engineglish.english.core.CommuteSlot
import com.cartutor.engineglish.english.core.ContentSource
import com.cartutor.engineglish.english.core.HookTrigger
import com.cartutor.engineglish.english.core.LearnerState
import com.cartutor.engineglish.english.core.LearningItem
import com.cartutor.engineglish.english.core.Level
import com.cartutor.engineglish.english.core.LoadLevel
import com.cartutor.engineglish.english.core.Mistake
import com.cartutor.engineglish.english.core.PlanKind
import com.cartutor.engineglish.english.core.PlanRequest
import com.cartutor.engineglish.english.core.ProgressState
import com.cartutor.engineglish.english.core.PronIssueType
import com.cartutor.engineglish.english.core.SessionPlan
import com.cartutor.engineglish.english.core.Skill
import com.cartutor.engineglish.english.core.SlotMode
import com.cartutor.engineglish.english.core.Track
import com.cartutor.engineglish.english.core.UnitInfo
import com.cartutor.engineglish.english.core.UnitKey
import com.cartutor.engineglish.english.core.UnitRecord
import com.cartutor.engineglish.english.core.Verdict
import com.cartutor.engineglish.english.learn.LearnFixtures.HOUR
import com.cartutor.engineglish.english.learn.LearnFixtures.MIN
import com.cartutor.engineglish.english.learn.LearnFixtures.NOW
import com.cartutor.engineglish.english.learn.LearnFixtures.TODAY
import com.cartutor.engineglish.english.learn.LearnFixtures.dueCards
import com.cartutor.engineglish.english.learn.LearnFixtures.futureCards
import com.cartutor.engineglish.english.learn.LearnFixtures.profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 출퇴근 세션 계획 — 예산 · 새 표현/복습 배분 · 순서 · 주행 NORMAL 비율 · 마무리 후보 · 연장 (명세 §6.4) */
class CommuteSessionPlannerTest {
    private val content = LearnFixtures.content()
    private val scheduler = SrsScheduler()
    private val planner = CommuteSessionPlanner(scheduler, UnitProgression(scheduler))

    /** 초급 유닛의 NORMAL 문항 27개 (복습 카드용) */
    private val normalBeginner: List<String> = (1..3).flatMap { u -> (1..10).filter { it != 8 }.map { LearnFixtures.id(Track.BUSINESS, Level.BEGINNER, u, it) } }

    private fun request(
        state: LearnerState,
        slot: CommuteSlot = CommuteSlot.MORNING,
        mode: SlotMode = SlotMode.NEW_EXPRESSIONS,
        track: Track = Track.BUSINESS,
        level: Level = Level.INTERMEDIATE,
        route: Int? = 32 * 60,
        parked: Boolean = false,
        now: Long = NOW,
        reviewOnly: Boolean = false,
        pronDrill: PronIssueType? = null,
        exclude: Set<String> = emptySet(),
    ) = PlanRequest(state, slot, mode, track, level, route, parked, now, reviewOnly, pronDrill, exclude)

    private fun kinds(plan: SessionPlan) = plan.items.map { it.kind }
    private fun ids(plan: SessionPlan, kind: PlanKind) = plan.items.filter { it.kind == kind }.map { it.itemId }

    private val R = PlanKind.REVIEW
    private val N = PlanKind.NEW

    @Test fun morningCommuteThirtyTwoMinutesPlansSixNewAndTenReviews() {
        val reviewIds = normalBeginner.take(12)
        val state = LearnerState(profile = profile(level = Level.INTERMEDIATE), cards = dueCards(reviewIds))
        val plan = planner.plan(request(state), content)

        assertEquals("출근길 32분 → 새 표현 6개 + 복습 10개", plan.titleKo)
        assertEquals(6, plan.newCount)
        assertEquals(10, plan.reviewCount)
        assertEquals(32, plan.routeMinutes)
        assertEquals(672, plan.budgetSec)
        assertEquals(CommuteSlot.MORNING, plan.slot)
        assertEquals(SlotMode.NEW_EXPRESSIONS, plan.mode)
        // 복습 2개 몸풀기 → 새 1 : 복습 2
        assertEquals(listOf(R, R, N, R, R, N, R, R, N, R, R, N, R, R, N, N), kinds(plan))
        // 새 표현 = 지금 유닛(중급 1) 앞에서부터, 복습 = 많이 밀린 순
        assertEquals((1..6).map { LearnFixtures.id(Track.BUSINESS, Level.INTERMEDIATE, 1, it) }, ids(plan, N))
        assertEquals(reviewIds.take(10), ids(plan, R))
        // 오늘의 한 문장 = 첫 문장형 새 표현 (EXPRESSION)
        assertEquals("biz.i1.04", plan.wrapUpItemId)
        assertEquals(NOW, plan.createdMs)
        assertEquals(plan.items.size, plan.items.map { it.itemId }.toSet().size)
    }

    @Test fun eveningCommuteReviewsTodaysMistakesFirst() {
        val evening = NOW + 10 * HOUR + 30 * MIN // 18:10 KST
        val mistakeIds = listOf("biz.b3.04", "biz.b3.05", "biz.b3.06")
        val dueIds = normalBeginner.filter { it !in mistakeIds }.take(20)
        val state = LearnerState(
            profile = profile(level = Level.INTERMEDIATE),
            cards = dueCards(dueIds, evening) + futureCards(mistakeIds, evening),
            mistakes = mistakeIds.mapIndexed { i, id -> Mistake(id, TODAY, Verdict.WRONG, NOW + i * MIN) } +
                Mistake("biz.b1.01", TODAY - 1, Verdict.WRONG, NOW - 20 * HOUR), // 어제 실수는 '오늘 틀린 표현' 아님
        )
        val plan = planner.plan(request(state, slot = CommuteSlot.EVENING, mode = SlotMode.REVIEW, now = evening), content)

        assertEquals("퇴근길 32분 → 오늘 틀린 표현 3개 + 복습 15개 + 새 표현 2개", plan.titleKo)
        assertEquals(2, plan.newCount)
        assertEquals(18, plan.reviewCount)
        assertEquals(List(3) { PlanKind.MISTAKE } + List(15) { R } + List(2) { N }, kinds(plan))
        assertEquals(mistakeIds, ids(plan, PlanKind.MISTAKE))
        // 새 표현은 VOCAB 뿐 → 문장형인 첫 틀린 표현이 마무리 후보
        assertEquals("biz.b3.04", plan.wrapUpItemId)

        val noMistakes = planner.plan(request(state.copy(mistakes = emptyList()), slot = CommuteSlot.EVENING, mode = SlotMode.REVIEW, now = evening), content)
        assertEquals("퇴근길 32분 → 복습 18개 + 새 표현 2개", noMistakes.titleKo)
        assertEquals(18, noMistakes.reviewCount)
        assertEquals(2, noMistakes.newCount)
    }

    @Test fun parkedOrNoRouteUsesTenMinuteBudget() {
        val state = LearnerState(profile = profile(level = Level.INTERMEDIATE), cards = dueCards(normalBeginner.take(12)))
        val parked = planner.plan(request(state, route = 1920, parked = true), content)
        assertEquals("오늘의 세션 → 새 표현 5개 + 복습 10개", parked.titleKo)
        assertNull(parked.routeMinutes)
        assertEquals(600, parked.budgetSec)
        val freeDrive = planner.plan(request(state, route = null, parked = false), content)
        assertEquals("오늘의 세션 → 새 표현 5개 + 복습 10개", freeDrive.titleKo)
    }

    @Test fun mixedModeOnOtherSlot() {
        val state = LearnerState(profile = profile(level = Level.INTERMEDIATE), cards = dueCards(normalBeginner.take(15)))
        val plan = planner.plan(request(state, slot = CommuteSlot.OTHER, mode = SlotMode.MIXED, route = 25 * 60), content)
        assertEquals("주행 25분 → 새 표현 3개 + 복습 11개", plan.titleKo)
        assertEquals(listOf(R, R, N, R, R, N, R, R, N, R, R, R, R, R), kinds(plan))
    }

    @Test fun firstSessionWithoutCardsTurnsReviewTimeIntoNewItems() {
        val state = LearnerState(profile = profile(level = Level.INTERMEDIATE))
        val morning = planner.plan(request(state), content)
        assertEquals("출근길 32분 → 새 표현 8개", morning.titleKo)
        assertEquals(8, morning.newCount)
        assertEquals(0, morning.reviewCount)
        assertTrue(kinds(morning).all { it == N })

        val evening = planner.plan(request(state, slot = CommuteSlot.EVENING, mode = SlotMode.REVIEW, now = NOW + 11 * HOUR), content)
        assertEquals("퇴근길 32분 → 새 표현 8개", evening.titleKo)
    }

    @Test fun drivingPlansKeepSeventyPercentNormalItemsAndSkipParkedOnly() {
        val custom = customContent()
        val state = LearnerState(profile = profile(track = Track.DAILY, level = Level.BEGINNER))
        val drive = planner.plan(request(state, track = Track.DAILY, level = Level.BEGINNER), custom)
        val items = drive.items.map { custom.item(it.itemId)!! }
        val normal = items.count { it.maxLoad == LoadLevel.NORMAL }
        assertEquals(8, items.size)
        assertTrue("NORMAL 비율 ${normal}/${items.size}", normal >= 0.7 * items.size)
        assertTrue(items.none { it.maxLoad == LoadLevel.PARKED })
        assertEquals(listOf("day.r.01", "day.r.02"), items.filter { it.maxLoad == LoadLevel.RELAXED }.map { it.id })

        // 정차 중에는 제한 없음 (정차 전용 문항도 나옴)
        val parked = planner.plan(request(state, track = Track.DAILY, level = Level.BEGINNER, parked = true, route = null), custom)
        val parkedItems = parked.items.map { custom.item(it.itemId)!! }
        assertTrue(parkedItems.any { it.maxLoad == LoadLevel.PARKED })
        assertTrue(parkedItems.count { it.maxLoad == LoadLevel.RELAXED } > 2)
    }

    @Test fun toeicTrackWhileDrivingStillMostlyNormal() {
        val state = LearnerState(profile = profile(track = Track.TOEIC_SPEAKING, level = Level.BEGINNER), cards = dueCards(normalBeginner.take(4)))
        val plan = planner.plan(request(state, track = Track.TOEIC_SPEAKING, level = Level.BEGINNER), content)
        val items = plan.items.map { content.item(it.itemId)!! }
        val normal = items.count { it.maxLoad == LoadLevel.NORMAL }
        assertTrue(normal >= 0.7 * items.size)
        assertTrue(items.any { it.skill == Skill.TOEIC_QA }) // 여유 구간(RELAXED)용 토익 문항도 일부 들어감
    }

    @Test fun reviewDueCardsForParkedOnlyItemsWaitUntilParked() {
        val custom = customContent()
        val state = LearnerState(profile = profile(track = Track.DAILY, level = Level.BEGINNER), cards = dueCards(listOf("day.p.01", "day.n.01")))
        val drive = planner.plan(request(state, track = Track.DAILY, level = Level.BEGINNER, reviewOnly = true), custom)
        assertEquals(listOf("day.n.01"), drive.items.map { it.itemId })
        val parked = planner.plan(request(state, track = Track.DAILY, level = Level.BEGINNER, reviewOnly = true, parked = true), custom)
        assertEquals(listOf("day.p.01", "day.n.01"), parked.items.map { it.itemId })
    }

    @Test fun reviewOnlyHasNoNewItems() {
        val state = LearnerState(
            profile = profile(level = Level.INTERMEDIATE),
            cards = dueCards(normalBeginner.take(5)),
            mistakes = listOf(Mistake(normalBeginner[4], TODAY, Verdict.PARTIAL, NOW - MIN)),
        )
        val plan = planner.plan(request(state, parked = true, route = null, reviewOnly = true), content)
        assertEquals("오늘의 복습 → 오늘 틀린 표현 1개 + 복습 4개", plan.titleKo)
        assertEquals(0, plan.newCount)
        assertEquals(5, plan.reviewCount)
        assertEquals(normalBeginner[4], plan.items.first().itemId)
        assertEquals(PlanKind.MISTAKE, plan.items.first().kind)

        val empty = planner.plan(request(LearnerState(profile = profile()), parked = true, route = null, reviewOnly = true), content)
        assertTrue(empty.items.isEmpty())
        assertEquals("오늘의 복습 → 지금 복습할 표현이 없어요", empty.titleKo)
        assertNull(empty.wrapUpItemId)
    }

    @Test fun lowestEaseCardsFillReviewsWhenFewAreDue() {
        val due = dueCards(normalBeginner.take(2))
        val notDue = mapOf(
            "biz.b2.01" to CardState(itemId = "biz.b2.01", reps = 3, ease = 2.6, intervalDays = 5.0, dueAtMs = NOW + 2 * HOUR),
            "biz.b2.02" to CardState(itemId = "biz.b2.02", reps = 3, ease = 1.4, intervalDays = 5.0, dueAtMs = NOW + 5 * HOUR),
            "biz.b2.03" to CardState(itemId = "biz.b2.03", reps = 3, ease = 1.9, intervalDays = 5.0, dueAtMs = NOW + 1 * HOUR),
        )
        val state = LearnerState(profile = profile(level = Level.INTERMEDIATE), cards = due + notDue)
        val plan = planner.plan(request(state), content)
        assertEquals(normalBeginner.take(2) + listOf("biz.b2.02", "biz.b2.03", "biz.b2.01"), ids(plan, R))
    }

    @Test fun newItemsComeFromCurrentUnitThenFollowingUnits() {
        val b1 = UnitKey(Track.BUSINESS, Level.BEGINNER, 1)
        val b2Seen = (1..3).map { LearnFixtures.id(Track.BUSINESS, Level.BEGINNER, 2, it) }
        val state = LearnerState(
            profile = profile(level = Level.BEGINNER),
            progress = ProgressState(units = mapOf(b1.id to UnitRecord(b1.id, passed = true))),
            cards = futureCards(content.unitItems(b1).map { it.id } + b2Seen),
        )
        val plan = planner.plan(request(state, level = Level.BEGINNER, parked = true, route = null), content)
        val news = ids(plan, N)
        assertEquals(listOf("biz.b2.04", "biz.b2.05", "biz.b2.06", "biz.b2.07", "biz.b2.08"), news.take(5))
        assertTrue(news.none { it in b2Seen || it.startsWith("biz.b1.") })

        // 다 본 유닛 뒤에는 다음 유닛 (b2 → b3)
        val more = planner.plan(request(state, level = Level.BEGINNER, parked = true, route = null, exclude = (4..10).map { "biz.b2.%02d".format(it) }.toSet()), content)
        assertEquals("biz.b3.01", ids(more, N).first())
    }

    @Test fun excludeIsRespected() {
        val state = LearnerState(profile = profile(level = Level.INTERMEDIATE), cards = dueCards(normalBeginner.take(12)))
        val plan = planner.plan(request(state, exclude = setOf("biz.i1.01", normalBeginner[0])), content)
        assertFalse(plan.items.any { it.itemId == "biz.i1.01" || it.itemId == normalBeginner[0] })
        assertEquals("biz.i1.02", ids(plan, N).first())
    }

    @Test fun pronDrillPlansSixUnseenPairsFirst() {
        val state = LearnerState(
            profile = profile(),
            cards = futureCards(listOf("pron.r_l.01", "pron.r_l.02")),
        )
        val plan = planner.plan(request(state, parked = true, route = null, pronDrill = PronIssueType.R_L), content)
        assertEquals((3..8).map { "pron.r_l.0$it" }, plan.items.map { it.itemId })
        assertTrue(plan.items.all { it.kind == PlanKind.DRILL })
        assertEquals("발음 연습 · R / L 구분 → 짝 단어 6개", plan.titleKo)
        assertEquals(6, plan.newCount)

        val none = planner.plan(request(state, parked = true, route = null, pronDrill = PronIssueType.TH_D), content)
        assertTrue(none.items.isEmpty())
        assertEquals("발음 연습 · TH / D 구분 → 준비된 연습이 없어요", none.titleKo)
    }

    @Test fun extendAddsReviewsFirstThenNew() {
        val reviewIds = normalBeginner.take(12)
        val state = LearnerState(profile = profile(level = Level.INTERMEDIATE), cards = dueCards(reviewIds))
        val req = request(state)
        val plan = planner.plan(req, content)
        val asked = plan.items.map { it.itemId }.toSet()
        val ext = planner.extend(plan, req.copy(exclude = asked + reviewIds[11]), content, 5)
        assertEquals(plan.items, ext.items.take(plan.items.size))
        val added = ext.items.drop(plan.items.size)
        assertEquals(5, added.size)
        assertEquals(PlanKind.REVIEW, added[0].kind)
        assertEquals(reviewIds[10], added[0].itemId) // 남은 복습 하나 (다른 하나는 exclude)
        assertEquals(listOf("biz.i1.07", "biz.i1.08", "biz.i1.09", "biz.i1.10"), added.drop(1).map { it.itemId })
        assertEquals(plan.reviewCount + 1, ext.reviewCount)
        assertEquals(plan.newCount + 4, ext.newCount)
        assertEquals(plan.id, ext.id)
        assertEquals(plan, planner.extend(plan, req, content, 0))
    }

    @Test fun budgetTargetsAndHelpers() {
        assertEquals(672, CommuteSessionPlanner.budgetSec(1920, parked = false))
        assertEquals(600, CommuteSessionPlanner.budgetSec(1920, parked = true))
        assertEquals(600, CommuteSessionPlanner.budgetSec(null, parked = false))
        assertEquals(180, CommuteSessionPlanner.budgetSec(300, parked = false)) // 최소 180초
        assertEquals(32, CommuteSessionPlanner.routeMinutes(1919, parked = false))
        assertEquals(1, CommuteSessionPlanner.routeMinutes(20, parked = false))
        assertNull(CommuteSessionPlanner.routeMinutes(1920, parked = true))
        assertNull(CommuteSessionPlanner.routeMinutes(null, parked = false))

        assertEquals(6 to 10, CommuteSessionPlanner.targets(672, SlotMode.NEW_EXPRESSIONS, reviewOnly = false))
        assertEquals(2 to 18, CommuteSessionPlanner.targets(672, SlotMode.REVIEW, reviewOnly = false))
        assertEquals(5 to 10, CommuteSessionPlanner.targets(600, SlotMode.NEW_EXPRESSIONS, reviewOnly = false))
        assertEquals(4 to 12, CommuteSessionPlanner.targets(600, SlotMode.MIXED, reviewOnly = false))
        assertEquals(1 to 4, CommuteSessionPlanner.targets(180, SlotMode.NEW_EXPRESSIONS, reviewOnly = false))
        assertEquals(0 to 6, CommuteSessionPlanner.targets(180, SlotMode.REVIEW, reviewOnly = false))
        assertEquals(0 to 20, CommuteSessionPlanner.targets(600, SlotMode.NEW_EXPRESSIONS, reviewOnly = true))
        assertEquals(8 to 30, CommuteSessionPlanner.targets(5_000, SlotMode.NEW_EXPRESSIONS, reviewOnly = false))

        assertEquals(CommuteSlot.MORNING, CommuteSessionPlanner.previewSlot(NOW)) // 07:40
        assertEquals(CommuteSlot.EVENING, CommuteSessionPlanner.previewSlot(NOW + 5 * HOUR)) // 12:40
        // 도착 1분 전부터는 주행 학습을 끝낸다
        assertTrue(CommuteSessionPlanner.isLessonEndTime(60))
        assertFalse(CommuteSessionPlanner.isLessonEndTime(61))
        assertFalse(CommuteSessionPlanner.isLessonEndTime(null))
    }

    @Test fun shortRouteStillPlansAtLeastOneNewItem() {
        val state = LearnerState(profile = profile(level = Level.INTERMEDIATE), cards = dueCards(normalBeginner.take(10)))
        val plan = planner.plan(request(state, route = 5 * 60), content)
        assertEquals("출근길 5분 → 새 표현 1개 + 복습 4개", plan.titleKo)
        assertEquals(180, plan.budgetSec)
    }

    @Test fun pickHookItemPrefersUnseenItems() {
        val none = LearnerState()
        assertEquals("hook.rain.01", CommuteSessionPlanner.pickHookItem(content, none, HookTrigger.RAIN)!!.id)
        val seen01 = LearnerState(cards = futureCards(listOf("hook.rain.01")))
        assertEquals("hook.rain.02", CommuteSessionPlanner.pickHookItem(content, seen01, HookTrigger.RAIN)!!.id)
        val both = LearnerState(
            cards = mapOf(
                "hook.rain.01" to CardState(itemId = "hook.rain.01", lastReviewedMs = NOW - HOUR),
                "hook.rain.02" to CardState(itemId = "hook.rain.02", lastReviewedMs = NOW - 2 * HOUR),
            ),
        )
        assertEquals("hook.rain.02", CommuteSessionPlanner.pickHookItem(content, both, HookTrigger.RAIN)!!.id)
        assertEquals("hook.rain.02", CommuteSessionPlanner.pickHookItem(content, none, HookTrigger.RAIN, exclude = setOf("hook.rain.01"))!!.id)
        assertNull(CommuteSessionPlanner.pickHookItem(content, none, HookTrigger.RAIN) { false })
        assertNull(CommuteSessionPlanner.pickHookItem(content, none, HookTrigger.TUNNEL))
    }

    /**
     * 일상 초급 유닛 2개: 1유닛 = 정차 전용 1 + RELAXED 5 + NORMAL 4, 2유닛 = RELAXED 3 + NORMAL 7.
     * id: day.p.NN (PARKED) · day.r.NN (RELAXED) · day.n.NN (NORMAL)
     */
    private fun customContent(): ContentSource {
        fun item(id: String, unit: Int, load: LoadLevel) = LearningItem(
            id = id, track = Track.DAILY, level = Level.BEGINNER, unit = unit, skill = Skill.SENTENCE_BUILD,
            promptKo = "문항", answers = listOf("Can we push the meeting to tomorrow?"), meaningKo = "뜻", maxLoad = load,
        )
        val u1 = listOf(item("day.p.01", 1, LoadLevel.PARKED)) +
            (1..5).map { item("day.r.%02d".format(it), 1, LoadLevel.RELAXED) } +
            (1..4).map { item("day.n.%02d".format(it), 1, LoadLevel.NORMAL) }
        val u2 = (6..8).map { item("day.r.%02d".format(it), 2, LoadLevel.RELAXED) } +
            (5..11).map { item("day.n.%02d".format(it), 2, LoadLevel.NORMAL) }
        return FakeContent(
            all = u1 + u2,
            unitInfos = listOf(
                UnitInfo(UnitKey(Track.DAILY, Level.BEGINNER, 1), "1", "1", u1.map { it.id }),
                UnitInfo(UnitKey(Track.DAILY, Level.BEGINNER, 2), "2", "2", u2.map { it.id }),
            ),
        )
    }
}
