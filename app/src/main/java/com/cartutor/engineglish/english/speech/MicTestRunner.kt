package com.cartutor.engineglish.english.speech

import android.util.Log
import com.cartutor.engineglish.english.core.Clock
import com.cartutor.engineglish.english.core.ListenOutcome
import com.cartutor.engineglish.english.core.MicTestState
import com.cartutor.engineglish.english.core.MicTester
import com.cartutor.engineglish.english.core.SpeechLang
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 설정 '마이크 테스트' — Pleos SpeechToText 로 6초 동안 한 번 듣고, 받아 적은 글자와 입력 크기를 보여 준다.
 *
 * 마이크 소리는 Pleos 음성 인식 서비스가 받는다. 이 앱은 글자와 크기 숫자(RMS)만 받는다 (RECORD_AUDIO·AudioRecord 없음).
 * 입력 크기는 서비스마다 단위가 달라(dB 또는 0..1) 이번 테스트에서 들어온 최소~최대 범위로 맞춘 상대값을 쓴다.
 * 로그에는 글자 수와 값 개수만 남긴다 (말한 내용은 남기지 않는다).
 */
class MicTestRunner(
    private val port: PleosSpeechPort,
    private val scope: CoroutineScope,
    private val clock: Clock,
) : MicTester {

    private val _state = MutableStateFlow(MicTestState())
    override val state: StateFlow<MicTestState> = _state.asStateFlow()

    private var job: Job? = null

    override fun start(lang: SpeechLang) {
        if (job?.isActive == true) return
        val baseCount = port.inputRms.value.count
        _state.value = MicTestState(running = true, lang = lang, deadlineMs = clock.nowMs() + WINDOW_MS)
        job = scope.launch {
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            val meter = launch {
                port.inputRms.collect { r ->
                    val n = r.count - baseCount
                    if (n <= 0) return@collect
                    lo = minOf(lo, r.value)
                    hi = maxOf(hi, r.value)
                    val level = relative(r.value, lo, hi)
                    _state.update { it.copy(level = level, peak = maxOf(it.peak, level), levelSamples = n) }
                }
            }
            val words = launch {
                port.partial.collect { p -> if (p.isNotBlank()) _state.update { it.copy(heard = p) } }
            }
            val outcome = try {
                port.listen(lang, WINDOW_MS)
            } finally {
                meter.cancel()
                words.cancel()
            }
            val samples = _state.value.levelSamples
            // 값이 하나도 안 왔거나 내내 같은 값이면(막힌 마이크는 0 만 보낸다) 소리가 들어오지 않은 것
            val live = samples > 0 && hi - lo > FLAT_SPAN
            val (text, ok, result) = when (outcome) {
                is ListenOutcome.Heard -> Triple(outcome.text, true, "잘 들려요. 말로 답할 수 있어요.")
                ListenOutcome.Silence ->
                    if (live) Triple(_state.value.heard, false, "소리는 들어오는데 말을 알아듣지 못했어요. 조금 더 크게 말해 보세요.")
                    else Triple("", false, "아무 소리도 들어오지 않았어요. 마이크 연결을 확인해 주세요.")
                ListenOutcome.Stopped -> Triple(_state.value.heard, null, "테스트를 멈췄어요.")
                is ListenOutcome.Failed -> Triple(_state.value.heard, false, outcome.reasonKo)
            }
            val range = if (samples > 0) "%.4f..%.4f".format(java.util.Locale.US, lo, hi) else "none"
            Log.i(TAG, "mic test ${outcome.javaClass.simpleName} lang=$lang chars=${text.length} rmsSamples=$samples rmsRange=$range live=$live")
            _state.update { it.copy(running = false, heard = text, level = 0f, peak = if (live) it.peak else 0f, resultKo = result, ok = ok) }
        }
    }

    override fun cancel() {
        val j = job ?: return
        if (!j.isActive) return
        j.cancel()
        _state.update { it.copy(running = false, level = 0f, resultKo = "테스트를 멈췄어요.", ok = null) }
    }

    companion object {
        private const val TAG = "EngineglishSpeech"
        const val WINDOW_MS = 6_000L
        /** 입력 크기 값의 최소~최대 차이가 이보다 작으면 '변화 없음'(소리 없음)으로 본다 */
        const val FLAT_SPAN = 1e-6

        /** 이번 테스트에서 본 최소~최대 범위 안의 위치 (범위가 아직 좁으면 0.15 로 '들어오고 있음'만 표시) */
        fun relative(value: Double, lo: Double, hi: Double): Float {
            val span = hi - lo
            if (span <= 1e-9) return 0.15f
            return ((value - lo) / span).toFloat().coerceIn(0f, 1f)
        }
    }
}
