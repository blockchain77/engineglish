package com.yeopjari.app.english.speech

import com.yeopjari.app.english.core.Clock
import com.yeopjari.app.english.core.ListenOutcome
import com.yeopjari.app.english.core.SpeakOutcome
import com.yeopjari.app.english.core.SpeechDurations
import com.yeopjari.app.english.core.SpeechLang
import com.yeopjari.app.english.core.SpeechPort
import com.yeopjari.app.english.core.SpeechRate
import com.yeopjari.app.english.core.SpeechStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 순수 Kotlin 음성 포트 — JVM 테스트(코치 시나리오, VirtualTime)와 엔진 없는 데모용.
 *
 * - speak: 말하기 시간 추정치(SpeechDurations)만큼 기다린 뒤 DONE. 새 speak·listen·stopAll 이 오면 STOPPED.
 * - listen: inject()/simulateFinal() 로만 답이 들어온다 (PleosSpeechPort 와 같은 [ListenSession] 규칙).
 * - 취소되면 CancellationException 을 그대로 던진다.
 */
class SimulatedSpeechPort(
    private val clock: Clock,
    private val speakDurationMs: (String, SpeechLang, SpeechRate) -> Long = { t, l, r -> SpeechDurations.estimateMs(t, l, r) },
) : SpeechPort {

    data class Spoken(val text: String, val lang: SpeechLang, val rate: SpeechRate, val atMs: Long, var outcome: SpeakOutcome? = null)

    private val _status = MutableStateFlow(
        SpeechStatus(sttReady = true, sttEnglish = true, ttsKoEngine = ENGINE_NAME, ttsEnEngine = ENGINE_NAME),
    )
    private val _speaking = MutableStateFlow(false)
    private val _listening = MutableStateFlow(false)
    private val _partial = MutableStateFlow("")
    override val status: StateFlow<SpeechStatus> = _status.asStateFlow()
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    override val listening: StateFlow<Boolean> = _listening.asStateFlow()
    override val partial: StateFlow<String> = _partial.asStateFlow()

    private val spokenLog = ArrayList<Spoken>()
    private val injections = InjectionBox()
    private var currentSpeak: CompletableDeferred<SpeakOutcome>? = null
    private var session: ListenSession? = null

    /** 지금까지 말한 문장 (테스트 확인용) */
    val spoken: List<Spoken> get() = synchronized(spokenLog) { spokenLog.toList() }

    /** 지금 듣는 중인 창 (테스트용) */
    val currentSession: ListenSession? get() = session

    override suspend fun speak(text: String, lang: SpeechLang, rate: SpeechRate): SpeakOutcome {
        session?.stop()
        currentSpeak?.complete(SpeakOutcome.STOPPED)
        val done = CompletableDeferred<SpeakOutcome>()
        currentSpeak = done
        val line = Spoken(text, lang, rate, clock.nowMs())
        synchronized(spokenLog) { spokenLog += line }
        _speaking.value = true
        try {
            val r = withTimeoutOrNull(speakDurationMs(text, lang, rate).coerceAtLeast(1L)) { done.await() } ?: SpeakOutcome.DONE
            line.outcome = r
            return r
        } finally {
            if (currentSpeak === done) {
                currentSpeak = null
                _speaking.value = false
            }
        }
    }

    override suspend fun listen(lang: SpeechLang, timeoutMs: Long): ListenOutcome {
        val now = clock.nowMs()
        injections.take(now)?.let { text -> return ListenOutcome.Heard(text, SpeechTimingRules.injected(now, text, now)) }
        currentSpeak?.complete(SpeakOutcome.STOPPED)
        session?.stop()
        val s = ListenSession(lang, now, timeoutMs)
        s.onReady(now)
        session = s
        _partial.value = ""
        _listening.value = true
        try {
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { s.awaitEnd() }
            return s.outcome(clock.nowMs())
        } finally {
            if (session === s) {
                session = null
                _listening.value = false
                _partial.value = ""
            }
        }
    }

    override fun inject(text: String) {
        val now = clock.nowMs()
        val s = session
        if (s != null && s.inject(text, now)) return
        injections.put(text, now)
    }

    /** 말하기·듣기를 멈추고, 쌓여 있던 주입 답도 버린다 (끼어들기: stopAll → inject 순서) */
    override fun stopAll() {
        injections.clear()
        currentSpeak?.complete(SpeakOutcome.STOPPED)
        session?.stop()
        _speaking.value = false
    }

    // ───────── 테스트 훅: 진짜 STT 처럼 신호를 흘려 넣는다 ─────────

    fun simulateSpeechStart() { session?.onSpeechStart(clock.nowMs()) }

    fun simulateSpeechEnd() { session?.onSpeechEnd(clock.nowMs()) }

    fun simulatePartial(text: String) {
        val s = session ?: return
        s.onPartial(text, clock.nowMs())
        _partial.value = s.partial
    }

    fun simulateRms(value: Double) { session?.onRms(value, clock.nowMs()) }

    /** 최종 결과. 듣는 중이 아니면 false */
    fun simulateFinal(text: String): Boolean = session?.onFinal(text, clock.nowMs()) ?: false

    fun simulateError(code: String) {
        session?.onError(code)
        if (code == SttErrors.UNAVAILABLE_LANGUAGE) _status.value = _status.value.copy(sttEnglish = false)
    }

    fun setStatus(status: SpeechStatus) { _status.value = status }

    companion object {
        const val ENGINE_NAME = "시뮬레이션"
    }
}
