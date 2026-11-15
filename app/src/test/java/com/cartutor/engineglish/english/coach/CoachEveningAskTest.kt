package com.cartutor.engineglish.english.coach

import com.cartutor.engineglish.english.core.CoachPhase
import com.cartutor.engineglish.english.core.CommuteSlot
import com.cartutor.engineglish.english.core.EveningStudy
import com.cartutor.engineglish.english.core.LearnerSettings
import com.cartutor.engineglish.english.core.LearnerState
import com.cartutor.engineglish.english.core.LoadLevel
import com.cartutor.engineglish.english.core.MediaKey
import com.cartutor.engineglish.english.core.SlotMode
import com.cartutor.engineglish.english.core.SpeechLang
import com.cartutor.engineglish.english.core.TurnSignalState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 퇴근길 학습 질문 (명세 §7.1) — 바로 시작하지 않고 먼저 묻는다. 가상 시간 + 가짜 포트 */
class CoachEveningAskTest {
    private var harness: CoachHarness? = null

    private fun h(settings: LearnerSettings = LearnerSettings()): CoachHarness =
        CoachHarness(LearnerState(profile = TestContent.profile(), settings = settings)).start().also { harness = it }

    @After fun noErrors() {
        assertNull("코치 오류", harness?.coach?.lastError)
    }

    private fun asks(h: CoachHarness): Int = h.speech.spoken.count { it.text == Say.EVENING_ASK }

    /** 퇴근길 출발 → 3초 달린 뒤 질문 카드가 뜰 때까지 */
    private fun askShown(h: CoachHarness) {
        h.driveEvening()
        h.advance(2_500)
        assertFalse("3초 달리기 전에는 묻지 않는다", h.ui.eveningAsk.visible)
        h.advance(1_000)
        assertTrue(h.ui.eveningAsk.visible)
        assertEquals(1, h.ui.eveningAsk.attempt)
    }

    @Test fun eveningCommuteAsksFirstAndStartsOnVoiceYes() {
        val h = h()
        askShown(h)
        // 묻기만 하고 시작하지는 않는다
        assertNull(h.ui.session)
        assertEquals(CoachPhase.IDLE, h.ui.phase)
        assertEquals("퇴근길에도 영어 복습할까요?", h.speech.spoken.single().text)
        assertEquals(SpeechLang.KO, h.speech.spoken.single().lang)
        assertEquals("퇴근길에도 영어 복습할까요?", h.ui.eveningAsk.questionKo)
        assertEquals("할게요", h.ui.eveningAsk.yesLabelKo)
        assertEquals("오늘은 쉴게요", h.ui.eveningAsk.noLabelKo)
        assertFalse(h.ui.eveningAsk.listening)

        // 질문을 다 말하면 10초 동안 대답을 듣는다 (한국어)
        assertTrue(h.advanceUntil(5_000) { h.ui.eveningAsk.listening })
        assertEquals(SpeechLang.KO to 10_000L, h.speech.listens.last())
        assertNotNull(h.ui.eveningAsk.deadlineMs)

        h.speech.inject("응 할게")
        assertFalse(h.ui.eveningAsk.visible)
        assertNotNull(h.ui.session)
        assertEquals(CoachPhase.BRIEFING, h.ui.phase)
        val req = h.planner.requests.last()
        assertEquals(CommuteSlot.EVENING, req.slot)
        assertEquals(SlotMode.REVIEW, req.mode)
        assertEquals(1, asks(h))
    }

    @Test fun eveningAskNoByButtonSkipsSessionAndIsNotAskedAgainToday() {
        val h = h()
        askShown(h)
        val question = h.speech.spoken.last()
        // 질문을 말하는 도중에 '오늘은 쉴게요' 버튼 → 질문을 끊고 짧게 대답만
        h.coach.answerEveningAsk(false)
        assertFalse(h.ui.eveningAsk.visible)
        assertEquals(h.t.nowMs(), question.stoppedAtMs)
        assertNull(h.ui.session)
        h.advance(300)
        assertEquals("알겠어요. 오늘은 쉴게요.", h.speech.spoken.last().text)
        h.advance(30_000)
        assertNull(h.ui.session)
        assertFalse(h.ui.eveningAsk.visible)
        assertEquals(1, asks(h))

        // 중간에 세웠다가 다시 출발해도 (새 주행) 오늘은 다시 묻지 않는다
        h.park()
        h.advance(5_000)
        h.driveEvening(driveId = "drive-e2")
        h.advance(8_000)
        assertFalse(h.ui.eveningAsk.visible)
        assertNull(h.ui.session)
        assertEquals(1, asks(h))
    }

    @Test fun yesIsRememberedForTheRestOfTheEveningCommute() {
        val h = h()
        askShown(h)
        h.coach.answerEveningAsk(true)
        assertNotNull(h.ui.session)
        h.coach.stopSession()
        // 잠깐 세웠다가 다시 출발 → 묻지 않고 이어서 시작
        h.park()
        h.advance(5_000)
        h.driveEvening(driveId = "drive-e2")
        h.advance(3_500)
        assertNotNull(h.ui.session)
        assertFalse(h.ui.eveningAsk.visible)
        assertEquals(1, asks(h))
    }

    @Test fun noAnswerWithinTenSecondsReasksOnceThenGivesUpSilently() {
        val h = h()
        askShown(h)
        assertTrue(h.advanceUntil(5_000) { h.ui.eveningAsk.listening })
        val left = h.ui.eveningAsk.deadlineMs!! - h.t.nowMs()
        assertTrue("대답 창은 10초: $left", left in 9_900L..10_000L)
        h.advance(9_800)
        assertTrue(h.ui.eveningAsk.visible)
        h.advance(300)
        assertFalse("10초가 지나면 카드를 내린다", h.ui.eveningAsk.visible)
        assertNull(h.ui.session)

        // 잠시 뒤 한 번만 다시 묻는다
        assertTrue(h.advanceUntil(5_000) { h.ui.eveningAsk.visible })
        assertEquals(2, h.ui.eveningAsk.attempt)
        assertEquals(2, asks(h))

        // 또 답이 없으면 조용히 그만둔다 — 세션도, 아무 말도 없다
        h.advance(20_000)
        assertFalse(h.ui.eveningAsk.visible)
        assertNull(h.ui.session)
        h.advance(60_000)
        assertEquals("질문 두 번 말고는 아무 말도 하지 않는다", 2, h.speech.spoken.size)
        assertEquals(CoachPhase.IDLE, h.ui.phase)
        assertFalse(h.speech.isListening)
    }

    @Test fun focusLoadStopsTheQuestionAndReasksOnceWhenLoadDrops() {
        val h = h()
        askShown(h)
        val question = h.speech.spoken.last()
        // 질문 도중 방향지시등 → 바로 멈추고 카드를 내린다
        h.set { it.copy(turnSignal = TurnSignalState.LEFT) }
        assertEquals(LoadLevel.FOCUS, h.ui.load.level)
        assertFalse(h.ui.eveningAsk.visible)
        assertEquals(h.t.nowMs(), question.stoppedAtMs)
        assertFalse(h.speech.isListening)
        // 운전 집중 구간에서는 묻지 않는다
        h.advance(8_000)
        assertFalse(h.ui.eveningAsk.visible)
        assertEquals(1, asks(h))

        // 부하가 내려가면(히스테리시스 4초) 한 번 다시 묻는다
        h.set { it.copy(turnSignal = TurnSignalState.OFF) }
        h.advance(3_900)
        assertFalse(h.ui.eveningAsk.visible)
        assertTrue(h.advanceUntil(3_000) { h.ui.eveningAsk.visible })
        assertEquals(2, h.ui.eveningAsk.attempt)
        assertEquals(LoadLevel.NORMAL, h.ui.load.level)
        assertEquals(2, asks(h))

        h.coach.answerEveningAsk(true)
        assertNotNull(h.ui.session)
        assertFalse(h.ui.eveningAsk.visible)
    }

    @Test fun secondInterruptionGivesUpSilently() {
        val h = h()
        askShown(h)
        h.set { it.copy(turnSignal = TurnSignalState.LEFT) }
        h.set { it.copy(turnSignal = TurnSignalState.OFF) }
        assertTrue(h.advanceUntil(10_000) { h.ui.eveningAsk.visible && h.ui.eveningAsk.attempt == 2 })
        // 다시 묻는 중에 급제동 (CRITICAL)
        h.set { it.copy(accelMps2 = -5.5f, brakePedal = true) }
        assertEquals(LoadLevel.CRITICAL, h.ui.load.level)
        assertFalse(h.ui.eveningAsk.visible)
        assertFalse(h.speech.speaking.value)
        h.set { it.copy(accelMps2 = 0f, brakePedal = false) }
        h.advance(60_000)
        assertFalse(h.ui.eveningAsk.visible)
        assertNull(h.ui.session)
        assertEquals(2, asks(h))
        assertEquals(2, h.speech.spoken.size)
    }

    @Test fun settingAlwaysStartsEveningSessionWithoutAsking() {
        // 출근길 자동 시작을 꺼 둬도 퇴근길은 '퇴근길 학습' 설정만 따른다
        val h = h(LearnerSettings(eveningStudy = EveningStudy.ALWAYS, autoStartOnCommute = false))
        h.driveEvening()
        h.advance(3_500)
        assertNotNull(h.ui.session)
        assertEquals(CoachPhase.BRIEFING, h.ui.phase)
        assertFalse(h.ui.eveningAsk.visible)
        assertEquals(0, asks(h))
        assertEquals(CommuteSlot.EVENING, h.planner.requests.last().slot)
    }

    @Test fun settingNeverNeitherAsksNorStarts() {
        val h = h(LearnerSettings(eveningStudy = EveningStudy.NEVER, autoStartOnCommute = true))
        h.driveEvening()
        h.advance(30_000)
        assertNull(h.ui.session)
        assertFalse(h.ui.eveningAsk.visible)
        assertTrue(h.speech.spoken.isEmpty())
        // 직접 시작하는 건 언제든 된다
        assertTrue(h.coach.startSession().ok)
    }

    @Test fun defaultSettingIsAskAndMorningStillAutoStarts() {
        assertEquals(EveningStudy.ASK, LearnerSettings().eveningStudy)
        val h = h()
        h.driveMorning()
        h.advance(3_500)
        assertNotNull("출근길은 묻지 않고 자동 시작", h.ui.session)
        assertFalse(h.ui.eveningAsk.visible)
        assertEquals(0, asks(h))
    }

    @Test fun typedAnswersPlayButtonAndManualStartAlsoResolveTheQuestion() {
        // 타이핑·adb: 알아듣지 못하면 안내만, '낫 투데이'는 거절
        var h = h()
        askShown(h)
        h.coach.onAnswerText("음 글쎄")
        assertTrue(h.ui.eveningAsk.visible)
        assertEquals(Say.EVENING_HINT, h.ui.toast?.textKo)
        h.coach.onAnswerText("not today")
        assertFalse(h.ui.eveningAsk.visible)
        assertNull(h.ui.session)

        // 핸들 재생 버튼 = 할게요
        h = h()
        askShown(h)
        h.coach.onMediaKey(MediaKey.PLAY_PAUSE)
        assertNotNull(h.ui.session)
        assertFalse(h.ui.eveningAsk.visible)

        // 질문 중에 직접 세션을 시작하면 질문은 접는다
        h = h()
        askShown(h)
        assertTrue(h.coach.startSession().ok)
        assertFalse(h.ui.eveningAsk.visible)
        assertNotNull(h.ui.session)
        h.advance(30_000)
        assertEquals(1, asks(h))
    }

    @Test fun parkingOrArrivingSoonDropsTheQuestion() {
        var h = h()
        askShown(h)
        h.park()
        assertFalse(h.ui.eveningAsk.visible)
        h.advance(30_000)
        assertNull(h.ui.session)
        assertEquals(1, asks(h))

        // 도착까지 3분도 안 남았으면 처음부터 묻지 않는다
        h = h()
        h.driveEvening(remainingSec = 150)
        h.advance(10_000)
        assertFalse(h.ui.eveningAsk.visible)
        assertEquals(0, asks(h))
    }

    @Test fun eveningAnswersUnderstandKoreanAndEnglish() {
        val commands = FakeCommands()
        val yes = listOf(
            "응", "네", "네!", "예", "좋아", "좋아요", "할게", "할게요", "응 할게", "그래 하자", "yes", "Yes.", "Sure", "okay", "OK",
            "Okay, let's do it", "예스", "오케이", "no problem",
        )
        val no = listOf(
            "아니", "아니요", "괜찮아", "괜찮아요", "쉴게", "오늘은 쉴게요", "다음에", "다음에 할게요", "안 할래", "no", "No thanks",
            "not today", "Not today.", "maybe later", "노",
        )
        for (t in yes) assertEquals(t, true, EveningAnswers.parse(t, commands))
        for (t in no) assertEquals(t, false, EveningAnswers.parse(t, commands))
        for (t in listOf("", "  ", "음...", "복습할까요", "what")) assertNull(t, EveningAnswers.parse(t, commands))
    }
}
