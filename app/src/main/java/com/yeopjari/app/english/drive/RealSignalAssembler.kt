package com.yeopjari.app.english.drive

import com.yeopjari.app.core.model.Place
import com.yeopjari.app.core.model.RouteState
import com.yeopjari.app.core.model.VehicleSnapshot
import com.yeopjari.app.english.core.CommuteMap
import com.yeopjari.app.english.core.CommutePlace
import com.yeopjari.app.english.core.DestinationCategory
import com.yeopjari.app.english.core.DrivingSignals
import com.yeopjari.app.english.core.GearPosition
import com.yeopjari.app.english.core.LatLng
import com.yeopjari.app.english.core.LoadThresholds
import com.yeopjari.app.english.core.RoadKind
import com.yeopjari.app.english.core.SignalOrigin
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 실차(Pleos) 원천 → DrivingSignals (명세 §4.1 표). 순수 Kotlin + 내부 상태(가속도·핸들 각속도·정체·주행 id).
 *
 * - 가속도: 속도 표본으로 ≥300ms 창의 Δv/Δt
 * - 핸들 각속도: 연속 표본의 |Δ각|/Δt (1초 동안 변화 없으면 0)
 * - 정체: D 단에서 시속 15km 미만이 30초 이상 (시속 15km 이상 10초면 풀림)
 * - 구간: 위치를 지도 구간 선분에 150m 안으로 맞춤, 터널 = 구간 도로가 TUNNEL
 * - 목적지: 이름이나 300m 안으로 지도 장소에 맞추고, '공항'이면 AIRPORT. 지도 밖 목적지 이름은 화면에 쓰지 않는다 ("목적지")
 */
class RealSignalAssembler(private val map: CommuteMap) {

    data class Inputs(
        val vehicle: VehicleSnapshot,
        val route: RouteState,
        val location: LatLng?,
        val uxRestricted: Boolean,
        val lastArrivalMs: Long?,
    )

    private class SpeedSample(val atMs: Long, val kph: Float)

    private val speeds = ArrayDeque<SpeedSample>()
    private var lastSteerDeg: Float? = null
    private var lastSteerChangeMs = 0L
    private var steerRate = 0f
    private var slowSinceMs: Long? = null
    private var fastSinceMs: Long? = null
    private var jam = false
    private val driveIds = DriveIdTracker()
    private var originPlaceId: String? = null

    fun assemble(input: Inputs, nowMs: Long): DrivingSignals {
        val v = input.vehicle
        val r = input.route
        val gear = NaviMapping.gear(v.gear.name)
        val speed = v.speedKph.coerceAtLeast(0f)
        val accel = accelAt(nowMs, speed)
        val rate = steeringRate(nowMs, v.steeringDeg)

        if (driveIds.update(gear, nowMs)) {
            originPlaceId = input.location?.let { MapMatcher.nearestPlace(map, it) }?.id
        }
        val jamNow = trafficJam(gear, speed, nowMs)

        val segment = input.location?.let { MapMatcher.match(map, it) }
        val dest = if (r.active) r.destination else null
        val destPlace = matchDestination(dest)
        val airport = destPlace == null && NaviMapping.isAirportName(dest?.name)
        val category = when {
            destPlace != null -> destPlace.category
            airport -> DestinationCategory.AIRPORT
            dest != null -> DestinationCategory.OTHER
            else -> null
        }
        val destName = when {
            destPlace != null -> destPlace.nameKo
            airport -> dest?.name
            dest != null -> DestinationCategory.OTHER.ko
            else -> null
        }
        val arrived = input.lastArrivalMs?.let { nowMs - it in 0 until ARRIVED_MS } ?: false

        return DrivingSignals(
            atMs = nowMs,
            origin = SignalOrigin.PLEOS,
            gear = gear,
            speedKph = speed,
            speedLimitKph = r.speedLimitKph?.takeIf { it > 0 } ?: r.camera?.limitKph,
            accelMps2 = accel,
            brakePedal = v.brakePedal,
            turnSignal = v.turnSignal,
            steeringDeg = v.steeringDeg,
            steeringRateDps = rate,
            cruiseActive = v.cruiseActive,
            wipersOn = v.wipersOn,
            headlightsOn = v.headlightsOn,
            uxRestricted = input.uxRestricted,
            maneuver = if (r.active) r.maneuver else null,
            camera = r.camera,
            caution = r.caution,
            charging = v.isCharging,
            socPercent = v.socPercent.roundToInt().coerceIn(0, 100),
            routeActive = r.active,
            routeRemainingSec = if (r.active) (r.remainingSec ?: r.remainingMin * 60) else null,
            routeRemainingM = if (r.active) (r.remainingM ?: (r.remainingKm * 1000f).roundToInt()) else null,
            destinationName = destName,
            destinationCategory = category,
            destinationPlaceId = destPlace?.id,
            originPlaceId = originPlaceId,
            location = input.location,
            segmentId = segment?.id,
            segmentName = segment?.nameKo,
            roadKind = segment?.road,
            inTunnel = segment?.road == RoadKind.TUNNEL,
            trafficJam = jamNow,
            arrived = arrived,
            driveId = driveIds.driveId,
        )
    }

    fun reset() {
        speeds.clear()
        lastSteerDeg = null
        steerRate = 0f
        slowSinceMs = null
        fastSinceMs = null
        jam = false
        driveIds.reset()
        originPlaceId = null
    }

    private fun matchDestination(dest: Place?): CommutePlace? {
        dest ?: return null
        map.places.firstOrNull { it.nameKo == dest.name }?.let { return it }
        if (dest.lat == 0.0 && dest.lng == 0.0) return null
        return MapMatcher.nearestPlace(map, LatLng(dest.lat, dest.lng))
    }

    private fun accelAt(now: Long, kph: Float): Float {
        val last = speeds.lastOrNull()
        if (last == null || last.kph != kph) speeds.addLast(SpeedSample(now, kph))
        while (speeds.size > 2 && now - speeds.first().atMs > SPEED_KEEP_MS) speeds.removeFirst()
        val ref = speeds.lastOrNull { now - it.atMs >= ACCEL_WINDOW_MS } ?: return 0f
        val dt = (now - ref.atMs) / 1000f
        if (dt <= 0f || dt > ACCEL_MAX_DT_S) return 0f
        return (kph - ref.kph) / 3.6f / dt
    }

    private fun steeringRate(now: Long, deg: Float): Float {
        val last = lastSteerDeg
        if (last == null) {
            lastSteerDeg = deg
            lastSteerChangeMs = now
            return 0f
        }
        if (deg != last) {
            val dt = (now - lastSteerChangeMs).coerceIn(50L, 500L) / 1000f
            steerRate = abs(deg - last) / dt
            lastSteerDeg = deg
            lastSteerChangeMs = now
        } else if (now - lastSteerChangeMs > STEER_IDLE_MS) {
            steerRate = 0f
        }
        return steerRate
    }

    private fun trafficJam(gear: GearPosition, kph: Float, now: Long): Boolean {
        if (gear != GearPosition.D) {
            slowSinceMs = null
            fastSinceMs = null
            jam = false
            return false
        }
        if (kph < LoadThresholds.JAM_SPEED_KPH) {
            fastSinceMs = null
            if (slowSinceMs == null) slowSinceMs = now
            if (now - (slowSinceMs ?: now) >= LoadThresholds.JAM_MIN_MS) jam = true
        } else {
            slowSinceMs = null
            if (jam) {
                if (fastSinceMs == null) fastSinceMs = now
                if (now - (fastSinceMs ?: now) >= JAM_CLEAR_MS) {
                    jam = false
                    fastSinceMs = null
                }
            }
        }
        return jam
    }

    companion object {
        const val ARRIVED_MS = 5_000L
        const val ACCEL_WINDOW_MS = 300L
        const val ACCEL_MAX_DT_S = 2.5f
        const val SPEED_KEEP_MS = 2_000L
        const val STEER_IDLE_MS = 1_000L
        const val JAM_CLEAR_MS = 10_000L
    }
}
