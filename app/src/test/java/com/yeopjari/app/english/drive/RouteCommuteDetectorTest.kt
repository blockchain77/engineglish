package com.yeopjari.app.english.drive

import com.yeopjari.app.english.core.CommutePattern
import com.yeopjari.app.english.core.CommuteSlot
import com.yeopjari.app.english.core.DestinationCategory
import com.yeopjari.app.english.core.DrivingSignals
import com.yeopjari.app.english.core.GearPosition
import com.yeopjari.app.english.core.Kst
import com.yeopjari.app.english.core.VirtualTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 출퇴근 판단 (명세 §4.6) — 경로 방향 우선, 시각은 보조 */
class RouteCommuteDetectorTest {

    private val map = CommuteMaps.default
    private val detector = RouteCommuteDetector(map)
    private val morning = VirtualTime.START_MS // 월 07:40 KST

    private fun at(h: Int, m: Int): Long =
        ZonedDateTime.of(2026, 11, 16, h, m, 0, 0, ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()

    private fun sig(origin: String? = null, dest: String? = null, cat: DestinationCategory? = null) = DrivingSignals(
        atMs = morning, gear = GearPosition.D, speedKph = 40f, originPlaceId = origin, destinationPlaceId = dest,
        destinationCategory = cat, routeRemainingSec = 1_800,
    )

    @Test fun routeDirectionWinsWithHighConfidence() {
        val m = detector.detect(sig(CommuteMaps.PLACE_HOME, CommuteMaps.PLACE_OFFICE), CommutePattern(), at(18, 30))
        assertEquals(CommuteSlot.MORNING, m.slot)
        assertEquals(0.9f, m.confidence, 1e-6f)
        assertEquals(CommuteMaps.ROUTE_MORNING, m.routeId)
        assertEquals(1_800, m.routeRemainingSec)
        assertEquals(DestinationCategory.OFFICE, m.destinationCategory)

        val e = detector.detect(sig(CommuteMaps.PLACE_OFFICE, CommuteMaps.PLACE_HOME), CommutePattern(), morning)
        assertEquals(CommuteSlot.EVENING, e.slot)
        assertEquals(CommuteMaps.ROUTE_EVENING, e.routeId)
    }

    @Test fun destinationOnlyThenTimeOfDay() {
        assertEquals(0.7f, detector.detect(sig(dest = CommuteMaps.PLACE_OFFICE), CommutePattern(), at(20, 0)).confidence, 1e-6f)
        assertEquals(CommuteSlot.MORNING, detector.detect(sig(cat = DestinationCategory.OFFICE), CommutePattern(), at(20, 0)).slot)
        assertEquals(CommuteSlot.EVENING, detector.detect(sig(dest = CommuteMaps.PLACE_HOME), CommutePattern(), morning).slot)

        val am = detector.detect(sig(), CommutePattern(), at(7, 40))
        assertEquals(CommuteSlot.MORNING, am.slot)
        assertEquals(0.4f, am.confidence, 1e-6f)
        assertNull(am.routeId)
        assertEquals(CommuteSlot.EVENING, detector.detect(sig(), CommutePattern(), at(18, 30)).slot)
        val noon = detector.detect(sig(), CommutePattern(), at(13, 0))
        assertEquals(CommuteSlot.OTHER, noon.slot)
        assertEquals(0.2f, noon.confidence, 1e-6f)
        assertEquals(CommuteSlot.OTHER, detector.detect(sig(cat = DestinationCategory.AIRPORT), CommutePattern(), at(23, 0)).slot)
    }

    @Test fun learnedDeparturesRefineTimeGuess() {
        val p = CommutePattern(eveningDepartures = listOf(15 * 60 + 10, 15 * 60 + 20, 15 * 60 + 30))
        val c = detector.detect(sig(), p, at(15, 0))
        assertEquals(CommuteSlot.EVENING, c.slot)
        assertEquals(0.5f, c.confidence, 1e-6f)
    }

    @Test fun learnKeepsLastTenMinutesOnly() {
        var p = CommutePattern()
        val ctx = detector.detect(sig(CommuteMaps.PLACE_HOME, CommuteMaps.PLACE_OFFICE), p, morning)
        repeat(12) { i -> p = detector.learn(p, ctx, morning + i * 60_000L) }
        assertEquals(10, p.morningDepartures.size)
        assertEquals(Kst.minuteOfDay(morning + 11 * 60_000L), p.morningDepartures.last())
        assertEquals(12, p.trips)
        val other = detector.learn(p, ctx.copy(slot = CommuteSlot.OTHER), morning)
        assertEquals(13, other.trips)
        assertEquals(p.morningDepartures, other.morningDepartures)
    }
}
