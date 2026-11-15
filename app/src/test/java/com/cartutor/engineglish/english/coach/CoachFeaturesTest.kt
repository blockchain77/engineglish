package com.cartutor.engineglish.english.coach

import com.cartutor.engineglish.english.core.CoachPhase
import com.cartutor.engineglish.english.core.CommuteSlot
import com.cartutor.engineglish.english.core.DailyGoal
import com.cartutor.engineglish.english.core.DemoAnswerKind
import com.cartutor.engineglish.english.core.GearPosition
import com.cartutor.engineglish.english.core.HookTrigger
import com.cartutor.engineglish.english.core.LearnerSettings
import com.cartutor.engineglish.english.core.LearnerState
import com.cartutor.engineglish.english.core.Level
import com.cartutor.engineglish.english.core.PlanKind
import com.cartutor.engineglish.english.core.PulseKind
import com.cartutor.engineglish.english.core.ScreenMode
import com.cartutor.engineglish.english.core.SuggestionKind
import com.cartutor.engineglish.english.core.Track
import com.cartutor.engineglish.english.core.Verdict
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 상황 훅 · 레벨 테스트 · 출근길 자동 시작 · 데모 답 · 저장/삭제 */
class CoachFeaturesTest {
    private var harness: CoachHarness? = null

    private fun h(state: LearnerState? = null): CoachHarness =
        (if (state == null) CoachHarness() else CoachHarness(state)).start().also { harness = it }

    @After fun noTurnErrors() {
        assertNull("세션 턴 오류", harness?.coach?.lastError)
    }

    @Test fun contextHookAfterJamExitIsDeliveredWhenLoadAllows() {
        val h = h()
        h.script(TestContent.expression to PlanKind.NEW, TestContent.toeic to PlanKind.NEW)
        h.driveMorning()
        assertTrue(h.startAndListen())
        h.coach.onAnswerText("I'm running late.")
        h.advance(3_000)
        assertNull(h.ui.prompt)

        h.set { it.copy(trafficJam = true, speedKph = 8f) }
        h.advance(21_000)
        assertNull(h.ui.prompt)
        h.set { it.copy(trafficJam = false, speedKph = 50f) }
        assertTrue(h.advanceUntil(15_000) { h.ui.prompt?.kind == PlanKind.HOOK && h.ui.phase == CoachPhase.LISTENING })
        assertEquals("hook.jam.01", h.ui.prompt?.itemId)
        assertEquals("방금 그 상황", h.ui.prompt?.titleKo)
        assertTrue(h.speech.texts().contains(TestContent.JAM_INTRO))

        h.coach.onAnswerText("I was stuck in traffic.")
        val o = h.progression.outcomes.last()
        assertEquals("hook.jam.01", o.item.id)
        assertEquals(Verdict.CORRECT, o.result.verdict)
    }

    @Test fun airportHookAsksToSwitchToTravel() {
        val h = h()
        h.script(TestContent.vocab to PlanKind.NEW, TestContent.expression to PlanKind.NEW, TestContent.sentence to PlanKind.NEW)
        h.driveMorning()
        assertTrue(h.startAndListen())
        h.planner.script = null
        h.hooks.fire(HookTrigger.AIRPORT, h.t.nowMs())
        h.advance(250)
        h.coach.onAnswerText("postpone")
        assertTrue(h.advanceUntil(10_000) { h.ui.phase == CoachPhase.CONFIRM && h.speech.isListening })
        assertEquals(SuggestionKind.SWITCH_TRACK_TRAVEL, h.ui.suggestion?.kind)
        assertTrue(h.speech.texts().contains(Say.AIRPORT_Q))

        h.coach.onAnswerText("네")
        assertEquals(Track.TRAVEL, h.ui.session?.track)
        assertTrue(h.advanceUntil(10_000) { h.ui.prompt?.itemId == "hook.air.01" && h.ui.phase == CoachPhase.LISTENING })
        assertTrue(h.speech.texts().contains(Say.TRAVEL_YES))
        assertNull(h.ui.suggestion)
        h.coach.onAnswerText("boarding pass")
        assertTrue(h.advanceUntil(10_000) { h.ui.prompt?.itemId == "trv.b1.01" && h.ui.phase == CoachPhase.LISTENING })
    }

    @Test fun placementFlowWhileParked() {
        val h = h(LearnerState(profile = TestContent.profile(placementDone = false), settings = LearnerSettings(autoStartOnCommute = false)))
        assertEquals(ScreenMode.FULL, h.ui.screenMode)
        assertEquals(SuggestionKind.PLACEMENT_NEEDED, h.ui.suggestion?.kind)
        h.coach.startPlacement()
        assertEquals(CoachPhase.PLACEMENT, h.ui.phase)
        val first = h.ui.placement!!
        assertEquals(0, first.index)
        assertEquals(12, first.total)
        assertEquals(Level.INTERMEDIATE, first.item!!.level)

        repeat(12) { i ->
            val item = h.ui.placement!!.item!!
            val choice = if (i % 4 == 3) 1 else item.answerIndex
            h.coach.choosePlacementAnswer(choice)
            assertEquals(choice == item.answerIndex, h.ui.placement!!.lastCorrect)
            h.coach.choosePlacementAnswer(choice) // 0.8초 안의 두 번째 탭은 무시
            h.advance(800)
        }
        assertEquals(12, h.ui.placement!!.index)
        assertNull(h.ui.placement!!.item)
        val result = h.ui.placement!!.result!!
        assertEquals(9, result.correct)
        h.coach.finishPlacement()
        assertEquals(CoachPhase.IDLE, h.ui.phase)
        assertNull(h.ui.placement)
        val p = h.repo.state.value.profile!!
        assertTrue(p.placementDone)
        assertEquals(result.level, p.level)
        assertEquals(9, p.placementCorrect)
        assertTrue(h.feedback.pulses.contains(PulseKind.GREEN))
    }

    @Test fun placementNeedsParkingAndPausesWhenDrivingStarts() {
        val h = h(LearnerState(profile = TestContent.profile(placementDone = false), settings = LearnerSettings(autoStartOnCommute = false)))
        h.coach.startPlacement()
        val item = h.ui.placement!!.item!!
        h.coach.choosePlacementAnswer(item.answerIndex)
        h.advance(800)
        assertEquals(1, h.ui.placement!!.index)

        h.driveMorning()
        assertEquals(CoachPhase.IDLE, h.ui.phase)
        assertNull(h.ui.placement)
        assertEquals("정차 후 이용할 수 있어요", h.ui.toast?.textKo)
        h.coach.startPlacement()
        assertEquals(CoachPhase.IDLE, h.ui.phase)

        h.park()
        h.advance(4_500)
        h.coach.startPlacement()
        assertEquals(CoachPhase.PLACEMENT, h.ui.phase)
        assertEquals(1, h.ui.placement!!.index)
    }

    @Test fun commuteDriveAutoStartsAfterThreeSecondsOfMoving() {
        val h = h(LearnerState(profile = TestContent.profile(), settings = LearnerSettings(autoStartOnCommute = true)))
        h.driveMorning(driveId = "drive-9")
        h.advance(2_500)
        assertNull(h.ui.session)
        h.advance(1_000)
        assertNotNull(h.ui.session)
        assertEquals(CoachPhase.BRIEFING, h.ui.phase)
        assertEquals(CommuteSlot.MORNING, h.planner.requests.last().slot)
        assertEquals(1_920, h.planner.requests.last().routeRemainingSec)
        // 출근길은 묻지 않고 바로 시작한다 (묻는 건 퇴근길뿐)
        assertFalse(h.ui.eveningAsk.visible)
        assertFalse(h.speech.texts().contains(Say.EVENING_ASK))
        // 같은 주행에서 세션을 끝내면 다시 자동 시작하지 않는다
        h.coach.stopSession()
        h.advance(5_000)
        assertNull(h.ui.session)
    }

    @Test fun onboardingCreatesProfileAndShowsPlacementSuggestion() {
        val h = h(LearnerState())
        assertTrue(h.ui.ready)
        assertEquals(CoachPhase.ONBOARDING, h.ui.phase)
        h.coach.completeOnboarding(Track.TRAVEL, DailyGoal.LIGHT)
        val p = h.repo.state.value.profile!!
        assertEquals(Track.TRAVEL, p.track)
        assertEquals(DailyGoal.LIGHT, p.goal)
        assertFalse(p.placementDone)
        assertEquals(CoachPhase.IDLE, h.ui.phase)
        assertEquals(SuggestionKind.PLACEMENT_NEEDED, h.ui.suggestion?.kind)
        h.coach.skipPlacement(Level.ADVANCED)
        assertEquals(Level.ADVANCED, h.repo.state.value.profile!!.level)
        assertTrue(h.repo.state.value.profile!!.placementDone)
    }

    @Test fun saveExpressionReviewNowAndEraseAll() {
        val h = h()
        h.script(TestContent.sentence to PlanKind.NEW, TestContent.vocab to PlanKind.NEW)
        h.driveMorning()
        assertTrue(h.startAndListen())
        val saved = h.coach.saveExpression()
        assertTrue(saved.ok)
        assertEquals("표현을 저장했어요", h.ui.toast?.textKo)
        assertEquals(TestContent.sentence.modelAnswer, h.repo.state.value.saved.single().text)
        h.coach.saveExpression(TestContent.sentence.modelAnswer.uppercase())
        assertEquals(1, h.repo.state.value.saved.size)

        assertFalse(h.coach.reviewNow().ok)

        h.coach.eraseAllData()
        assertEquals(CoachPhase.ONBOARDING, h.ui.phase)
        assertNull(h.ui.session)
        assertNull(h.ui.profile)
        assertEquals(1, h.repo.erased)
        assertEquals("학습 기록을 모두 지웠어요", h.ui.toast?.textKo)
    }

    @Test fun parkedLessonShowsEnglishChoicesAndAcceptsTap() {
        val h = h()
        h.script(TestContent.sentence to PlanKind.NEW)
        assertTrue(h.startAndListen())
        val p = h.ui.prompt!!
        assertEquals(3, p.choices.size)
        assertTrue(p.choices.contains(TestContent.sentence.modelAnswer))
        assertNull(p.explanationKo)
        assertEquals(ScreenMode.FULL, h.ui.screenMode)
        // SENTENCE_BUILD 기본 12초 (정차 상한 60초)
        assertEquals(12_000L, h.speech.listens.last().second)
        h.coach.chooseOption(p.choices.indexOf(TestContent.sentence.modelAnswer))
        val o = h.progression.outcomes.single()
        assertEquals(Verdict.CORRECT, o.result.verdict)
        assertFalse(o.driving)
    }

    @Test fun parkedHomeShowsPlanPreviewAndReviewSuggestion() {
        val h = h(LearnerState(
            profile = TestContent.profile(),
            settings = LearnerSettings(autoStartOnCommute = false),
            cards = TestContent.dueCards(10, com.cartutor.engineglish.english.core.VirtualTime.START_MS),
        ))
        assertEquals(CoachPhase.IDLE, h.ui.phase)
        assertEquals("출근길 32분 → 새 표현 6개 + 복습 10개", h.ui.nextPlanPreviewKo)
        assertEquals(10, h.ui.library.dueSoon.size)
        assertEquals("지금 복습", h.ui.library.dueSoon.first().tagKo)
        val s = h.ui.suggestion!!
        assertEquals(SuggestionKind.REVIEW_DUE, s.kind)
        assertEquals("복습할 표현이 10개 있어요", s.titleKo)

        h.coach.acceptSuggestion(s.id)
        assertNotNull(h.ui.session)
        assertTrue(h.planner.requests.last().reviewOnly)
        assertEquals("복습 10개를 시작할게요.", h.speech.spoken.first().text)
        assertTrue(h.waitPhase(CoachPhase.LISTENING))
        // 정차 중 복습은 보기 탭으로도 답할 수 있다
        assertEquals(3, h.ui.prompt?.choices?.size)
    }

    @Test fun demoAnswersAreBuiltFromTheCurrentItem() {
        val s = TestContent.sentence
        assertEquals("Can we push the meeting to tomorrow?", DemoAnswers.make(s, DemoAnswerKind.CORRECT))
        assertEquals("Can we push the to tomorrow?", DemoAnswers.make(s, DemoAnswerKind.PARTIAL))
        assertEquals("Can we pull the meeting tomorrow?", DemoAnswers.make(s, DemoAnswerKind.WRONG))
        assertEquals("Can we push the meeting to tomollow?", DemoAnswers.make(s, DemoAnswerKind.L1_ERROR))
        assertEquals("I don't know", DemoAnswers.make(s, DemoAnswerKind.DONT_KNOW))
        assertEquals("light", DemoAnswers.make(TestContent.minimal, DemoAnswerKind.L1_ERROR))
        assertEquals("postpones", DemoAnswers.make(TestContent.vocab, DemoAnswerKind.PARTIAL))
        assertEquals(DemoAnswers.NO_IDEA, DemoAnswers.make(TestContent.repeat, DemoAnswerKind.WRONG))
        assertEquals("Sank you", DemoAnswers.make(TestContent.vocab.copy(answers = listOf("Thank you"), keywords = listOf("thank")), DemoAnswerKind.L1_ERROR))
    }

    @Test fun demoL1ErrorFallsBackToFinalConsonantDropWhenNoSwapApplies() {
        // 치환 규칙(th/sh/r/f/v/z)이 걸리는 단어가 없는 문장 — 예전엔 정답을 그대로 돌려줘 '정답'으로 채점됐다
        val item = TestContent.sentence.copy(answers = listOf("Let me check my schedule."), keywords = listOf("check"))
        val l1 = DemoAnswers.make(item, DemoAnswerKind.L1_ERROR)
        assertEquals("Let me chec my schedule.", l1)
        val issue = com.cartutor.engineglish.english.speech.KoreanL1PronunciationAnalyzer().detect("check", "chec")
        assertEquals(com.cartutor.engineglish.english.core.PronIssueType.FINAL_CONSONANT_DROP, issue?.type)
    }
}
