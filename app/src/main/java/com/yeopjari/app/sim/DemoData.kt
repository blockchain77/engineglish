package com.yeopjari.app.sim

import com.yeopjari.app.core.model.Charger
import com.yeopjari.app.core.model.Place
import com.yeopjari.app.core.model.PlaceKind
import com.yeopjari.app.core.model.Routine
import com.yeopjari.app.core.model.TripRecord

/**
 * 데모·시연용 가상 데이터.
 * 실제 서비스에서는 NaviHelper SDK(POI/경로), 환경부 전기차 충전소 API, 한국도로공사 휴게소·졸음쉼터 API로 대체된다.
 * (좌표·가용 대수·가격은 시연을 위한 예시 값)
 */
object Places {
    val home = Place("home", "집", PlaceKind.HOME, 37.5133, 127.1001, "서울 송파구 잠실동")
    val work = Place("work", "회사 · 판교 테크노밸리", PlaceKind.WORK, 37.4020, 127.1086, "경기 성남시 분당구 삼평동")
    val gyeongpo = Place("gyeongpo", "강릉 경포해변", PlaceKind.DESTINATION, 37.8055, 128.9087, "강원 강릉시 강문동")
    val hoengseong = Place("hoengseong", "횡성휴게소 (강릉방향)", PlaceKind.REST_AREA, 37.4870, 127.9850, "영동고속도로", listOf("고속충전", "키즈 놀이공간", "식당가"))
    val pyeongchang = Place("pyeongchang", "평창휴게소 (강릉방향)", PlaceKind.REST_AREA, 37.5636, 128.4200, "영동고속도로", listOf("고속충전", "산책로"))
    val deokpyeong = Place("deokpyeong", "덕평자연휴게소", PlaceKind.REST_AREA, 37.2390, 127.4050, "영동고속도로", listOf("반려견 놀이터", "산책로", "고속충전"))
    val yeoju = Place("yeoju", "여주휴게소 (강릉방향)", PlaceKind.REST_AREA, 37.3110, 127.6170, "영동고속도로", listOf("고속충전", "식당가"))
    val manghyang = Place("manghyang", "망향휴게소 (부산방향)", PlaceKind.REST_AREA, 36.8900, 127.1670, "경부고속도로", listOf("식당가", "편의점"))
    val shelter = Place("shelter", "다음 졸음쉼터", PlaceKind.DROWSY_SHELTER, 36.9500, 127.1500, "경부고속도로 부산방향", listOf("화장실", "그늘막"))
    val serviceCenter = Place("svc", "현대 블루핸즈 판교점", PlaceKind.SERVICE, 37.3990, 127.1110, "경기 성남시 분당구", listOf("타이어 점검", "예약 가능"))
    val tireShop = Place("tire", "가까운 타이어 공기 주입 가능 주유소", PlaceKind.SERVICE, 37.4050, 127.1150, "1.8km", listOf("무료 공기 주입"))
    val cafeNearCharger = Place("cafe", "충전소 옆 베이커리 카페", PlaceKind.CAFE, 37.4871, 127.9852, "도보 2분")

    val workGarage = Place("garage", "회사 지하주차장", PlaceKind.WORK, 37.4021, 127.1087, "판교 사옥 B1~B4")

    val all = listOf(home, work, gyeongpo, hoengseong, pyeongchang, deokpyeong, yeoju, manghyang, shelter, serviceCenter, tireShop, cafeNearCharger, workGarage)
    /** 시연 장소 + 개인 장소 사전(가상 운전자)에서 찾는다 */
    fun byId(id: String): Place? = all.firstOrNull { it.id == id } ?: com.yeopjari.app.ai.personal.PersonaFactory.poiById(id)?.place
}

object Chargers {
    val routeToGangneung = listOf(
        Charger(
            Places.hoengseong.copy(name = "E-pit 횡성휴게소 (강릉방향)", kind = PlaceKind.CHARGER), 350, 4, 6, 347, "E-pit",
            0.0f, 118f, listOf("키즈 놀이공간", "식당가", "화장실"),
            lastSessionMinAgo = 6, faultReports30d = 0, memberPricePerKwh = 309, expectedQueue = 0,
        ),
        Charger(
            Places.yeoju.copy(name = "여주휴게소 (강릉방향) 급속", kind = PlaceKind.CHARGER), 200, 1, 4, 324, "환경부",
            0.0f, 74f, listOf("식당가"),
            lastSessionMinAgo = 22, faultReports30d = 1, memberPricePerKwh = 324, expectedQueue = 2,
        ),
        Charger(
            Places.pyeongchang.copy(name = "평창휴게소 (강릉방향) 급속", kind = PlaceKind.CHARGER), 100, 2, 2, 324, "환경부",
            0.0f, 165f, listOf("산책로"),
            lastSessionMinAgo = 2_880, faultReports30d = 3, memberPricePerKwh = 324, expectedQueue = 0,
        ),
    )
}

object DemoHistory {
    val trips = listOf(
        TripRecord("t1", "판교 출근", "오늘 08:31", 18.4f, 46, 5.6f, 94, "정체 구간에서 회생제동을 잘 활용했어요. 출발 전 공조 예열로 주행 중 소모 전력을 7% 아꼈어요.", listOf("출퇴근", "루틴 실행")),
        TripRecord("t2", "강릉 가족여행", "11월 9일", 236.2f, 214, 4.9f, 91, "횡성휴게소에서 18분 충전하며 아이들 간식 시간을 함께 가졌어요. 졸음 위험 1회 감지 후 휴식으로 해소.", listOf("가족", "충전 1회", "휴게 2회")),
        TripRecord("t3", "덕평 반려견 나들이", "11월 2일", 61.0f, 72, 5.4f, 97, "펫 케어 모드로 18분 동안 실내 22°C를 유지했어요.", listOf("반려견", "펫 케어")),
        TripRecord("t4", "판교 퇴근", "11월 1일", 19.1f, 58, 5.8f, 88, "급제동 2회가 있었어요. 분당수서로 진입 구간에서 차간 거리를 조금 더 두면 좋아요.", listOf("출퇴근")),
    )

    val routines = listOf(
        Routine("r1", "평일 출근", listOf("평일 07:30~09:00", "D 기어 진입"), listOf("회사로 길 안내", "실내 21°C", "출근 브리핑 읽기"), true, false, 14),
        Routine("r2", "충전 완료 알림", listOf("충전 SOC 80% 도달"), listOf("음성 알림", "다음 목적지 안내 준비"), true, false, 6),
        Routine("r3", "비 오는 날 퇴근", listOf("평일 18:00 이후", "강수 예보"), listOf("집까지 예상 지연 안내", "제습 모드"), true, true, 3),
        Routine("r4", "아이 하원", listOf("평일 16:20", "어린이집 반경 500m"), listOf("뒷좌석 온도 23°C", "키즈 콘텐츠 준비"), false, true, 0),
    )
}
