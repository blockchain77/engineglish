package com.yeopjari.app.ai.personal

import com.yeopjari.app.core.model.Place
import com.yeopjari.app.core.model.PlaceKind
import com.yeopjari.app.sim.Places
import java.time.LocalDate
import kotlin.random.Random

/**
 * 가상 운전자 '서준'(잠실 거주 · 판교 출근 · 아이 둘 · 격주 토요일 대전 본가 방문)의
 * 개인 장소 사전과 90일 방문 기록. 시연과 정확도 평가에 쓰는 합성 데이터다.
 *
 * - 좌표는 근사치이며 실제 상호의 영업 정보와 무관하다.
 * - 실차에서는 이 자리를 NaviHelper 목적지 이력 · 주차(P) 위치 · FusedLocation 체류 기록이 채운다.
 */
object PersonaFactory {
    /** 기준일: 2026-11-14 (토요일) — 시연 시나리오와 평가가 같은 날짜를 쓴다 */
    val BASE_DATE: LocalDate = LocalDate.of(2026, 11, 14)
    val BASE_DAY: Int = BASE_DATE.toEpochDay().toInt()

    private fun p(id: String, name: String, kind: PlaceKind, lat: Double, lng: Double, addr: String) =
        Place(id, name, kind, lat, lng, addr)

    val pois: List<Poi> = listOf(
        Poi(Places.home, null, Category.FAMILY, listOf("우리 집")),
        Poi(Places.work, null, Category.WORK, listOf("회사", "사무실")),
        Poi(p("kinder", "해솔유치원", PlaceKind.KIDS, 37.5152, 127.1046, "서울 송파구 · 가상"), null, Category.KIDS, listOf("도윤이 유치원")),
        Poi(p("sb_pangyo", "스타벅스 판교테크노밸리점", PlaceKind.CAFE, 37.4013, 127.1081, "성남시 분당구 삼평동"), "스타벅스", Category.CAFE),
        Poi(p("sb_jamsil", "스타벅스 잠실새내역점", PlaceKind.CAFE, 37.5113, 127.0862, "서울 송파구 잠실동"), "스타벅스", Category.CAFE),
        Poi(p("sb_gangnam", "스타벅스 강남R점", PlaceKind.CAFE, 37.4985, 127.0281, "서울 강남구 역삼동"), "스타벅스", Category.CAFE),
        Poi(p("ssd_station", "성심당 대전역점", PlaceKind.CAFE, 36.3315, 127.4339, "대전 동구 정동"), "성심당", Category.BAKERY),
        Poi(p("ssd_main", "성심당 본점", PlaceKind.CAFE, 36.3276, 127.4274, "대전 중구 은행동"), "성심당", Category.BAKERY),
        Poi(p("ssd_dcc", "성심당 DCC점", PlaceKind.CAFE, 36.3757, 127.3922, "대전 유성구 도룡동"), "성심당", Category.BAKERY, listOf("성심당 디씨씨점")),
        Poi(p("church_dh", "대흥동 성당", PlaceKind.DESTINATION, 36.3254, 127.4262, "대전 중구 대흥동"), null, Category.OTHER),
        Poi(p("parents", "대전 본가 (부모님 댁)", PlaceKind.HOME, 36.3510, 127.3790, "대전 서구 둔산동"), null, Category.FAMILY, listOf("엄마 집", "본가", "부모님 댁", "할머니 댁")),
        Poi(p("pb_lake", "파리바게뜨 잠실레이크점", PlaceKind.CAFE, 37.5087, 127.1011, "서울 송파구 잠실동"), "파리바게뜨", Category.BAKERY),
        Poi(p("hd_pangyo", "현대백화점 판교점", PlaceKind.DESTINATION, 37.3926, 127.1121, "성남시 분당구 백현동"), "현대백화점", Category.DEPT),
        Poi(p("hd_coex", "현대백화점 무역센터점", PlaceKind.DESTINATION, 37.5083, 127.0596, "서울 강남구 삼성동"), "현대백화점", Category.DEPT),
        Poi(p("hd_outlet", "현대프리미엄아울렛 스페이스원", PlaceKind.DESTINATION, 37.6152, 127.1590, "경기 남양주시"), "현대프리미엄아울렛", Category.DEPT),
        Poi(p("starfield", "스타필드 하남", PlaceKind.DESTINATION, 37.5455, 127.2237, "경기 하남시"), "스타필드", Category.DEPT),
        Poi(p("lotte_mart", "롯데마트 제타플렉스 잠실점", PlaceKind.DESTINATION, 37.5121, 127.0987, "서울 송파구 잠실동"), "롯데마트", Category.MART),
        Poi(p("homeplus", "홈플러스 잠실점", PlaceKind.DESTINATION, 37.5076, 127.0838, "서울 송파구 잠실동"), "홈플러스", Category.MART),
        Poi(p("pedi", "튼튼소아청소년과", PlaceKind.HOSPITAL, 37.5141, 127.1023, "서울 송파구 · 가상"), null, Category.HOSPITAL, listOf("소아과", "애들 병원")),
        Poi(p("asan", "서울아산병원", PlaceKind.HOSPITAL, 37.5267, 127.1084, "서울 송파구 풍납동"), null, Category.HOSPITAL),
        Poi(p("olympic", "올림픽공원", PlaceKind.PARK, 37.5206, 127.1214, "서울 송파구 방이동"), null, Category.PARK),
    )

    fun poiById(id: String): Poi? = pois.firstOrNull { it.place.id == id }

    /**
     * 기준일 이전 [days]일 동안의 방문 기록을 만든다 (같은 seed → 같은 기록).
     * 생활 패턴:
     *  - 평일: 08:20 유치원 → 08:48 스타벅스 판교(60%) → 회사, 저녁 가끔 파리바게뜨·마트
     *  - 격주 토요일: 10:35 성심당 대전역점 → 11:30 대전 본가 (8주 전 한 번은 성심당 본점)
     *  - 나머지 토요일: 스타벅스 잠실새내역점 · 올림픽공원 / 일요일: 홈플러스 · 스타벅스 잠실
     */
    fun visits(baseDay: Int = BASE_DAY, days: Int = 90, seed: Int = 7): List<Visit> {
        val r = Random(seed)
        val out = ArrayList<Visit>(260)
        fun add(id: String, day: Int, minute: Int) {
            val dow = LocalDate.ofEpochDay(day.toLong()).dayOfWeek.value
            out += Visit(id, day, dow, (minute + r.nextInt(-12, 13)).coerceIn(0, 1439))
        }
        for (d in 1..days) {
            val day = baseDay - d
            when (LocalDate.ofEpochDay(day.toLong()).dayOfWeek.value) {
                in 1..5 -> {
                    if (r.nextFloat() < 0.85f) add("kinder", day, 8 * 60 + 20)
                    if (r.nextFloat() < 0.60f) add("sb_pangyo", day, 8 * 60 + 48)
                    add("work", day, 9 * 60 + 5)
                    if (r.nextFloat() < 0.10f) add("hd_pangyo", day, 12 * 60 + 30)
                    if (r.nextFloat() < 0.22f) add("pb_lake", day, 18 * 60 + 50)
                    if (r.nextFloat() < 0.08f) add("lotte_mart", day, 19 * 60 + 20)
                    add("home", day, 19 * 60 + 40)
                }
                6 -> {
                    if ((d / 7) % 2 == 0) { // 격주 토요일: 대전 본가
                        if (d == 56) add("ssd_main", day, 11 * 60) else add("ssd_station", day, 10 * 60 + 35)
                        add("parents", day, 11 * 60 + 30)
                    } else {
                        if (r.nextFloat() < 0.70f) add("sb_jamsil", day, 11 * 60)
                        if (r.nextFloat() < 0.60f) add("olympic", day, 15 * 60)
                    }
                }
                7 -> {
                    if (r.nextFloat() < 0.35f) add("sb_jamsil", day, 11 * 60 + 30)
                    if (r.nextFloat() < 0.45f) add("homeplus", day, 16 * 60)
                }
            }
            when (d) {
                12, 33, 61 -> add("pedi", day, 17 * 60 + 10)      // 월요일 소아과
                26, 54 -> add("sb_gangnam", day, 19 * 60)          // 월요일 저녁 미팅
                27, 48 -> add("hd_coex", day, 14 * 60)             // 일요일
                41 -> add("hd_outlet", day, 14 * 60 + 30)          // 일요일
            }
        }
        return out
    }
}
