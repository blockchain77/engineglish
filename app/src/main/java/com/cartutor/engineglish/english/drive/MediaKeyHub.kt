package com.cartutor.engineglish.english.drive

import android.content.Context
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cartutor.engineglish.english.core.Clock
import com.cartutor.engineglish.english.core.MediaKey
import com.cartutor.engineglish.english.core.MediaKeyPort
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 핸들 미디어 버튼 (명세 §4.9) — 다음 = 넘기기, 이전 = 다시, 재생/일시정지 = 잠깐/계속.
 *
 * 두 길로 받는다:
 *  1) 앱이 앞에 있을 때: MainActivity.onKeyDown/onKeyUp → [onKeyEvent] (키를 누른 순간, 반복 0 일 때만)
 *  2) 내비 화면 등 다른 앱이 앞에 있을 때: android.media.session.MediaSession 콜백 (세션 중에만 활성)
 * 같은 버튼이 두 길로 동시에 와도 300ms 안의 같은 키는 한 번만 보낸다.
 * context = null 이면 MediaSession 없이 키 매핑만 한다 (JVM 테스트).
 */
class MediaKeyHub(context: Context?, private val clock: Clock) : MediaKeyPort {

    private val appContext: Context? = context?.applicationContext ?: context
    private val _keys = MutableSharedFlow<MediaKey>(extraBufferCapacity = 16)
    override val keys: SharedFlow<MediaKey> = _keys.asSharedFlow()

    private val _active = MutableStateFlow(false)
    /** MediaSession 이 활성인가 (세션 진행 중) */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private var session: MediaSession? = null
    private var lastKey: MediaKey? = null
    private var lastKeyAtMs = 0L

    override fun onKeyEvent(keyCode: Int, down: Boolean): Boolean = onKeyEvent(keyCode, down, 0)

    /** repeatCount > 0 (길게 누름 반복)은 무시하고 소비만 한다 */
    fun onKeyEvent(keyCode: Int, down: Boolean, repeatCount: Int): Boolean {
        val key = MediaKey.fromKeyCode(keyCode) ?: return false
        if (down && repeatCount == 0) dispatch(key, debounce = true)
        return true
    }

    override fun inject(key: MediaKey) = dispatch(key, debounce = false)

    override fun setActive(active: Boolean) {
        _active.value = active
        val ctx = appContext ?: return
        runCatching {
            val s = session ?: if (active) createSession(ctx).also { session = it } else return
            s.setPlaybackState(playbackState(if (active) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED))
            s.isActive = active
        }.onFailure { Log.i(TAG, "MediaSession unavailable: ${it.javaClass.simpleName}") }
    }

    fun release() {
        _active.value = false
        runCatching {
            session?.isActive = false
            session?.release()
        }
        session = null
    }

    private fun dispatch(key: MediaKey, debounce: Boolean) {
        val now = clock.nowMs()
        if (debounce && key == lastKey && now - lastKeyAtMs in 0 until DEBOUNCE_MS) {
            // 길게 누르고 있으면 창을 늘린다 (한 번 누름 = 한 문제)
            lastKeyAtMs = now
            return
        }
        lastKey = key
        lastKeyAtMs = now
        _keys.tryEmit(key)
    }

    private fun createSession(ctx: Context): MediaSession {
        val s = MediaSession(ctx, SESSION_TAG)
        s.setCallback(object : MediaSession.Callback() {
            override fun onSkipToNext() {
                dispatch(MediaKey.NEXT, debounce = true)
            }

            override fun onSkipToPrevious() {
                dispatch(MediaKey.PREVIOUS, debounce = true)
            }

            override fun onPlay() {
                dispatch(MediaKey.PLAY_PAUSE, debounce = true)
            }

            override fun onPause() {
                dispatch(MediaKey.PLAY_PAUSE, debounce = true)
            }
        }, Handler(Looper.getMainLooper()))
        return s
    }

    private fun playbackState(state: Int): PlaybackState = PlaybackState.Builder()
        .setActions(
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE,
        )
        .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
        .build()

    companion object {
        const val SESSION_TAG = "Engineglish"
        const val DEBOUNCE_MS = 300L
        private const val TAG = "MediaKeyHub"
    }
}
