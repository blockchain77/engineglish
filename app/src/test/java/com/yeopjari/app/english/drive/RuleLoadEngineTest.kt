package com.yeopjari.app.english.drive

import com.yeopjari.app.english.core.CameraAhead
import com.yeopjari.app.english.core.CameraKind
import com.yeopjari.app.english.core.CautionAhead
import com.yeopjari.app.english.core.CautionKind
import com.yeopjari.app.english.core.DrivingSignals
import com.yeopjari.app.english.core.GearPosition
import com.yeopjari.app.english.core.LoadAssessment
import com.yeopjari.app.english.core.LoadLevel
import com.yeopjari.app.english.core.Maneuver
import com.yeopjari.app.english.core.ManeuverType
import com.yeopjari.app.english.core.ReasonCode
import com.yeopjari.app.english.core.TurnSignalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleLoadEngineTest {

    private val engine = RuleLoadEngine()
    private val t0 = 1_000_000L

    private fun instant(s: DrivingSignals): LoadAssessment = RuleLoadEngine().instant(s)
    private fun codes(a: LoadAssessment) = a.reasons.map { it.code }

    // ───────────── 즉시 판단: 이유 규칙 ─────────────

    @Test fun parkedGearIsParkedAndNothingElseCounts() {
        val a = instant(DrivingSignals(atMs = t0, gear = GearPosition.P, turnSignal = TurnSignalState.LEFT, navVoicePrompt = true))
        assertEquals(LoadLevel.PARKED, a.level)
        assertEquals(listOf(ReasonCode.PARKED), codes(a))
        assertEquals("정차 중", a.reasons[0].textKo)
        assertTrue(a.index in 0..9)
    }

    @Test fun chargingStandstillIsParkedWithSoc() {
        val a = instant(DrivingSignals(atMs = t0, gear = GearPosition.N, charging = true, socPercent = 45, speedKph = 0f))
        assertEquals(LoadLevel.PARKED, a.level)
        assertEquals("충전 중 45%", a.reasons[0].textKo)
    }

    @Test fun plainCityDrivingIsNormal() {
        val a = instant(driving(t0, 45f))
        assertEquals(LoadLevel.NORMAL, a.level)
        assertEquals(listOf(ReasonCode.CITY), codes(a))
        assertEquals("시내 주행", a.reasons[0].textKo)
    }

    @Test fun criticalReasons() {
        assertEquals(LoadLevel.CRITICAL, instant(driving(t0).copy(accelMps2 = -3.6f)).level)
        assertEquals("급제동", instant(driving(t0).copy(accelMps2 = -3.5f)).reasons[0].textKo)
        assertEquals(LoadLevel.NORMAL, instant(driving(t0).copy(accelMps2 = -3.4f)).level)

        val over = instant(driving(t0, 61f).copy(speedLimitKph = 50))
        assertEquals(LoadLevel.CRITICAL, over.level)
        assertEquals("과속 · 제한 50km/h", over.reasons[0].textKo)
        assertEquals(LoadLevel.NORMAL, instant(driving(t0, 60f).copy(speedLimitKph = 50)).level)

        assertEquals(listOf(ReasonCode.NAV_PROMPT), codes(instant(driving(t0).copy(navVoicePrompt = true))))
        assertEquals("내비 음성 안내 중", instant(driving(t0).copy(navVoicePrompt = true)).reasons[0].textKo)
        assertEquals(listOf(ReasonCode.AUDIO_FOCUS), codes(instant(driving(t0).copy(audioFocusLost = true))))
    }

    @Test fun turnSignalIsFocus() {
        val a = instant(driving(t0).copy(turnSignal = TurnSignalState.RIGHT))
        assertEquals(LoadLevel.FOCUS, a.level)
        assertEquals("방향지시등", a.reasons[0].textKo)
    }

    @Test fun maneuverWithin300mOrEightSeconds() {
        val near = instant(driving(t0, 40f).copy(maneuver = Maneuver(ManeuverType.LEFT, 284)))
        assertEquals(LoadLevel.FOCUS, near.level)
        assertEquals("교차로 접근 280m", near.reasons[0].textKo)
        assertEquals(LoadLevel.NORMAL, instant(driving(t0, 40f).copy(maneuver = Maneuver(ManeuverType.LEFT, 310))).level)
        // 시속 150km 에서 320m = 7.7초 → FOCUS, 400m = 9.6초 → 아님
        assertEquals(LoadLevel.FOCUS, instant(driving(t0, 150f).copy(maneuver = Maneuver(ManeuverType.EXIT, 320))).level)
        assertEquals(LoadLevel.NORMAL, instant(driving(t0, 150f).copy(maneuver = Maneuver(ManeuverType.EXIT, 400))).level)
        // 직진·터널 안내는 부하가 아니다
        assertEquals(LoadLevel.NORMAL, instant(driving(t0).copy(maneuver = Maneuver(ManeuverType.STRAIGHT, 50))).level)
        assertEquals(LoadLevel.NORMAL, instant(driving(t0).copy(maneuver = Maneuver(ManeuverType.TUNNEL, 50))).level)
    }

    @Test fun maneuverTextsByType() {
        fun text(type: ManeuverType, d: Int) = instant(driving(t0, 30f).copy(maneuver = Maneuver(type, d))).reasons[0].textKo
        assertEquals("나들목 진출 150m", text(ManeuverType.EXIT, 150))
        assertEquals("합류 구간 200m", text(ManeuverType.MERGE, 196))
        assertEquals("합류 구간 100m", text(ManeuverType.ENTER, 100))
        assertEquals("차로 변경 290m", text(ManeuverType.KEEP_LEFT, 285))
        assertEquals("요금소 80m", text(ManeuverType.TOLL, 80))
        assertEquals("목적지 90m", text(ManeuverType.ARRIVE, 92))
        assertEquals("휴게소 진입 250m", text(ManeuverType.REST_AREA, 250))
        assertEquals("교차로 접근 40m", text(ManeuverType.U_TURN, 40))
        assertEquals("교차로 접근 120m", text(ManeuverType.ROUNDABOUT, 120))
    }

    @Test fun steeringAngleOrRate() {
        assertEquals(LoadLevel.FOCUS, instant(driving(t0).copy(steeringDeg = -30f)).level)
        assertEquals(LoadLevel.FOCUS, instant(driving(t0).copy(steeringRateDps = 70f)).level)
        assertEquals("핸들 조작", instant(driving(t0).copy(steeringDeg = 26f)).reasons[0].textKo)
        assertEquals(LoadLevel.NORMAL, instant(driving(t0).copy(steeringDeg = 20f, steeringRateDps = 10f)).level)
    }

    @Test fun cameraAndCautionZones() {
        val cam = instant(driving(t0).copy(camera = CameraAhead(CameraKind.SPEED_FIXED, 150, 60)))
        assertEquals(LoadLevel.FOCUS, cam.level)
        assertEquals("단속 카메라 150m", cam.reasons[0].textKo)
        assertEquals(LoadLevel.NORMAL, instant(driving(t0).copy(camera = CameraAhead(CameraKind.SPEED_FIXED, 250))).level)

        val school = instant(driving(t0, 28f).copy(caution = CautionAhead(CautionKind.SCHOOL_ZONE, 0, inside = true)))
        assertEquals(listOf(ReasonCode.SCHOOL_ZONE), codes(school))
        assertEquals("어린이 보호구역", school.reasons[0].textKo)
        val bump = instant(driving(t0).copy(caution = CautionAhead(CautionKind.SPEED_BUMP, 120)))
        assertEquals(listOf(ReasonCode.CAUTION_ZONE), codes(bump))
        assertEquals("과속방지턱", bump.reasons[0].textKo)
        assertEquals(LoadLevel.NORMAL, instant(driving(t0).copy(caution = CautionAhead(CautionKind.SCHOOL_ZONE, 260))).level)
    }

    @Test fun rainAtNightAndReverseAreFocus() {
        val rn = instant(driving(t0).copy(wipersOn = true, headlightsOn = true))
        assertEquals(LoadLevel.FOCUS, rn.level)
        assertEquals("야간 빗길", rn.reasons[0].textKo)
        val rev = instant(driving(t0, 5f).copy(gear = GearPosition.R))
        assertEquals(LoadLevel.FOCUS, rev.level)
        assertEquals("후진 중", rev.reasons[0].textKo)
    }

    @Test fun normalInfoReasons() {
        val jam = instant(driving(t0, 0f).copy(trafficJam = true))
        assertEquals(LoadLevel.NORMAL, jam.level)
        assertEquals(listOf(ReasonCode.TRAFFIC_JAM), codes(jam))
        assertEquals("정체 구간", jam.reasons[0].textKo)
        assertEquals("신호 대기", instant(driving(t0, 0f)).reasons[0].textKo)
        assertEquals("비", instant(driving(t0).copy(wipersOn = true)).reasons[0].textKo)
        assertEquals("야간", instant(driving(t0).copy(headlightsOn = true)).reasons[0].textKo)
    }

    @Test fun relaxedByCruiseOrSteadyHighway() {
        val cruise = instant(driving(t0, 80f).copy(cruiseActive = true))
        assertEquals(LoadLevel.RELAXED, cruise.level)
        assertEquals("크루즈 주행", cruise.reasons[0].textKo)
        assertEquals(LoadLevel.NORMAL, instant(driving(t0, 25f).copy(cruiseActive = true)).level)
        assertEquals(LoadLevel.NORMAL, instant(driving(t0, 80f).copy(cruiseActive = true, wipersOn = true)).level)
        assertEquals(LoadLevel.NORMAL, instant(driving(t0, 80f).copy(cruiseActive = true, trafficJam = true)).level)
        assertEquals(LoadLevel.NORMAL, instant(driving(t0, 80f).copy(cruiseActive = true, headlightsOn = true)).level)

        val steady = instant(driving(t0, 90f))
        assertEquals(LoadLevel.RELAXED, steady.level)
        assertEquals("고속 직진 구간", steady.reasons[0].textKo)
        assertEquals(LoadLevel.NORMAL, instant(driving(t0, 90f).copy(camera = CameraAhead(CameraKind.SPEED_FIXED, 400))).level)
        assertEquals(LoadLevel.RELAXED, instant(driving(t0, 90f).copy(camera = CameraAhead(CameraKind.SPEED_FIXED, 600))).level)
        assertEquals(LoadLevel.NORMAL, instant(driving(t0, 90f).copy(maneuver = Maneuver(ManeuverType.EXIT, 900))).level)
        assertEquals(LoadLevel.RELAXED, instant(driving(t0, 90f).copy(maneuver = Maneuver(ManeuverType.EXIT, 1_200))).level)
        assertEquals(LoadLevel.NORMAL, instant(driving(t0, 90f).copy(accelMps2 = 1.5f)).level)
        assertEquals(LoadLevel.NORMAL, instant(driving(t0, 90f).copy(steeringDeg = 10f)).level)
        assertEquals(LoadLevel.NORMAL, instant(driving(t0, 55f)).level)
        // 주행 중이면 항상 켜지는 AAOS 주의분산 제한은 RELAXED 를 막지 않는다
        assertEquals(LoadLevel.RELAXED, instant(driving(t0, 80f).copy(cruiseActive = true, uxRestricted = true)).level)
    }

    @Test fun reasonsSortedByLevelMaxThree() {
        val a = instant(
            driving(t0, 40f).copy(
                headlightsOn = true,
                turnSignal = TurnSignalState.LEFT,
                camera = CameraAhead(CameraKind.SPEED_FIXED, 100),
                accelMps2 = -4f,
            ),
        )
        assertEquals(LoadLevel.CRITICAL, a.level)
        assertEquals(3, a.reasons.size)
        assertEquals(ReasonCode.HARD_BRAKE, a.reasons[0].code)
        assertTrue(a.reasons.drop(1).all { it.code.level == LoadLevel.FOCUS })
    }

    @Test fun indexStaysInsideLevelBand() {
        assertEquals(35 + 5, instant(driving(t0, 65f).copy(headlightsOn = true)).index)
        assertEquals("즉시 판단은 15초 규칙 없이 RELAXED 대역", 10 + 5, instant(driving(t0, 65f)).index)
        val crit = instant(driving(t0, 130f).copy(accelMps2 = -5f, navVoicePrompt = true, audioFocusLost = true))
        assertEquals(100, crit.index)
        for (lv in LoadLevel.entries) {
            for (speed in listOf(0f, 50f, 200f)) for (n in 0..5) {
                assertTrue(RuleLoadEngine.indexOf(lv, speed, n) in lv.bandStart..lv.bandEnd)
            }
        }
    }

    // ───────────── 히스테리시스 ─────────────

    @Test fun escalatesImmediately() {
        engine.update(driving(t0))
        val a = engine.update(driving(t0 + 500).copy(turnSignal = TurnSignalState.LEFT))
        assertEquals(LoadLevel.FOCUS, a.level)
        assertEquals(t0 + 500, a.sinceMs)
    }

    @Test fun deescalatesOnlyAfterFourStableSeconds() {
        engine.update(driving(t0))
        engine.update(driving(t0).copy(turnSignal = TurnSignalState.LEFT))
        var t = t0 + 1_000
        var a = engine.update(driving(t))
        assertEquals(LoadLevel.FOCUS, a.level)
        assertEquals(LoadLevel.NORMAL, a.rawLevel)
        assertEquals("방향지시등", a.reasons[0].textKo)
        while (t < t0 + 4_500) {
            t += 500
            a = engine.update(driving(t))
            assertEquals("t=${t - t0}", LoadLevel.FOCUS, a.level)
        }
        a = engine.update(driving(t0 + 5_000))
        assertEquals(LoadLevel.NORMAL, a.level)
        assertEquals(t0 + 5_000, a.sinceMs)
    }

    @Test fun anyHigherSampleResetsTheWindow() {
        engine.update(driving(t0).copy(turnSignal = TurnSignalState.LEFT))
        engine.update(driving(t0 + 1_000))
        engine.update(driving(t0 + 3_000).copy(turnSignal = TurnSignalState.LEFT))
        engine.update(driving(t0 + 3_500))
        assertEquals(LoadLevel.FOCUS, engine.update(driving(t0 + 7_000)).level)
        assertEquals(LoadLevel.NORMAL, engine.update(driving(t0 + 7_500)).level)
    }

    @Test fun hardBrakeHoldsCriticalThreeSecondsThenFocusFourSeconds() {
        var t = t0
        engine.update(driving(t, 60f))
        while (t < t0 + 1_500) {
            assertEquals(LoadLevel.CRITICAL, engine.update(driving(t, 40f).copy(accelMps2 = -5.5f)).level)
            t += 250
        }
        // 원인 해소 (t0+1500) → 3초 동안 CRITICAL
        while (t < t0 + 4_500) {
            val a = engine.update(driving(t, 35f))
            assertEquals("t=${t - t0}", LoadLevel.CRITICAL, a.level)
            t += 250
        }
        var a = engine.update(driving(t0 + 4_500, 35f))
        assertEquals(LoadLevel.FOCUS, a.level)
        assertEquals("급제동 이유는 FOCUS 동안 유지", ReasonCode.HARD_BRAKE, a.reasons[0].code)
        t = t0 + 4_750
        while (t < t0 + 8_500) {
            a = engine.update(driving(t, 35f))
            assertEquals("t=${t - t0}", LoadLevel.FOCUS, a.level)
            t += 250
        }
        a = engine.update(driving(t0 + 8_500, 35f))
        assertEquals(LoadLevel.NORMAL, a.level)
    }

    @Test fun criticalStepsToFocusWithFocusReasonsWhenFocusIsRaw() {
        engine.update(driving(t0).copy(navVoicePrompt = true))
        engine.update(driving(t0 + 500).copy(turnSignal = TurnSignalState.LEFT))
        val a = engine.update(driving(t0 + 3_500).copy(turnSignal = TurnSignalState.LEFT))
        assertEquals(LoadLevel.FOCUS, a.level)
        assertEquals(ReasonCode.TURN_SIGNAL, a.reasons[0].code)
    }

    @Test fun steadyHighwayNeedsFifteenSecondsThenFourSecondsToSettle() {
        var t = t0
        var a = engine.update(driving(t, 90f))
        assertEquals(LoadLevel.NORMAL, a.level)
        assertEquals("고속 주행", a.reasons[0].textKo)
        while (t < t0 + 19_000) {
            t += 500
            a = engine.update(driving(t, 90f))
            if (t < t0 + 19_000) assertEquals("t=${t - t0}", LoadLevel.NORMAL, a.level)
        }
        assertEquals(LoadLevel.RELAXED, a.level)
        assertEquals("고속 직진 구간", a.reasons[0].textKo)
    }

    @Test fun steadyTimerRestartsWhenConditionBreaks() {
        var t = t0
        while (t <= t0 + 10_000) { engine.update(driving(t, 90f)); t += 500 }
        engine.update(driving(t, 90f).copy(steeringDeg = 12f))
        val restart = t + 500
        t = restart
        while (t < restart + 15_000) {
            assertEquals(LoadLevel.NORMAL, engine.update(driving(t, 90f)).level)
            t += 500
        }
        // 조건 15초 → raw RELAXED, 다시 4초 안정
        while (t < restart + 19_000) { engine.update(driving(t, 90f)); t += 500 }
        assertEquals(LoadLevel.RELAXED, engine.update(driving(restart + 19_000, 90f)).level)
    }

    @Test fun cruiseIsRawRelaxedImmediatelyButStillDeescalatesSlowly() {
        engine.update(driving(t0, 80f))
        val a = engine.update(driving(t0 + 500, 80f).copy(cruiseActive = true))
        assertEquals(LoadLevel.NORMAL, a.level)
        assertEquals(LoadLevel.RELAXED, a.rawLevel)
        assertEquals(LoadLevel.NORMAL, engine.update(driving(t0 + 4_000, 80f).copy(cruiseActive = true)).level)
        assertEquals(LoadLevel.RELAXED, engine.update(driving(t0 + 4_500, 80f).copy(cruiseActive = true)).level)
    }

    @Test fun parkingSettlesToParkedAndResetClears() {
        engine.update(driving(t0))
        engine.update(DrivingSignals(atMs = t0 + 1_000, gear = GearPosition.P))
        assertEquals(LoadLevel.NORMAL, engine.update(DrivingSignals(atMs = t0 + 4_500, gear = GearPosition.P)).level)
        assertEquals(LoadLevel.PARKED, engine.update(DrivingSignals(atMs = t0 + 5_000, gear = GearPosition.P)).level)
        engine.update(driving(t0 + 6_000).copy(navVoicePrompt = true))
        engine.reset()
        val a = engine.update(DrivingSignals(atMs = t0 + 6_500, gear = GearPosition.P))
        assertEquals(LoadLevel.PARKED, a.level)
        assertFalse(a.reasons.isEmpty())
    }
}
