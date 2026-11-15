package com.cartutor.engineglish.english.drive

import com.cartutor.engineglish.english.core.CautionKind
import com.cartutor.engineglish.english.core.ContextHookDetector
import com.cartutor.engineglish.english.core.DestinationCategory
import com.cartutor.engineglish.english.core.DrivingSignals
import com.cartutor.engineglish.english.core.HookEvent
import com.cartutor.engineglish.english.core.HookThresholds
import com.cartutor.engineglish.english.core.HookTrigger
import com.cartutor.engineglish.english.core.LearnThresholds
import com.cartutor.engineglish.english.core.LoadAssessment

/**
 * '방금 겪은 상황' 감지 (명세 §4.4) — 정체를 빠져나옴, 비, 터널, 회사·집 도착 무렵, 공항, 충전, 밤길, 크루즈, 어린이 보호구역.
 *
 * - 트리거마다 주행(driveId)당 한 번. driveId 가 바뀌면 주행 단위 상태를 스스로 지운다.
 * - 같은 트리거는 10분 안에 두 번 울리지 않는다 (주행이 바뀌어도 유지). [reset] 은 쿨다운까지 모두 지운다 (새 데모 주행).
 * - 시간은 DrivingSignals.atMs (실시간).
 * 전달 시점(부하 ≤ NORMAL, 문항 사이)은 코치가 정한다 — 여기서는 감지만 한다.
 */
class RuleContextHookDetector : ContextHookDetector {

    private var driveKey: String? = null
    private var driveSeen = false
    private val firedThisDrive = HashSet<HookTrigger>()
    private val lastFiredMs = HashMap<HookTrigger, Long>()

    // 조건 타이머 (주행 단위)
    private var jamSinceMs: Long? = null
    private var jamQualified = false
    private var jamEndedMs: Long? = null
    private var jamClearSinceMs: Long? = null
    private var wipersSinceMs: Long? = null
    private var wasInTunnel = false
    private var chargingSinceMs: Long? = null
    private var nightSinceMs: Long? = null
    private var cruiseSinceMs: Long? = null
    private var schoolWasInside = false
    private var schoolLeftSinceMs: Long? = null

    override fun update(signals: DrivingSignals, load: LoadAssessment): List<HookEvent> {
        val s = signals
        val t = s.atMs
        if (!driveSeen || s.driveId != driveKey) {
            driveSeen = true
            driveKey = s.driveId
            resetDriveState()
        }
        val out = ArrayList<HookEvent>(1)

        // JAM_EXIT — 정체 20초 이상 → 풀린 뒤 시속 30km 이상 5초
        if (s.trafficJam) {
            if (jamSinceMs == null) jamSinceMs = t
            if (t - (jamSinceMs ?: t) >= HookThresholds.JAM_MIN_MS) jamQualified = true
            jamClearSinceMs = null
            jamEndedMs = null
        } else {
            if (jamSinceMs != null) jamEndedMs = t
            jamSinceMs = null
            if (jamQualified) {
                val ended = jamEndedMs
                if (ended != null && t - ended > JAM_EXIT_STALE_MS) {
                    jamQualified = false
                    jamClearSinceMs = null
                } else if (s.speedKph >= HookThresholds.JAM_EXIT_SPEED_KPH) {
                    if (jamClearSinceMs == null) jamClearSinceMs = t
                    if (t - (jamClearSinceMs ?: t) >= HookThresholds.JAM_EXIT_CLEAR_MS) {
                        fire(out, HookTrigger.JAM_EXIT, t, "정체 구간을 빠져나왔어요")
                        jamQualified = false
                        jamClearSinceMs = null
                    }
                } else {
                    jamClearSinceMs = null
                }
            }
        }

        // RAIN — 와이퍼 10초 이상
        if (s.wipersOn) {
            if (wipersSinceMs == null) wipersSinceMs = t
            if (t - (wipersSinceMs ?: t) >= HookThresholds.RAIN_MIN_MS) fire(out, HookTrigger.RAIN, t, "비가 내리고 있어요")
        } else {
            wipersSinceMs = null
        }

        // TUNNEL — 터널을 빠져나온 순간
        if (wasInTunnel && !s.inTunnel) fire(out, HookTrigger.TUNNEL, t, "터널을 지났어요")
        wasInTunnel = s.inTunnel

        // 회사·집 도착 5분 전
        val remain = s.routeRemainingSec
        if (remain != null && remain <= HookThresholds.ARRIVING_SEC && !s.arrived) {
            val minutes = ((remain + 59) / 60).coerceAtLeast(1)
            when (s.destinationCategory) {
                DestinationCategory.OFFICE -> fire(out, HookTrigger.ARRIVING_OFFICE, t, "회사 도착 ${minutes}분 전")
                DestinationCategory.HOME -> fire(out, HookTrigger.ARRIVING_HOME, t, "집 도착 ${minutes}분 전")
                else -> Unit
            }
        }

        // AIRPORT — 목적지가 공항
        if (s.destinationCategory == DestinationCategory.AIRPORT) fire(out, HookTrigger.AIRPORT, t, "공항 가는 길이에요")

        // CHARGING — 충전 5초 이상
        if (s.charging) {
            if (chargingSinceMs == null) chargingSinceMs = t
            if (t - (chargingSinceMs ?: t) >= HookThresholds.CHARGING_MIN_MS) fire(out, HookTrigger.CHARGING, t, "충전을 시작했어요")
        } else {
            chargingSinceMs = null
        }

        // NIGHT_DRIVE — 전조등 켜고 주행 60초
        if (s.headlightsOn && !s.parked) {
            if (nightSinceMs == null) nightSinceMs = t
            if (t - (nightSinceMs ?: t) >= HookThresholds.NIGHT_MIN_MS) fire(out, HookTrigger.NIGHT_DRIVE, t, "밤길을 달리고 있어요")
        } else {
            nightSinceMs = null
        }

        // CRUISE — 크루즈 20초 이상
        if (s.cruiseActive) {
            if (cruiseSinceMs == null) cruiseSinceMs = t
            if (t - (cruiseSinceMs ?: t) >= HookThresholds.CRUISE_MIN_MS) fire(out, HookTrigger.CRUISE, t, "크루즈로 달리고 있어요")
        } else {
            cruiseSinceMs = null
        }

        // SCHOOL_ZONE — 어린이 보호구역 안 → 벗어나 5초
        val c = s.caution
        val insideSchool = c != null && c.kind == CautionKind.SCHOOL_ZONE && c.inside
        if (insideSchool) {
            schoolWasInside = true
            schoolLeftSinceMs = null
        } else if (schoolWasInside) {
            if (schoolLeftSinceMs == null) schoolLeftSinceMs = t
            if (t - (schoolLeftSinceMs ?: t) >= HookThresholds.SCHOOL_ZONE_LEFT_MS) {
                fire(out, HookTrigger.SCHOOL_ZONE, t, "어린이 보호구역을 지났어요")
                schoolWasInside = false
                schoolLeftSinceMs = null
            }
        }
        return out
    }

    override fun reset() {
        driveSeen = false
        driveKey = null
        lastFiredMs.clear()
        resetDriveState()
    }

    private fun fire(out: MutableList<HookEvent>, trigger: HookTrigger, t: Long, detailKo: String) {
        if (trigger in firedThisDrive) return
        val last = lastFiredMs[trigger]
        if (last != null && t - last < LearnThresholds.HOOK_COOLDOWN_MS) return
        firedThisDrive += trigger
        lastFiredMs[trigger] = t
        out += HookEvent(trigger, t, detailKo)
    }

    private fun resetDriveState() {
        firedThisDrive.clear()
        jamSinceMs = null
        jamQualified = false
        jamEndedMs = null
        jamClearSinceMs = null
        wipersSinceMs = null
        wasInTunnel = false
        chargingSinceMs = null
        nightSinceMs = null
        cruiseSinceMs = null
        schoolWasInside = false
        schoolLeftSinceMs = null
    }

    companion object {
        /** 정체가 끝나고 이만큼 지나도록 시속 30km 를 못 넘으면 '빠져나옴' 순간은 지난 것으로 본다 */
        const val JAM_EXIT_STALE_MS = 3 * 60 * 1000L
    }
}
