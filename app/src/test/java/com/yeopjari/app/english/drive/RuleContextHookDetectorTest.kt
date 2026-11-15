package com.yeopjari.app.english.drive

import com.yeopjari.app.english.core.CautionAhead
import com.yeopjari.app.english.core.CautionKind
import com.yeopjari.app.english.core.DestinationCategory
import com.yeopjari.app.english.core.DrivingSignals
import com.yeopjari.app.english.core.GearPosition
import com.yeopjari.app.english.core.HookEvent
import com.yeopjari.app.english.core.HookTrigger
import com.yeopjari.app.english.core.LoadAssessment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleContextHookDetectorTest {

    private val detector = RuleContextHookDetector()
    private var t = 5_000_000L
    private val fired = ArrayList<HookEvent>()

    /** [seconds] 초 동안 500ms 마다 같은 신호를 넣는다 */
    private fun run(seconds: Int, drive: String = "d1", f: (DrivingSignals) -> DrivingSignals) {
        repeat(seconds * 2) {
            t += 500
            fired += detector.update(f(driving(t).copy(driveId = drive)), LoadAssessment())
        }
    }

    private fun triggers() = fired.map { it.trigger }

    @Test fun rainAfterTenSecondsOfWipersOncePerDrive() {
        run(9) { it.copy(wipersOn = true) }
        assertTrue(triggers().isEmpty())
        run(2) { it.copy(wipersOn = true) }
        assertEquals(listOf(HookTrigger.RAIN), triggers())
        run(5) { it }
        run(15) { it.copy(wipersOn = true) }
        assertEquals("같은 주행에서는 한 번", 1, triggers().count { it == HookTrigger.RAIN })
    }

    @Test fun cooldownSurvivesNewDriveButResetClearsIt() {
        run(11) { it.copy(wipersOn = true) }
        assertEquals(1, triggers().size)
        run(11, drive = "d2") { it.copy(wipersOn = true) }
        assertEquals("10분 쿨다운", 1, triggers().size)
        t += 10 * 60 * 1000L
        run(11, drive = "d3") { it.copy(wipersOn = true) }
        assertEquals(2, triggers().size)
        detector.reset()
        run(11, drive = "d3") { it.copy(wipersOn = true) }
        assertEquals("reset 은 쿨다운까지 지운다", 3, triggers().size)
    }

    @Test fun jamExitNeedsLongJamThenFastFiveSeconds() {
        run(21) { it.copy(trafficJam = true, speedKph = 6f) }
        run(4) { it.copy(speedKph = 40f) }
        assertTrue(triggers().isEmpty())
        run(2) { it.copy(speedKph = 40f) }
        assertEquals(listOf(HookTrigger.JAM_EXIT), triggers())
        assertEquals("정체 구간을 빠져나왔어요", fired[0].detailKo)
    }

    @Test fun shortJamOrSlowExitDoesNotFire() {
        run(15) { it.copy(trafficJam = true, speedKph = 6f) }
        run(10) { it.copy(speedKph = 40f) }
        assertTrue(triggers().isEmpty())
        run(25) { it.copy(trafficJam = true, speedKph = 6f) }
        run(30) { it.copy(speedKph = 20f) }
        assertTrue(triggers().isEmpty())
        run(6) { it.copy(speedKph = 35f) }
        assertEquals(listOf(HookTrigger.JAM_EXIT), triggers())
    }

    @Test fun tunnelFiresOnExit() {
        run(5) { it.copy(inTunnel = true) }
        assertTrue(triggers().isEmpty())
        run(1) { it }
        assertEquals(listOf(HookTrigger.TUNNEL), triggers())
    }

    @Test fun arrivingOfficeAndHome() {
        run(2) { it.copy(destinationCategory = DestinationCategory.OFFICE, routeRemainingSec = 900) }
        assertTrue(triggers().isEmpty())
        run(2) { it.copy(destinationCategory = DestinationCategory.OFFICE, routeRemainingSec = 290) }
        assertEquals(listOf(HookTrigger.ARRIVING_OFFICE), triggers())
        assertEquals("회사 도착 5분 전", fired[0].detailKo)
        run(2, drive = "evening") { it.copy(destinationCategory = DestinationCategory.HOME, routeRemainingSec = 120) }
        assertEquals(HookTrigger.ARRIVING_HOME, triggers().last())
    }

    @Test fun airportOnceChargingNightCruise() {
        run(3) { it.copy(destinationCategory = DestinationCategory.AIRPORT) }
        assertEquals(listOf(HookTrigger.AIRPORT), triggers())

        fired.clear()
        run(6, drive = "c") { DrivingSignals(atMs = it.atMs, gear = GearPosition.P, charging = true, driveId = "c") }
        assertEquals(listOf(HookTrigger.CHARGING), triggers())

        fired.clear()
        run(59, drive = "n") { it.copy(headlightsOn = true) }
        assertTrue(triggers().isEmpty())
        run(2, drive = "n") { it.copy(headlightsOn = true) }
        assertEquals(listOf(HookTrigger.NIGHT_DRIVE), triggers())

        fired.clear()
        run(21, drive = "k") { it.copy(cruiseActive = true, speedKph = 85f) }
        assertEquals(listOf(HookTrigger.CRUISE), triggers())
    }

    @Test fun schoolZoneAfterLeavingFiveSeconds() {
        run(8) { it.copy(speedKph = 28f, caution = CautionAhead(CautionKind.SCHOOL_ZONE, 0, inside = true)) }
        run(4) { it }
        assertTrue(triggers().isEmpty())
        run(2) { it }
        assertEquals(listOf(HookTrigger.SCHOOL_ZONE), triggers())
    }
}
