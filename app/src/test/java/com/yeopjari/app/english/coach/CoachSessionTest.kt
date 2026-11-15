package com.yeopjari.app.english.coach

import com.yeopjari.app.english.core.CoachPhase
import com.yeopjari.app.english.core.CommuteSlot
import com.yeopjari.app.english.core.GearPosition
import com.yeopjari.app.english.core.Grade
import com.yeopjari.app.english.core.HudIcon
import com.yeopjari.app.english.core.LearnerSettings
import com.yeopjari.app.english.core.LearnerState
import com.yeopjari.app.english.core.LoadLevel
import com.yeopjari.app.english.core.PlanKind
import com.yeopjari.app.english.core.PulseKind
import com.yeopjari.app.english.core.ScreenMode
import com.yeopjari.app.english.core.SlotMode
import com.yeopjari.app.english.core.SoundCue
import com.yeopjari.app.english.core.SpeechLang
import com.yeopjari.app.english.core.TurnSignalState
import com.yeopjari.app.english.core.Verdict
import com.yeopjari.app.english.core.VirtualTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 코치 세션 시나리오 — 가상 시간 + 가짜 포트 (명세 §14 coach) */
class CoachSessionTest {
    private var harness: CoachHarness? = null

    private fun h(state: LearnerState? = null): CoachHarness =
        (if (state == null) CoachHarness() else CoachHarness(state)).start().also { harness = it }

    @After fun noTurnErrors() {
        assertNull("세션 턴 오류", harness?.coach?.lastError)
    }

    @Test fun morningCommuteBriefingUsesPlanAndRouteTime() {
        val h = h(LearnerState(
            profile = TestContent.profile(),
            settings = LearnerSettings(autoStartOnCommute = false),
            cards = TestContent.dueCards(10, VirtualTime.START_MS),
        ))
        h.driveMorning()
        h.advance(500)
        assertEquals(ScreenMode.DRIVE, h.ui.screenMode)
        assertEquals(LoadLevel.NORMAL, h.ui.load.level)

        val res = h.coach.startSession()
        assertTrue(res.ok)
        assertEquals(CoachPhase.BRIEFING, h.ui.phase)
        assertEquals("출근길 32분 → 새 표현 6개 + 복습 10개", h.ui.session?.titleKo)
        assertEquals("출근길 32분, 새 표현 6개와 복습 10개로 시작할게요.", h.speech.spoken.first().text)
        val req = h.planner.requests.last()
        assertEquals(CommuteSlot.MORNING, req.slot)
        assertEquals(SlotMode.NEW_EXPRESSIONS, req.mode)
        assertEquals(1_920, req.routeRemainingSec)
        assertFalse(req.parked)
        assertEquals(listOf(true), h.keys.activeCalls)
        assertEquals(CommuteSlot.MORNING, h.ui.drive.slot)
        assertEquals(32, h.ui.drive.routeRemainingMin)
        assertEquals(3, h.ui.drive.routeStrip.size)
        assertTrue(h.ui.drive.routeStrip.first().current)

        // 첫 문항(복습)을 듣는 단계까지: 운전 중엔 영어 글자 · 보기 없음, HUD 힌트 한 단어
        assertTrue(h.waitPhase(CoachPhase.LISTENING))
        val p = h.ui.prompt!!
        assertEquals(PlanKind.REVIEW, p.kind)
        assertNull(p.displayEn)
        assertTrue(p.choices.isEmpty())
        assertEquals(HudIcon.LISTENING, h.ui.hud.icon)
        assertNotNull(h.ui.hud.hint)
        // VOCAB 기본 7초 < NORMAL 상한 12초
        assertEquals(7_000L, h.speech.listens.last().second)
    }

    @Test fun correctAnswerIsGradedOnceWithGreenPulse() {
        val h = h()
        h.script(TestContent.expression to PlanKind.NEW, TestContent.vocab to PlanKind.NEW)
        h.driveMorning()
        assertTrue(h.startAndListen())
        assertEquals("출근길 32분, 새 표현 2개로 시작할게요.", h.speech.spoken.first().text)
        assertEquals("day.b1.02", h.ui.prompt?.itemId)
        assertEquals("running", h.ui.hud.hint)
        assertEquals("running", h.feedback.hints.last())

        h.coach.onAnswerText("I'm running late.")
        assertEquals(1, h.progression.outcomes.size)
        val o = h.progression.outcomes.single()
        assertEquals(Verdict.CORRECT, o.result.verdict)
        assertEquals(Grade.GOOD, o.grade)
        assertTrue(o.isNew)
        assertTrue(o.driving)
        assertEquals(listOf(PulseKind.GREEN), h.feedback.pulses)
        assertEquals(CoachPhase.FEEDBACK, h.ui.phase)
        assertEquals(Verdict.CORRECT, h.ui.feedback?.verdict)
        assertEquals(1, h.ui.session?.answered)
        assertEquals(1, h.ui.session?.correct)
        assertEquals(1, h.ui.session?.newDone)
        assertNotNull(h.ui.pulse)
        // 정답 효과음은 채점 때 한 번
        assertEquals(listOf(SoundCue.CORRECT), h.sound.cues)
        assertEquals(1, h.ui.session?.streak)

        // 다음 문항으로
        assertTrue(h.advanceUntil(10_000) { h.ui.prompt?.itemId == "day.b1.01" && h.ui.phase == CoachPhase.LISTENING })
        h.speech.injectLatencyMs = 900
        h.coach.onAnswerText("postpone")
        assertEquals(Grade.EASY, h.progression.outcomes.last().grade)
    }

    @Test fun turnSignalDuringListeningHoldsAndResumesSameItemWithoutGrading() {
        val h = h()
        h.script(TestContent.expression to PlanKind.NEW, TestContent.vocab to PlanKind.NEW)
        h.driveMorning()
        assertTrue(h.startAndListen())
        val stops = h.speech.stopAllCount

        h.set { it.copy(turnSignal = TurnSignalState.LEFT) }
        assertEquals(LoadLevel.FOCUS, h.ui.load.level)
        assertTrue(h.ui.load.reasons.any { it.textKo == "방향지시등" })
        assertEquals(CoachPhase.HOLD, h.ui.phase)
        assertNull(h.ui.prompt)
        assertEquals(HudIcon.HOLD, h.ui.hud.icon)
        assertTrue(h.speech.stopAllCount > stops)
        assertFalse(h.speech.isListening)
        h.advance(3_000)
        assertEquals(CoachPhase.HOLD, h.ui.phase)
        assertTrue(h.progression.outcomes.isEmpty())

        val mark = h.speech.spoken.size
        h.set { it.copy(turnSignal = TurnSignalState.OFF) }
        h.advance(3_900)
        assertEquals(CoachPhase.HOLD, h.ui.phase)
        assertEquals(mark, h.speech.spoken.size)
        assertTrue(h.waitPhase(CoachPhase.LISTENING, 10_000))
        val said = h.speech.textsSince(mark)
        assertEquals("아까 그 문제 다시 할게요.", said.first())
        assertTrue(said.contains(TestContent.expression.promptKo))
        assertEquals("day.b1.02", h.ui.prompt?.itemId)
        assertEquals(1, h.ui.session?.interrupted)
        assertTrue(h.progression.outcomes.isEmpty())

        h.coach.onAnswerText("I'm running late.")
        assertEquals(1, h.progression.outcomes.size)
        assertEquals(Verdict.CORRECT, h.progression.outcomes.single().result.verdict)
    }

    @Test fun criticalStopsTtsImmediatelyAndRecoversThroughFocus() {
        val h = h()
        h.script(TestContent.sentence to PlanKind.NEW)
        h.driveMorning()
        h.coach.startSession()
        assertTrue(h.advanceUntil(20_000) { h.ui.phase == CoachPhase.PROMPTING && h.speech.speaking.value })
        h.advance(300)
        val utterance = h.speech.spoken.last()
        assertEquals(TestContent.sentence.promptKo, utterance.text)
        val now = h.t.nowMs()

        h.set { it.copy(accelMps2 = -5.5f, brakePedal = true) }
        assertEquals(LoadLevel.CRITICAL, h.ui.load.level)
        assertEquals(now, utterance.stoppedAtMs)
        assertFalse(h.speech.speaking.value)
        assertEquals(CoachPhase.HOLD, h.ui.phase)

        h.set { it.copy(accelMps2 = 0f, brakePedal = false) }
        h.advance(2_900)
        assertEquals(LoadLevel.CRITICAL, h.ui.load.level)
        assertTrue(h.advanceUntil(5_000) { h.ui.load.level == LoadLevel.FOCUS })
        assertEquals(CoachPhase.HOLD, h.ui.phase)
        assertTrue(h.advanceUntil(6_000) { h.ui.load.level == LoadLevel.NORMAL })
        assertTrue(h.waitPhase(CoachPhase.LISTENING, 10_000))
        assertTrue(h.speech.texts().contains("아까 그 문제 다시 할게요."))
        assertTrue(h.progression.outcomes.isEmpty())
    }

    @Test fun focusLetsShortPhraseFinishThenHolds() {
        val h = h()
        h.script(TestContent.expression to PlanKind.NEW, TestContent.vocab to PlanKind.NEW)
        h.driveMorning()
        assertTrue(h.startAndListen())
        h.coach.onAnswerText("I'm running late.")
        val praise = h.speech.spoken.last()
        assertEquals("완벽해요!", praise.text)

        h.set { it.copy(turnSignal = TurnSignalState.RIGHT) }
        assertEquals(LoadLevel.FOCUS, h.ui.load.level)
        assertEquals(CoachPhase.FEEDBACK, h.ui.phase)
        assertTrue(h.waitPhase(CoachPhase.HOLD, 3_000))
        assertNull(praise.stoppedAtMs)
        assertNotNull(praise.endedAtMs)
        assertEquals(0, h.ui.session?.interrupted)
    }

    @Test fun relaxedAllowsToeicWhileNormalDefersIt() {
        val h = h()
        h.script(TestContent.toeic to PlanKind.NEW, TestContent.vocab to PlanKind.NEW)
        h.driveMorning()
        assertTrue(h.startAndListen())
        assertEquals("day.b1.01", h.ui.prompt?.itemId)
        h.coach.onAnswerText("postpone")
        h.advance(3_000)
        assertNull(h.ui.prompt)

        h.set { it.copy(cruiseActive = true, speedKph = 90f) }
        assertTrue(h.advanceUntil(15_000) { h.ui.prompt?.itemId == "day.b1.06" && h.ui.phase == CoachPhase.LISTENING })
        assertEquals(LoadLevel.RELAXED, h.ui.load.level)
        assertEquals(30_000L, h.speech.listens.last().second)
        val en = h.speech.spoken.last { it.lang == SpeechLang.EN }
        assertEquals("How do you usually get to work?", en.text)
    }

    @Test fun twoSilencesRevealWithoutGradeAndRequeueOnce() {
        val h = h()
        h.script(TestContent.vocab to PlanKind.NEW, TestContent.expression to PlanKind.NEW)
        h.driveMorning()
        assertTrue(h.startAndListen())
        assertEquals(7_000L, h.speech.listens.last().second)

        h.advance(7_100)
        assertTrue(h.speech.texts().contains("다시 들려드릴게요."))
        assertTrue(h.advanceUntil(20_000) { h.ui.prompt?.itemId == "day.b1.02" && h.ui.phase == CoachPhase.LISTENING })
        assertTrue(h.progression.outcomes.isEmpty())
        assertTrue(h.speech.spoken.any { it.lang == SpeechLang.EN && it.text == "postpone" })
        assertEquals(0, h.ui.session?.answered)

        h.coach.onAnswerText("I'm running late.")
        assertTrue(h.advanceUntil(20_000) { h.ui.prompt?.itemId == "day.b1.01" && h.ui.phase == CoachPhase.LISTENING })
        assertTrue(h.advanceUntil(40_000) { h.progression.outcomes.size == 2 })
        val last = h.progression.outcomes.last()
        assertEquals("day.b1.01", last.item.id)
        assertEquals(Grade.AGAIN, last.grade)
        assertEquals(Verdict.NO_ANSWER, last.result.verdict)
    }

    @Test fun lessonEndsOneMinuteBeforeArrivalWithClosingLineAndNoMoreQuestions() {
        val h = h()
        h.script(TestContent.vocab to PlanKind.NEW, TestContent.expression to PlanKind.NEW, TestContent.sentence to PlanKind.NEW)
        h.driveMorning(remainingSec = 600)
        assertTrue(h.startAndListen())
        h.coach.onAnswerText("postpone")
        assertTrue(h.waitListening("day.b1.02"))

        // 61초 남음: 아직 묻는다
        h.set { it.copy(routeRemainingSec = 61) }
        assertEquals(CoachPhase.LISTENING, h.ui.phase)
        assertNotNull(h.ui.session)
        val outcomes = h.progression.outcomes.size
        val listens = h.speech.listens.size

        // 60초 남음: 묻던 문항은 채점하지 않고 접는다 — 더 묻지 않고 마무리 인사만
        h.set { it.copy(routeRemainingSec = 60) }
        assertEquals(CoachPhase.SUMMARY, h.ui.phase)
        assertNull(h.ui.session)
        assertNull(h.ui.prompt)
        assertFalse(h.speech.isListening)
        h.advance(200)
        assertEquals("곧 도착해요. 오늘 학습은 여기까지 할게요.", h.speech.spoken.last().text)

        // 도착할 때까지 아무것도 더 묻지 않는다 ('오늘의 한 문장' 따라 말하기도 없다)
        h.set { it.copy(routeRemainingSec = 30) }
        h.advance(20_000)
        assertEquals(outcomes, h.progression.outcomes.size)
        assertEquals(listens, h.speech.listens.size)
        assertEquals("곧 도착해요. 오늘 학습은 여기까지 할게요.", h.speech.spoken.last().text)
        assertEquals(1, h.speech.spoken.count { it.text == Say.ARRIVING_SOON })
        assertFalse(h.speech.texts().any { it.contains("오늘의 한 문장") })
        assertEquals(1, h.ui.summary?.answered)
        assertEquals(1, h.progression.sessions.size)
        // 도착 직전에 끝낸 출근길도 출퇴근 패턴으로 배운다
        assertEquals(1, h.commute.learned.size)
        assertEquals(false, h.keys.activeCalls.last())

        // 도착 1분 안쪽에서는 새로 시작하지도 않는다
        val res = h.coach.startSession()
        assertFalse(res.ok)
        assertEquals("곧 도착해요. 도착한 뒤에 시작해 주세요", h.ui.toast?.textKo)
        assertNull(h.ui.session)
    }

    @Test fun closingLineWaitsWhileLoadIsFocus() {
        val h = h()
        h.script(TestContent.vocab to PlanKind.NEW, TestContent.expression to PlanKind.NEW)
        h.driveMorning(remainingSec = 300)
        assertTrue(h.startAndListen())
        h.coach.onAnswerText("postpone")
        h.advance(500)
        // 도착 직전 우회전 — 운전 집중 구간에서 60초가 된다
        h.set { it.copy(turnSignal = TurnSignalState.RIGHT) }
        assertTrue(h.waitPhase(CoachPhase.HOLD, 3_000))
        h.set { it.copy(routeRemainingSec = 60) }
        assertEquals(CoachPhase.SUMMARY, h.ui.phase)
        h.advance(3_000)
        assertFalse(h.speech.texts().contains(Say.ARRIVING_SOON))
        // 부하가 내려간 뒤에 한마디
        h.set { it.copy(turnSignal = TurnSignalState.OFF) }
        assertTrue(h.advanceUntil(8_000) { h.speech.texts().contains(Say.ARRIVING_SOON) })
        assertEquals(LoadLevel.NORMAL, h.ui.load.level)
    }

    @Test fun arrivalEndsWithSummaryAndRecords() {
        val h = h()
        h.script(TestContent.vocab to PlanKind.NEW, TestContent.expression to PlanKind.NEW)
        h.driveMorning()
        assertTrue(h.startAndListen())
        h.coach.onAnswerText("postpone")
        h.advance(1_000)

        h.set { it.copy(arrived = true, gear = GearPosition.P, speedKph = 0f, routeActive = false, uxRestricted = false) }
        assertEquals(CoachPhase.SUMMARY, h.ui.phase)
        assertNull(h.ui.session)
        val s = h.ui.summary!!
        assertEquals("출근길 세션 완료", s.titleKo)
        assertEquals(1, s.answered)
        assertEquals(1, s.correct)
        assertEquals(1, s.newLearned)
        assertNotNull(s.fluency)
        assertEquals(1, h.progression.sessions.size)
        assertEquals(1, h.repo.state.value.sessions.size)
        assertEquals(1, h.commute.learned.size)
        assertEquals(false, h.keys.activeCalls.last())

        // 요약을 닫아도, 정차 화면이 되면 주차 대시보드가 뜨고 그때 인사한다
        h.coach.dismissSummary()
        assertEquals(CoachPhase.IDLE, h.ui.phase)
        assertNull(h.ui.summary)
        assertFalse(h.ui.showParkedDashboard)
        assertTrue(h.advanceUntil(6_000) { h.ui.showParkedDashboard })
        assertEquals(1, h.ui.dashboard?.answered)
        assertTrue(h.advanceUntil(4_000) { h.speech.texts().contains("도착했어요. 오늘 1문제 중 1개 맞혔어요. 수고했어요!") })
    }

    @Test fun answerTextDuringHoldIsIgnoredAndPauseStaysSilent() {
        val h = h()
        h.script(TestContent.expression to PlanKind.NEW)
        h.driveMorning()
        assertTrue(h.startAndListen())
        h.set { it.copy(turnSignal = TurnSignalState.LEFT) }
        assertEquals(CoachPhase.HOLD, h.ui.phase)

        val injected = h.speech.injected.size
        h.coach.onAnswerText("I'm running late.")
        assertEquals("지금은 답을 받지 않아요", h.ui.toast?.textKo)
        assertEquals(injected, h.speech.injected.size)
        assertTrue(h.progression.outcomes.isEmpty())
        assertEquals(CoachPhase.HOLD, h.ui.phase)

        val spoken = h.speech.spoken.size
        h.coach.onAnswerText("pause")
        assertEquals(CoachPhase.PAUSED, h.ui.phase)
        h.set { it.copy(turnSignal = TurnSignalState.OFF) }
        h.advance(8_000)
        assertEquals(CoachPhase.PAUSED, h.ui.phase)
        assertEquals(spoken, h.speech.spoken.size)

        h.coach.resume()
        assertTrue(h.waitPhase(CoachPhase.LISTENING, 10_000))
        assertEquals("다시 할게요.", h.speech.textsSince(spoken).first())
        assertEquals("day.b1.02", h.ui.prompt?.itemId)
    }

    @Test fun parkingMidRouteKeepsSessionAsParkedLesson() {
        val h = h()
        h.script(TestContent.sentence to PlanKind.NEW, TestContent.vocab to PlanKind.NEW)
        h.driveMorning()
        assertTrue(h.startAndListen())
        assertTrue(h.ui.prompt!!.choices.isEmpty())
        h.park()
        assertTrue(h.advanceUntil(6_000) { h.ui.screenMode == ScreenMode.FULL })
        assertNotNull(h.ui.session)
        assertEquals("day.b1.03", h.ui.prompt?.itemId)
        assertEquals(3, h.ui.prompt?.choices?.size)
        assertTrue(h.progression.outcomes.isEmpty())
    }

    @Test fun arrivalDuringFocusGreetsWithDashboardAfterLoadDrops() {
        val h = h()
        h.script(TestContent.vocab to PlanKind.NEW, TestContent.expression to PlanKind.NEW)
        h.driveMorning()
        assertTrue(h.startAndListen())
        h.coach.onAnswerText("postpone")
        h.advance(1_000)
        h.set { it.copy(turnSignal = TurnSignalState.RIGHT) }
        assertEquals(LoadLevel.FOCUS, h.ui.load.level)
        h.set { it.copy(arrived = true, gear = GearPosition.P, speedKph = 0f, routeActive = false, uxRestricted = false, turnSignal = TurnSignalState.OFF) }
        assertEquals(CoachPhase.SUMMARY, h.ui.phase)
        val line = "도착했어요. 오늘 1문제 중 1개 맞혔어요. 수고했어요!"
        h.advance(500)
        assertFalse(h.speech.texts().contains(line))
        assertFalse(h.ui.showParkedDashboard)
        assertTrue(h.advanceUntil(8_000) { h.speech.texts().contains(line) })
        assertTrue(h.ui.showParkedDashboard)
        assertEquals(LoadLevel.PARKED, h.ui.load.level)
    }

    @Test fun sttUnavailableWarnsOnceInjectionStillWorksAndNothingIsGradedWrong() {
        val h = h()
        h.speech.failListenCode = "UNAVAILABLE_LANGUAGE"
        h.script(TestContent.vocab to PlanKind.NEW, TestContent.expression to PlanKind.NEW)
        h.driveMorning()
        assertTrue(h.startAndListen())
        h.advance(7_100)
        assertEquals("음성 인식을 쓸 수 없어요", h.ui.toast?.textKo)
        assertTrue(h.waitPhase(CoachPhase.LISTENING, 10_000))
        h.coach.injectDemoAnswer(com.yeopjari.app.english.core.DemoAnswerKind.CORRECT)
        assertEquals(Verdict.CORRECT, h.progression.outcomes.single().result.verdict)

        // 다음 문항은 끝까지 인식 실패 → 다시 묻고, 끝으로 한 번 보내고, 그래도 실패면 채점 없이 넘어간다
        assertTrue(h.advanceUntil(180_000) { h.ui.phase == CoachPhase.SUMMARY })
        assertEquals(1, h.progression.outcomes.size)
    }

    @Test fun planDoneWithRouteTimeLeftAsksToExtend() {
        val h = h()
        h.script(TestContent.vocab to PlanKind.NEW)
        h.planner.extendScript = listOf(com.yeopjari.app.english.core.PlannedItem(TestContent.expression.id, PlanKind.REVIEW))
        h.driveMorning()
        assertTrue(h.startAndListen())
        h.coach.onAnswerText("postpone")
        assertTrue("phase=${h.ui.phase} said=${h.speech.texts()} err=${h.coach.lastError}",
            h.advanceUntil(10_000) { h.ui.phase == CoachPhase.CONFIRM && h.speech.isListening })
        assertEquals(com.yeopjari.app.english.core.SuggestionKind.REVIEW_DUE, h.ui.suggestion?.kind)
        assertTrue(h.speech.texts().contains(Say.EXTEND_Q))
        h.coach.onCommand(com.yeopjari.app.english.core.LearnerCommand.YES)
        assertTrue(h.advanceUntil(10_000) { h.ui.prompt?.itemId == "day.b1.02" && h.ui.phase == CoachPhase.LISTENING })
        assertTrue(h.speech.texts().contains(Say.EXTEND_YES))
        h.coach.onAnswerText("I'm running late.")

        // 두 번째로 다 했을 때: 질문을 말하는 도중 '아니요'를 누르면 질문을 끊고 바로 요약
        assertTrue(h.advanceUntil(10_000) { h.ui.phase == CoachPhase.CONFIRM && h.speech.speaking.value })
        val question = h.speech.spoken.last()
        h.coach.dismissSuggestion(h.ui.suggestion!!.id)
        assertEquals(CoachPhase.SUMMARY, h.ui.phase)
        assertEquals(h.t.nowMs(), question.stoppedAtMs)
        assertEquals(2, h.ui.summary?.answered)
    }
}
