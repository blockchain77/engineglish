package com.yeopjari.app.english.drive

import com.yeopjari.app.core.model.Gear
import com.yeopjari.app.core.model.Place
import com.yeopjari.app.core.model.PlaceKind
import com.yeopjari.app.english.core.CameraKind
import com.yeopjari.app.english.core.CautionKind
import com.yeopjari.app.english.core.DemoScenario
import com.yeopjari.app.english.core.DemoSignal
import com.yeopjari.app.english.core.DestinationCategory
import com.yeopjari.app.english.core.DrivingSignals
import com.yeopjari.app.english.core.GearPosition
import com.yeopjari.app.english.core.HookTrigger
import com.yeopjari.app.english.core.LoadLevel
import com.yeopjari.app.english.core.ManeuverType
import com.yeopjari.app.english.core.SignalOrigin
import com.yeopjari.app.english.core.TurnSignalState
import com.yeopjari.app.english.core.VirtualTime
import com.yeopjari.app.sim.CommuteSimulator
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 시뮬레이터 시나리오 · 경로 이벤트 · 데모 주입 (가상 시간) */
class CommuteSimulatorTest {

    private val vt = VirtualTime()
    private val scope = vt.scope()
    private val map = CommuteMaps.default
    private val sim = CommuteSimulator(scope, vt, map)
    private val s: DrivingSignals get() = sim.signals.value

    @After fun tearDown() {
        sim.release()
        scope.cancel()
    }

    /** 250ms 씩 흘리며 [block] 을 부른다. block 이 true 면 멈춤 */
    private fun drive(maxMs: Long, block: (DrivingSignals) -> Boolean = { false }): Long {
        var t = 0L
        while (t < maxMs) {
            vt.advanceBy(250)
            t += 250
            if (block(s)) break
        }
        return t
    }

    @Test fun idleIsParkedAtHome() {
        assertNull(sim.scenario.value)
        assertEquals(GearPosition.P, s.gear)
        assertFalse(s.routeActive)
        assertFalse(s.uxRestricted)
        assertEquals(SignalOrigin.SIMULATOR, s.origin)
        assertEquals(map.place(CommuteMaps.PLACE_HOME)!!.point, s.location)
        vt.advanceBy(1_000)
        assertEquals("틱마다 atMs 갱신", vt.nowMs(), s.atMs)
        assertEquals(CommuteSimulator.DEFAULT_TIME_SCALE, sim.timeScale.value, 0f)
    }

    @Test fun morningStartsParkedThenDepartsOnTheSameDrive() {
        sim.play(DemoScenario.MORNING)
        assertEquals(DemoScenario.MORNING, sim.scenario.value)
        assertEquals(1L, sim.runId.value)
        assertTrue(s.routeActive)
        assertEquals(1_920, s.routeRemainingSec)
        assertEquals(22_000, s.routeRemainingM)
        assertEquals(CommuteMaps.PLACE_OFFICE, s.destinationPlaceId)
        assertEquals(DestinationCategory.OFFICE, s.destinationCategory)
        assertEquals("회사 (판교테크노밸리)", s.destinationName)
        assertEquals(CommuteMaps.PLACE_HOME, s.originPlaceId)
        assertEquals(CommuteMaps.SEG_HOME_STREET, s.segmentId)
        assertEquals(GearPosition.P, s.gear)
        val drive = s.driveId
        assertNotNull(drive)
        vt.advanceBy(2_250)
        assertEquals(GearPosition.D, s.gear)
        assertTrue(s.uxRestricted)
        assertEquals("2초 정차는 같은 주행", drive, s.driveId)
        // 레거시 게이트웨이 보기
        assertEquals(Gear.D, sim.vehicle.snapshot.value.gear)
        assertTrue(sim.navi.route.value.active)
        assertTrue(sim.navi.route.value.remainingMin in 31..32)
        assertNotNull(sim.location.location.value)
        assertTrue(runBlocking { sim.navi.setDestination(Place("x", "x", PlaceKind.DESTINATION, 37.401, 127.108)) }.isFailure)
    }

    @Test fun morningRouteEmitsEveryEventInOrderWithoutSpuriousCriticals() {
        sim.setTimeScale(20f)
        sim.play(DemoScenario.MORNING)
        val segments = ArrayList<String>()
        val maneuverTypes = LinkedHashSet<ManeuverType>()
        var leftNear = false
        var turnLeft = false
        var steerLeft = false
        var steerRight = false
        var schoolInside = false
        var schoolCamera = false
        var fixedCamera = false
        var tunnel = false
        var cruise = false
        var jam = false
        var lastRemaining = Int.MAX_VALUE
        drive(400_000) { x ->
            x.segmentId?.let { if (segments.lastOrNull() != it) segments += it }
            x.maneuver?.let { m ->
                maneuverTypes += m.type
                if (m.type == ManeuverType.LEFT && m.distanceM <= 300) leftNear = true
            }
            if (x.turnSignal == TurnSignalState.LEFT) turnLeft = true
            if (x.steeringDeg <= -25f) steerLeft = true
            if (x.steeringDeg >= 25f) steerRight = true
            if (x.caution?.kind == CautionKind.SCHOOL_ZONE && x.caution!!.inside) schoolInside = true
            x.camera?.let { c ->
                if (c.distanceM <= 200 && c.kind == CameraKind.SCHOOL_ZONE) schoolCamera = true
                if (c.distanceM <= 200 && c.kind == CameraKind.SPEED_FIXED) fixedCamera = true
            }
            if (x.inTunnel) tunnel = true
            if (x.cruiseActive) cruise = true
            if (x.trafficJam) jam = true
            x.routeRemainingSec?.let { r ->
                assertTrue("남은 시간은 줄기만", r <= lastRemaining)
                lastRemaining = r
            }
            x.speedLimitKph?.let { lim -> assertTrue("과속 없음: ${x.speedKph} / $lim @ ${x.segmentId}", x.speedKph <= lim + 10) }
            assertTrue("시뮬레이터가 스스로 급제동하지 않음: ${x.accelMps2}", x.accelMps2 > -3.5f)
            x.arrived
        }
        assertEquals(map.route(CommuteMaps.ROUTE_MORNING)!!.segmentIds, segments)
        assertEquals(listOf(ManeuverType.LEFT, ManeuverType.EXIT, ManeuverType.RIGHT, ManeuverType.ARRIVE), maneuverTypes.toList())
        assertTrue(leftNear && turnLeft && steerLeft && steerRight)
        assertTrue(schoolInside && schoolCamera && fixedCamera)
        assertTrue(tunnel && cruise && jam)
        // 도착
        assertTrue(s.arrived)
        assertEquals(GearPosition.P, s.gear)
        assertFalse(s.routeActive)
        assertNull(s.segmentId)
        assertEquals(map.place(CommuteMaps.PLACE_OFFICE)!!.point, s.location)
        vt.advanceBy(5_250)
        assertFalse(s.arrived)
    }

    @Test fun morningAtDefaultScaleDrivesLoadLevelsAndContextHooks() {
        val engine = RuleLoadEngine()
        val hooks = RuleContextHookDetector()
        sim.play(DemoScenario.MORNING)
        val levels = LinkedHashSet<LoadLevel>()
        val triggers = ArrayList<HookTrigger>()
        drive(900_000) { x ->
            val a = engine.update(x)
            levels += a.level
            triggers += hooks.update(x, a).map { it.trigger }
            x.arrived
        }
        assertTrue("도착해야 함", s.arrived)
        assertTrue(levels.containsAll(listOf(LoadLevel.PARKED, LoadLevel.NORMAL, LoadLevel.FOCUS, LoadLevel.RELAXED)))
        assertFalse("정상 주행만으로는 CRITICAL 이 없다", LoadLevel.CRITICAL in levels)
        assertTrue(triggers.toString(), triggers.containsAll(listOf(HookTrigger.SCHOOL_ZONE, HookTrigger.TUNNEL, HookTrigger.CRUISE, HookTrigger.JAM_EXIT, HookTrigger.ARRIVING_OFFICE)))
        assertEquals("트리거는 주행당 한 번", triggers.size, triggers.toSet().size)
    }

    @Test fun eveningIsTheMirroredRouteHome() {
        sim.setTimeScale(20f)
        sim.play(DemoScenario.EVENING)
        assertEquals(CommuteMaps.PLACE_OFFICE, s.originPlaceId)
        assertEquals(DestinationCategory.HOME, s.destinationCategory)
        assertEquals(1_920, s.routeRemainingSec)
        assertEquals(ManeuverType.LEFT, s.maneuver?.type)
        assertEquals(CommuteMaps.SEG_PANGYO_STATION_ROAD, s.segmentId)
        val segments = ArrayList<String>()
        drive(400_000) { x ->
            x.segmentId?.let { if (segments.lastOrNull() != it) segments += it }
            x.speedLimitKph?.let { lim -> assertTrue(x.speedKph <= lim + 10) }
            x.arrived
        }
        assertEquals(map.route(CommuteMaps.ROUTE_EVENING)!!.segmentIds, segments)
        assertEquals(map.place(CommuteMaps.PLACE_HOME)!!.point, s.location)
    }

    @Test fun chargingAndParkedScenarios() {
        sim.play(DemoScenario.CHARGING)
        assertEquals(GearPosition.P, s.gear)
        assertTrue(s.charging)
        val soc0 = s.socPercent!!
        assertEquals(map.place(CommuteMaps.PLACE_CHARGER)!!.point, s.location)
        vt.advanceBy(60_000)
        assertTrue(s.socPercent!! > soc0)
        val drive1 = s.driveId

        sim.play(DemoScenario.PARKED)
        assertEquals(GearPosition.P, s.gear)
        assertFalse(s.charging)
        assertFalse(s.routeActive)
        assertNotEquals("시나리오마다 새 주행", drive1, s.driveId)
        assertEquals(2L, sim.runId.value)

        sim.stop()
        assertNull(sim.scenario.value)
        assertEquals(map.place(CommuteMaps.PLACE_HOME)!!.point, s.location)
    }

    private fun departSlowly() {
        sim.setTimeScale(1f)
        sim.play(DemoScenario.MORNING)
        vt.advanceBy(12_000)
        assertEquals(GearPosition.D, s.gear)
        assertTrue(s.speedKph > 15f)
    }

    @Test fun turnSignalInjection() {
        departSlowly()
        sim.inject(DemoSignal.TURN_ON)
        assertEquals("다음 안내가 좌회전", TurnSignalState.LEFT, s.turnSignal)
        vt.advanceBy(9_500)
        assertEquals(TurnSignalState.LEFT, s.turnSignal)
        vt.advanceBy(750)
        assertEquals("10초 뒤 자동으로 꺼짐", TurnSignalState.OFF, s.turnSignal)
        sim.inject(DemoSignal.INTERSECTION)
        sim.inject(DemoSignal.TURN_ON)
        assertEquals("다음 안내가 우회전이면 오른쪽", TurnSignalState.RIGHT, s.turnSignal)
        sim.inject(DemoSignal.TURN_OFF)
        assertNotEquals(TurnSignalState.LEFT, s.turnSignal)
    }

    @Test fun intersectionCountsDownThenSteersRight() {
        departSlowly()
        sim.inject(DemoSignal.INTERSECTION)
        assertEquals(ManeuverType.RIGHT, s.maneuver?.type)
        assertEquals(280, s.maneuver?.distanceM)
        var sawSteer = false
        var lastDistance = 280
        drive(60_000) { x ->
            val m = x.maneuver
            if (m != null && m.type == ManeuverType.RIGHT) {
                assertTrue(m.distanceM <= lastDistance)
                lastDistance = m.distanceM
            }
            if (x.steeringDeg >= 30f) sawSteer = true
            sawSteer && abs(x.steeringDeg) < 5f
        }
        assertTrue(sawSteer)
        assertNotEquals("교차로가 지나가면 원래 경로 안내", ManeuverType.RIGHT, s.maneuver?.type)
    }

    @Test fun hardBrakeThenRecover() {
        departSlowly()
        val before = s.speedKph
        sim.inject(DemoSignal.HARD_BRAKE)
        vt.advanceBy(250)
        assertEquals(-5.5f, s.accelMps2, 0f)
        assertTrue(s.brakePedal)
        vt.advanceBy(1_500)
        assertTrue(s.speedKph <= (before - 20f).coerceAtLeast(0f) + 1f)
        assertTrue(s.accelMps2 > -3.5f)
        vt.advanceBy(7_000)
        assertTrue("다시 속도를 낸다", s.speedKph > 15f)
    }

    @Test fun jamCruiseNavPromptTunnelSchoolNightRain() {
        departSlowly()
        sim.inject(DemoSignal.JAM)
        assertTrue(s.trafficJam)
        vt.advanceBy(5_000)
        assertTrue(s.trafficJam)
        assertTrue("정체 중 시속 0~12km", s.speedKph <= 13f)
        vt.advanceBy(20_250)
        assertFalse(s.trafficJam)

        sim.inject(DemoSignal.NEAR_ARRIVAL)
        assertTrue(abs(s.routeRemainingSec!! - CommuteSimulator.NEAR_ARRIVAL_SEC) <= 1)
        assertTrue("마무리(남은 60초) 직전으로 건너뛴다", s.routeRemainingSec!! > 60)
        assertEquals(CommuteMaps.SEG_PANGYO_STATION_ROAD, s.segmentId)
        sim.inject(DemoSignal.CRUISE_ON)
        vt.advanceBy(15_000)
        assertTrue(s.cruiseActive)
        assertTrue(s.speedKph >= 45f && s.speedKph <= 52f)
        sim.inject(DemoSignal.CRUISE_OFF)
        assertFalse(s.cruiseActive)

        sim.inject(DemoSignal.NAV_PROMPT)
        assertTrue(s.navVoicePrompt)
        assertTrue(sim.injectedNavPrompt(vt.nowMs()))
        vt.advanceBy(3_250)
        assertFalse(s.navVoicePrompt)

        sim.inject(DemoSignal.TUNNEL)
        assertTrue(s.inTunnel)
        vt.advanceBy(15_250)
        assertFalse(s.inTunnel)

        sim.inject(DemoSignal.SCHOOL_ZONE)
        assertEquals(CautionKind.SCHOOL_ZONE, s.caution?.kind)
        assertTrue(s.caution!!.inside)
        assertEquals(30, s.speedLimitKph)
        assertTrue("보호구역 주입이 과속을 만들지 않음", s.speedKph <= 40f)
        val engine = RuleLoadEngine()
        assertEquals(LoadLevel.FOCUS, engine.instant(s).level)
        vt.advanceBy(12_250)
        assertFalse(s.caution?.inside == true)

        sim.inject(DemoSignal.NIGHT_ON)
        sim.inject(DemoSignal.RAIN_ON)
        assertTrue(s.headlightsOn && s.wipersOn)
        sim.inject(DemoSignal.NIGHT_OFF)
        sim.inject(DemoSignal.RAIN_OFF)
        assertFalse(s.headlightsOn || s.wipersOn)
    }

    @Test fun airportDestinationAndArrive() {
        departSlowly()
        sim.inject(DemoSignal.AIRPORT_DESTINATION)
        assertEquals(DestinationCategory.AIRPORT, s.destinationCategory)
        assertEquals(CommuteMaps.PLACE_AIRPORT, s.destinationPlaceId)
        assertEquals("김포공항", s.destinationName)

        sim.inject(DemoSignal.ARRIVE)
        vt.advanceBy(3_250)
        assertEquals(GearPosition.P, s.gear)
        assertTrue(s.arrived)
        assertFalse(s.routeActive)
        assertEquals(map.place(CommuteMaps.PLACE_AIRPORT)!!.point, s.location)
        vt.advanceBy(5_000)
        assertFalse(s.arrived)
    }

    @Test fun parkedCarHasNoSteeringOrBrakeAndTimeScaleIsClamped() {
        assertEquals(0f, s.steeringDeg, 0f)
        assertFalse(s.brakePedal)

        sim.setTimeScale(50f)
        assertEquals(20f, sim.timeScale.value, 0f)
        sim.setTimeScale(0.1f)
        assertEquals(1f, sim.timeScale.value, 0f)
    }
}
