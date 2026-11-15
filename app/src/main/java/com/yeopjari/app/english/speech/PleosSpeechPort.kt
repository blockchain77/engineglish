package com.yeopjari.app.english.speech

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.yeopjari.app.english.core.Clock
import com.yeopjari.app.english.core.ListenOutcome
import com.yeopjari.app.english.core.SpeakOutcome
import com.yeopjari.app.english.core.SpeechDurations
import com.yeopjari.app.english.core.SpeechLang
import com.yeopjari.app.english.core.SpeechPort
import com.yeopjari.app.english.core.SpeechRate
import com.yeopjari.app.english.core.SpeechStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit
import android.speech.tts.TextToSpeech as AndroidTts

/**
 * Pleos 음성 포트 (명세 §5.6) — 차량 마이크는 Pleos SpeechToText SDK 로만 듣는다 (RECORD_AUDIO·AudioRecord 없음).
 *
 * STT: SpeechToText(ON_DEVICE) 하나. 언어가 바뀔 때만 setLocale(Locale.US / Locale.KOREA) 후 request().
 *   listen() 창은 엔진이 없거나 오류가 나도 timeoutMs 동안 열어 둔다 → inject() 로 데모 답을 넣을 수 있다.
 *   엔진이 말을 못 듣고 일찍 끝나면(빈 결과·TIMEOUT) 남은 시간이 3초 이상일 때 한 번 다시 듣는다.
 * TTS: Pleos TextToSpeech(ON_DEVICE) 하나. setLocale 은 서비스가 '패키지별'로 저장하므로(SDK 확인)
 *   인스턴스를 둘 만들지 않고, 언어가 바뀔 때만 setLocale 한 뒤 speak 한다 (SDK 가 단일 스레드로 순서를 지킨다).
 *   한국어 = Pleos → Android TTS ko-KR. 영어 보통 = Pleos en-US (UNAVAILABLE_LANGUAGE 면 이후 Android TTS) → Android TTS en-US.
 *   영어 천천히 = Android TTS en-US (0.75 × 영어 속도) → 없으면 Pleos 로 단어 사이 쉼표.
 * 로그에는 오류 코드만 남긴다 (발화·답 내용은 남기지 않음).
 */
class PleosSpeechPort(
    context: Context,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val audio: AndroidAudioEnvironment,
) : SpeechPort {

    private val app: Context = context.applicationContext

    private val _status = MutableStateFlow(SpeechStatus(ttsKoEngine = ENGINE_NONE, ttsEnEngine = ENGINE_NONE))
    private val _speaking = MutableStateFlow(false)
    private val _listening = MutableStateFlow(false)
    private val _partial = MutableStateFlow("")
    override val status: StateFlow<SpeechStatus> = _status.asStateFlow()
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    override val listening: StateFlow<Boolean> = _listening.asStateFlow()
    override val partial: StateFlow<String> = _partial.asStateFlow()

    /** 영어 보통 속도에서 Pleos TTS 를 먼저 쓸지 (false = Android TTS 먼저). Pleos 가 영어를 거절하면 자동으로 Android */
    @Volatile var preferPleosEnglish: Boolean = true
        set(value) {
            field = value
            refreshEngineNames()
        }

    private val injections = InjectionBox()
    @Volatile private var session: ListenSession? = null
    @Volatile private var englishRate: Float = 1.0f
    private val speakGen = AtomicLong(0)

    // ───────── STT ─────────

    private val stt: SpeechToText? = runCatching { SpeechToText(app, SttMode.ON_DEVICE) }.getOrNull()
    @Volatile private var sttReady = false
    @Volatile private var sttLocale: Locale? = null
    @Volatile private var sttRunning = false

    private val sttListener = object : ResultListener {
        override fun onReady() { session?.onReady(now()) }
        override fun onStartedRecognition() { session?.onSpeechStart(now()) }
        override fun onUpdated(stt: String, completed: Boolean) { onSttText(stt, completed) }
        override fun onUpdatedEpdData(on: Long, off: Long) { session?.onEpdData(on, off) }
        override fun onUpdatedRms(rms: Double) { session?.onRms(rms, now()) }
        override fun onEndedRecognition() { session?.onSpeechEnd(now()) }
        override fun onError(errorType: SttError) { onSttError(errorType.name) }
    }

    // ───────── Pleos TTS ─────────

    private class Utterance(val issuedMs: Long) {
        val result = CompletableDeferred<SpeakOutcome>()
        @Volatile var started = false
        @Volatile var error: String? = null
    }

    private val pleosTts: TextToSpeech? = runCatching { TextToSpeech(app, TtsMode.ON_DEVICE) }.getOrNull()
    @Volatile private var pleosTtsReady = false
    @Volatile private var pleosLocale: Locale? = null
    /** null = 아직 모름, false = en-US 거절(UNAVAILABLE_LANGUAGE) */
    @Volatile private var pleosEnglishOk: Boolean? = null
    @Volatile private var pleosUtterance: Utterance? = null

    private val ttsListener = object : EventListener {
        override fun onReady() {}
        override fun onStart() { pleosUtterance?.started = true }
        override fun onDone() { finishPleos(SpeakOutcome.DONE, null) }
        override fun onStop() { finishPleos(SpeakOutcome.STOPPED, null) }
        override fun onUpdatedRms(rms: Double) {}
        override fun onError(errorType: TtsError) { finishPleos(SpeakOutcome.FAILED, errorType.name) }
    }

    // ───────── Android TTS (대체 · 영어 속도 조절) ─────────

    @Volatile private var androidTts: AndroidTts? = null
    @Volatile private var androidReady = false
    @Volatile private var androidEnglish = false
    private val androidWaiters = ConcurrentHashMap<String, CompletableDeferred<SpeakOutcome>>()
    private val utteranceSeq = AtomicLong(0)

    init {
        initStt()
        initPleosTts()
        initAndroidTts()
        refreshEngineNames()
    }

    fun setEnglishRate(rate: Float) {
        englishRate = rate.coerceIn(MIN_RATE, MAX_RATE)
    }

    // ═════════ SpeechPort ═════════

    override suspend fun speak(text: String, lang: SpeechLang, rate: SpeechRate): SpeakOutcome {
        if (text.isBlank()) return SpeakOutcome.DONE
        session?.stop()
        val gen = speakGen.incrementAndGet()
        if (_speaking.value) stopSpeech()
        _speaking.value = true
        audio.acquireFocus()
        try {
            for (route in routes(lang, rate)) {
                val r = when (route) {
                    Route.PLEOS -> speakPleos(if (lang == SpeechLang.EN && rate == SpeechRate.SLOW) slowText(text) else text, lang, rate)
                    Route.ANDROID -> speakAndroid(text, lang, rate)
                }
                if (r != SpeakOutcome.FAILED) return r
                if (speakGen.get() != gen) return SpeakOutcome.STOPPED
            }
            updateStatus { it.copy(lastErrorKo = "음성 출력을 쓸 수 없어요") }
            return SpeakOutcome.FAILED
        } finally {
            if (speakGen.get() == gen) _speaking.value = false
            audio.releaseFocus()
        }
    }

    override suspend fun listen(lang: SpeechLang, timeoutMs: Long): ListenOutcome {
        val opened = now()
        injections.take(opened)?.let { text -> return ListenOutcome.Heard(text, SpeechTimingRules.injected(opened, text, opened)) }
        if (_speaking.value) {
            speakGen.incrementAndGet()
            stopSpeech()
        }
        session?.stop()
        val s = ListenSession(lang, opened, timeoutMs)
        session = s
        _partial.value = ""
        _listening.value = true
        audio.acquireFocus()
        try {
            startRecognition(s)
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { s.awaitEnd() }
            if (s.end == ListenSession.End.FINAL && !s.hasEpd) {
                withTimeoutOrNull(SpeechTimingRules.EPD_GRACE_MS) { s.awaitEpd() }
            }
            return s.outcome(now())
        } finally {
            if (session === s) session = null
            stopRecognition()
            _listening.value = false
            _partial.value = ""
            audio.releaseFocus()
        }
    }

    override fun inject(text: String) {
        val t = now()
        val s = session
        if (s != null && s.inject(text, t)) {
            stopRecognition()
            return
        }
        injections.put(text, t)
    }

    /** 말하기·듣기를 즉시 멈추고, 쌓여 있던 주입 답도 버린다 (끼어들기: stopAll → inject 순서) */
    override fun stopAll() {
        speakGen.incrementAndGet()
        injections.clear()
        session?.stop()
        stopRecognition()
        stopSpeech()
    }

    fun release() {
        stopAll()
        injections.clear()
        runCatching { stt?.removeListener(sttListener) }
        runCatching { stt?.release() }
        runCatching { pleosTts?.removeEventListener(ttsListener) }
        runCatching { pleosTts?.release() }
        runCatching { androidTts?.shutdown() }
        androidTts = null
        androidReady = false
        pleosTtsReady = false
        sttReady = false
    }

    // ═════════ STT ═════════

    private fun initStt() {
        val engine = stt
        if (engine == null) {
            updateStatus { it.copy(sttReady = false, lastErrorKo = "음성 인식 SDK 를 쓸 수 없어요") }
            return
        }
        runCatching {
            engine.initialize(object : SttInit {
                override fun onSuccess() {
                    runCatching { engine.addListener(sttListener) }
                    // 앱 프로세스가 듣는 도중 강제 종료되면 음성 서비스가 이 앱 이름으로 계속 듣고 있을 수 있다 → 시작할 때 한 번 닫는다
                    if (!sttRunning) runCatching { engine.stop() }
                    runCatching {
                        engine.setLocale(Locale.US)
                        sttLocale = Locale.US
                    }
                    sttReady = true
                    updateStatus { it.copy(sttReady = true) }
                }

                override fun onFailure(errorType: SttError) {
                    Log.i(TAG, "stt init failed ${errorType.name}")
                    updateStatus { it.copy(sttReady = false, lastErrorKo = "음성 인식을 준비하지 못했어요") }
                }
            })
        }.onFailure { Log.i(TAG, "stt unavailable ${it.javaClass.simpleName}") }
    }

    private fun startRecognition(s: ListenSession): Boolean {
        val engine = stt
        if (engine == null) {
            s.onError(SttErrors.SDK_MISSING)
            return false
        }
        if (!sttReady) {
            s.onError(SttErrors.NOT_READY)
            return false
        }
        val locale = if (s.lang == SpeechLang.EN) Locale.US else Locale.KOREA
        return try {
            if (sttLocale != locale) {
                engine.setLocale(locale)
                sttLocale = locale
            }
            engine.request()
            sttRunning = true
            audio.markSelfAudio()
            true
        } catch (t: Throwable) {
            Log.i(TAG, "stt request failed ${t.javaClass.simpleName}")
            s.onError(SttErrors.REQUEST_FAILED)
            false
        }
    }

    private fun stopRecognition() {
        if (!sttRunning) return
        sttRunning = false
        runCatching { stt?.stop() }
    }

    private fun onSttText(text: String, completed: Boolean) {
        val s = session ?: return
        val t = now()
        if (!completed) {
            s.onPartial(text, t)
            if (text.isNotBlank()) _partial.value = s.partial
            return
        }
        sttRunning = false
        if (s.onFinal(text, t)) {
            if (s.lang == SpeechLang.EN) setSttEnglish(true)
        } else if (text.isBlank()) {
            maybeRestart(s)
        }
    }

    private fun onSttError(code: String) {
        Log.i(TAG, "stt error $code")
        val s = session
        if (code == SttErrors.UNAVAILABLE_LANGUAGE && sttLocale == Locale.US) setSttEnglish(false)
        if (s == null) return
        sttRunning = false
        s.onError(code)
        if (code == SttErrors.TIMEOUT) maybeRestart(s)
        else updateStatus { it.copy(lastErrorKo = SttErrors.reasonKo(code, s.lang)) }
    }

    /** 엔진이 아무것도 못 듣고 끝났는데 창이 3초 이상 남았으면 잠깐 쉬었다가 한 번 더 듣는다 */
    private fun maybeRestart(s: ListenSession) {
        if (session !== s || s.isEnded) return
        if (s.deadlineMs - now() < RESTART_MIN_REMAINING_MS || s.restarts >= MAX_RESTARTS) return
        s.restarts++
        scope.launch {
            delay(RESTART_DELAY_MS)
            if (session === s && !s.isEnded && !sttRunning) startRecognition(s)
        }
    }

    private fun setSttEnglish(ok: Boolean) {
        if (_status.value.sttEnglish != ok) updateStatus { it.copy(sttEnglish = ok) }
    }

    // ═════════ TTS ═════════

    private enum class Route { PLEOS, ANDROID }

    private fun routes(lang: SpeechLang, rate: SpeechRate): List<Route> = when {
        lang == SpeechLang.KO -> listOf(Route.PLEOS, Route.ANDROID)
        rate == SpeechRate.SLOW -> listOf(Route.ANDROID, Route.PLEOS)
        preferPleosEnglish && pleosEnglishOk != false -> listOf(Route.PLEOS, Route.ANDROID)
        else -> listOf(Route.ANDROID, Route.PLEOS)
    }

    private fun initPleosTts() {
        val tts = pleosTts ?: return
        runCatching {
            tts.initialize(object : TtsInit {
                override fun onSuccess() {
                    runCatching { tts.addEventListener(ttsListener) }
                    runCatching {
                        tts.setLocale(Locale.KOREA)
                        pleosLocale = Locale.KOREA
                    }
                    pleosTtsReady = true
                    refreshEngineNames()
                }

                override fun onFailure(errorType: TtsError) {
                    Log.i(TAG, "pleos tts init failed ${errorType.name}")
                    refreshEngineNames()
                }
            })
        }.onFailure { Log.i(TAG, "pleos tts unavailable ${it.javaClass.simpleName}") }
    }

    private suspend fun speakPleos(text: String, lang: SpeechLang, rate: SpeechRate): SpeakOutcome {
        val tts = pleosTts ?: return SpeakOutcome.FAILED
        if (!pleosTtsReady) return SpeakOutcome.FAILED
        if (lang == SpeechLang.EN && pleosEnglishOk == false) return SpeakOutcome.FAILED
        val locale = if (lang == SpeechLang.EN) Locale.US else Locale.KOREA
        val u = Utterance(now())
        pleosUtterance = u
        try {
            if (pleosLocale != locale) {
                tts.setLocale(locale)
                pleosLocale = locale
            }
            tts.speak(text, true)
        } catch (t: Throwable) {
            Log.i(TAG, "pleos tts speak failed ${t.javaClass.simpleName}")
            if (pleosUtterance === u) pleosUtterance = null
            return SpeakOutcome.FAILED
        }
        audio.markSelfAudio()
        try {
            val r = withTimeoutOrNull(safetyMs(text, lang, rate)) { u.result.await() }
                ?: if (u.started) SpeakOutcome.DONE else SpeakOutcome.FAILED
            if (lang == SpeechLang.EN) {
                if (r == SpeakOutcome.FAILED && u.error == SttErrors.UNAVAILABLE_LANGUAGE) setPleosEnglish(false)
                else if (r == SpeakOutcome.DONE && u.started) setPleosEnglish(true)
            }
            if (r == SpeakOutcome.FAILED) Log.i(TAG, "pleos tts failed ${u.error ?: "NO_CALLBACK"}")
            return r
        } catch (e: CancellationException) {
            runCatching { tts.stop() }
            throw e
        } finally {
            if (pleosUtterance === u) pleosUtterance = null
        }
    }

    /** 이전 발화의 늦은 onStop/onDone 은 새 발화를 끝내지 않게 거른다 */
    private fun finishPleos(outcome: SpeakOutcome, error: String?) {
        val u = pleosUtterance ?: return
        if (!u.started && outcome != SpeakOutcome.FAILED && now() - u.issuedMs < STALE_EVENT_MS) return
        if (error != null) u.error = error
        u.result.complete(outcome)
    }

    private fun setPleosEnglish(ok: Boolean) {
        if (pleosEnglishOk == ok) return
        pleosEnglishOk = ok
        refreshEngineNames()
    }

    private fun initAndroidTts() {
        androidTts = runCatching { AndroidTts(app) { status -> onAndroidInit(status) } }.getOrNull()
    }

    private fun onAndroidInit(status: Int) {
        val tts = androidTts
        if (status != AndroidTts.SUCCESS || tts == null) {
            refreshEngineNames()
            return
        }
        androidEnglish = runCatching { tts.isLanguageAvailable(Locale.US) >= AndroidTts.LANG_AVAILABLE }.getOrDefault(false)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { completeAndroid(utteranceId, SpeakOutcome.DONE) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { completeAndroid(utteranceId, SpeakOutcome.FAILED) }
            override fun onError(utteranceId: String?, errorCode: Int) { completeAndroid(utteranceId, SpeakOutcome.FAILED) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { completeAndroid(utteranceId, SpeakOutcome.STOPPED) }
        })
        androidReady = true
        refreshEngineNames()
    }

    private fun completeAndroid(id: String?, outcome: SpeakOutcome) {
        if (id == null) return
        androidWaiters[id]?.complete(outcome)
    }

    private suspend fun speakAndroid(text: String, lang: SpeechLang, rate: SpeechRate): SpeakOutcome {
        val tts = androidTts ?: return SpeakOutcome.FAILED
        if (!androidReady) return SpeakOutcome.FAILED
        val locale = if (lang == SpeechLang.EN) Locale.US else Locale.KOREA
        val speechRate = when {
            lang == SpeechLang.KO -> 1.0f
            rate == SpeechRate.SLOW -> SLOW_FACTOR * englishRate
            else -> englishRate
        }
        val id = "yeopjari-" + utteranceSeq.incrementAndGet()
        val waiter = CompletableDeferred<SpeakOutcome>()
        androidWaiters[id] = waiter
        try {
            val queued = runCatching {
                tts.setLanguage(locale)
                tts.setSpeechRate(speechRate)
                tts.speak(text, AndroidTts.QUEUE_FLUSH, null, id) == AndroidTts.SUCCESS
            }.getOrDefault(false)
            if (!queued) return SpeakOutcome.FAILED
            audio.markSelfAudio()
            return withTimeoutOrNull(safetyMs(text, lang, rate)) { waiter.await() } ?: SpeakOutcome.DONE
        } catch (e: CancellationException) {
            runCatching { tts.stop() }
            throw e
        } finally {
            androidWaiters.remove(id)
        }
    }

    private fun stopSpeech() {
        pleosUtterance?.result?.complete(SpeakOutcome.STOPPED)
        for (w in androidWaiters.values) w.complete(SpeakOutcome.STOPPED)
        if (pleosTtsReady) runCatching { pleosTts?.stop() }
        if (androidReady) runCatching { androidTts?.stop() }
        _speaking.value = false
    }

    private fun refreshEngineNames() {
        val ko = when {
            pleosTtsReady -> "Pleos TTS (온디바이스)"
            androidReady -> "Android TTS (대체)"
            else -> ENGINE_NONE
        }
        val en = when {
            preferPleosEnglish && pleosTtsReady && pleosEnglishOk != false -> "Pleos TTS en-US"
            androidReady -> if (androidEnglish) "Android TTS en-US" else "Android TTS (영어 음성 미확인)"
            pleosTtsReady && pleosEnglishOk != false -> "Pleos TTS en-US"
            else -> ENGINE_NONE
        }
        updateStatus { it.copy(ttsKoEngine = ko, ttsEnEngine = en) }
    }

    private fun updateStatus(f: (SpeechStatus) -> SpeechStatus) {
        _status.update(f)
    }

    private fun now(): Long = clock.nowMs()

    companion object {
        private const val TAG = "YeopjariSpeech"
        const val ENGINE_NONE = "없음"
        const val SLOW_FACTOR = 0.75f
        const val MIN_RATE = 0.6f
        const val MAX_RATE = 1.3f
        const val RESTART_MIN_REMAINING_MS = 3_000L
        const val RESTART_DELAY_MS = 250L
        const val MAX_RESTARTS = 1
        const val STALE_EVENT_MS = 300L

        /** speak 안전 시간 = 추정 × 2 + 3초 (콜백이 오지 않아도 멈추지 않게) */
        fun safetyMs(text: String, lang: SpeechLang, rate: SpeechRate): Long = SpeechDurations.estimateMs(text, lang, rate) * 2 + 3_000L

        /** Pleos 에는 속도 설정이 없어 단어 사이에 쉼표를 넣어 천천히 읽게 한다 */
        fun slowText(text: String): String = text.trim().split(Regex("\\s+")).joinToString(", ")
    }
}
