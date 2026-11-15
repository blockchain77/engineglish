package com.cartutor.engineglish.english.drive

import com.cartutor.engineglish.english.core.CautionKind
import com.cartutor.engineglish.english.core.DrivingLoadEngine
import com.cartutor.engineglish.english.core.DrivingSignals
import com.cartutor.engineglish.english.core.GearPosition
import com.cartutor.engineglish.english.core.LoadAssessment
import com.cartutor.engineglish.english.core.LoadLevel
import com.cartutor.engineglish.english.core.LoadReason
import com.cartutor.engineglish.english.core.LoadThresholds
import com.cartutor.engineglish.english.core.Maneuver
import com.cartutor.engineglish.english.core.ManeuverType
import com.cartutor.engineglish.english.core.ReasonCode
import com.cartutor.engineglish.english.core.TurnSignalState
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 운전 부하 엔진 (명세 §4.2).
 *  - [instant]: 신호 하나만 보고 이유를 모아 단계를 정한다 (순수).
 *  - [update]: 히스테리시스 — 올라갈 땐 즉시, 내려갈 땐 낮은 단계가 4초 안정돼야 (CRITICAL 은 원인 해소 후 3초 뒤 FOCUS 로).
 * 시간은 항상 DrivingSignals.atMs 를 쓴다 (가상 시계로 시험 가능).
 *
 * uxRestricted 해석: AAOS 는 '움직이면 항상' 주의분산 제한을 켜므로, 이것 때문에 RELAXED 를 막지는 않는다.
 * 대신 제한 중이면 '충전 중 정차'로 PARKED 판정을 하지 않는다 (기어 P 는 그대로 PARKED).
 */
class RuleLoadEngine : DrivingLoadEngine {

    /** 즉시 판단 (내부용). steadyCond = 고속 직진 조건 충족, steadyChosen = RELAXED 이유가 STEADY_HIGHWAY */
    private class Eval(
        val level: LoadLevel,
        val reasons: List<LoadReason>,
        val steadyCond: Boolean,
        val steadyChosen: Boolean,
    )

    private var initialized = false
    private var current: LoadLevel = LoadLevel.PARKED
    private var sinceMs: Long = 0L
    private var heldReasons: List<LoadReason> = emptyList()

    private var windowStartMs: Long? = null
    private var windowMax: LoadLevel = LoadLevel.PARKED
    private var windowMaxReasons: List<LoadReason> = emptyList()

    private var steadySinceMs: Long? = null

    override fun instant(signals: DrivingSignals): LoadAssessment {
        val e = evaluate(signals)
        return LoadAssessment(
            level = e.level,
            index = indexOf(e.level, signals.speedKph, e.reasons.size),
            reasons = e.reasons,
            rawLevel = e.level,
            sinceMs = signals.atMs,
            atMs = signals.atMs,
        )
    }

    override fun update(signals: DrivingSignals): LoadAssessment {
        val t = signals.atMs
        var e = evaluate(signals)

        // 고속 직진은 15초 이어져야 RELAXED (크루즈는 즉시)
        if (e.steadyCond) {
            if (steadySinceMs == null) steadySinceMs = t
        } else {
            steadySinceMs = null
        }
        val steadyFor = t - (steadySinceMs ?: t)
        if (e.steadyChosen && steadyFor < LoadThresholds.RELAXED_STEADY_MS) {
            e = Eval(LoadLevel.NORMAL, listOf(LoadReason(ReasonCode.CITY, cityText(signals))), steadyCond = true, steadyChosen = false)
        }

        val raw = e.level
        when {
            !initialized -> {
                initialized = true
                setCurrent(raw, t, e.reasons)
                clearWindow()
            }
            raw.ordinal > current.ordinal -> {
                // 올라갈 때는 즉시
                setCurrent(raw, t, e.reasons)
                clearWindow()
            }
            raw == current -> {
                heldReasons = e.reasons
                clearWindow()
            }
            else -> deescalate(raw, e.reasons, t)
        }
        return output(signals, raw)
    }

    override fun reset() {
        initialized = false
        current = LoadLevel.PARKED
        sinceMs = 0L
        heldReasons = emptyList()
        clearWindow()
        steadySinceMs = null
    }

    // ───────────────────────── 히스테리시스 ─────────────────────────

    private fun deescalate(raw: LoadLevel, reasons: List<LoadReason>, t: Long) {
        val start = windowStartMs
        if (start == null) {
            windowStartMs = t
            windowMax = raw
            windowMaxReasons = reasons
        } else if (raw.ordinal >= windowMax.ordinal) {
            windowMax = raw
            windowMaxReasons = reasons
        }
        val ws = windowStartMs ?: t
        if (current == LoadLevel.CRITICAL) {
            if (t - ws >= LoadThresholds.CRITICAL_MIN_HOLD_MS) {
                // 항상 FOCUS 를 거친다 (급제동 뒤 최소 4초 침묵)
                val next = LoadLevel.max(windowMax, LoadLevel.FOCUS)
                val nextReasons = if (windowMax == next) windowMaxReasons else heldReasons
                setCurrent(next, t, nextReasons)
                restartWindow(raw, reasons, t)
            }
        } else if (t - ws >= LoadThresholds.DEESCALATE_STABLE_MS) {
            setCurrent(windowMax, t, windowMaxReasons)
            restartWindow(raw, reasons, t)
        }
    }

    private fun setCurrent(level: LoadLevel, t: Long, reasons: List<LoadReason>) {
        current = level
        sinceMs = t
        heldReasons = reasons
    }

    private fun clearWindow() {
        windowStartMs = null
        windowMax = LoadLevel.PARKED
        windowMaxReasons = emptyList()
    }

    /** 한 단계 내려간 직후: 지금 신호가 더 낮으면 바로 새 창을 연다 */
    private fun restartWindow(raw: LoadLevel, reasons: List<LoadReason>, t: Long) {
        if (raw.ordinal < current.ordinal) {
            windowStartMs = t
            windowMax = raw
            windowMaxReasons = reasons
        } else {
            clearWindow()
        }
    }

    private fun output(s: DrivingSignals, raw: LoadLevel): LoadAssessment {
        val reasons = heldReasons.take(MAX_REASONS)
        return LoadAssessment(
            level = current,
            index = indexOf(current, s.speedKph, reasons.size),
            reasons = reasons,
            rawLevel = raw,
            sinceMs = sinceMs,
            atMs = s.atMs,
        )
    }

    // ───────────────────────── 즉시 판단 ─────────────────────────

    private fun evaluate(s: DrivingSignals): Eval {
        val speed = s.speedKph

        // 1. PARKED — 다른 건 보지 않는다
        val chargingStill = s.charging && speed < LoadThresholds.STOPPED_SPEED_KPH && !s.uxRestricted
        if (s.gear == GearPosition.P || chargingStill) {
            val r = if (s.charging) {
                LoadReason(ReasonCode.CHARGING, s.socPercent?.let { "충전 중 $it%" } ?: "충전 중")
            } else {
                LoadReason(ReasonCode.PARKED, "정차 중")
            }
            return Eval(LoadLevel.PARKED, listOf(r), steadyCond = false, steadyChosen = false)
        }

        // 2. CRITICAL
        val critical = ArrayList<LoadReason>(2)
        if (s.accelMps2 <= -LoadThresholds.HARD_DECEL_MPS2) critical += LoadReason(ReasonCode.HARD_BRAKE, "급제동")
        val limit = s.speedLimitKph
        if (limit != null && limit > 0 && speed > limit + LoadThresholds.OVERSPEED_MARGIN_KPH) {
            critical += LoadReason(ReasonCode.OVERSPEED, "과속 · 제한 ${limit}km/h")
        }
        if (s.navVoicePrompt) critical += LoadReason(ReasonCode.NAV_PROMPT, "내비 음성 안내 중")
        if (s.audioFocusLost) critical += LoadReason(ReasonCode.AUDIO_FOCUS, "다른 음성 안내 중")

        // 3. FOCUS
        val focus = ArrayList<LoadReason>(3)
        if (s.turnSignal != TurnSignalState.OFF) focus += LoadReason(ReasonCode.TURN_SIGNAL, "방향지시등")
        val m = s.maneuver
        if (m != null && maneuverNear(m, speed)) focus += LoadReason(ReasonCode.MANEUVER, maneuverText(m))
        if (abs(s.steeringDeg) > LoadThresholds.STEERING_FOCUS_DEG || s.steeringRateDps > LoadThresholds.STEERING_RATE_FOCUS_DPS) {
            focus += LoadReason(ReasonCode.STEERING, "핸들 조작")
        }
        val cam = s.camera
        if (cam != null && cam.distanceM in 0..LoadThresholds.CAMERA_FOCUS_M) {
            focus += LoadReason(ReasonCode.CAMERA, "단속 카메라 ${round10(cam.distanceM)}m")
        }
        val caution = s.caution
        if (caution != null && (caution.inside || caution.distanceM in 0..LoadThresholds.CAUTION_FOCUS_M)) {
            focus += if (caution.kind == CautionKind.SCHOOL_ZONE) {
                LoadReason(ReasonCode.SCHOOL_ZONE, "어린이 보호구역")
            } else {
                LoadReason(ReasonCode.CAUTION_ZONE, caution.kind.ko)
            }
        }
        if (s.wipersOn && s.headlightsOn) focus += LoadReason(ReasonCode.RAIN_NIGHT, "야간 빗길")
        if (s.gear == GearPosition.R) focus += LoadReason(ReasonCode.REVERSE, "후진 중")

        // 4. NORMAL 정보 (정체는 그 자체로 FOCUS 가 아니다)
        val info = ArrayList<LoadReason>(2)
        if (s.trafficJam) {
            info += LoadReason(ReasonCode.TRAFFIC_JAM, "정체 구간")
        } else if (speed < LoadThresholds.STOPPED_SPEED_KPH) {
            info += LoadReason(ReasonCode.STOPPED, "신호 대기")
        }
        if (s.wipersOn && !s.headlightsOn) info += LoadReason(ReasonCode.RAIN, "비")
        if (s.headlightsOn && !s.wipersOn) info += LoadReason(ReasonCode.NIGHT, "야간")

        // 5. RELAXED — 막는 이유가 하나도 없을 때만
        val clear = critical.isEmpty() && focus.isEmpty() && info.isEmpty() && s.turnSignal == TurnSignalState.OFF
        val steadyCond = clear &&
            speed > LoadThresholds.RELAXED_MIN_SPEED_KPH &&
            abs(s.steeringDeg) <= LoadThresholds.RELAXED_MAX_STEERING_DEG &&
            abs(s.accelMps2) <= LoadThresholds.RELAXED_MAX_ACCEL_MPS2 &&
            noDemandingManeuverWithin(m, LoadThresholds.RELAXED_MANEUVER_CLEAR_M) &&
            (cam == null || cam.distanceM < 0 || cam.distanceM > LoadThresholds.RELAXED_CAMERA_CLEAR_M) &&
            caution == null
        val cruise = clear && s.cruiseActive && speed >= LoadThresholds.CRUISE_MIN_SPEED_KPH
        val relaxed: LoadReason? = when {
            cruise -> LoadReason(ReasonCode.CRUISE, "크루즈 주행")
            steadyCond -> LoadReason(ReasonCode.STEADY_HIGHWAY, "고속 직진 구간")
            else -> null
        }

        val reasons = ArrayList<LoadReason>(critical.size + focus.size + info.size + 1)
        reasons += critical
        reasons += focus
        reasons += info
        if (relaxed != null) {
            reasons += relaxed
        } else if (reasons.isEmpty()) {
            // 6. 그 밖은 NORMAL
            reasons += LoadReason(ReasonCode.CITY, cityText(s))
        }
        val level = reasons.maxOf { it.code.level.ordinal }.let { LoadLevel.entries[it] }
        val sorted = reasons.sortedByDescending { it.code.level.ordinal }.take(MAX_REASONS)
        return Eval(level, sorted, steadyCond, steadyChosen = relaxed?.code == ReasonCode.STEADY_HIGHWAY)
    }

    companion object {
        const val MAX_REASONS = 3

        /** 300m 안이거나 지금 속도로 8초 안 */
        fun maneuverNear(m: Maneuver, speedKph: Float): Boolean {
            if (!m.type.demanding || m.distanceM < 0) return false
            if (m.distanceM <= LoadThresholds.MANEUVER_FOCUS_M) return true
            if (speedKph < LoadThresholds.STOPPED_SPEED_KPH) return false
            val seconds = m.distanceM / (speedKph / 3.6f)
            return seconds <= LoadThresholds.MANEUVER_FOCUS_SEC
        }

        fun maneuverText(m: Maneuver): String {
            val d = round10(m.distanceM)
            return when (m.type) {
                ManeuverType.EXIT -> "나들목 진출 ${d}m"
                ManeuverType.ENTER, ManeuverType.MERGE -> "합류 구간 ${d}m"
                ManeuverType.KEEP_LEFT, ManeuverType.KEEP_RIGHT -> "차로 변경 ${d}m"
                ManeuverType.TOLL -> "요금소 ${d}m"
                ManeuverType.ARRIVE -> "목적지 ${d}m"
                ManeuverType.REST_AREA -> "휴게소 진입 ${d}m"
                else -> "교차로 접근 ${d}m"
            }
        }

        /** 10m 단위 반올림 (화면이 1m 단위로 깜빡이지 않게) */
        fun round10(m: Int): Int = ((m.coerceAtLeast(0) / 10.0).roundToInt()) * 10

        fun indexOf(level: LoadLevel, speedKph: Float, reasonCount: Int): Int {
            val speedPart = floor(speedKph.coerceAtLeast(0f) / 130f * 10f).toInt()
            val raw = level.bandStart + speedPart + 4 * (reasonCount - 1).coerceAtLeast(0)
            return raw.coerceIn(level.bandStart, level.bandEnd)
        }

        private fun noDemandingManeuverWithin(m: Maneuver?, meters: Int): Boolean =
            m == null || !m.type.demanding || m.distanceM < 0 || m.distanceM > meters

        private fun cityText(s: DrivingSignals): String = if (s.speedKph > 60f) "고속 주행" else "시내 주행"
    }
}
