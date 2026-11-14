package com.yeopjari.app.core.gateway

import com.yeopjari.app.core.model.GeoPoint
import com.yeopjari.app.core.model.Place
import com.yeopjari.app.core.model.RouteState
import com.yeopjari.app.core.model.VehicleSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 차량 플랫폼 추상화 계층.
 * 앱의 모든 기능은 아래 인터페이스에만 의존하며, 구현은 두 가지다.
 *  - Pleos*Gateway : Pleos Connect SDK(Vehicle / NaviHelper / STT / TTS / FusedLocation) 실연동
 *  - Sim*Gateway   : 시연·개발용 시뮬레이터 (Pleos 에뮬레이터 Virtual Console과 병행 사용)
 */
enum class Source(val label: String) { PLEOS_SDK("Pleos SDK"), SIMULATOR("시뮬레이터") }

interface VehicleGateway {
    val source: Source
    val snapshot: StateFlow<VehicleSnapshot>
    suspend fun setHvacPower(on: Boolean): Result<Unit>
    suspend fun setHvacTemperature(celsius: Float): Result<Unit>
    suspend fun setFanSpeed(level: Int): Result<Unit>
    suspend fun setRecirculation(on: Boolean): Result<Unit>
    /** EvBattery.setEvChargePercentLimit — 지하주차장 90% 제한 등 */
    suspend fun setChargeLimit(percent: Int): Result<Unit>
    fun start()
    fun stop()
}

interface NaviGateway {
    val source: Source
    val route: StateFlow<RouteState>
    suspend fun setDestination(place: Place): Result<Unit>
    suspend fun addWaypoint(place: Place): Result<Unit>
    suspend fun cancelGuidance(): Result<Unit>
}

/** 운전자 주의분산 제한 상태 (AAOS CarUxRestrictionsManager) */
interface UxRestrictionGateway {
    val source: Source
    /** true = 주의분산 최적화 필요 (주행 중) */
    val restricted: StateFlow<Boolean>
}

interface LocationGateway {
    val source: Source
    val location: StateFlow<GeoPoint?>
}

sealed interface SttEvent {
    data object Ready : SttEvent
    data class Partial(val text: String) : SttEvent
    data class Final(val text: String) : SttEvent
    data class Error(val message: String) : SttEvent
    data object End : SttEvent
}

/** 음성 입력 — 차량 마이크 음성은 반드시 Pleos SpeechToText SDK로만 처리한다 (외부 STT 전송 금지) */
interface SpeechInput {
    val available: Boolean
    val events: Flow<SttEvent>
    fun start()
    fun stop()
}

/** 음성 출력 — Pleos TextToSpeech SDK */
interface SpeechOutput {
    val available: Boolean
    val speaking: StateFlow<Boolean>
    fun speak(text: String, utteranceId: String = System.currentTimeMillis().toString())
    fun stop()
}
