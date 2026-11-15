package com.yeopjari.app.core.model

enum class PlaceKind(val label: String) {
    HOME("집"), WORK("회사"), CHARGER("충전소"), REST_AREA("휴게소"), DROWSY_SHELTER("졸음쉼터"),
    CAFE("카페"), RESTAURANT("식당"), PARK("공원"), SERVICE("서비스센터"), KIDS("키즈 시설"),
    PET("반려동물 동반"), DESTINATION("목적지"), SCHOOL("학교"), HOSPITAL("병원")
}

data class Place(
    val id: String,
    val name: String,
    val kind: PlaceKind,
    val lat: Double,
    val lng: Double,
    val address: String = "",
    val tags: List<String> = emptyList(),
)

/** 충전소 메타데이터 (환경부 충전소 Open API / CPO 연동 가정) */
data class Charger(
    val place: Place,
    val maxKw: Int,
    val available: Int,
    val total: Int,
    val pricePerKwh: Int,
    val operator: String,
    val distanceFromRouteKm: Float,
    val kmFromStart: Float,
    val amenities: List<String> = emptyList(),
    /** 마지막 충전 세션 후 경과 분 — 오래 비어 있으면 고장 가능성 (커뮤니티 노하우를 지표화) */
    val lastSessionMinAgo: Int = 10,
    /** 최근 30일 고장 제보 수 */
    val faultReports30d: Int = 0,
    /** 사용자 보유 멤버십 기준 실결제 단가 */
    val memberPricePerKwh: Int = pricePerKwh,
    /** 도착 시 예상 대기 대수 (시간대·연휴 혼잡 예측) */
    val expectedQueue: Int = 0,
) {
    /** 신뢰도 0~100: 최근 세션·고장 제보·가용 비율을 결합 */
    val reliability: Int
        get() {
            val recency = when {
                lastSessionMinAgo < 30 -> 45
                lastSessionMinAgo < 180 -> 35
                lastSessionMinAgo < 1440 -> 18
                else -> 4
            }
            val faults = (30 - faultReports30d * 9).coerceAtLeast(0)
            val avail = if (total == 0) 0 else (25 * available / total)
            return (recency + faults + avail).coerceIn(0, 100)
        }
}

/** NaviHelper SDK로부터 받은 경로 안내 상태 */
data class RouteState(
    val active: Boolean = false,
    val destination: Place? = null,
    val waypoints: List<Place> = emptyList(),
    val totalKm: Float = 0f,
    val remainingKm: Float = 0f,
    val remainingMin: Int = 0,
    val trafficDelayMin: Int = 0,
    val nextGuidance: String = "",
    /** 다음 회전까지 거리 (NaviHelper TBTInfo.distance) — 운전 부하 추정에 사용 */
    val nextTurnMeters: Int? = null,
    val roadName: String = "",
    val isHighway: Boolean = false,
    /** NaviHelper TripInfo.estimatedSocAtDest — 내비 엔진이 계산한 도착 SOC */
    val naviEstimatedSoc: Int? = null,
    /** NaviHelper onCurrentRoadSpeedLimit — HUD 제한속도 표시 */
    val speedLimitKph: Int? = null,
) {
    val progress: Float get() = if (totalKm <= 0f) 0f else (1f - remainingKm / totalKm).coerceIn(0f, 1f)
}

data class GeoPoint(val lat: Double, val lng: Double, val headingDeg: Float = 0f)
