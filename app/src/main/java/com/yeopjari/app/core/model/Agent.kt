package com.yeopjari.app.core.model

/**
 * AI가 어느 계층에서 처리되었는지.
 *  - ON_DEVICE : 차량 내 경량(양자화) 모델 — 단순 명령/의도 분류/슬롯 추출, 오프라인 동작
 *  - CLOUD     : 옆자리 자체 추론 LLM 서버 — 다단계 계획, 다중 제약 추론, 요약
 *  - RULE      : 안전 규칙 엔진 (AI 판단 이전에 항상 우선)
 */
enum class AiTier(val label: String, val short: String) {
    ON_DEVICE("온디바이스 SLM", "온디바이스"),
    CLOUD("클라우드 추론 LLM", "클라우드 추론"),
    RULE("안전 규칙 엔진", "안전 규칙"),
}

enum class Speaker { USER, AGENT, SYSTEM }

data class ToolCall(
    val name: String,
    val args: Map<String, String> = emptyMap(),
    val status: ToolStatus = ToolStatus.DONE,
    val resultSummary: String = "",
)

enum class ToolStatus { PENDING_CONFIRM, RUNNING, DONE, BLOCKED }

/** 대화 응답에 함께 표시되는 리치 카드 */
sealed interface AgentCard {
    data class RoutePlan(
        val title: String,
        val stops: List<PlanStop>,
        val totalMin: Int,
        val arrivalSoc: Int?,
        val note: String = "",
    ) : AgentCard

    data class ChargerOptions(val chargers: List<Charger>, val recommendedId: String?) : AgentCard
    data class VehicleCheck(val items: List<CheckItem>) : AgentCard
    data class Diagnosis(val title: String, val severity: WarningLevel, val explanation: String, val actions: List<String>) : AgentCard
    data class RoutineDraft(val routine: Routine) : AgentCard
    data class Briefing(val lines: List<Pair<String, String>>) : AgentCard
    data class Confirm(val question: String, val confirmLabel: String, val actionId: String) : AgentCard
    data class Game(val title: String, val prompt: String, val choices: List<String>) : AgentCard

    /** 개인화 보정 결과 — 들린 말, 보정된 이름, 후보(최대 3곳)와 확률 */
    data class PlaceChoices(
        val heard: String,
        val corrected: String?,
        val options: List<PlaceOption>,
        /** "바로 실행" · "한 번 확인" · "선택지 제시" */
        val stageLabel: String,
        val waypoint: Boolean,
        /** 이 카드를 만든 보정 결과 id — 지금 진행 중인 되묻기의 카드만 누를 수 있게 */
        val resolutionId: Long = 0,
    ) : AgentCard
}

data class PlaceOption(val placeId: String, val name: String, val percent: Int, val tags: List<String>)

data class PlanStop(
    val label: String,
    val kind: PlaceKind,
    val etaClock: String,
    val detail: String = "",
    val durationMin: Int = 0,
    val socOnArrival: Int? = null,
)

data class CheckItem(val label: String, val value: String, val ok: Boolean)

data class AgentTurn(
    val id: Long,
    val speaker: Speaker,
    val text: String,
    val tier: AiTier? = null,
    val latencyMs: Int = 0,
    val reasoning: List<String> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    val cards: List<AgentCard> = emptyList(),
    val privacyNote: String? = null,
)

/** 오브(Orb) 애니메이션 상태 */
enum class AgentPhase { IDLE, LISTENING, ROUTING, THINKING, SPEAKING }
