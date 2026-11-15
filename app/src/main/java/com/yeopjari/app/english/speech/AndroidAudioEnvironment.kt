package com.yeopjari.app.english.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.yeopjari.app.english.core.AudioEnvironment
import com.yeopjari.app.english.core.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 오디오 환경 (명세 §5.6) — 표준 AudioManager API 만 쓴다 (녹음 없음).
 *
 * - navPromptActive: 다른 앱이 USAGE_ASSISTANCE_NAVIGATION_GUIDANCE 로 재생 중 (registerAudioPlaybackCallback).
 * - focusLost: 우리가 포커스를 쥔 동안 AUDIOFOCUS_LOSS* 를 받으면 true — 최소 3초, 다시 얻거나 놓으면 false (최대 15초).
 * - 포커스: 말하기·듣기 전에 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK (USAGE_ASSISTANT, CONTENT_TYPE_SPEECH) 요청,
 *   끝나면 1.2초 뒤 반납 (문장 사이에 음악이 들썩이지 않게 참조 횟수로 묶는다).
 * - Pleos TTS/STT 는 다른 프로세스(서비스)가 소리를 내므로, 그 서비스가 포커스를 가져가 생기는 '자기 유발' 손실은
 *   [markSelfAudio] 직후 1.5초 동안 무시한다 (자기 TTS 때문에 CRITICAL 로 멈추는 일 방지).
 */
class AndroidAudioEnvironment(
    context: Context,
    private val scope: CoroutineScope,
    private val clock: Clock,
) : AudioEnvironment {

    private val am: AudioManager? = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val main = Handler(Looper.getMainLooper())

    private val _nav = MutableStateFlow(false)
    private val _focusLost = MutableStateFlow(false)
    override val navPromptActive: StateFlow<Boolean> = _nav.asStateFlow()
    override val focusLost: StateFlow<Boolean> = _focusLost.asStateFlow()

    private val lock = Any()
    private var holders = 0
    /** 포커스 요청이 살아 있음 (반납 전) */
    private var requested = false
    /** 지금 포커스를 쥐고 있음 (일시 손실 중이면 false) */
    private var owned = false
    private var focusRequest: AudioFocusRequest? = null
    private var abandonJob: Job? = null
    private var lostJob: Job? = null
    @Volatile private var lostAtMs = 0L
    @Volatile private var selfAudioUntilMs = 0L
    @Volatile private var released = false

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            _nav.value = configs.orEmpty().any { isNavGuidance(it) }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change -> onFocusChange(change) }

    init {
        runCatching {
            am?.registerAudioPlaybackCallback(playbackCallback, main)
            _nav.value = am?.activePlaybackConfigurations.orEmpty().any { isNavGuidance(it) }
        }.onFailure { Log.i(TAG, "playback callback unavailable: ${it.javaClass.simpleName}") }
    }

    private fun isNavGuidance(c: AudioPlaybackConfiguration): Boolean =
        runCatching { c.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE }.getOrDefault(false)

    /** 말하기·듣기 시작 전에 (짝: [releaseFocus]) */
    fun acquireFocus() {
        synchronized(lock) {
            if (released) return
            holders++
            abandonJob?.cancel()
            abandonJob = null
            if (requested) return
            requested = true
        }
        val manager = am ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener, main)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .build()
        val result = runCatching { manager.requestAudioFocus(request) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        synchronized(lock) {
            focusRequest = request
            owned = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        // 통화 중 등으로 거절되면 잠깐 '다른 음성 안내 중' 으로 알린다
        if (result == AudioManager.AUDIOFOCUS_REQUEST_FAILED) markLost()
    }

    /** 말하기·듣기 끝 — 1.2초 안에 다시 쓰지 않으면 포커스 반납 */
    fun releaseFocus() {
        synchronized(lock) {
            if (holders > 0) holders--
            if (holders > 0 || released) return
            abandonJob?.cancel()
            abandonJob = scope.launch {
                delay(ABANDON_DELAY_MS)
                abandonNow()
            }
        }
    }

    /** Pleos TTS/STT 를 막 시작했다 — 그 서비스가 포커스를 가져가 생기는 손실은 잠깐 무시 */
    fun markSelfAudio() {
        selfAudioUntilMs = clock.nowMs() + SELF_AUDIO_GRACE_MS
    }

    private fun abandonNow() {
        val req = synchronized(lock) {
            if (holders > 0) return
            val r = focusRequest
            focusRequest = null
            requested = false
            owned = false
            r
        }
        if (req != null) runCatching { am?.abandonAudioFocusRequest(req) }
        maybeClearLost()
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                synchronized(lock) { owned = true }
                maybeClearLost()
            }
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                synchronized(lock) {
                    owned = false
                    if (change == AudioManager.AUDIOFOCUS_LOSS) requested = false
                }
                if (clock.nowMs() < selfAudioUntilMs) return
                markLost()
            }
        }
    }

    private fun markLost() {
        lostAtMs = clock.nowMs()
        _focusLost.value = true
        lostJob?.cancel()
        lostJob = scope.launch {
            delay(MIN_LOST_MS)
            // 3초 뒤: 다시 얻었거나 더는 쓰지 않으면 해제, 아니면 최대 15초까지 기다린다
            var waited = MIN_LOST_MS
            while (waited < MAX_LOST_MS) {
                val stillLost = synchronized(lock) { holders > 0 && !owned }
                if (!stillLost) break
                delay(POLL_MS)
                waited += POLL_MS
            }
            _focusLost.value = false
        }
    }

    private fun maybeClearLost() {
        if (!_focusLost.value) return
        if (clock.nowMs() - lostAtMs >= MIN_LOST_MS) {
            lostJob?.cancel()
            _focusLost.value = false
        }
        // 3초가 안 지났으면 markLost 의 작업이 3초 뒤에 내린다
    }

    fun release() {
        synchronized(lock) {
            released = true
            holders = 0
            abandonJob?.cancel()
        }
        lostJob?.cancel()
        runCatching { am?.unregisterAudioPlaybackCallback(playbackCallback) }
        abandonNow()
        _focusLost.value = false
        _nav.value = false
    }

    companion object {
        private const val TAG = "YeopjariAudio"
        const val ABANDON_DELAY_MS = 1_200L
        const val SELF_AUDIO_GRACE_MS = 1_500L
        const val MIN_LOST_MS = 3_000L
        const val MAX_LOST_MS = 15_000L
        private const val POLL_MS = 500L
    }
}
