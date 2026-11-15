package com.yeopjari.app.english.speech

import com.yeopjari.app.english.core.ListenOutcome
import com.yeopjari.app.english.core.SpeakOutcome
import com.yeopjari.app.english.core.SpeechLang
import com.yeopjari.app.english.core.SpeechRate
import com.yeopjari.app.english.core.SpeechThresholds
import com.yeopjari.app.english.core.VirtualTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 듣기 창 규칙 (주입 TTL · 시간 끝 처리 · EPD 시각) — 안드로이드 없이 */
class ListenSessionTest {

    // ───────── InjectionBox ─────────

    @Test fun injectionLivesFifteenSeconds() {
        val box = InjectionBox()
        box.put("postpone", 1_000)
        assertTrue(box.hasPending(1_000 + SpeechThresholds.INJECT_TTL_MS))
        assertEquals("postpone", box.take(1_000 + SpeechThresholds.INJECT_TTL_MS))
        assertNull(box.take(1_000 + SpeechThresholds.INJECT_TTL_MS)) // 한 번만
        box.put("late", 1_000)
        assertNull(box.take(1_000 + SpeechThresholds.INJECT_TTL_MS + 1))
    }

    // ───────── ListenSession 결과 ─────────

    @Test fun finalResultWins() {
        val s = ListenSession(SpeechLang.EN, 0, 10_000)
        s.onReady(200)
        s.onPartial("can we", 1_500)
        assertTrue(s.onFinal("Can we push the meeting?", 3_000))
        assertFalse(s.inject("ignored", 3_100)) // 먼저 끝난 쪽이 이긴다
        val o = s.outcome(3_200) as ListenOutcome.Heard
        assertEquals("Can we push the meeting?", o.text)
        assertEquals(200L, o.timing.listenStartMs)
        assertEquals(3_000L, o.timing.finalMs)
        assertFalse(o.timing.injected)
    }

    @Test fun blankFinalDoesNotEndTheWindow() {
        val s = ListenSession(SpeechLang.EN, 0, 10_000)
        assertFalse(s.onFinal("  ", 2_000))
        assertNull(s.end)
        assertEquals(ListenOutcome.Silence, s.outcome(10_000))
    }

    @Test fun timeoutFallsBackToPartialThenErrorThenSilence() {
        val partial = ListenSession(SpeechLang.EN, 0, 8_000).apply { onPartial("can we push", 2_000) }
        assertEquals("can we push", (partial.outcome(8_000) as ListenOutcome.Heard).text)

        val failed = ListenSession(SpeechLang.EN, 0, 8_000).apply { onError(SttErrors.UNAVAILABLE_LANGUAGE) }
        val f = failed.outcome(8_000) as ListenOutcome.Failed
        assertEquals("UNAVAILABLE_LANGUAGE", f.code)
        assertEquals("이 차의 음성 인식이 영어를 지원하지 않아요", f.reasonKo)

        val timeoutOnly = ListenSession(SpeechLang.EN, 0, 8_000).apply { onError(SttErrors.TIMEOUT) }
        assertEquals(ListenOutcome.Silence, timeoutOnly.outcome(8_000))
        assertEquals(ListenOutcome.Silence, ListenSession(SpeechLang.EN, 0, 8_000).outcome(8_000))
    }

    @Test fun injectionAfterEngineErrorStillAnswers() {
        val s = ListenSession(SpeechLang.EN, 0, 12_000)
        s.onError(SttErrors.UNAVAILABLE_LANGUAGE)
        assertTrue(s.inject("Can we push the meeting to tomorrow?", 3_000))
        val o = s.outcome(3_000) as ListenOutcome.Heard
        assertTrue(o.timing.injected)
        assertEquals(900L, o.timing.speechStartMs)
        assertEquals(900L + 7 * 380, o.timing.speechEndMs)
        assertEquals(3_000L, o.timing.finalMs)
    }

    @Test fun stopGivesStopped() {
        val s = ListenSession(SpeechLang.EN, 0, 12_000)
        assertTrue(s.stop())
        assertEquals(ListenOutcome.Stopped, s.outcome(100))
    }

    // ───────── 시각 추정 ─────────

    @Test fun epdEventsAreSpeechStartAndEnd() {
        val s = ListenSession(SpeechLang.EN, 0, 10_000)
        s.onReady(300)
        s.onSpeechStart(1_500)
        s.onPartial("can", 1_800)
        s.onSpeechEnd(4_000)
        s.onFinal("can we go", 4_600)
        val t = (s.outcome(4_700) as ListenOutcome.Heard).timing
        assertEquals(300L, t.listenStartMs)
        assertEquals(1_500L, t.speechStartMs)
        assertEquals(4_000L, t.speechEndMs)
    }

    @Test fun withoutEpdUsesFirstAndLastPartial() {
        val s = ListenSession(SpeechLang.EN, 0, 10_000)
        s.onReady(100)
        s.onPartial("can", 1_600)
        s.onPartial("can we go", 3_000)
        s.onFinal("can we go", 3_800)
        val t = (s.outcome(3_800) as ListenOutcome.Heard).timing
        assertEquals(1_300L, t.speechStartMs)
        assertEquals(3_000L, t.speechEndMs)
        // RMS 가 없으면 부분 결과 도착 표식
        assertEquals(listOf(1_600L, 3_000L), t.rms.map { it.atMs })
        assertTrue(t.rms.all { it.rms == SpeechTimingRules.ACTIVITY_MARK })
    }

    @Test fun suspiciousEarlyEpdOnIsReplacedByPartialTiming() {
        val s = ListenSession(SpeechLang.EN, 0, 10_000)
        s.onReady(100)
        s.onSpeechStart(150) // 마이크 열림과 같은 때 (실제 발화 아님)
        s.onPartial("can we", 3_000)
        s.onFinal("can we", 3_600)
        assertEquals(2_700L, (s.outcome(3_600) as ListenOutcome.Heard).timing.speechStartMs)
    }

    @Test fun epochEpdDataIsUsedWhenNoEvents() {
        val base = 1_800_000_000_000L
        val s = ListenSession(SpeechLang.EN, base, 10_000)
        s.onFinal("hello there", base + 4_000)
        s.onEpdData(base + 1_200, base + 3_100)
        val t = s.timing(base + 4_000)
        assertEquals(base + 1_200, t.speechStartMs)
        assertEquals(base + 3_100, t.speechEndMs)
    }

    @Test fun relativeEpdDataGivesDuration() {
        val s = ListenSession(SpeechLang.EN, 0, 10_000)
        s.onSpeechEnd(5_000)
        s.onFinal("hello there my friend", 5_400)
        s.onEpdData(800, 2_800) // 단위 미확인 → 길이(2초)만 쓴다
        val t = s.timing(5_400)
        assertEquals(3_000L, t.speechStartMs)
        assertEquals(5_000L, t.speechEndMs)
    }

    @Test fun rmsRiseIsLastResort() {
        val s = ListenSession(SpeechLang.EN, 0, 10_000)
        listOf(0.01, 0.02, 0.01, 0.02, 0.6, 0.7, 0.5).forEachIndexed { i, v -> s.onRms(v, 1_000L + i * 100) }
        s.onFinal("ok", 3_000)
        val t = s.timing(3_000)
        assertEquals(1_400L, t.speechStartMs)
        assertEquals(7, t.rms.size)
    }

    // ───────── SimulatedSpeechPort (가상 시간) ─────────

    @Test fun injectWhileListeningCompletesImmediately() {
        val vt = VirtualTime()
        val port = SimulatedSpeechPort(vt)
        var out: ListenOutcome? = null
        vt.scope().launch { out = port.listen(SpeechLang.EN, 12_000) }
        vt.advanceBy(2_000)
        assertTrue(port.listening.value)
        port.inject("Can we push the meeting to tomorrow?")
        vt.runCurrent()
        val heard = out as ListenOutcome.Heard
        assertEquals("Can we push the meeting to tomorrow?", heard.text)
        assertTrue(heard.timing.injected)
        assertEquals(VirtualTime.START_MS + 900, heard.timing.speechStartMs)
        assertFalse(port.listening.value)
    }

    @Test fun injectBeforeListenIsKeptForFifteenSeconds() {
        val vt = VirtualTime()
        val port = SimulatedSpeechPort(vt)
        port.inject("postpone")
        vt.advanceBy(10_000)
        var out: ListenOutcome? = null
        vt.scope().launch { out = port.listen(SpeechLang.EN, 7_000) }
        vt.runCurrent()
        assertEquals("postpone", (out as ListenOutcome.Heard).text)
    }

    @Test fun expiredInjectionIsDropped() {
        val vt = VirtualTime()
        val port = SimulatedSpeechPort(vt)
        port.inject("postpone")
        vt.advanceBy(16_000)
        var out: ListenOutcome? = null
        vt.scope().launch { out = port.listen(SpeechLang.EN, 7_000) }
        vt.runCurrent()
        assertNull(out)
        vt.advanceBy(7_000)
        assertEquals(ListenOutcome.Silence, out)
    }

    @Test fun stopAllDropsPendingInjection() {
        val vt = VirtualTime()
        val port = SimulatedSpeechPort(vt)
        port.inject("stale answer") // 듣는 중이 아님 → 보관
        port.stopAll() // CRITICAL 등 → 버린다
        var out: ListenOutcome? = null
        vt.scope().launch { out = port.listen(SpeechLang.EN, 5_000) }
        vt.runCurrent()
        assertNull(out) // 낡은 답은 버려져서 아직 듣는 중
        port.inject("fresh answer")
        vt.runCurrent()
        assertEquals("fresh answer", (out as ListenOutcome.Heard).text)
        // 끼어들기 순서(stopAll → inject → listen)는 그대로 동작
        port.stopAll()
        port.inject("barge in")
        var next: ListenOutcome? = null
        vt.scope().launch { next = port.listen(SpeechLang.EN, 5_000) }
        vt.runCurrent()
        assertEquals("barge in", (next as ListenOutcome.Heard).text)
    }

    @Test fun windowStaysOpenAfterSttErrorSoDemoCanAnswer() {
        val vt = VirtualTime()
        val port = SimulatedSpeechPort(vt)
        var out: ListenOutcome? = null
        vt.scope().launch { out = port.listen(SpeechLang.EN, 12_000) }
        vt.advanceBy(300)
        port.simulateError(SttErrors.UNAVAILABLE_LANGUAGE)
        vt.advanceBy(3_000)
        assertNull(out) // 오류가 나도 창은 열려 있다
        assertEquals(false, port.status.value.sttEnglish)
        port.inject("I'm running late")
        vt.runCurrent()
        assertEquals("I'm running late", (out as ListenOutcome.Heard).text)
    }

    @Test fun errorWithoutAnswerFailsAtTimeout() {
        val vt = VirtualTime()
        val port = SimulatedSpeechPort(vt)
        var out: ListenOutcome? = null
        vt.scope().launch { out = port.listen(SpeechLang.EN, 6_000) }
        port.simulateError("MIC_ERROR")
        vt.advanceBy(5_999)
        assertNull(out)
        vt.advanceBy(1)
        assertEquals("MIC_ERROR", (out as ListenOutcome.Failed).code)
    }

    @Test fun partialIsUsedAtTimeout() {
        val vt = VirtualTime()
        val port = SimulatedSpeechPort(vt)
        var out: ListenOutcome? = null
        vt.scope().launch { out = port.listen(SpeechLang.EN, 6_000) }
        vt.advanceBy(1_000)
        port.simulateSpeechStart()
        port.simulatePartial("can we push")
        assertEquals("can we push", port.partial.value)
        vt.advanceBy(5_000)
        val heard = out as ListenOutcome.Heard
        assertEquals("can we push", heard.text)
        assertEquals(VirtualTime.START_MS + 1_000, heard.timing.speechStartMs)
        assertEquals("", port.partial.value)
    }

    @Test fun simulatedFinalWithTiming() {
        val vt = VirtualTime()
        val port = SimulatedSpeechPort(vt)
        var out: ListenOutcome? = null
        vt.scope().launch { out = port.listen(SpeechLang.EN, 10_000) }
        vt.advanceBy(1_200)
        port.simulateSpeechStart()
        vt.advanceBy(2_000)
        port.simulateSpeechEnd()
        vt.advanceBy(400)
        assertTrue(port.simulateFinal("Let's call it a day"))
        vt.runCurrent()
        val heard = out as ListenOutcome.Heard
        val m = EpdFluencyMeter().measure(heard.timing, heard.text)
        assertEquals(1_200L, m.latencyMs)
        assertEquals(2_000L, m.speechMs)
    }

    @Test fun speakTakesEstimatedTimeAndCanBeStopped() {
        val vt = VirtualTime()
        val port = SimulatedSpeechPort(vt) { _, _, _ -> 2_000L }
        var first: SpeakOutcome? = null
        vt.scope().launch { first = port.speak("출근길 32분, 시작할게요.", SpeechLang.KO) }
        assertTrue(port.speaking.value)
        vt.advanceBy(1_999)
        assertNull(first)
        vt.advanceBy(1)
        assertEquals(SpeakOutcome.DONE, first)
        assertFalse(port.speaking.value)

        var second: SpeakOutcome? = null
        vt.scope().launch { second = port.speak("Can we push the meeting to tomorrow?", SpeechLang.EN, SpeechRate.SLOW) }
        vt.advanceBy(500)
        port.stopAll()
        vt.runCurrent()
        assertEquals(SpeakOutcome.STOPPED, second)
        assertEquals(listOf(SpeechLang.KO, SpeechLang.EN), port.spoken.map { it.lang })
        assertEquals(SpeechRate.SLOW, port.spoken.last().rate)
    }

    @Test fun newSpeakStopsThePreviousOne() {
        val vt = VirtualTime()
        val port = SimulatedSpeechPort(vt) { _, _, _ -> 2_000L }
        val scope = vt.scope()
        var first: SpeakOutcome? = null
        var second: SpeakOutcome? = null
        scope.launch { first = port.speak("하나", SpeechLang.KO) }
        vt.advanceBy(500)
        scope.launch { second = port.speak("둘", SpeechLang.KO) }
        vt.runCurrent()
        assertEquals(SpeakOutcome.STOPPED, first)
        vt.advanceBy(2_000)
        assertEquals(SpeakOutcome.DONE, second)
    }

    @Test fun cancellationPropagatesAndClearsState() {
        val vt = VirtualTime()
        val port = SimulatedSpeechPort(vt) { _, _, _ -> 5_000L }
        var cancelled = false
        val job = vt.scope().launch {
            try {
                port.speak("길게 말하는 중이에요", SpeechLang.KO)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        vt.advanceBy(1_000)
        job.cancel()
        vt.runCurrent()
        assertTrue(cancelled)
        assertFalse(port.speaking.value)

        var listenCancelled = false
        val listenJob = vt.scope().launch {
            try {
                port.listen(SpeechLang.EN, 10_000)
            } catch (e: CancellationException) {
                listenCancelled = true
                throw e
            }
        }
        vt.advanceBy(1_000)
        assertTrue(port.listening.value)
        listenJob.cancel()
        vt.runCurrent()
        assertTrue(listenCancelled)
        assertFalse(port.listening.value)
    }
}
