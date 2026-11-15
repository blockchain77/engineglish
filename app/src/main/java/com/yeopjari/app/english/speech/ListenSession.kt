package com.yeopjari.app.english.speech

import com.yeopjari.app.english.core.ListenOutcome
import com.yeopjari.app.english.core.RmsSample
import com.yeopjari.app.english.core.SpeechLang
import com.yeopjari.app.english.core.SpeechThresholds
import com.yeopjari.app.english.core.SpeechTiming
import com.yeopjari.app.english.core.wordCount
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.abs

/**
 * 한 번의 listen() 창 — PleosSpeechPort 와 SimulatedSpeechPort 가 함께 쓰는 순수 상태 기계 (스레드 안전, JVM 테스트 대상).
 *
 * 창은 최종 결과·주입 답·stopAll() 중 먼저 온 것으로 끝나거나, 포트가 정한 시간(timeoutMs)이 다 되면 끝난다.
 * 엔진 오류가 나도 창은 열어 둔다 → 에뮬레이터에서 영어 STT 가 안 돼도 주입 답(데모)이 들어올 수 있다.
 *
 * Pleos STT 3.3.1 콜백 의미 (classes.jar 확인):
 * onReady = 듣기 준비, onStartedRecognition = EPD_ON(발화 시작), onEndedRecognition = EPD_OFF(발화 끝),
 * onUpdated(text, false) = 부분 결과, onUpdated(text, true) 다음에 onUpdatedEpdData(on, off) 가 바로 온다.
 */
class ListenSession(val lang: SpeechLang, val openedMs: Long, val timeoutMs: Long) {

    enum class End { FINAL, INJECTED, STOPPED }

    private val lock = Any()
    private val ended = CompletableDeferred<End>()
    private val epdArrived = CompletableDeferred<Unit>()

    private var endReason: End? = null
    private var readyMs: Long? = null
    private var epdOnEventMs: Long? = null
    private var epdOffEventMs: Long? = null
    private var firstPartialMs: Long? = null
    private var lastPartialMs: Long? = null
    private var partialText: String = ""
    private var finalText: String? = null
    private var finalMs: Long? = null
    private var epd: Pair<Long, Long>? = null
    private var errorCode: String? = null
    private var injectedText: String? = null
    private var injectedMs: Long? = null
    private val rms = ArrayList<RmsSample>()
    private val partialMarks = ArrayList<Long>()

    /** 엔진이 결과 없이 끝난 뒤 다시 request() 한 횟수 (포트가 관리) */
    @Volatile var restarts: Int = 0

    val deadlineMs: Long get() = openedMs + timeoutMs
    val end: End? get() = synchronized(lock) { endReason }
    val isEnded: Boolean get() = end != null
    val partial: String get() = synchronized(lock) { partialText }
    val error: String? get() = synchronized(lock) { errorCode }
    val hasEpd: Boolean get() = synchronized(lock) { epd != null }
    val listenStartMs: Long get() = synchronized(lock) { readyMs ?: openedMs }

    fun onReady(now: Long) {
        synchronized(lock) { if (readyMs == null) readyMs = now }
    }

    /** EPD_ON (onStartedRecognition) */
    fun onSpeechStart(now: Long) {
        synchronized(lock) { if (epdOnEventMs == null) epdOnEventMs = now }
    }

    /** EPD_OFF (onEndedRecognition) */
    fun onSpeechEnd(now: Long) {
        synchronized(lock) { epdOffEventMs = now }
    }

    fun onPartial(text: String, now: Long) {
        synchronized(lock) {
            if (text.isBlank() || endReason != null) return
            partialText = text.trim()
            if (firstPartialMs == null) firstPartialMs = now
            lastPartialMs = now
            if (partialMarks.size < MAX_MARKS) partialMarks += now
        }
    }

    /** 최종 결과. 빈 결과면 false (엔진이 아무것도 못 들음 → 포트가 다시 들을 수 있다) */
    fun onFinal(text: String, now: Long): Boolean {
        synchronized(lock) {
            if (endReason != null || text.isBlank()) return false
            finalText = text.trim()
            finalMs = now
            endReason = End.FINAL
        }
        ended.complete(End.FINAL)
        return true
    }

    fun onEpdData(on: Long, off: Long) {
        synchronized(lock) { epd = on to off }
        epdArrived.complete(Unit)
    }

    fun onRms(value: Double, now: Long) {
        synchronized(lock) {
            if (!value.isNaN() && !value.isInfinite() && rms.size < MAX_RMS) rms += RmsSample(now, value)
        }
    }

    /** 엔진 오류. TIMEOUT(말 없음)은 오류로 치지 않는다 */
    fun onError(code: String) {
        synchronized(lock) { if (code != SttErrors.TIMEOUT && endReason == null) errorCode = code }
    }

    fun inject(text: String, now: Long): Boolean {
        synchronized(lock) {
            if (endReason != null) return false
            injectedText = text
            injectedMs = now
            endReason = End.INJECTED
        }
        ended.complete(End.INJECTED)
        return true
    }

    fun stop(): Boolean {
        synchronized(lock) {
            if (endReason != null) return false
            endReason = End.STOPPED
        }
        ended.complete(End.STOPPED)
        return true
    }

    suspend fun awaitEnd(): End = ended.await()

    suspend fun awaitEpd() = epdArrived.await()

    /** 지금(또는 시간 끝) 결과: 주입/최종 → Heard, 부분 결과 → Heard, 엔진 오류 → Failed, 그 외 Silence */
    fun outcome(now: Long): ListenOutcome = synchronized(lock) {
        when (endReason) {
            End.INJECTED -> {
                val text = injectedText.orEmpty()
                ListenOutcome.Heard(text, SpeechTimingRules.injected(readyMs ?: openedMs, text, injectedMs ?: now))
            }
            End.STOPPED -> ListenOutcome.Stopped
            End.FINAL -> ListenOutcome.Heard(finalText.orEmpty(), timingLocked(finalMs ?: now))
            null -> {
                val err = errorCode
                when {
                    partialText.isNotBlank() -> ListenOutcome.Heard(partialText, timingLocked(now))
                    err != null -> ListenOutcome.Failed(err, SttErrors.reasonKo(err, lang))
                    else -> ListenOutcome.Silence
                }
            }
        }
    }

    fun timing(now: Long): SpeechTiming = synchronized(lock) { timingLocked(now) }

    private fun timingLocked(finalAt: Long): SpeechTiming {
        val listenStart = readyMs ?: openedMs
        val fp = firstPartialMs
        // 1) 발화 시작: EPD_ON 이벤트 (단, 첫 부분 결과보다 한참 앞서면 '마이크 열림' 으로 보고 부분 결과 기준)
        var start: Long? = epdOnEventMs
        if (start != null && fp != null && fp - start > SpeechTimingRules.EPD_ON_SUSPECT_MS) start = fp - SpeechTimingRules.PARTIAL_LEAD_MS
        var end: Long? = epdOffEventMs
        // 2) onUpdatedEpdData(on, off) — 단위 미확인: epoch ms 로 보이면 그대로, 작은 값이면 길이만 쓴다
        val e = epd
        var epdDuration: Long? = null
        if (e != null) {
            val (on, off) = e
            if (on > EPOCH_2020_MS && off >= on && abs(off - finalAt) < 10 * 60_000L) {
                if (start == null) start = on
                if (end == null) end = off
            } else if (on >= 0 && off > on) {
                var d = off - on
                if (d > 120_000L) d /= 16 // 16kHz 샘플 수로 보이면 ms 로
                if (d in 1..120_000L) epdDuration = d
            }
        }
        if (start == null && end != null && epdDuration != null) start = end - epdDuration
        // 3) 첫 부분 결과 − 0.3초, 4) RMS 상승
        if (start == null && fp != null) start = (fp - SpeechTimingRules.PARTIAL_LEAD_MS).coerceAtLeast(listenStart)
        if (start == null) start = rmsRiseLocked()
        if (end == null && start != null && epdDuration != null) end = start + epdDuration
        if (end == null) end = lastPartialMs ?: if (start != null) finalAt else null
        val s = start
        if (s != null && end != null && end < s) end = s
        val samples: List<RmsSample> =
            if (rms.isNotEmpty()) rms.toList() else partialMarks.map { RmsSample(it, SpeechTimingRules.ACTIVITY_MARK) }
        return SpeechTiming(
            listenStartMs = listenStart,
            speechStartMs = start,
            speechEndMs = end,
            finalMs = finalAt,
            rms = samples,
            injected = false,
        )
    }

    /** 처음 세 샘플(조용할 때)보다 확 커진 첫 시각 */
    private fun rmsRiseLocked(): Long? {
        if (rms.size < 4) return null
        val base = SpeechText.median(rms.take(3).map { it.rms })
        val max = rms.maxOf { it.rms }
        if (max - base <= 1e-9) return null
        val threshold = base + 0.5 * (max - base)
        return rms.drop(3).firstOrNull { it.rms >= threshold }?.atMs
    }

    companion object {
        private const val MAX_RMS = 3_000
        private const val MAX_MARKS = 500
        private const val EPOCH_2020_MS = 1_577_836_800_000L
    }
}

/** 시간 규칙 상수 + 주입 답의 추정 시간 */
object SpeechTimingRules {
    /** 주입 답: 듣기 시작 0.9초 뒤 말하기 시작, 단어당 0.38초 (명세 §5.6) */
    const val INJECT_LATENCY_MS = 900L
    const val INJECT_MS_PER_WORD = 380L
    /** 첫 부분 결과는 실제 발화보다 이만큼 늦게 온다고 본다 */
    const val PARTIAL_LEAD_MS = 300L
    /** EPD_ON 이 첫 부분 결과보다 이보다 앞서면 '마이크 열림' 신호로 본다 */
    const val EPD_ON_SUSPECT_MS = 1_500L
    /** 최종 결과 뒤 onUpdatedEpdData 를 기다리는 시간 */
    const val EPD_GRACE_MS = 150L
    /** RMS 가 없을 때 부분 결과 도착을 표시하는 값 (FluencyMeter 가 틈으로 쉼을 잰다) */
    const val ACTIVITY_MARK = -1.0

    fun injected(listenStartMs: Long, text: String, nowMs: Long): SpeechTiming {
        val words = wordCount(text).coerceAtLeast(1)
        val start = listenStartMs + INJECT_LATENCY_MS
        return SpeechTiming(
            listenStartMs = listenStartMs,
            speechStartMs = start,
            speechEndMs = start + INJECT_MS_PER_WORD * words,
            finalMs = nowMs,
            injected = true,
        )
    }
}

/** 듣지 않을 때 들어온 주입 답 — 15초 동안 다음 listen() 에 바로 돌려준다 */
class InjectionBox(private val ttlMs: Long = SpeechThresholds.INJECT_TTL_MS) {
    private var pending: Pair<String, Long>? = null

    @Synchronized fun put(text: String, nowMs: Long) {
        pending = text to nowMs
    }

    /** 유효한 주입 답을 꺼낸다 (꺼내면 사라짐, 15초 지난 것은 버림) */
    @Synchronized fun take(nowMs: Long): String? {
        val p = pending ?: return null
        pending = null
        return if (nowMs - p.second <= ttlMs) p.first else null
    }

    @Synchronized fun clear() {
        pending = null
    }

    @Synchronized fun hasPending(nowMs: Long): Boolean {
        val p = pending ?: return false
        return nowMs - p.second <= ttlMs
    }
}

/** STT 오류 코드 → 화면용 한국어 */
object SttErrors {
    const val TIMEOUT = "TIMEOUT"
    const val UNAVAILABLE_LANGUAGE = "UNAVAILABLE_LANGUAGE"
    const val SDK_MISSING = "SDK_MISSING"
    const val NOT_READY = "NOT_READY"
    const val REQUEST_FAILED = "REQUEST_FAILED"

    fun reasonKo(code: String, lang: SpeechLang): String = when (code) {
        UNAVAILABLE_LANGUAGE -> if (lang == SpeechLang.EN) "이 차의 음성 인식이 영어를 지원하지 않아요" else "음성 인식 언어를 쓸 수 없어요"
        "MIC_ERROR", "AUDIO_ERROR" -> "마이크를 쓸 수 없어요"
        "PERMISSION_DENIED", "UNAUTHENTICATED" -> "음성 인식 권한이 없어요"
        "NETWORK_ERROR", "SERVER_INTERNAL_ERROR" -> "음성 인식 서비스에 연결하지 못했어요"
        SDK_MISSING, NOT_READY -> "음성 인식을 준비하지 못했어요"
        else -> "음성 인식에 문제가 생겼어요"
    }
}
