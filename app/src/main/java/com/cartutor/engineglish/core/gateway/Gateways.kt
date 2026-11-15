package com.cartutor.engineglish.core.gateway

import com.cartutor.engineglish.core.model.GeoPoint
import com.cartutor.engineglish.core.model.Place
import com.cartutor.engineglish.core.model.RouteState
import com.cartutor.engineglish.core.model.VehicleSnapshot
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

/**
 * 앰비언트 라이트 색 (Pleos Vehicle SDK · Light.get/setAmbientLightBrightnessColor) — 정답/오답 1초 펄스용.
 * 읽기는 CAR_LIGHTS, 쓰기는 CONTROL_CAR_LIGHTS 권한이 필요하다. 실패·미지원이면 null / false.
 */
interface AmbientLightGateway {
    /** 지금 운전석 앰비언트 색 (RGB int). 못 읽으면 null */
    suspend fun readColor(): Int?
    /** 색 바꾸기 (RGB 0xRRGGBB). 성공하면 true */
    suspend fun writeColor(rgb: Int): Boolean
}
