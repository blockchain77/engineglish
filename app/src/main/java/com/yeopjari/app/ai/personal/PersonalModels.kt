package com.yeopjari.app.ai.personal

import com.yeopjari.app.core.model.Place

/** 장소 종류(말로 부르는 범주) — "빵집 들렀다 가자"처럼 이름 대신 종류로 말할 때 쓴다 */
enum class Category(val label: String, val words: List<String>) {
    BAKERY("빵집", listOf("빵집", "베이커리", "제과점", "빵가게")),
    CAFE("카페", listOf("카페", "커피숍", "커피집", "커피")),
    MART("마트", listOf("마트", "대형마트", "장보러", "슈퍼")),
    DEPT("백화점", listOf("백화점", "아울렛")),
    KIDS("유치원", listOf("유치원", "어린이집")),
    HOSPITAL("병원", listOf("병원", "소아과", "소아청소년과")),
    PARK("공원", listOf("공원")),
    FAMILY("가족", emptyList()),
    WORK("회사", emptyList()),
    OTHER("기타", emptyList()),
}

/** 개인 장소 사전의 한 항목 — 차 안에만 저장된다 */
data class Poi(
    val place: Place,
    val brand: String? = null,
    val category: Category = Category.OTHER,
    val aliases: List<String> = emptyList(),
) {
    /** 발음 비교에 쓰는 이름들 (정식 이름 + 별칭 + 브랜드) */
    val names: List<String> get() = listOf(place.name) + aliases + listOfNotNull(brand)
    /** 음성 안내용 짧은 이름 — 괄호 설명 제거 */
    val spoken: String get() = place.name.substringBefore(" (").trim()
}

/** 방문 기록 한 건 — 차 안 암호화 저장소에만 존재하고 서버로 보내지 않는다 */
data class Visit(val placeId: String, val epochDay: Int, val dow: Int, val minute: Int)

enum class StopMode { DESTINATION, WAYPOINT }

/** 명령 해석 결과 중 '장소' 부분 */
data class PlaceQuery(
    /** 장소를 가리키는 말 (예: "서심다응", "스타벅스", "엄마 집") */
    val text: String,
    val mode: StopMode = StopMode.DESTINATION,
    /** 이름 대신 종류로 말한 경우 (예: "빵집") */
    val category: Category? = null,
    /** "늘 가던", "자주 가는" 같은 습관 표현이 있었는지 */
    val habit: Boolean = false,
    /** "근처", "가까운" 표현이 있었는지 */
    val nearby: Boolean = false,
    val raw: String = text,
)

/** 지금 상황 — 날짜·요일·시각·위치·안내 중인 목적지 */
data class Situation(
    val epochDay: Int,
    /** 1=월 … 7=일 */
    val dow: Int,
    /** 0~1439 */
    val minute: Int,
    val lat: Double?,
    val lng: Double?,
    val destLat: Double? = null,
    val destLng: Double? = null,
)

/** 순위 모델 입력(특징) — 모두 0~1 */
data class Features(
    /** 들린 말과 이름의 발음 유사도 (종류로 말했으면 종류 일치 여부) */
    val phon: Double,
    /** 90일 방문 빈도 */
    val freq: Double,
    /** 지금 요일·시간대에 가던 곳인지 */
    val time: Double,
    /** 현재 위치 또는 경로상 접근성 */
    val dist: Double,
    /** 최근 방문 */
    val recent: Double,
    /** 사용자가 직접 표현한 선호 — 같은 말로 고른 기록에서 이 곳의 몫(평일·주말 구분) · 즐겨찾기 0.7 */
    val pref: Double,
) {
    fun vec(): DoubleArray = doubleArrayOf(phon, freq, time, dist, recent, pref)
}

data class Candidate(
    val poi: Poi,
    val f: Features,
    val score: Double,
    val prob: Double,
    /** HUD·화면에 붙는 짧은 근거 (예: "지난번 선택", "가는 길", "주로 토요일 오전", "자주 감 5회", "2.1km") */
    val tags: List<String>,
    /** 순위 계산에 쓴 거리 — 안내 중이면 경로까지의 거리(우회 거리), 아니면 직선거리 */
    val km: Double?,
    val onRoute: Boolean,
    /** 현재 위치에서의 직선거리 — 화면·HUD에 보여 주는 값 */
    val kmDirect: Double? = null,
    /** 단골인지 (90일 4회 이상 방문 또는 이 말로 고른 적 있음) — "늘 가던 곳" 표현은 이때만 */
    val habitual: Boolean = false,
)

/** 확신도에 따른 행동 */
enum class Tier(val label: String) {
    AUTO("바로 실행"),
    CONFIRM("한 번 확인"),
    CHOOSE("선택지 제시"),
    NONE("찾지 못함"),
}

data class Resolution(
    val query: PlaceQuery,
    /** 확률 높은 순 */
    val candidates: List<Candidate>,
    val tier: Tier,
    /** 발음 보정이 일어났다면 보정된 이름 (예: "성심당") */
    val correctedTo: String?,
    /** 전에 배운 표현으로 알아들었는지 */
    val aliasHit: Boolean = false,
    /** 이 결과를 계산할 때 쓴 가중치 6개 (학습 뒤에도 그때의 확률을 그대로 설명하기 위해) */
    val weights: List<Double> = emptyList(),
    /** 결과 식별자 — 대화 카드·HUD 버튼이 지금 진행 중인 되묻기의 것인지 확인할 때 */
    val id: Long = nextResolutionId(),
    /** 이 결과를 계산한 상황 (선택 기억을 평일·주말로 나눠 남길 때 요일을 본다) */
    val situation: Situation? = null,
) {
    val top: Candidate? get() = candidates.firstOrNull()

    companion object {
        private var seq = 0L
        @Synchronized fun nextResolutionId(): Long = ++seq
    }
}

data class LearnOutcome(
    val before: DoubleArray,
    val after: DoubleArray,
    /** 새로 기억한 표현: 들린 말 → 알아들은 이름 */
    val alias: Pair<String, String>?,
    val chosen: Candidate,
)
