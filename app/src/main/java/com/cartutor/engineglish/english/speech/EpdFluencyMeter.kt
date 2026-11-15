package com.cartutor.engineglish.english.speech

import com.cartutor.engineglish.english.core.FluencyMeter
import com.cartutor.engineglish.english.core.FluencyMetrics
import com.cartutor.engineglish.english.core.FluencyRating
import com.cartutor.engineglish.english.core.RmsSample
import com.cartutor.engineglish.english.core.SpeechThresholds
import com.cartutor.engineglish.english.core.SpeechTiming
import com.cartutor.engineglish.english.core.wordCount
import java.util.Locale

/**
 * 유창성 측정 (명세 §5.5) — STT 의 EPD(발화 시작·끝) 시각과 RMS 로 잰다.
 *
 * - 응답 지연 = 첫 발화 − 듣기 시작, 말한 시간 = 발화 끝 − 첫 발화, 분당 단어 = 단어 수 / 말한 시간 (400ms 이상일 때).
 * - 쉼: 발화 구간 RMS 가 기준(바닥 + 0.35 × (중앙값 − 바닥), 바닥은 발화 전 소음 또는 0) 아래로 400ms 이상 머문 곳.
 *   RMS 가 없으면 부분 인식 결과가 1초 넘게 끊긴 곳 ([SpeechTimingRules.ACTIVITY_MARK] 표식).
 * - 등급: SMOOTH(지연 ≤ 1.5초, 분당 ≥ 100 또는 모름, 쉼 ≤ 1) · HESITANT(지연 > 4초 또는 분당 < 60 또는 쉼 ≥ 3) · 그 외 OK.
 */
class EpdFluencyMeter : FluencyMeter {

    override fun measure(timing: SpeechTiming, heard: String): FluencyMetrics {
        val start = timing.speechStartMs
        val end = if (start == null) null else (timing.speechEndMs ?: timing.finalMs).coerceAtLeast(start)
        val latency = start?.let { (it - timing.listenStartMs).coerceAtLeast(0L) }
        val speech = if (start != null && end != null) end - start else null
        val words = wordCount(heard)
        val wpm = if (speech != null && speech >= MIN_SPEECH_FOR_WPM_MS && words > 0) {
            Math.round(words / (speech / 60_000.0)).toInt()
        } else null
        val pauses = if (start != null && end != null) detectPauses(timing.rms, timing.listenStartMs, start, end) else emptyList()
        val rating = when {
            latency != null && latency <= SMOOTH_LATENCY_MS && (wpm == null || wpm >= SMOOTH_WPM) && pauses.size <= 1 -> FluencyRating.SMOOTH
            (latency != null && latency > HESITANT_LATENCY_MS) || (wpm != null && wpm < HESITANT_WPM) || pauses.size >= HESITANT_PAUSES -> FluencyRating.HESITANT
            else -> FluencyRating.OK
        }
        return FluencyMetrics(
            latencyMs = latency,
            speechMs = speech,
            wordsPerMinute = wpm,
            pauseCount = pauses.size,
            longestPauseMs = pauses.maxOrNull() ?: 0L,
            rating = rating,
            estimated = timing.injected,
            noteKo = note(latency, wpm, pauses.size, timing.injected),
        )
    }

    companion object {
        const val MIN_SPEECH_FOR_WPM_MS = 400L
        const val SMOOTH_LATENCY_MS = 1_500L
        const val HESITANT_LATENCY_MS = 4_000L
        const val SMOOTH_WPM = 100
        const val HESITANT_WPM = 60
        const val HESITANT_PAUSES = 3
        const val PAUSE_RMS_RATIO = 0.35
        /** 부분 인식 결과 사이가 이만큼 비면 쉼 (RMS 가 없을 때) */
        const val GAP_PAUSE_MS = 1_000L

        /** 발화 구간 안의 쉼 길이들 (ms) */
        fun detectPauses(samples: List<RmsSample>, listenStartMs: Long, startMs: Long, endMs: Long): List<Long> {
            val inWindow = samples.filter { it.atMs in startMs..endMs }.sortedBy { it.atMs }
            if (inWindow.isEmpty()) return emptyList()
            if (inWindow.all { it.rms == SpeechTimingRules.ACTIVITY_MARK }) return gapPauses(inWindow.map { it.atMs })
            val real = inWindow.filter { it.rms != SpeechTimingRules.ACTIVITY_MARK && !it.rms.isNaN() }
            if (real.size < 3) return emptyList()
            val pre = samples.filter { it.atMs >= listenStartMs && it.atMs < startMs && it.rms != SpeechTimingRules.ACTIVITY_MARK }.map { it.rms }
            val floor = when {
                pre.isNotEmpty() -> SpeechText.median(pre)
                real.all { it.rms >= 0.0 } -> 0.0
                else -> real.minOf { it.rms }
            }
            val med = SpeechText.median(real.map { it.rms })
            if (med <= floor) return emptyList()
            val threshold = floor + PAUSE_RMS_RATIO * (med - floor)
            val pauses = ArrayList<Long>()
            var seenVoice = false
            var runStart: Long? = null
            for (s in real) {
                if (s.rms < threshold) {
                    if (seenVoice && runStart == null) runStart = s.atMs
                } else {
                    val rs = runStart
                    if (rs != null && s.atMs - rs >= SpeechThresholds.PAUSE_MIN_MS) pauses += s.atMs - rs
                    runStart = null
                    seenVoice = true
                }
            }
            return pauses
        }

        private fun gapPauses(times: List<Long>): List<Long> =
            times.zipWithNext { a, b -> b - a }.filter { it >= GAP_PAUSE_MS }

        /** "1.2초 만에 답했어요 · 분당 118단어" (+ " · 멈춤 2번", 추정이면 " (추정)") */
        fun note(latencyMs: Long?, wpm: Int?, pauses: Int, estimated: Boolean): String {
            val parts = ArrayList<String>(3)
            if (latencyMs != null) parts += String.format(Locale.ROOT, "%.1f초 만에 답했어요", latencyMs / 1000.0)
            if (wpm != null) parts += "분당 ${wpm}단어"
            if (pauses > 0) parts += "멈춤 ${pauses}번"
            val body = if (parts.isEmpty()) "말한 시간을 재지 못했어요" else parts.joinToString(" · ")
            return if (estimated) "$body (추정)" else body
        }
    }
}
