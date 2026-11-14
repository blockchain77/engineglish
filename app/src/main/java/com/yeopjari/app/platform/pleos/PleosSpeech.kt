package com.yeopjari.app.platform.pleos

import android.content.Context
import android.util.Log
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import com.yeopjari.app.core.gateway.SpeechInput
import com.yeopjari.app.core.gateway.SpeechOutput
import com.yeopjari.app.core.gateway.SttEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * Pleos SpeechToText SDK — 온디바이스 모드.
 * 차량 마이크 음성은 차량 밖(외부 STT)으로 보내지 않는다. 텍스트로 변환된 결과만 AI 계층에 전달.
 */
class PleosSpeechInput(context: Context) : SpeechInput {
    private val stt = runCatching { SpeechToText(context, SttMode.ON_DEVICE) }.getOrNull()
    @Volatile override var available: Boolean = false
        private set
    private val _events = MutableSharedFlow<SttEvent>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<SttEvent> = _events.asSharedFlow()

    private val listener = object : ResultListener {
        override fun onReady() { _events.tryEmit(SttEvent.Ready) }
        override fun onStartedRecognition() {}
        override fun onUpdated(stt: String, completed: Boolean) {
            _events.tryEmit(if (completed) SttEvent.Final(stt) else SttEvent.Partial(stt))
        }
        override fun onUpdatedEpdData(on: Long, off: Long) {}
        override fun onUpdatedRms(rms: Double) {}
        override fun onEndedRecognition() { _events.tryEmit(SttEvent.End) }
        override fun onError(errorType: SttError) { _events.tryEmit(SttEvent.Error(errorType.name)) }
    }

    init {
        runCatching {
            stt?.initialize(object : SttInit {
                override fun onSuccess() {
                    available = true
                    stt.setLocale(Locale.KOREA)
                    stt.addListener(listener)
                }
                override fun onFailure(errorType: SttError) { Log.i(TAG, "STT 초기화 실패: $errorType") }
            })
        }.onFailure { Log.i(TAG, "Pleos STT SDK 사용 불가", it) }
    }

    override fun start() { runCatching { stt?.request() }.onFailure { _events.tryEmit(SttEvent.Error(it.message ?: "STT")) } }
    override fun stop() { runCatching { stt?.stop() } }
    fun release() { runCatching { stt?.removeListener(listener); stt?.release() } }

    companion object { private const val TAG = "PleosSTT" }
}

/** Pleos TextToSpeech SDK — 온디바이스 모드 (Client Secret 하드코딩 불필요) */
class PleosSpeechOutput(context: Context) : SpeechOutput {
    private val tts = runCatching { TextToSpeech(context, TtsMode.ON_DEVICE) }.getOrNull()
    @Volatile override var available: Boolean = false
        private set
    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val listener = object : EventListener {
        override fun onReady() {}
        override fun onStart() { _speaking.value = true }
        override fun onDone() { _speaking.value = false }
        override fun onStop() { _speaking.value = false }
        override fun onUpdatedRms(rms: Double) {}
        override fun onError(errorType: TtsError) { _speaking.value = false; Log.w(TAG, "TTS error $errorType") }
    }

    init {
        runCatching {
            tts?.initialize(object : TtsInit {
                override fun onSuccess() {
                    available = true
                    tts.setLocale(Locale.KOREA)
                    tts.addEventListener(listener)
                }
                override fun onFailure(errorType: TtsError) { Log.i(TAG, "TTS 초기화 실패: $errorType") }
            })
        }.onFailure { Log.i(TAG, "Pleos TTS SDK 사용 불가", it) }
    }

    override fun speak(text: String, utteranceId: String) {
        runCatching { _speaking.value = true; tts?.speak(text, true) }.onFailure { _speaking.value = false }
    }
    override fun stop() { runCatching { tts?.stop() }; _speaking.value = false }
    fun release() { runCatching { tts?.removeEventListener(listener); tts?.release() } }

    companion object { private const val TAG = "PleosTTS" }
}

/** 비(非)Pleos 환경(일반 AAOS 에뮬레이터 등) 개발용 대체 음성 출력 — 안드로이드 기본 TTS 엔진 */
class AndroidSpeechOutput(context: Context) : SpeechOutput {
    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    @Volatile override var available: Boolean = false
        private set
    private var engine: android.speech.tts.TextToSpeech? = null

    init {
        engine = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                engine?.language = Locale.KOREAN
                engine?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) { _speaking.value = true }
                    override fun onDone(id: String?) { _speaking.value = false }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { _speaking.value = false }
                })
                available = true
            }
        }
    }

    override fun speak(text: String, utteranceId: String) {
        _speaking.value = true
        engine?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }
    override fun stop() { engine?.stop(); _speaking.value = false }
}

/** Pleos TTS 우선, 실패 시 안드로이드 TTS */
class CompositeSpeechOutput(private val primary: SpeechOutput, private val fallback: SpeechOutput) : SpeechOutput {
    private val current: SpeechOutput get() = if (primary.available) primary else fallback
    override val available: Boolean get() = primary.available || fallback.available
    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> get() = current.speaking
    override fun speak(text: String, utteranceId: String) = current.speak(text, utteranceId)
    override fun stop() { primary.stop(); fallback.stop(); _speaking.value = false }
    val engineName: String get() = if (primary.available) "Pleos TTS (온디바이스)" else if (fallback.available) "Android TTS (대체)" else "음성 출력 없음"
}
