package com.yeopjari.app.ai.context

import com.yeopjari.app.core.model.RouteState
import com.yeopjari.app.core.model.VehicleSnapshot
import com.yeopjari.app.sim.SimFlags
import kotlin.math.max
import kotlin.math.roundToInt

/** 사용자 프로필 (온디바이스 개인화 메모리에 저장되는 값의 예시 — 가상의 페르소나) */
data class UserProfile(
    val name: String = "서준",
    val kids: List<Pair<String, Int>> = listOf("하율" to 7, "도윤" to 5),
    val petName: String = "콩이",
    val preferredTempC: Float = 21.5f,
    val homeLabel: String = "잠실 집",
    val workLabel: String = "판교 회사",
)

/**
 * 드라이브 컨텍스트 — AI(온디바이스/클라우드)가 판단에 사용하는 단일 입력.
 * 모든 값은 Vehicle / NaviHelper / FusedLocation SDK로부터 실시간 갱신된다.
 */
data class DriveContext(
    val vehicle: VehicleSnapshot,
    val route: RouteState,
    val clockSec: Float,
    val clockLabel: String,
    val flags: SimFlags,
    val profile: UserProfile,
    val energy: EnergyForecast,
    val fatigue: FatigueAssessment,
    val load: CognitiveLoad,
) {
    val hour: Int get() = (clockSec / 3600f).toInt()
    val hasRearPassengers: Boolean get() = flags.kidsOnBoard || vehicle.occupants.size > 2
}

// ───────────────────────── 에너지 예측 모델 ─────────────────────────

data class SocPoint(val km: Float, val soc: Float, val label: String? = null)

data class EnergyForecast(
    val arrivalSoc: Float?,
    val effectiveKmPerKwh: Float,
    val needsCharge: Boolean,
    val marginKm: Int,
    val factors: List<Pair<String, String>>,
    val curve: List<SocPoint>,
)

/**
 * 도착 SOC 예측 — 물리 기반 베이스라인 (향후 차량별 주행 이력으로 학습한 경량 회귀 모델로 대체).
 * 속도·외기온·공조 부하를 반영한다.
 */
object EnergyModel {
    fun forecast(v: VehicleSnapshot, r: RouteState): EnergyForecast {
        val speedFactor = if (r.isHighway) 0.86f else 1.04f
        val tempFactor = when {
            v.outsideTempC < 0f -> 0.78f
            v.outsideTempC < 7f -> 0.86f
            v.outsideTempC > 29f -> 0.92f
            else -> 1f
        }
        val hvacFactor = if (v.hvac.power) 0.96f else 1f
        val eff = v.efficiencyKmPerKwh * speedFactor * tempFactor * hvacFactor
        val factors = buildList {
            add("주행 패턴" to if (r.isHighway) "고속 -14%" else "시내 +4%")
            if (tempFactor < 1f) add("외기온 ${v.outsideTempC.roundToInt()}°C" to "-${((1 - tempFactor) * 100).roundToInt()}%")
            if (v.hvac.power) add("공조 부하" to "-4%")
            add("유효 전비" to "%.1f km/kWh".format(eff))
        }
        if (!r.active) {
            return EnergyForecast(null, eff, false, (v.socPercent / 100f * v.batteryKwh * eff).toInt(), factors, emptyList())
        }
        val needKwh = r.remainingKm / eff
        val own = v.socPercent - needKwh / v.batteryKwh * 100f
        // 내비 엔진(NaviHelper TripInfo.estimatedSocAtDest)이 값을 주면 보수적으로 결합
        val arrival = r.naviEstimatedSoc?.let { minOf(own, it.toFloat()) * 0.6f + maxOf(own, it.toFloat()) * 0.4f } ?: own
        val factorsWithNavi = if (r.naviEstimatedSoc != null) factors + ("내비 예측" to "${r.naviEstimatedSoc}%") else factors
        val reserveKm = ((arrival - 10f) / 100f * v.batteryKwh * eff).toInt()
        val curve = buildList {
            val steps = 8
            for (i in 0..steps) {
                val km = r.remainingKm * i / steps
                add(SocPoint(km, max(0f, v.socPercent - (km / eff) / v.batteryKwh * 100f)))
            }
        }
        return EnergyForecast(arrival, eff, arrival < 12f, reserveKm, factorsWithNavi, curve)
    }

    /** 충전 경유 시 SOC 곡선 (충전소 위치까지 감소 → 목표 SOC 회복 → 목적지까지 감소) */
    fun curveWithStop(v: VehicleSnapshot, r: RouteState, stopKm: Float, targetSoc: Float, eff: Float, stopLabel: String): List<SocPoint> {
        val out = mutableListOf<SocPoint>()
        val atStop = v.socPercent - (stopKm / eff) / v.batteryKwh * 100f
        for (i in 0..4) {
            val km = stopKm * i / 4
            out += SocPoint(km, v.socPercent - (km / eff) / v.batteryKwh * 100f)
        }
        out += SocPoint(stopKm + 0.01f, targetSoc, stopLabel)
        val rest = r.remainingKm - stopKm
        for (i in 1..4) {
            val km = rest * i / 4
            out += SocPoint(stopKm + km, targetSoc - (km / eff) / v.batteryKwh * 100f)
        }
        out[4] = out[4].copy(label = "도착 ${atStop.roundToInt()}%")
        return out
    }
}

// ───────────────────────── 졸음 위험 모델 ─────────────────────────

data class FatigueFactor(val label: String, val points: Int)

data class FatigueAssessment(val score: Int, val level: Int, val factors: List<FatigueFactor>) {
    val levelLabel: String get() = listOf("양호", "주의", "경고", "위험")[level]
}

/**
 * 졸음 위험 추정 — 연속 주행 시간·생체리듬(시간대)·단조로움·실내 환경 기반의 설명 가능한 점수.
 * 향후: 조향/차선 유지 패턴 시계열을 입력으로 하는 온디바이스 경량 분류 모델로 고도화.
 */
object FatigueModel {
    fun assess(v: VehicleSnapshot, r: RouteState, hour: Int): FatigueAssessment {
        val f = mutableListOf<FatigueFactor>()
        val drive = (v.continuousDriveMin * 0.42f).coerceAtMost(55f).roundToInt()
        if (drive > 0) f += FatigueFactor("연속 주행 ${v.continuousDriveMin}분", drive)
        val circadian = when (hour) {
            in 2..5 -> 26
            in 13..15 -> 15
            in 22..23, 0, 1 -> 14
            else -> 0
        }
        if (circadian > 0) f += FatigueFactor("생체리듬 저하 시간대", circadian)
        if (r.isHighway && v.speedKph > 80f) f += FatigueFactor("고속도로 단조 주행", 11)
        if (v.cabinTempC > 23.5f && v.hvac.recirculation) f += FatigueFactor("내기순환 · 따뜻한 실내", 7)
        if (v.occupants.size == 1 && v.speedKph > 5f) f += FatigueFactor("혼자 운전 중", 4)
        val score = f.sumOf { it.points }.coerceIn(0, 100)
        val level = when {
            score < 40 -> 0
            score < 62 -> 1
            score < 78 -> 2
            else -> 3
        }
        return FatigueAssessment(score, level, f.sortedByDescending { it.points })
    }
}

// ───────────────────────── 운전 부하 (인터럽션 매니저 입력) ─────────────────────────

enum class LoadLevel(val label: String) { LOW("여유"), MEDIUM("보통"), HIGH("집중 필요") }

data class CognitiveLoad(val level: LoadLevel, val reason: String)

/**
 * 운전 부하 추정 — AI가 '말을 걸어도 되는 순간'을 고른다.
 * 회전 직전·저속 혼잡 구간에서는 비긴급 알림을 보류한다.
 */
object LoadModel {
    /** 입력: 차속(CarPropertyManager) + 다음 회전까지 거리(NaviHelper TBTInfo) + 도로 유형 */
    fun estimate(v: VehicleSnapshot, r: RouteState): CognitiveLoad {
        if (v.speedKph < 3f) return CognitiveLoad(LoadLevel.LOW, "정차 중")
        val turn = r.nextTurnMeters
        return when {
            turn != null && turn < 1000 -> CognitiveLoad(LoadLevel.HIGH, "${turn}m 앞 방향 전환")
            !r.isHighway && v.speedKph in 15f..65f -> CognitiveLoad(LoadLevel.MEDIUM, "시내 주행")
            r.isHighway -> CognitiveLoad(LoadLevel.LOW, "고속도로 차로 유지")
            else -> CognitiveLoad(LoadLevel.MEDIUM, "주행 중")
        }
    }
}
