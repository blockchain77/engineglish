package com.yeopjari.app.english.drive

import com.yeopjari.app.core.model.Gear
import com.yeopjari.app.core.model.Place
import com.yeopjari.app.core.model.PlaceKind
import com.yeopjari.app.core.model.RouteState
import com.yeopjari.app.core.model.VehicleSnapshot
import com.yeopjari.app.english.core.DemoScenario
import com.yeopjari.app.english.core.DestinationCategory
import com.yeopjari.app.english.core.DrivingSignals
import com.yeopjari.app.english.core.GearPosition
import com.yeopjari.app.english.core.LatLng
import com.yeopjari.app.english.core.Maneuver
import com.yeopjari.app.english.core.ManeuverType
import com.yeopjari.app.english.core.RoadKind
import com.yeopjari.app.english.core.SignalOrigin
import com.yeopjari.app.english.core.TurnSignalState
import com.yeopjari.app.english.core.VirtualTime
import com.yeopjari.app.sim.CommuteSimulator
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 신호 허브: 원천 선택 · 하트비트 · 오디오 합치기 · 실차 신호 조립 */
class DrivingSignalHubTest {

    private val vt = VirtualTime()
    private val scope = vt.scope()
    private val map = CommuteMaps.default
    private val sim = CommuteSimulator(scope, vt, map)
    private val audio = FakeAudio()

    private val connected = MutableStateFlow(false)
    private val vehicle = MutableStateFlow(VehicleSnapshot())
    private val route = MutableStateFlow(RouteState())
    private val location = MutableStateFlow<LatLng?>(null)
    private val ux = MutableStateFlow(false)
    private val arrivals = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    private val hub = DrivingSignalHub(scope, vt, map, sim, audio, CarInputs(connected, vehicle, route, location, ux, arrivals))
    private val s: DrivingSignals get() = hub.signals.value

    @After fun tearDown() {
        sim.release()
        scope.cancel()
    }

    @Test fun simulatorIdleWhenPleosIsNotConnectedWithHeartbeat() {
        assertEquals(SignalOrigin.SIMULATOR, s.origin)
        assertEquals(GearPosition.P, s.gear)
        repeat(20) {
            vt.advanceBy(100)
            assertTrue("atMs 는 500ms 안으로 새로", vt.nowMs() - s.atMs <= 500)
        }
    }

    @Test fun pleosWhenConnectedButScenarioWins() {
        vehicle.value = VehicleSnapshot(gear = Gear.D, speedKph = 42f, turnSignal = TurnSignalState.RIGHT, headlightsOn = true)
        ux.value = true
        connected.value = true
        assertEquals(SignalOrigin.PLEOS, s.origin)
        assertEquals(SignalOrigin.PLEOS, hub.origin.value)
        assertEquals(GearPosition.D, s.gear)
        assertEquals(42f, s.speedKph, 0f)
        assertEquals(TurnSignalState.RIGHT, s.turnSignal)
        assertTrue(s.headlightsOn && s.uxRestricted)

        sim.play(DemoScenario.MORNING)
        assertEquals(SignalOrigin.SIMULATOR, s.origin)
        assertEquals(GearPosition.P, s.gear)
        assertEquals(CommuteMaps.PLACE_OFFICE, s.destinationPlaceId)
        sim.stop()
        assertEquals(SignalOrigin.PLEOS, s.origin)
    }

    @Test fun audioFlagsAreMergedInBothModes() {
        audio.navPromptActive.value = true
        assertTrue(s.navVoicePrompt)
        audio.navPromptActive.value = false
        assertFalse(s.navVoicePrompt)
        connected.value = true
        audio.focusLost.value = true
        assertTrue(s.audioFocusLost)
        audio.focusLost.value = false
        sim.inject(com.yeopjari.app.english.core.DemoSignal.NAV_PROMPT)
        assertTrue("실차 원천이어도 데모 내비 음성은 합친다", s.navVoicePrompt)
    }

    @Test fun derivesAccelSteeringRateAndJam() {
        connected.value = true
        vehicle.value = VehicleSnapshot(gear = Gear.D, speedKph = 60f)
        vt.advanceBy(500)
        vehicle.value = VehicleSnapshot(gear = Gear.D, speedKph = 40f)
        assertTrue("60→40km/h in 0.5s ≈ −11 m/s²: ${s.accelMps2}", s.accelMps2 < -3.5f)
        vt.advanceBy(3_000)
        assertEquals(0f, s.accelMps2, 0.01f)

        vehicle.value = vehicle.value.copy(steeringDeg = 0f)
        vt.advanceBy(200)
        vehicle.value = vehicle.value.copy(steeringDeg = 40f)
        assertTrue("빠른 핸들 조작: ${s.steeringRateDps}", s.steeringRateDps > 60f)
        vt.advanceBy(1_500)
        assertEquals(0f, s.steeringRateDps, 0f)

        vehicle.value = vehicle.value.copy(speedKph = 8f)
        vt.advanceBy(29_000)
        assertFalse(s.trafficJam)
        vt.advanceBy(1_500)
        assertTrue("시속 15km 미만 30초 → 정체", s.trafficJam)
        vehicle.value = vehicle.value.copy(speedKph = 40f)
        vt.advanceBy(9_000)
        assertTrue(s.trafficJam)
        vt.advanceBy(1_500)
        assertFalse(s.trafficJam)
    }

    @Test fun destinationMatchingAndPrivacyOfUnknownNames() {
        connected.value = true
        vehicle.value = VehicleSnapshot(gear = Gear.D, speedKph = 50f)
        val office = map.place(CommuteMaps.PLACE_OFFICE)!!.point
        route.value = RouteState(
            active = true,
            destination = Place("poi1", "판교 어딘가 빌딩", PlaceKind.DESTINATION, office.lat + 0.001, office.lng),
            remainingMin = 25, remainingKm = 18f, remainingSec = 1_500, remainingM = 18_000,
            maneuver = Maneuver(ManeuverType.LEFT, 250), speedLimitKph = 60,
        )
        assertEquals(DestinationCategory.OFFICE, s.destinationCategory)
        assertEquals(CommuteMaps.PLACE_OFFICE, s.destinationPlaceId)
        assertEquals("회사 (판교테크노밸리)", s.destinationName)
        assertEquals(1_500, s.routeRemainingSec)
        assertEquals(18_000, s.routeRemainingM)
        assertEquals(ManeuverType.LEFT, s.maneuver?.type)
        assertEquals(60, s.speedLimitKph)

        route.value = route.value.copy(destination = Place("p2", "친구네 아파트 101동", PlaceKind.DESTINATION, 37.6, 127.3))
        assertEquals(DestinationCategory.OTHER, s.destinationCategory)
        assertEquals("지도 밖 목적지 이름은 쓰지 않는다", "목적지", s.destinationName)
        assertNull(s.destinationPlaceId)

        route.value = route.value.copy(destination = Place("p3", "김포공항 국내선", PlaceKind.DESTINATION, 37.5, 126.0))
        assertEquals(DestinationCategory.AIRPORT, s.destinationCategory)

        route.value = RouteState()
        assertNull(s.destinationCategory)
        assertFalse(s.routeActive)
        assertNull(s.maneuver)
    }

    @Test fun locationMapsToSegmentsAndTunnel() {
        connected.value = true
        vehicle.value = VehicleSnapshot(gear = Gear.D, speedKph = 70f)
        val tunnel = map.segment(CommuteMaps.SEG_CITY_TUNNEL)!!
        location.value = LatLng((tunnel.start.lat + tunnel.end.lat) / 2, (tunnel.start.lng + tunnel.end.lng) / 2)
        assertEquals(CommuteMaps.SEG_CITY_TUNNEL, s.segmentId)
        assertEquals(RoadKind.TUNNEL, s.roadKind)
        assertTrue(s.inTunnel)
        location.value = LatLng(37.3, 126.9)
        assertNull(s.segmentId)
        assertFalse(s.inTunnel)
    }

    @Test fun arrivalFlagLastsFiveSeconds() {
        connected.value = true
        vehicle.value = VehicleSnapshot(gear = Gear.P)
        arrivals.tryEmit(Unit)
        assertTrue(s.arrived)
        vt.advanceBy(4_750)
        assertTrue(s.arrived)
        vt.advanceBy(500)
        assertFalse(s.arrived)
    }

    @Test fun driveIdChangesOnlyAfterLongPark() {
        connected.value = true
        location.value = map.place(CommuteMaps.PLACE_HOME)!!.point
        vehicle.value = VehicleSnapshot(gear = Gear.P)
        assertNull(s.driveId)
        vehicle.value = VehicleSnapshot(gear = Gear.D, speedKph = 10f)
        val first = s.driveId
        assertNotNull(first)
        assertEquals("출발한 곳이 집", CommuteMaps.PLACE_HOME, s.originPlaceId)
        vehicle.value = VehicleSnapshot(gear = Gear.P)
        vt.advanceBy(30_000)
        vehicle.value = VehicleSnapshot(gear = Gear.D, speedKph = 10f)
        assertEquals("30초 정차는 같은 주행", first, s.driveId)
        vehicle.value = VehicleSnapshot(gear = Gear.P)
        vt.advanceBy(61_000)
        vehicle.value = VehicleSnapshot(gear = Gear.D, speedKph = 10f)
        assertNotEquals(first, s.driveId)
    }

    @Test fun driveIdTrackerRules() {
        val tr = DriveIdTracker()
        assertFalse(tr.update(GearPosition.P, 0))
        assertTrue(tr.update(GearPosition.D, 1_000))
        val id = tr.driveId
        assertFalse(tr.update(GearPosition.UNKNOWN, 2_000))
        assertFalse(tr.update(GearPosition.P, 3_000))
        assertFalse(tr.update(GearPosition.D, 62_999))
        assertEquals(id, tr.driveId)
        assertFalse(tr.update(GearPosition.P, 70_000))
        assertTrue(tr.update(GearPosition.R, 130_000))
        assertNotEquals(id, tr.driveId)
        val forced = tr.forceNew(140_000, parked = true)
        assertEquals(forced, tr.driveId)
        assertFalse("시나리오 시작 직후 출발은 같은 주행", tr.update(GearPosition.D, 142_000))
    }
}
