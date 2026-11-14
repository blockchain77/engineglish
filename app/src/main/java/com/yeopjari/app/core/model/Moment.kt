package com.yeopjari.app.core.model

/**
 * 모먼트(Moment) — 컨텍스트 엔진이 차량 상태·경로·시간·사용자 패턴을 종합해
 * "지금 이 순간 필요한 것"을 선제적으로 제안하는 단위.
 */
enum class MomentType(val label: String) {
    COMMUTE_BRIEFING("출근 브리핑"),
    CHARGE_NEEDED("충전 필요"),
    CHARGING_SESSION("충전 세션"),
    FATIGUE("졸음 위험"),
    FAMILY_BREAK("가족 휴식"),
    PET_CARE("펫 케어"),
    VEHICLE_ISSUE("차량 점검"),
    TRAFFIC("교통 변화"),
    REST("쉼"),
    ROUTINE("루틴"),
    PARKING("주차 위치"),
    REAR_SEAT("뒷좌석 확인"),
    WEATHER("날씨"),
}

enum class Urgency(val weight: Int) { LOW(1), NORMAL(2), HIGH(3), CRITICAL(4) }

data class MomentAction(
    val id: String,
    val label: String,
    val primary: Boolean = false,
)

data class Moment(
    val id: String,
    val type: MomentType,
    val urgency: Urgency,
    val title: String,
    val body: String,
    val actions: List<MomentAction> = emptyList(),
    val speak: String? = null,
    val tier: AiTier = AiTier.ON_DEVICE,
    val metric: String? = null,
    val metricLabel: String? = null,
    /** 운전 부하가 높을 때 미뤄질 수 있는가 */
    val deferrable: Boolean = true,
)

/** 루틴 — "조건(트리거) → 동작" 자동화. 자연어로 생성 가능 */
data class Routine(
    val id: String,
    val name: String,
    val triggers: List<String>,
    val actions: List<String>,
    val enabled: Boolean = true,
    val createdByAi: Boolean = false,
    val runsThisMonth: Int = 0,
)

data class TripRecord(
    val id: String,
    val title: String,
    val dateLabel: String,
    val km: Float,
    val minutes: Int,
    val efficiency: Float,
    val safetyScore: Int,
    val aiSummary: String,
    val tags: List<String> = emptyList(),
)
