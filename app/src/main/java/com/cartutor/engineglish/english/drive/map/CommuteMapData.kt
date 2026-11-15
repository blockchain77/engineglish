package com.cartutor.engineglish.english.drive

import com.cartutor.engineglish.english.core.CommuteMap
import com.cartutor.engineglish.english.core.CommutePlace
import com.cartutor.engineglish.english.core.CommuteRoute
import com.cartutor.engineglish.english.core.DestinationCategory
import com.cartutor.engineglish.english.core.LatLng
import com.cartutor.engineglish.english.core.ManeuverType
import com.cartutor.engineglish.english.core.RoadKind
import com.cartutor.engineglish.english.core.RouteSegment
import com.cartutor.engineglish.english.core.SegmentEvent
import com.cartutor.engineglish.english.core.SegmentEventKind

/**
 * 가상 출퇴근 지도 · 잠실 ↔ 판교 (명세 §4.7) — 예전 개인 장소·방문 기록·시나리오를 모두 지우고 새로 만든 지도.
 *
 * - 장소는 공공 랜드마크(역·업무지구·공항) 근처의 '가상' 장소뿐이고, 좌표는 소수점 3자리(약 100m)로 거칠게 둔다.
 * - 실제 주소·이름·개인 방문 기록은 없다. 학습자 페르소나도 이름 없는 가상 인물이다.
 * - 구간 id 는 화면·테스트가 쓰는 키라서 바꾸지 않는다. 출근·퇴근은 같은 구간을 반대로 달린다
 *   (구간 이벤트는 출근 방향 기준, 퇴근 방향은 [RoutePlan] 이 거울처럼 뒤집는다).
 * - 출근 22.0km · 명목 32분.
 */
object CommuteMaps {
    const val MAP_ID = "map.virtual.jamsil_pangyo"
    const val MAP_VERSION = 1

    const val PLACE_HOME = "place.home"
    const val PLACE_OFFICE = "place.office"
    const val PLACE_CHARGER = "place.charger"
    const val PLACE_AIRPORT = "place.airport"

    const val ROUTE_MORNING = "route.morning"
    const val ROUTE_EVENING = "route.evening"

    /** 출퇴근 명목 소요 시간 (32분) — 시뮬레이터 남은 시간의 기준 */
    const val NOMINAL_ROUTE_SEC = 1_920

    // 구간 id (불변)
    const val SEG_HOME_STREET = "seg.home_street"
    const val SEG_JAMSIL_CROSSROAD = "seg.jamsil_crossroad"
    const val SEG_JAMSIL_SCHOOL = "seg.jamsil_school"
    const val SEG_SONGPA_DAERO = "seg.songpa_daero"
    const val SEG_TANCHEON = "seg.tancheon"
    const val SEG_CITY_TUNNEL = "seg.city_tunnel"
    const val SEG_BUNDANG_SUSEO = "seg.bundang_suseo"
    const val SEG_PANGYO_JAM = "seg.pangyo_jam"
    const val SEG_PANGYO_IC = "seg.pangyo_ic"
    const val SEG_PANGYO_STATION_ROAD = "seg.pangyo_station_road"

    val default: CommuteMap = build()

    private fun p(lat: Double, lng: Double) = LatLng(lat, lng)

    private fun build(): CommuteMap {
        val places = listOf(
            CommutePlace(PLACE_HOME, "집 (잠실역 인근)", DestinationCategory.HOME, p(37.513, 127.100)),
            CommutePlace(PLACE_OFFICE, "회사 (판교테크노밸리)", DestinationCategory.OFFICE, p(37.401, 127.108)),
            CommutePlace(PLACE_CHARGER, "탄천 공영 충전소 (가상)", DestinationCategory.CHARGER, p(37.466, 127.116)),
            CommutePlace(PLACE_AIRPORT, "김포공항", DestinationCategory.AIRPORT, p(37.559, 126.802)),
        )
        val segments = listOf(
            RouteSegment(
                SEG_HOME_STREET, "집 앞 이면도로", RoadKind.LOCAL,
                p(37.513, 127.100), p(37.510, 127.104), lengthM = 800, speedLimitKph = 30, typicalSpeedKph = 25,
            ),
            RouteSegment(
                SEG_JAMSIL_CROSSROAD, "잠실역 사거리", RoadKind.CITY,
                p(37.510, 127.104), p(37.504, 127.107), lengthM = 1_000, speedLimitKph = 50, typicalSpeedKph = 40,
                events = listOf(
                    SegmentEvent(SegmentEventKind.MANEUVER, atM = 960, maneuver = ManeuverType.LEFT, textKo = "잠실역 사거리에서 좌회전"),
                ),
            ),
            RouteSegment(
                SEG_JAMSIL_SCHOOL, "잠실 어린이 보호구역", RoadKind.SCHOOL_ZONE,
                p(37.504, 127.107), p(37.498, 127.109), lengthM = 700, speedLimitKph = 30, typicalSpeedKph = 28,
                events = listOf(
                    SegmentEvent(SegmentEventKind.SCHOOL_ZONE, atM = 100, lengthM = 450, textKo = "초등학교 앞 어린이 보호구역", limitKph = 30),
                    SegmentEvent(SegmentEventKind.SPEED_CAMERA, atM = 180, textKo = "어린이 보호구역 단속 카메라", limitKph = 30),
                ),
            ),
            RouteSegment(
                SEG_SONGPA_DAERO, "송파대로", RoadKind.CITY,
                p(37.498, 127.109), p(37.480, 127.118), lengthM = 2_600, speedLimitKph = 60, typicalSpeedKph = 55,
                events = listOf(
                    SegmentEvent(SegmentEventKind.SPEED_CAMERA, atM = 1_600, textKo = "과속 단속 카메라", limitKph = 60),
                ),
            ),
            RouteSegment(
                SEG_TANCHEON, "탄천 강변길", RoadKind.RIVERSIDE,
                p(37.480, 127.118), p(37.452, 127.112), lengthM = 3_800, speedLimitKph = 70, typicalSpeedKph = 65,
            ),
            RouteSegment(
                SEG_CITY_TUNNEL, "도심 터널 구간", RoadKind.TUNNEL,
                p(37.452, 127.112), p(37.440, 127.110), lengthM = 1_500, speedLimitKph = 70, typicalSpeedKph = 65,
                events = listOf(
                    SegmentEvent(SegmentEventKind.TUNNEL, atM = 100, lengthM = 1_300, textKo = "터널 1.3km"),
                ),
            ),
            // 고속 크루즈 구간 — 3km 넘게 이벤트 없음
            RouteSegment(
                SEG_BUNDANG_SUSEO, "분당수서로 고속 구간", RoadKind.HIGHWAY,
                p(37.440, 127.110), p(37.420, 127.100), lengthM = 6_200, speedLimitKph = 80, typicalSpeedKph = 80,
            ),
            RouteSegment(
                SEG_PANGYO_JAM, "판교 진입 정체 구간", RoadKind.JAM,
                p(37.420, 127.100), p(37.412, 127.098), lengthM = 2_400, speedLimitKph = 60, typicalSpeedKph = 40,
                events = listOf(
                    SegmentEvent(SegmentEventKind.JAM, atM = 300, lengthM = 500, textKo = "판교 진입 상습 정체"),
                ),
            ),
            RouteSegment(
                SEG_PANGYO_IC, "판교IC 진출로", RoadKind.EXPRESSWAY_EXIT,
                p(37.412, 127.098), p(37.405, 127.101), lengthM = 1_200, speedLimitKph = 50, typicalSpeedKph = 45,
                events = listOf(
                    SegmentEvent(SegmentEventKind.MANEUVER, atM = 150, maneuver = ManeuverType.EXIT, textKo = "판교IC로 나가요"),
                ),
            ),
            RouteSegment(
                SEG_PANGYO_STATION_ROAD, "판교역로", RoadKind.CITY,
                p(37.405, 127.101), p(37.401, 127.108), lengthM = 1_800, speedLimitKph = 50, typicalSpeedKph = 40,
                events = listOf(
                    SegmentEvent(SegmentEventKind.MANEUVER, atM = 600, maneuver = ManeuverType.RIGHT, textKo = "판교역로에서 우회전"),
                ),
            ),
        )
        val morning = segments.map { it.id }
        val routes = listOf(
            CommuteRoute(ROUTE_MORNING, PLACE_HOME, PLACE_OFFICE, morning),
            CommuteRoute(ROUTE_EVENING, PLACE_OFFICE, PLACE_HOME, morning.reversed()),
        )
        return CommuteMap(
            id = MAP_ID,
            version = MAP_VERSION,
            nameKo = "가상 출퇴근 지도 · 잠실 ↔ 판교",
            places = places,
            segments = segments,
            routes = routes,
        )
    }
}
