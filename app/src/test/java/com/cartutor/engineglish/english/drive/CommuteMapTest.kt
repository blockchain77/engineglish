package com.cartutor.engineglish.english.drive

import com.cartutor.engineglish.english.core.CameraKind
import com.cartutor.engineglish.english.core.DestinationCategory
import com.cartutor.engineglish.english.core.LatLng
import com.cartutor.engineglish.english.core.ManeuverType
import com.cartutor.engineglish.english.core.RoadKind
import com.cartutor.engineglish.english.core.SegmentEvent
import com.cartutor.engineglish.english.core.SegmentEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

/** 새 가상 출퇴근 지도 (잠실 ↔ 판교) — 지도 정보 초기화 후 재적용 검증 */
class CommuteMapTest {

    private val map = CommuteMaps.default
    private val morning = RoutePlan(map, map.route(CommuteMaps.ROUTE_MORNING)!!)
    private val evening = RoutePlan(map, map.route(CommuteMaps.ROUTE_EVENING)!!)

    private fun coarse3(v: Double) = abs(v * 1000 - (v * 1000).roundToLong()) < 1e-6

    @Test fun placesArePublicLandmarksWithCoarseCoordinates() {
        assertEquals(
            listOf(CommuteMaps.PLACE_HOME, CommuteMaps.PLACE_OFFICE, CommuteMaps.PLACE_CHARGER, CommuteMaps.PLACE_AIRPORT),
            map.places.map { it.id },
        )
        assertEquals(DestinationCategory.HOME, map.place(CommuteMaps.PLACE_HOME)!!.category)
        assertEquals(DestinationCategory.OFFICE, map.place(CommuteMaps.PLACE_OFFICE)!!.category)
        assertEquals(DestinationCategory.CHARGER, map.place(CommuteMaps.PLACE_CHARGER)!!.category)
        assertEquals(DestinationCategory.AIRPORT, map.place(CommuteMaps.PLACE_AIRPORT)!!.category)
        assertTrue(map.place(CommuteMaps.PLACE_HOME)!!.nameKo.contains("잠실역"))
        assertTrue(map.place(CommuteMaps.PLACE_OFFICE)!!.nameKo.contains("판교테크노밸리"))
        val points = map.places.map { it.point } + map.segments.flatMap { listOf(it.start, it.end) }
        for (p in points) assertTrue("좌표는 소수점 3자리: $p", coarse3(p.lat) && coarse3(p.lng))
        // 개인정보가 들어갈 자리 없음 (전화번호·이메일·번지 형식 없음)
        val texts = map.places.map { it.nameKo } + map.segments.map { it.nameKo } + map.nameKo
        for (s in texts) {
            assertFalse(s, s.contains('@'))
            assertFalse(s, Regex("\\d{2,4}-\\d{3,4}").containsMatchIn(s))
            assertFalse(s, s.contains("번지"))
        }
    }

    @Test fun segmentsFormOneChainWithRequiredFeatures() {
        val segs = map.segments
        assertEquals(10, segs.size)
        assertEquals(segs.size, segs.map { it.id }.toSet().size)
        assertTrue(segs.all { it.id.startsWith("seg.") && it.nameKo.isNotBlank() })
        assertTrue(segs.all { it.typicalSpeedKph in 1..it.speedLimitKph })
        segs.zipWithNext().forEach { (a, b) -> assertEquals("${a.id} → ${b.id}", a.end, b.start) }
        assertEquals(22_000, segs.sumOf { it.lengthM })
        assertEquals(map.place(CommuteMaps.PLACE_HOME)!!.point, segs.first().start)
        assertEquals(map.place(CommuteMaps.PLACE_OFFICE)!!.point, segs.last().end)

        val kinds = segs.map { it.road }.toSet()
        assertTrue(kinds.containsAll(listOf(RoadKind.LOCAL, RoadKind.CITY, RoadKind.SCHOOL_ZONE, RoadKind.RIVERSIDE, RoadKind.TUNNEL, RoadKind.HIGHWAY, RoadKind.JAM, RoadKind.EXPRESSWAY_EXIT)))
        val events = segs.flatMap { it.events }
        fun has(kind: SegmentEventKind) = events.any { it.kind == kind }
        assertTrue(has(SegmentEventKind.TUNNEL) && has(SegmentEventKind.SCHOOL_ZONE) && has(SegmentEventKind.SPEED_CAMERA) && has(SegmentEventKind.JAM))
        val maneuvers = events.mapNotNull { it.maneuver }
        assertTrue(maneuvers.containsAll(listOf(ManeuverType.LEFT, ManeuverType.EXIT, ManeuverType.RIGHT)))
        // 크루즈 구간: 3km 넘게 이벤트 없음
        val highway = segs.first { it.road == RoadKind.HIGHWAY }
        assertTrue(highway.events.isEmpty() && highway.lengthM >= 3_000)
        for (s in segs) for (e in s.events) assertTrue("${s.id} 이벤트 범위", e.atM in 0..s.lengthM && e.atM + e.lengthM <= s.lengthM)

        assertEquals(segs.map { it.id }, map.route(CommuteMaps.ROUTE_MORNING)!!.segmentIds)
        assertEquals(segs.map { it.id }.reversed(), map.route(CommuteMaps.ROUTE_EVENING)!!.segmentIds)
        assertEquals(CommuteMaps.ROUTE_EVENING, map.routeBetween(CommuteMaps.PLACE_OFFICE, CommuteMaps.PLACE_HOME)!!.id)
    }

    @Test fun morningPlanTimesAndEvents() {
        assertEquals(22_000, morning.totalM)
        assertTrue(morning.legs.none { it.reversed })
        assertEquals(CommuteMaps.NOMINAL_ROUTE_SEC, morning.remainingSec(0.0))
        assertEquals(0, morning.remainingSec(22_000.0))
        var prev = Int.MAX_VALUE
        var pos = 0.0
        while (pos <= 22_000.0) {
            val r = morning.remainingSec(pos)
            assertTrue(r <= prev)
            prev = r
            pos += 250.0
        }
        val near = morning.posForRemainingSec(170)
        assertTrue(abs(morning.remainingSec(near) - 170) <= 1)
        assertEquals(ManeuverType.LEFT, morning.maneuvers.first().type)
        assertEquals(1_760, morning.maneuvers.first().atM)
        assertEquals(ManeuverType.ARRIVE, morning.maneuvers.last().type)
        assertEquals(22_000, morning.maneuvers.last().atM)
        assertTrue(morning.maneuvers.last().descriptionKo.contains("판교테크노밸리"))
        assertEquals(CameraKind.SCHOOL_ZONE, morning.cameras.first().kind)
        assertEquals(CameraKind.SPEED_FIXED, morning.cameras.last().kind)
        val school = morning.zones.first { it.kind == SegmentEventKind.SCHOOL_ZONE }
        assertEquals(1_900, school.startM)
        assertEquals(30, morning.speedLimitAt(school.startM + 10.0))
        assertEquals(80, morning.speedLimitAt(15_000.0))
        assertEquals(CommuteMaps.SEG_BUNDANG_SUSEO, morning.legAt(15_000.0).segment.id)
        assertEquals(map.place(CommuteMaps.PLACE_OFFICE)!!.point, morning.locationAt(22_000.0))
    }

    @Test fun eveningIsTheSameRoadMirrored() {
        assertEquals(22_000, evening.totalM)
        assertTrue(evening.legs.all { it.reversed })
        assertEquals(CommuteMaps.NOMINAL_ROUTE_SEC, evening.remainingSec(0.0))
        // 판교역로 출근 우회전(600m) → 퇴근 좌회전(1200m)
        assertEquals(ManeuverType.LEFT, evening.maneuvers.first().type)
        assertEquals(1_200, evening.maneuvers.first().atM)
        // 판교IC 진출 → 진입(합류)
        assertTrue(evening.maneuvers.any { it.type == ManeuverType.ENTER })
        assertFalse(evening.maneuvers.any { it.type == ManeuverType.EXIT })
        // 도착은 경로 끝에 한 번
        assertEquals(1, evening.maneuvers.count { it.type == ManeuverType.ARRIVE })
        assertEquals(22_000, evening.maneuvers.last().atM)
        assertTrue(evening.maneuvers.last().descriptionKo.contains("잠실역"))
        // 어린이 보호구역 거울 위치: 퇴근 방향 구간 시작 22000-(1800+700)=19500, 구간 안 150..600
        val school = evening.zones.first { it.kind == SegmentEventKind.SCHOOL_ZONE }
        assertEquals(19_500 + 150, school.startM)
        assertEquals(19_500 + 600, school.endM)
        assertEquals(map.place(CommuteMaps.PLACE_OFFICE)!!.point, evening.locationAt(0.0))
        assertEquals(map.place(CommuteMaps.PLACE_HOME)!!.point, evening.locationAt(22_000.0))
    }

    @Test fun mirrorRules() {
        val e = SegmentEvent(SegmentEventKind.MANEUVER, atM = 100, maneuver = ManeuverType.LEFT, textKo = "좌회전")
        val m = RoutePlan.mirror(e, 1_000)!!
        assertEquals(900, m.atM)
        assertEquals(ManeuverType.RIGHT, m.maneuver)
        assertEquals("", m.textKo)
        assertEquals(ManeuverType.ENTER, RoutePlan.mirrorManeuver(ManeuverType.EXIT))
        assertEquals(ManeuverType.KEEP_LEFT, RoutePlan.mirrorManeuver(ManeuverType.KEEP_RIGHT))
        assertEquals(ManeuverType.MERGE, RoutePlan.mirrorManeuver(ManeuverType.MERGE))
        assertNull(RoutePlan.mirror(SegmentEvent(SegmentEventKind.MANEUVER, atM = 10, maneuver = ManeuverType.ARRIVE), 100))
        val zone = RoutePlan.mirror(SegmentEvent(SegmentEventKind.TUNNEL, atM = 100, lengthM = 1_300), 1_500)!!
        assertEquals(100, zone.atM)
        assertEquals(1_300, zone.lengthM)
    }

    @Test fun mapMatching() {
        val tancheon = map.segment(CommuteMaps.SEG_TANCHEON)!!
        val mid = LatLng((tancheon.start.lat + tancheon.end.lat) / 2, (tancheon.start.lng + tancheon.end.lng) / 2)
        assertEquals(CommuteMaps.SEG_TANCHEON, MapMatcher.match(map, mid)?.id)
        assertNull(MapMatcher.match(map, LatLng(37.300, 126.900)))
        assertEquals(CommuteMaps.PLACE_HOME, MapMatcher.nearestPlace(map, LatLng(37.5131, 127.1003))?.id)
        assertNull(MapMatcher.nearestPlace(map, LatLng(37.530, 127.100)))
        val d = MapMatcher.distanceM(map.place(CommuteMaps.PLACE_HOME)!!.point, map.place(CommuteMaps.PLACE_OFFICE)!!.point)
        assertTrue("잠실역–판교 직선 약 12.4km: $d", d in 12_000.0..13_000.0)
        val c = MapMatcher.coarse(LatLng(37.51234567, 127.10098765))
        assertEquals(37.5123, c.lat, 1e-9)
        assertEquals(127.101, c.lng, 1e-9)
        assertNotNull(RoutePlan.all(map)[CommuteMaps.ROUTE_EVENING])
    }
}
