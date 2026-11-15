package com.yeopjari.app.english.drive

import com.yeopjari.app.core.gateway.AmbientLightGateway
import com.yeopjari.app.english.core.AmbientPulse
import com.yeopjari.app.english.core.Clock
import com.yeopjari.app.english.core.PulseKind
import com.yeopjari.app.english.core.VehicleFeedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 차량 피드백 (명세 §4.10).
 *  - pulse: 정답 초록 / 다시 주황을 1초 동안 — 화면 가장자리 빛은 [lastPulse] 로 항상, 실제 앰비언트 라이트는
 *    Vehicle SDK Light API 가 될 때만 (현재 색을 읽고 → 펄스 색 → 1초 뒤 원래 색). 한 번이라도 실패하면 더 시도하지 않는다.
 *  - showHudHint: 앞유리 HUD 시뮬레이터에 힌트 한 단어 (공개 HUD API 가 없어 앱 안 HUD 로 보여 준다).
 * 코치는 부하 ≤ NORMAL 이고 설정이 켜져 있을 때만 pulse 를 부른다.
 */
class PleosVehicleFeedback(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val light: AmbientLightGateway?,
) : VehicleFeedback {

    private val _ambientSupported = MutableStateFlow(false)
    override val ambientSupported: StateFlow<Boolean> = _ambientSupported.asStateFlow()

    private val _lastPulse = MutableStateFlow<AmbientPulse?>(null)
    override val lastPulse: StateFlow<AmbientPulse?> = _lastPulse.asStateFlow()

    private val _hudHint = MutableStateFlow<String?>(null)
    override val hudHint: StateFlow<String?> = _hudHint.asStateFlow()

    private var seq = 0L
    private var disabled = light == null
    /** 펄스 전 원래 색 (펄스가 겹쳐도 처음 색으로 되돌린다) */
    private var originalColor: Int? = null
    private var job: Job? = null

    override fun pulse(kind: PulseKind) {
        val p = AmbientPulse(++seq, kind, clock.nowMs())
        _lastPulse.value = p
        val port = light ?: return
        if (disabled) return
        job?.cancel()
        job = scope.launch {
            try {
                val base = originalColor ?: withTimeoutOrNull(IO_TIMEOUT_MS) { port.readColor() }
                if (base == null) {
                    disable()
                    return@launch
                }
                originalColor = base
                val ok = withTimeoutOrNull(IO_TIMEOUT_MS) { port.writeColor(rgbOf(kind)) } ?: false
                if (!ok) {
                    disable()
                    return@launch
                }
                _ambientSupported.value = true
                delay(p.durationMs)
                withTimeoutOrNull(IO_TIMEOUT_MS) { port.writeColor(base) }
                originalColor = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                disable()
            }
        }
    }

    override fun showHudHint(text: String?) {
        _hudHint.value = text?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 앱 종료 — 펄스 중이면 원래 색으로 */
    fun release() {
        job?.cancel()
        job = null
        val base = originalColor ?: return
        val port = light ?: return
        originalColor = null
        if (!disabled) scope.launch { runCatching { withTimeoutOrNull(IO_TIMEOUT_MS) { port.writeColor(base) } } }
    }

    private fun disable() {
        disabled = true
        originalColor = null
        _ambientSupported.value = false
    }

    companion object {
        const val IO_TIMEOUT_MS = 1_500L

        /** PulseKind.argb (0xAARRGGBB) → Light API 용 RGB int (0xRRGGBB) */
        fun rgbOf(kind: PulseKind): Int = (kind.argb and 0xFFFFFFL).toInt()
    }
}
