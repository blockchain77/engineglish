package com.yeopjari.app.english.coach

import com.yeopjari.app.english.core.CoachPhase
import com.yeopjari.app.english.core.CommuteSlot
import com.yeopjari.app.english.core.GearPosition
import com.yeopjari.app.english.core.LearnerSettings
import com.yeopjari.app.english.core.LearnerState
import com.yeopjari.app.english.core.Level
import com.yeopjari.app.english.core.LoadAssessment
import com.yeopjari.app.english.core.LoadLevel
import com.yeopjari.app.english.core.PlanKind
import com.yeopjari.app.english.core.ProgressEvent
import com.yeopjari.app.english.core.PronIssueType
import com.yeopjari.app.english.core.PulseKind
import com.yeopjari.app.english.core.ScreenMode
import com.yeopjari.app.english.core.SoundCue
import com.yeopjari.app.english.core.Track
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 주차 대시보드 (명세 §7.2) · 효과음 (명세 §7.3) — 가상 시간 + 가짜 포트 */
class CoachDashboardTest {
    private var harness: CoachHarness? = null

    private fun h(state: LearnerState? = null): CoachHarness =
        (if (state == null) CoachHarness() else CoachHarness(state)).start().also { harness = it }

    @After fun noErrors() {
        assertNull("코치 오류", harness?.coach?.lastError)
    }

    // ───────────── 주차 대시보드 ─────────────

    @Test fun dashboardAppearsOnParkAfterDrivingSessionWithCorrectNumbers() {
        val h = h()
        h.script(
            TestContent.vocab to PlanKind.NEW, TestContent.expression to PlanKind.NEW,
            TestContent.sentence to PlanKind.NEW, TestContent.fillers[0] to PlanKind.NEW,
        )
        h.driveMorning()
        assertTrue(h.startAndListen())
        val startedMs = h.ui.session!!.startedAtMs

        h.coach.onAnswerText("postpone") // 정답 (+10 XP)
        assertTrue(h.waitListening("day.b1.02"))
        h.coach.onAnswerText("I'm lunning late.") // R/L — 거의 맞음 → 한 번 더
        assertTrue(h.advanceUntil(20_000) { h.ui.phase == CoachPhase.LISTENING && h.ui.prompt?.attempt == 2 })
        h.coach.onAnswerText("I'm lunning late.") // 또 거의 맞음 (+5 XP)
        assertTrue(h.waitListening("day.b1.03"))
        h.coach.onAnswerText("banana") // 오답 → 한 번 더
        assertTrue(h.advanceUntil(20_000) { h.ui.phase == CoachPhase.LISTENING && h.ui.prompt?.attempt == 2 })
        h.coach.onAnswerText("banana split") // 또 오답
        assertEquals(3, h.progression.outcomes.size)
        h.advance(1_000)
        assertNull("주행 중에는 대시보드가 없다", h.ui.dashboard)

        // 도착해서 P 단
        h.set { it.copy(arrived = true, gear = GearPosition.P, speedKph = 0f, routeActive = false, uxRestricted = false) }
        val endedMs = h.t.nowMs()
        assertEquals(CoachPhase.SUMMARY, h.ui.phase)
        assertNotNull(h.ui.dashboard)
        assertFalse("부하가 '정차'로 내려와 정차 화면이 될 때까지는 띄우지 않는다", h.ui.showParkedDashboard)
        assertTrue(h.sound.cues.none { it == SoundCue.SESSION_COMPLETE })

        assertTrue(h.advanceUntil(6_000) { h.ui.showParkedDashboard })
        assertEquals(ScreenMode.FULL, h.ui.screenMode)
        assertEquals(LoadLevel.PARKED, h.ui.load.level)
        // 옛 요약 시트와 겹치지 않는다
        assertEquals(CoachPhase.IDLE, h.ui.phase)
        assertNull(h.ui.summary)
        assertNull(h.ui.session)

        val d = h.ui.dashboard!!
        assertEquals("오늘도 수고했어요!", d.headlineKo)
        assertEquals(CommuteSlot.MORNING, d.slot)
        assertEquals(endedMs, d.endedAtMs)
        val minutes = ((endedMs - startedMs + 30_000L) / 60_000L).toInt().coerceAtLeast(1)
        assertEquals(minutes, d.minutes)
        assertEquals("출근길 ${minutes}분 동안 3문장을 연습했어요", d.subtitleKo)
        // 학습 결과
        assertEquals(3, d.answered)
        assertEquals(1, d.correct)
        assertEquals(1, d.partial)
        assertEquals(1, d.wrong)
        assertEquals(0, d.skipped)
        assertEquals(50, d.accuracyPercent) // (1 + 0.5) / 3
        assertEquals(2_000L, d.avgResponseMs) // 가짜 음성: 응답 지연 2초
        assertEquals(1, d.newExpressions)
        assertEquals(0, d.reviewed)
        assertEquals(1, d.bestStreak)
        // 발음 약점 + 예시
        val weak = d.pronWeakPoint!!
        assertEquals(PronIssueType.R_L, weak.type)
        assertEquals(2, weak.count)
        assertEquals("running", weak.expectedWord)
        assertEquals("lunning", weak.heardWord)
        assertEquals("‘running’ 대신 ‘lunning’처럼 들렸어요", weak.exampleKo)
        assertEquals("R은 혀끝을 말아요.", weak.tipKo)
        // 틀린 표현
        assertEquals(listOf("day.b1.02", "day.b1.03"), d.mistakes.map { it.itemId })
        assertEquals(listOf("거의 맞음", "틀림"), d.mistakes.map { it.tagKo })
        assertEquals("I'm running late.", d.mistakes.first().textEn)
        assertNotNull(d.bestSentence)
        // 진행률 (세션 전 → 후)
        assertEquals(15, d.xpGained)
        assertEquals(0, d.xpBefore)
        assertEquals(15, d.xpAfter)
        assertEquals(Level.BEGINNER, d.level)
        assertFalse(d.leveledUp)
        assertEquals(0f, d.levelProgressBefore, 1e-6f)
        assertEquals(1 / 20f, d.levelProgressAfter, 1e-6f)
        assertEquals("중급까지", d.levelGoalKo)
        assertEquals("유닛 1", d.unitTitleKo)
        assertEquals(0f, d.unitProgressBefore, 1e-6f)
        assertEquals(3 / 6f, d.unitProgressAfter, 1e-6f)
        assertFalse(d.unitPassed)
        assertEquals(0, d.goalBefore)
        assertEquals(3, d.goalDone)
        assertEquals(20, d.goalTarget)
        assertEquals(3 / 20f, d.goalProgressAfter, 1e-6f)
        assertFalse(d.goalReached)

        // 완료 징글 → 징글이 끝날 무렵 인사
        assertEquals(SoundCue.SESSION_COMPLETE, h.sound.cues.last())
        assertEquals(1, h.sound.cues.count { it == SoundCue.SESSION_COMPLETE })
        val line = "도착했어요. 오늘 3문제 중 1개 맞혔어요. 수고했어요!"
        assertFalse(h.speech.texts().contains(line))
        h.advance(2_500)
        assertEquals(line, h.speech.spoken.last().text)

        // 홈으로 → 닫히지만 결과는 남는다 → '지난 학습 결과'로 다시 본다 (징글은 다시 울리지 않는다)
        h.coach.dismissDashboard()
        assertFalse(h.ui.showParkedDashboard)
        assertEquals(d, h.ui.dashboard)
        assertTrue(h.coach.showDashboard().ok)
        assertTrue(h.ui.showParkedDashboard)
        assertEquals(1, h.sound.cues.count { it == SoundCue.SESSION_COMPLETE })

        // 틀린 표현 복습 → 대시보드를 닫고 그 표현들만 다시
        val res = h.coach.reviewMistakes()
        assertTrue(res.ok)
        assertFalse(h.ui.showParkedDashboard)
        assertEquals("틀린 표현 2개를 다시 해 볼게요.", h.speech.spoken.last().text)
        assertEquals(2, h.ui.session?.total)
        assertTrue(h.waitListening("day.b1.02"))
        assertEquals(PlanKind.MISTAKE, h.ui.prompt?.kind)
    }

    @Test fun midwayStopWithAnswersEndsSessionAndShowsDashboardUntilDrivingAgain() {
        val h = h()
        h.script(TestContent.vocab to PlanKind.NEW, TestContent.expression to PlanKind.NEW)
        h.driveMorning()
        assertTrue(h.startAndListen())
        h.coach.onAnswerText("postpone")
        h.advance(1_000)

        // 경로 중간(32분 남음)에 P 단 — 답한 문항이 있으니 여기서 끝낸다
        h.park()
        assertEquals(CoachPhase.SUMMARY, h.ui.phase)
        assertNull(h.ui.session)
        assertEquals(1, h.progression.sessions.size)
        assertTrue("중간 정차는 출퇴근 패턴으로 배우지 않는다", h.commute.learned.isEmpty())
        assertTrue(h.advanceUntil(6_000) { h.ui.showParkedDashboard })
        assertEquals(1, h.ui.dashboard!!.answered)
        assertEquals(100, h.ui.dashboard!!.accuracyPercent)
        assertTrue(h.ui.dashboard!!.mistakes.isEmpty())
        assertEquals(SoundCue.SESSION_COMPLETE, h.sound.cues.last())
        h.advance(2_500)
        assertEquals("오늘 1문제 중 1개 맞혔어요. 수고했어요!", h.speech.spoken.last().text)

        // 다시 출발하면 대시보드는 내려가고 (결과는 남는다) 인사도 멈춘다
        val greeting = h.speech.spoken.last()
        h.driveMorning(driveId = "drive-2")
        assertFalse(h.ui.showParkedDashboard)
        assertNotNull(h.ui.dashboard)
        assertNotNull(greeting.stoppedAtMs)
        // 주행 중에는 '지난 학습 결과'를 열 수 없다
        assertFalse(h.coach.showDashboard().ok)
        assertEquals("정차 후 이용할 수 있어요", h.ui.toast?.textKo)
    }

    @Test fun drivingOnAfterMidwayStopResumesCommuteLearning() {
        val h = h(LearnerState(profile = TestContent.profile(), settings = LearnerSettings(autoStartOnCommute = true)))
        h.driveMorning()
        h.advance(3_500)
        assertNotNull(h.ui.session)
        assertTrue(h.waitListening("day.b1.01"))
        h.coach.onAnswerText("postpone")
        h.advance(500)
        // 잠깐 세움 → 대시보드
        h.park()
        assertNull(h.ui.session)
        assertTrue(h.advanceUntil(6_000) { h.ui.showParkedDashboard })
        // 같은 주행으로 다시 출발 → 대시보드를 내리고 3초 뒤 출근길 학습을 이어 간다
        h.driveMorning()
        assertFalse(h.ui.showParkedDashboard)
        h.advance(2_500)
        assertNull(h.ui.session)
        h.advance(1_000)
        assertNotNull(h.ui.session)
        assertEquals(CoachPhase.BRIEFING, h.ui.phase)
        // 이미 답한 문항은 다시 새 표현으로 내지 않는다
        assertTrue(h.advanceUntil(20_000) { h.ui.phase == CoachPhase.LISTENING })
        assertEquals("day.b1.02", h.ui.prompt?.itemId)
    }

    @Test fun sessionFinishedWhileDrivingShowsDashboardLaterAtPark() {
        val h = h()
        h.script(TestContent.vocab to PlanKind.NEW, TestContent.expression to PlanKind.NEW)
        h.driveMorning()
        assertTrue(h.startAndListen())
        h.coach.onAnswerText("postpone")
        h.advance(500)
        h.coach.stopSession() // 주행 중 '그만'
        assertEquals(CoachPhase.SUMMARY, h.ui.phase)
        h.advance(60_000)
        assertFalse(h.ui.showParkedDashboard)
        assertTrue(h.sound.cues.none { it == SoundCue.SESSION_COMPLETE })

        h.park()
        assertTrue(h.advanceUntil(6_000) { h.ui.showParkedDashboard })
        assertEquals(CoachPhase.IDLE, h.ui.phase)
        assertEquals(1, h.ui.dashboard!!.answered)
        assertEquals(1, h.sound.cues.count { it == SoundCue.SESSION_COMPLETE })
        // 한 번만 뜬다
        h.coach.dismissDashboard()
        h.advance(10_000)
        assertFalse(h.ui.showParkedDashboard)
    }

    @Test fun noDashboardWithoutAnswersOrWithoutDriving() {
        // 답한 문항이 없는 도착 → 옛 요약 + 도착 인사만
        var h = h()
        h.script(TestContent.vocab to PlanKind.NEW)
        h.driveMorning()
        assertTrue(h.startAndListen())
        h.set { it.copy(arrived = true, gear = GearPosition.P, speedKph = 0f, routeActive = false, uxRestricted = false) }
        assertEquals(CoachPhase.SUMMARY, h.ui.phase)
        h.advance(8_000)
        assertFalse(h.ui.showParkedDashboard)
        assertNull(h.ui.dashboard)
        assertTrue(h.sound.cues.isEmpty())
        assertTrue(h.speech.texts().contains("도착했어요. 오늘도 수고했어요!"))
        assertFalse(h.coach.showDashboard().ok)
        assertEquals("아직 학습 결과가 없어요", h.ui.toast?.textKo)

        // 정차 중에만 한 학습 → 요약은 그대로, 대시보드는 저절로 뜨지 않는다 (결과는 '지난 학습 결과'로 볼 수 있다)
        h = h()
        h.script(TestContent.sentence to PlanKind.NEW)
        assertTrue(h.startAndListen())
        h.coach.chooseOption(h.ui.prompt!!.choices.indexOf(TestContent.sentence.modelAnswer))
        assertTrue(h.waitPhase(CoachPhase.SUMMARY, 30_000))
        h.advance(5_000)
        assertFalse(h.ui.showParkedDashboard)
        assertNotNull(h.ui.summary)
        assertEquals(1, h.ui.dashboard?.answered)
        assertTrue(h.sound.cues.none { it == SoundCue.SESSION_COMPLETE })
        assertTrue(h.coach.showDashboard().ok)
        assertTrue(h.ui.showParkedDashboard)
    }

    @Test fun reviewMistakesFallsBackToNormalReviewWhenNothingWasWrong() {
        val h = h(LearnerState(
            profile = TestContent.profile(),
            settings = LearnerSettings(autoStartOnCommute = false),
            cards = TestContent.dueCards(3, com.yeopjari.app.english.core.VirtualTime.START_MS),
        ))
        h.script(TestContent.vocab to PlanKind.NEW)
        h.driveMorning()
        assertTrue(h.startAndListen())
        h.coach.onAnswerText("postpone")
        h.advance(500)
        h.park()
        assertTrue(h.advanceUntil(6_000) { h.ui.showParkedDashboard })
        h.planner.script = null
        val res = h.coach.reviewMistakes()
        assertTrue(res.ok)
        assertFalse(h.ui.showParkedDashboard)
        assertTrue(h.planner.requests.last().reviewOnly)
        assertTrue(h.planner.requests.last().exclude.isEmpty())
        assertNotNull(h.ui.session)
    }

    // ───────────── 효과음 ─────────────

    @Test fun answerCuesFollowVerdictAndStreakFanfareEveryFiveInARow() {
        val h = h()
        val words = TestContent.fillers.take(6)
        h.script(*words.map { it to PlanKind.NEW }.toTypedArray(), TestContent.expression to PlanKind.NEW)
        h.driveMorning()
        assertTrue(h.startAndListen())
        // 정답 네 번은 정답음, 다섯 번째 연속 정답은 팡파르
        for ((i, w) in words.take(5).withIndex()) {
            assertTrue(h.waitListening(w.id))
            h.coach.onAnswerText(w.modelAnswer)
            assertEquals(i + 1, h.ui.session?.streak)
        }
        assertEquals(
            listOf(SoundCue.CORRECT, SoundCue.CORRECT, SoundCue.CORRECT, SoundCue.CORRECT, SoundCue.STREAK),
            h.sound.cues,
        )

        // 오답 → 낮고 순한 음, 두 번째 시도에서 맞히면 정답음 (연속 정답은 다시 0부터)
        assertTrue(h.waitListening(words[5].id))
        h.coach.onAnswerText("banana")
        assertEquals(SoundCue.WRONG, h.sound.cues.last())
        assertTrue(h.advanceUntil(20_000) { h.ui.phase == CoachPhase.LISTENING && h.ui.prompt?.attempt == 2 })
        h.coach.onAnswerText(words[5].modelAnswer)
        assertEquals(SoundCue.CORRECT, h.sound.cues.last())
        assertEquals(0, h.ui.session?.streak)

        // 거의 맞음 → 부드러운 중립 음
        assertTrue(h.waitListening("day.b1.02"))
        h.coach.onAnswerText("I'm lunning late.")
        assertEquals(SoundCue.PARTIAL, h.sound.cues.last())
        assertEquals(8, h.sound.cues.size)
        // 효과음은 TTS 를 끊지 않는다: 피드백 말은 그대로 이어진다
        assertTrue(h.speech.speaking.value)
        assertNull(h.speech.spoken.last().stoppedAtMs)
    }

    @Test fun noCueForSilenceSkipOrDontKnow() {
        val h = h()
        h.script(TestContent.vocab to PlanKind.NEW, TestContent.expression to PlanKind.NEW, TestContent.sentence to PlanKind.NEW)
        h.driveMorning()
        assertTrue(h.startAndListen())
        h.coach.onAnswerText("skip")
        assertTrue(h.waitListening("day.b1.02"))
        h.coach.onAnswerText("I don't know")
        assertTrue(h.waitListening("day.b1.03"))
        h.advance(13_000) // 침묵
        assertTrue(h.sound.cues.isEmpty())
        assertEquals(listOf(PulseKind.AMBER), h.feedback.pulses)
    }

    @Test fun levelUpFanfareComesRightBeforeItsPraiseLine() {
        val h = h()
        h.script(TestContent.vocab to PlanKind.NEW, TestContent.expression to PlanKind.NEW)
        h.driveMorning()
        assertTrue(h.startAndListen())
        h.progression.nextEvents = listOf(
            ProgressEvent.DailyGoalReached(20), ProgressEvent.LevelUp(Track.DAILY, Level.BEGINNER, Level.INTERMEDIATE),
        )
        h.coach.onAnswerText("postpone")
        assertEquals(listOf(SoundCue.CORRECT), h.sound.cues)
        assertTrue(h.advanceUntil(10_000) { h.sound.cues.size == 2 })
        assertEquals(SoundCue.LEVEL_UP, h.sound.cues.last())
        assertEquals("레벨 업! 이제 중급이에요.", h.speech.spoken.last().text)
    }

    @Test fun soundEffectsSettingOffIsSilentUntilTurnedOn() {
        val h = h(LearnerState(
            profile = TestContent.profile(),
            settings = LearnerSettings(autoStartOnCommute = false, soundEffects = false),
        ))
        h.script(TestContent.vocab to PlanKind.NEW, TestContent.expression to PlanKind.NEW)
        h.driveMorning()
        assertTrue(h.startAndListen())
        h.coach.onAnswerText("postpone")
        assertTrue(h.sound.cues.isEmpty())
        assertEquals(listOf(PulseKind.GREEN), h.feedback.pulses) // 앰비언트 펄스는 별도 설정

        h.coach.updateSettings { it.copy(soundEffects = true) }
        assertTrue(h.ui.settings.soundEffects)
        assertTrue(h.waitListening("day.b1.02"))
        h.coach.onAnswerText("I'm running late.")
        assertEquals(listOf(SoundCue.CORRECT), h.sound.cues)

        // 꺼 두면 주차 대시보드 징글도 없다 (인사는 바로)
        h.coach.updateSettings { it.copy(soundEffects = false) }
        h.advance(500)
        h.park()
        assertTrue(h.advanceUntil(6_000) { h.ui.showParkedDashboard })
        assertEquals(listOf(SoundCue.CORRECT), h.sound.cues)
        h.advance(500)
        assertEquals("오늘 2문제 중 2개 맞혔어요. 수고했어요!", h.speech.spoken.last().text)
    }

    @Test fun cuesAreNeverPlayedWhileLoadIsCritical() {
        val h = h()
        val ctx = CoachContext(h.deps, h.scope)
        ctx.load.value = LoadAssessment(level = LoadLevel.NORMAL)
        assertTrue(ctx.cue(SoundCue.CORRECT))
        ctx.load.value = LoadAssessment(level = LoadLevel.FOCUS)
        assertTrue(ctx.cue(SoundCue.WRONG))
        ctx.load.value = LoadAssessment(level = LoadLevel.CRITICAL)
        for (cue in SoundCue.entries) assertFalse(cue.name, ctx.cue(cue))
        assertEquals(listOf(SoundCue.CORRECT, SoundCue.WRONG), h.sound.cues)
    }

    @Test fun criticalLoadDuringAnswerHoldsWithoutAnySound() {
        val h = h()
        h.script(TestContent.vocab to PlanKind.NEW, TestContent.expression to PlanKind.NEW)
        h.driveMorning()
        assertTrue(h.startAndListen())
        // 답하려는 순간 급제동 → 듣기를 멈추고 HOLD. 그 뒤에 들어온 답은 받지 않고, 어떤 효과음도 없다
        h.set { it.copy(accelMps2 = -5.5f, brakePedal = true) }
        assertEquals(LoadLevel.CRITICAL, h.ui.load.level)
        assertEquals(CoachPhase.HOLD, h.ui.phase)
        h.coach.onAnswerText("postpone")
        h.advance(2_000)
        assertTrue(h.sound.cues.isEmpty())
        assertTrue(h.progression.outcomes.isEmpty())
    }
}
