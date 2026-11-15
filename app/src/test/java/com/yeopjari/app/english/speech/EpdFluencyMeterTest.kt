package com.yeopjari.app.english.speech

import com.yeopjari.app.english.core.FluencyRating
import com.yeopjari.app.english.core.RmsSample
import com.yeopjari.app.english.core.SpeechTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpdFluencyMeterTest {

    private val meter = EpdFluencyMeter()

    /** start..end 사이 100ms 간격 RMS — dips = (시작, 길이) 구간은 조용함 */
    private fun rms(start: Long, end: Long, voice: Double = 0.5, quiet: Double = 0.05, dips: List<Pair<Long, Long>> = emptyList()): List<RmsSample> =
        (start..end step 100).map { t -> RmsSample(t, if (dips.any { (s, d) -> t >= s && t < s + d }) quiet else voice) }

    @Test fun latencyDurationAndWpm() {
        val m = meter.measure(SpeechTiming(listenStartMs = 0, speechStartMs = 1_200, speechEndMs = 4_200, finalMs = 4_800), "Can we push the meeting tomorrow")
        assertEquals(1_200L, m.latencyMs)
        assertEquals(3_000L, m.speechMs)
        assertEquals(120, m.wordsPerMinute)
        assertEquals(0, m.pauseCount)
        assertEquals(FluencyRating.SMOOTH, m.rating)
        assertEquals("1.2초 만에 답했어요 · 분당 120단어", m.noteKo)
        assertTrue(!m.estimated)
    }

    @Test fun slowStartIsHesitant() {
        val m = meter.measure(SpeechTiming(0, 4_500, 7_000, 7_500), "I usually drive to work")
        assertEquals(FluencyRating.HESITANT, m.rating)
    }

    @Test fun slowSpeechIsHesitant() {
        val m = meter.measure(SpeechTiming(0, 1_000, 5_000, 5_500), "I drive car") // 3단어 / 4초 = 45 wpm
        assertEquals(45, m.wordsPerMinute)
        assertEquals(FluencyRating.HESITANT, m.rating)
    }

    @Test fun middleIsOk() {
        val m = meter.measure(SpeechTiming(0, 2_500, 5_500, 6_000), "Can we push the meeting tomorrow") // 2.5초, 120 wpm
        assertEquals(FluencyRating.OK, m.rating)
    }

    @Test fun pausesFromRmsDips() {
        val samples = rms(1_000, 6_000, dips = listOf(2_000L to 600L, 4_000L to 500L))
        val m = meter.measure(SpeechTiming(0, 1_000, 6_000, 6_500, rms = samples), "I usually drive to work and it takes thirty minutes")
        assertEquals(2, m.pauseCount)
        assertEquals(600L, m.longestPauseMs)
        assertEquals(FluencyRating.OK, m.rating) // 지연 1초지만 쉼 2번 → SMOOTH 아님
        assertTrue(m.noteKo, m.noteKo.endsWith("멈춤 2번"))
    }

    @Test fun threePausesIsHesitant() {
        val samples = rms(1_000, 8_000, dips = listOf(2_000L to 500L, 4_000L to 500L, 6_000L to 700L))
        val m = meter.measure(SpeechTiming(0, 1_000, 8_000, 8_500, rms = samples), "I usually drive to work and it takes about thirty minutes every day")
        assertEquals(3, m.pauseCount)
        assertEquals(FluencyRating.HESITANT, m.rating)
    }

    @Test fun shortDipsAndTrailingSilenceAreNotPauses() {
        val samples = rms(1_000, 5_000, dips = listOf(2_000L to 300L, 4_500L to 1_000L)) // 0.3초 짧은 틈 + 끝까지 조용
        val m = meter.measure(SpeechTiming(0, 1_000, 5_000, 5_500, rms = samples), "Can we push the meeting tomorrow")
        assertEquals(0, m.pauseCount)
    }

    @Test fun thresholdIs35PercentOfMedian() {
        // 중앙값 0.5 → 기준 0.175. 0.2 로 내려간 곳은 쉼이 아니고 0.1 은 쉼
        val notQuiet = rms(1_000, 5_000, quiet = 0.2, dips = listOf(2_000L to 800L))
        assertEquals(0, meter.measure(SpeechTiming(0, 1_000, 5_000, 5_500, rms = notQuiet), "a b c d e f").pauseCount)
        val quiet = rms(1_000, 5_000, quiet = 0.1, dips = listOf(2_000L to 800L))
        assertEquals(1, meter.measure(SpeechTiming(0, 1_000, 5_000, 5_500, rms = quiet), "a b c d e f").pauseCount)
    }

    @Test fun preSpeechNoiseRaisesTheFloor() {
        // 발화 전 소음 0.3 → 기준 0.3 + 0.35*(0.8-0.3) = 0.475 → 0.4 로 내려간 곳도 쉼
        val pre = (0L until 1_000L step 100).map { RmsSample(it, 0.3) }
        val speech = rms(1_000, 5_000, voice = 0.8, quiet = 0.4, dips = listOf(2_000L to 800L))
        val m = meter.measure(SpeechTiming(0, 1_000, 5_000, 5_500, rms = pre + speech), "a b c d e f")
        assertEquals(1, m.pauseCount)
    }

    @Test fun partialGapsCountAsPausesWithoutRms() {
        val marks = listOf(1_100L, 1_400L, 1_700L, 3_200L, 3_500L).map { RmsSample(it, SpeechTimingRules.ACTIVITY_MARK) }
        val m = meter.measure(SpeechTiming(0, 1_000, 3_600, 4_000, rms = marks), "I usually drive to work")
        assertEquals(1, m.pauseCount)
        assertEquals(1_500L, m.longestPauseMs)
    }

    @Test fun injectedTimingIsMarkedEstimated() {
        val timing = SpeechTimingRules.injected(listenStartMs = 10_000, text = "Can we push the meeting to tomorrow", nowMs = 10_500)
        assertEquals(10_900L, timing.speechStartMs)
        assertEquals(10_900L + 7 * 380, timing.speechEndMs)
        val m = meter.measure(timing, "Can we push the meeting to tomorrow")
        assertTrue(m.estimated)
        assertEquals(900L, m.latencyMs)
        assertEquals(FluencyRating.SMOOTH, m.rating)
        assertTrue(m.noteKo, m.noteKo.endsWith(" (추정)"))
    }

    @Test fun veryShortSpeechHasNoWpm() {
        val m = meter.measure(SpeechTiming(0, 1_000, 1_300, 1_500), "yes")
        assertNull(m.wordsPerMinute)
        assertEquals(FluencyRating.SMOOTH, m.rating)
    }

    @Test fun unknownSpeechStart() {
        val m = meter.measure(SpeechTiming(listenStartMs = 0, speechStartMs = null, speechEndMs = null, finalMs = 3_000), "hello there")
        assertNull(m.latencyMs)
        assertNull(m.speechMs)
        assertNull(m.wordsPerMinute)
        assertEquals(FluencyRating.OK, m.rating)
        assertEquals("말한 시간을 재지 못했어요", m.noteKo)
    }

    @Test fun missingSpeechEndFallsBackToFinal() {
        val m = meter.measure(SpeechTiming(0, 1_000, null, 4_000), "Can we push the meeting tomorrow")
        assertEquals(3_000L, m.speechMs)
        assertEquals(120, m.wordsPerMinute)
    }
}
