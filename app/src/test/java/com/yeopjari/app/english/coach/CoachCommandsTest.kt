package com.yeopjari.app.english.coach

import com.yeopjari.app.english.core.CoachPhase
import com.yeopjari.app.english.core.DemoAnswerKind
import com.yeopjari.app.english.core.Grade
import com.yeopjari.app.english.core.LearnerCommand
import com.yeopjari.app.english.core.MediaKey
import com.yeopjari.app.english.core.PlanKind
import com.yeopjari.app.english.core.PronIssueType
import com.yeopjari.app.english.core.PulseKind
import com.yeopjari.app.english.core.SpeechLang
import com.yeopjari.app.english.core.SpeechRate
import com.yeopjari.app.english.core.Verdict
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 음성 명령 · 핸들 버튼 · 시도 규칙 */
class CoachCommandsTest {
    private var harness: CoachHarness? = null

    private fun listening(vararg items: Pair<com.yeopjari.app.english.core.LearningItem, PlanKind>): CoachHarness {
        val h = CoachHarness().start().also { harness = it }
        h.script(*items)
        h.driveMorning()
        assertTrue(h.startAndListen())
        return h
    }

    @After fun noTurnErrors() {
        assertNull("세션 턴 오류", harness?.coach?.lastError)
    }

    @Test fun repeatReSpeaksPromptWithoutUsingAnAttempt() {
        val h = listening(TestContent.repeat to PlanKind.NEW, TestContent.vocab to PlanKind.NEW)
        val mark = h.speech.spoken.size
        h.coach.onCommand(LearnerCommand.REPEAT)
        assertTrue(h.waitPhase(CoachPhase.LISTENING, 10_000))
        val again = h.speech.spoken.drop(mark)
        assertEquals(listOf(TestContent.repeat.promptKo, "Traffic is really heavy this morning."), again.map { it.text })
        assertEquals(listOf(SpeechLang.KO, SpeechLang.EN), again.map { it.lang })
        assertEquals(1, h.ui.prompt?.attempt)
        assertTrue(h.progression.outcomes.isEmpty())
    }

    @Test fun slowerRepeatsEnglishPartSlowly() {
        val h = listening(TestContent.repeat to PlanKind.NEW)
        h.coach.onAnswerText("slower")
        assertTrue(h.waitPhase(CoachPhase.LISTENING, 10_000))
        val slow = h.speech.spoken.last()
        assertEquals("Traffic is really heavy this morning.", slow.text)
        assertEquals(SpeechRate.SLOW, slow.rate)
        assertTrue(h.progression.outcomes.isEmpty())
    }

    @Test fun skipByVoiceAndNextKeyMovesOnWithoutGrade() {
        val h = listening(TestContent.vocab to PlanKind.NEW, TestContent.expression to PlanKind.NEW, TestContent.sentence to PlanKind.NEW)
        h.coach.onAnswerText("skip")
        assertTrue(h.speech.texts().contains("넘어갈게요."))
        assertTrue(h.advanceUntil(10_000) { h.ui.prompt?.itemId == "day.b1.02" && h.ui.phase == CoachPhase.LISTENING })

        h.keys.inject(MediaKey.NEXT)
        assertTrue(h.advanceUntil(10_000) { h.ui.prompt?.itemId == "day.b1.03" && h.ui.phase == CoachPhase.LISTENING })
        assertTrue(h.progression.outcomes.isEmpty())
        assertEquals(0, h.ui.session?.answered)
    }

    @Test fun previousKeyRepeatsAndPlayPauseToggles() {
        val h = listening(TestContent.expression to PlanKind.NEW)
        val mark = h.speech.spoken.size
        h.keys.inject(MediaKey.PREVIOUS)
        assertTrue(h.waitPhase(CoachPhase.LISTENING, 10_000))
        assertEquals(TestContent.expression.promptKo, h.speech.spoken[mark].text)

        h.keys.inject(MediaKey.PLAY_PAUSE)
        assertEquals(CoachPhase.PAUSED, h.ui.phase)
        h.advance(200)
        assertEquals(Say.PAUSED, h.speech.spoken.last().text)
        h.advance(20_000)
        assertEquals(CoachPhase.PAUSED, h.ui.phase)

        val m2 = h.speech.spoken.size
        h.keys.inject(MediaKey.PLAY_PAUSE)
        assertTrue(h.waitPhase(CoachPhase.LISTENING, 10_000))
        assertEquals(listOf("다시 할게요.", TestContent.expression.promptKo), h.speech.textsSince(m2))
    }

    @Test fun hintIsTwoSpeechCallsAndCapsGradeAtHard() {
        val h = listening(TestContent.expression to PlanKind.NEW)
        val mark = h.speech.spoken.size
        h.coach.onCommand(LearnerCommand.HINT)
        assertTrue(h.waitPhase(CoachPhase.LISTENING, 10_000))
        val said = h.speech.spoken.drop(mark)
        assertEquals("힌트!", said[0].text)
        assertEquals(SpeechLang.KO, said[0].lang)
        assertEquals("running", said[1].text)
        assertEquals(SpeechLang.EN, said[1].lang)
        assertTrue(h.ui.prompt!!.hintShown)
        assertEquals("running", h.ui.hud.hint)

        h.speech.injectLatencyMs = 500
        h.coach.onAnswerText("I'm running late.")
        assertEquals(Grade.HARD, h.progression.outcomes.single().grade)
    }

    @Test fun meaningSpeaksKoreanMeaning() {
        val h = listening(TestContent.vocab to PlanKind.NEW)
        h.coach.onAnswerText("meaning")
        assertTrue(h.waitPhase(CoachPhase.LISTENING, 10_000))
        assertTrue(h.speech.texts().contains("뜻은 ‘미루다, 연기하다’예요."))
    }

    @Test fun dontKnowRevealsAndGradesAgain() {
        val h = listening(TestContent.sentence to PlanKind.NEW)
        h.coach.injectDemoAnswer(DemoAnswerKind.DONT_KNOW)
        val o = h.progression.outcomes.single()
        assertEquals(Grade.AGAIN, o.grade)
        assertEquals(Verdict.WRONG, o.result.verdict)
        h.advance(100)
        val said = h.speech.texts()
        assertTrue(said.contains("이렇게 말해요."))
        h.advance(4_000)
        assertTrue(h.speech.spoken.any { it.lang == SpeechLang.EN && it.text == TestContent.sentence.modelAnswer })
        assertEquals(listOf(PulseKind.AMBER), h.feedback.pulses)
    }

    @Test fun wrongThenCorrectOnSecondAttemptIsHard() {
        val h = listening(TestContent.sentence to PlanKind.NEW)
        h.coach.onAnswerText("banana")
        assertTrue(h.progression.outcomes.isEmpty())
        assertTrue(h.advanceUntil(15_000) { h.ui.phase == CoachPhase.LISTENING && h.ui.prompt?.attempt == 2 })
        val said = h.speech.texts()
        assertTrue(said.contains("아쉬워요. 이렇게 말해요."))
        assertTrue(said.contains(TestContent.sentence.modelAnswer))
        assertTrue(said.contains("한 번 더 말해 볼까요?"))
        // 운전 중엔 해설 글을 보여 주지 않는다 (정차 화면에서만)
        assertNull(h.ui.prompt?.explanationKo)

        h.coach.injectDemoAnswer(DemoAnswerKind.CORRECT)
        val o = h.progression.outcomes.single()
        assertEquals(Verdict.CORRECT, o.result.verdict)
        assertEquals(Grade.HARD, o.grade)
        assertEquals(listOf(PulseKind.AMBER, PulseKind.GREEN), h.feedback.pulses)
    }

    @Test fun l1ErrorGetsPronunciationFeedbackAndTip() {
        val h = listening(TestContent.minimal to PlanKind.DRILL)
        assertEquals(6_000L, h.speech.listens.last().second)
        h.coach.injectDemoAnswer(DemoAnswerKind.L1_ERROR)
        assertEquals(listOf("light"), h.speech.injected)
        val fb = h.ui.feedback!!
        assertEquals(Verdict.PARTIAL, fb.verdict)
        assertEquals(PronIssueType.R_L, fb.pronIssues.single().type)
        assertTrue(h.speech.texts().contains("‘right’가 ‘light’로 들렸어요."))
        assertTrue(h.advanceUntil(10_000) { h.speech.texts().contains("R은 혀끝을 말아요.") })
        assertTrue(h.advanceUntil(10_000) { h.ui.phase == CoachPhase.LISTENING && h.ui.prompt?.attempt == 2 })
    }

    @Test fun bargeInWhilePromptingGoesStraightToListening() {
        val h = CoachHarness().start().also { harness = it }
        h.script(TestContent.sentence to PlanKind.NEW)
        h.driveMorning()
        h.coach.startSession()
        assertTrue(h.advanceUntil(20_000) { h.ui.phase == CoachPhase.PROMPTING && h.speech.speaking.value })
        h.coach.onAnswerText(TestContent.sentence.modelAnswer)
        h.advance(50)
        assertEquals(Verdict.CORRECT, h.progression.outcomes.single().result.verdict)
    }

    @Test fun endSessionByVoiceShowsSummary() {
        val h = listening(TestContent.vocab to PlanKind.NEW, TestContent.expression to PlanKind.NEW)
        h.coach.onAnswerText("postpone")
        assertTrue(h.advanceUntil(10_000) { h.ui.prompt?.itemId == "day.b1.02" && h.ui.phase == CoachPhase.LISTENING })
        h.coach.onAnswerText("end session")
        assertEquals(CoachPhase.SUMMARY, h.ui.phase)
        assertEquals(1, h.ui.summary?.answered)
        h.advance(100)
        assertEquals("오늘은 여기까지 할게요. 1문제 중 1개 맞혔어요. 수고했어요!", h.speech.spoken.last().text)
    }
}
