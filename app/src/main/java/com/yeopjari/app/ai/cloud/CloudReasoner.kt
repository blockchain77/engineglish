package com.yeopjari.app.ai.cloud

import com.yeopjari.app.ai.context.DriveContext
import com.yeopjari.app.ai.nlu.Intent
import com.yeopjari.app.ai.nlu.NluResult
import com.yeopjari.app.ai.tools.ToolSpec
import com.yeopjari.app.core.model.AgentCard
import com.yeopjari.app.core.model.AgentTurn
import com.yeopjari.app.core.model.CheckItem
import com.yeopjari.app.core.model.PlaceKind
import com.yeopjari.app.core.model.PlanStop
import com.yeopjari.app.core.model.Routine
import com.yeopjari.app.core.model.Telltale
import com.yeopjari.app.core.model.ToolCall
import com.yeopjari.app.core.model.ToolStatus
import com.yeopjari.app.core.model.WarningLevel
import com.yeopjari.app.sim.Chargers
import kotlin.math.roundToInt

/** 클라우드로 전송되는 최소화된 컨텍스트 (PrivacyGuard 산출물) */
data class MinimizedContext(val sent: List<Pair<String, String>>, val withheld: List<String>)

data class ReasonerRequest(
    val utterance: String,
    val nlu: NluResult,
    val sent: MinimizedContext,
    val tools: List<ToolSpec>,
    val history: List<AgentTurn>,
)

data class ReasonerPlan(
    val reasoning: List<String>,
    val toolCalls: List<ToolCall>,
    val reply: String,
    val speak: String,
    val cards: List<AgentCard>,
    val latencyMs: Int,
)

/**
 * 클라우드 추론 LLM 인터페이스.
 *
 * 목표 구현: 옆자리 자체 추론 모델 서버 (차량 도메인 SFT + Tool-use 학습된 LLM)
 *   - 입력: 사용자 발화 + 최소화 컨텍스트 + 사용 가능한 Tool 스키마(JSON)
 *   - 출력: 추론 단계 요약, Tool 호출 계획, 음성용 짧은 답변, 화면용 카드
 *   - 향후 Pleos LLM SDK(Mode.SERVER) 공개 시 동일 인터페이스로 교체 가능
 * 현재 구현: [ScriptedReasoner] — 컨텍스트를 반영한 시나리오 응답 (AI 미탑재)
 */
interface CloudReasoner {
    val modelName: String
    suspend fun plan(request: ReasonerRequest, ctx: DriveContext): ReasonerPlan
}

class ScriptedReasoner : CloudReasoner {
    override val modelName = "Reasoner-LLM 자리표시자 (시나리오 응답)"

    override suspend fun plan(request: ReasonerRequest, ctx: DriveContext): ReasonerPlan = when (request.nlu.intent) {
        Intent.TRIP_PLAN -> tripPlan(ctx)
        Intent.DIAGNOSE -> diagnose(request.utterance, ctx)
        Intent.MANUAL_QA -> manual(request.utterance)
        Intent.ROUTINE_CREATE -> routine(request.utterance)
        Intent.BRIEFING -> briefing(ctx)
        Intent.KIDS_GAME -> kidsQuiz(ctx)
        Intent.DROWSY -> wakeTalk(ctx)
        else -> openTalk(request.utterance, ctx)
    }

    private fun tripPlan(ctx: DriveContext): ReasonerPlan {
        val v = ctx.vehicle
        val e = ctx.energy
        val arrivalNoStop = e.arrivalSoc?.roundToInt() ?: (v.socPercent - 56).roundToInt()
        val kids = ctx.profile.kids.joinToString(", ") { "${it.first}(${it.second})" }
        val stops = listOf(
            PlanStop("현재 위치 · 영동고속도로", PlaceKind.DESTINATION, ctx.clockLabel.substringAfter(" "), "SOC ${v.socPercent.roundToInt()}%"),
            PlanStop("횡성휴게소 E-pit", PlaceKind.CHARGER, "11:08", "350kW · 18분 충전 → 80% · 키즈 놀이공간 · 간식", 18, socOnArrival = 33),
            PlanStop("평창휴게소", PlaceKind.REST_AREA, "11:57", "선택 · 5분 스트레칭 (아이 멀미 대비)", 5),
            PlanStop("강릉 경포해변", PlaceKind.DESTINATION, "12:41", "점심: 초당순두부 거리 (도보 12분)", socOnArrival = 41),
        )
        return ReasonerPlan(
            reasoning = listOf(
                "도착 SOC 예측: 충전 없이 ${arrivalNoStop}% → 안전 여유(10%) 미달",
                "동승자: 성인 2 · 아동 2 ($kids) → 90분 내 휴식 필요",
                "경로상 충전소 3곳 비교: 출력·가용 대수·편의시설",
                "횡성 E-pit: 350kW 4/6 가용 · 키즈 공간 → 충전과 휴식 동시 해결",
                "점심 시간대(12시대) 도착 → 목적지 인근 식당 제안",
            ),
            toolCalls = listOf(
                ToolCall("ev_find_chargers", mapOf("min_kw" to "200"), ToolStatus.DONE, "3곳 탐색"),
                ToolCall("navi_add_waypoint", mapOf("place_id" to "hoengseong"), ToolStatus.PENDING_CONFIRM),
            ),
            reply = "충전 없이 가면 도착할 때 배터리가 ${arrivalNoStop.coerceAtLeast(0)}%라 빠듯해요. 횡성휴게소 E-pit에서 18분 충전하면서 아이들 간식·화장실을 해결하는 코스를 추천해요. 강릉에는 12시 41분, 배터리 41%로 도착해요.",
            speak = "횡성휴게소에서 18분 충전하면서 아이들 쉬는 시간을 갖는 코스를 추천해요. 경유지로 추가할까요?",
            cards = listOf(
                AgentCard.RoutePlan("강릉 가족여행 · 충전+휴식 통합 계획", stops, 149, 41, "총 2시간 29분 · 휴식 23분 포함"),
                AgentCard.Confirm("횡성휴게소 E-pit를 경유지로 추가할까요?", "경유지 추가", "add_waypoint:hoengseong"),
            ),
            latencyMs = 1840,
        )
    }

    private fun diagnose(utterance: String, ctx: DriveContext): ReasonerPlan {
        val v = ctx.vehicle
        val tpms = Telltale.TPMS in v.telltales || utterance.contains("타이어") || utterance.contains("공기압")
        return if (tpms) {
            val cold = v.outsideTempC < 3f
            ReasonerPlan(
                reasoning = listOf(
                    "경고등: TPMS 점등 (Safety · Telltales) · 주행 ${v.continuousDriveMin}분째",
                    "외기온 ${v.outsideTempC.roundToInt()}°C — 기온이 10°C 내려가면 공기압은 약 1~2psi 낮아짐",
                    if (cold) "출발 직후 점등 + 영하권 → 기온 영향 가능성 높음, 단 펑크 배제 불가" else "주행 중 점등 + 기온 정상 → 서행성 누출 가능성",
                    "매뉴얼 RAG: 권장 공기압은 운전석 도어 라벨 기준, 경고 시 감속 주행 권고",
                ),
                toolCalls = listOf(ToolCall("vehicle_health_check", emptyMap(), ToolStatus.DONE, "경고등·주행 이력 조회"), ToolCall("navi_add_waypoint", mapOf("place_id" to "tire"), ToolStatus.PENDING_CONFIRM)),
                reply = if (cold) "영하의 아침에는 타이어 공기가 수축해서 경고등이 켜지는 일이 흔해요. 다만 한쪽 타이어가 눈에 띄게 낮거나 주행 중 쏠림이 느껴지면 펑크일 수 있어요. 1.8km 앞 주유소에서 무료로 공기를 넣고, 다시 켜지면 점검을 받아 보세요."
                else "기온 영향으로 보기 어려워서 못 같은 이물질로 천천히 새는 펑크일 가능성이 있어요. 급가속·급제동을 피하고 가까운 공기 주입소에서 확인해 보세요.",
                speak = "기온이 크게 떨어져 공기압이 내려갔을 가능성이 높아요. 가까운 공기 주입소를 경유지로 넣을까요?",
                cards = listOf(
                    AgentCard.Diagnosis(if (cold) "저온에 의한 공기압 저하 가능성 높음" else "서행성 공기 누출 의심", WarningLevel.CAUTION,
                        "TPMS 경고등 점등 · 외기온 ${v.outsideTempC.roundToInt()}°C · 출발 ${v.continuousDriveMin}분 후 점등",
                        listOf("80km/h 이하로 부드럽게 주행", "공기 주입 후 경고등 소등 확인 (10분 주행)", "재점등 시 블루핸즈 판교점 점검 (예약 가능)")),
                    AgentCard.Confirm("무료 공기 주입 가능한 주유소(1.8km)를 경유지로 넣을까요?", "경유지 추가", "add_waypoint:tire"),
                ),
                latencyMs = 1620,
            )
        } else ReasonerPlan(
            reasoning = listOf("증상 키워드 추출: 소리/진동", "차량 상태 데이터 대조: 이상 신호 없음", "매뉴얼·정비 지식베이스 검색"),
            toolCalls = listOf(ToolCall("vehicle_health_check", emptyMap(), ToolStatus.DONE, "이상 없음")),
            reply = "지금 차량 데이터에는 이상 신호가 없어요. 저속에서 '끼익' 소리가 난다면 아침 습기로 브레이크 디스크에 생긴 얇은 녹이 원인일 때가 많아 몇 번 제동하면 사라져요. 계속되면 패드 마모일 수 있으니 점검을 권해요.",
            speak = "지금 차량 데이터에는 이상이 없어요. 소리가 계속되면 브레이크 점검을 권해요.",
            cards = listOf(AgentCard.Diagnosis("일시적 소음 가능성 높음", WarningLevel.INFO, "차량 신호 정상 · 습기/녹 가능성", listOf("몇 차례 부드럽게 제동", "1주 이상 지속 시 점검 예약"))),
            latencyMs = 1380,
        )
    }

    private fun manual(utterance: String): ReasonerPlan {
        val (title, answer) = when {
            utterance.contains("오토홀드") -> "오토 홀드" to "정차 후 브레이크에서 발을 떼도 차가 멈춰 있게 하는 기능이에요. 기어 옆 'AUTO HOLD' 버튼을 누르면 켜지고, 계기판에 흰색 표시가 뜬 뒤 정차하면 초록색으로 바뀌어요. 가속 페달을 밟으면 자동 해제돼요."
            utterance.contains("회생") -> "회생제동" to "감속할 때 모터가 발전기처럼 돌며 배터리를 충전하는 기능이에요. 운전대 뒤 왼쪽 패들을 당기면 회생 단계가 높아지고, 가장 높은 단계(i-PEDAL)에서는 가속 페달 하나로 정차까지 할 수 있어요. 정체 구간에서 효율이 특히 좋아요."
            utterance.contains("V2L", ignoreCase = true) -> "V2L (실외 전력 공급)" to "차 배터리로 외부 전자기기에 전기를 쓸 수 있는 기능이에요. 충전구에 V2L 커넥터를 꽂고 커넥터 버튼을 누르면 작동해요. 배터리 하한선을 설정해 두면 주행 가능 거리를 지킬 수 있어요."
            else -> "차량 기능 안내" to "말씀하신 기능을 매뉴얼에서 찾았어요. 주행 중에는 핵심만 음성으로 안내하고, 정차하면 단계별 화면으로 자세히 보여 드릴게요."
        }
        return ReasonerPlan(
            reasoning = listOf("질문 의도: 차량 기능 사용법", "차종 매뉴얼 임베딩 검색 (RAG) · 상위 3개 단락", "주행 중 → 음성 요약 우선"),
            toolCalls = emptyList(),
            reply = answer,
            speak = answer.substringBefore(".") + ".",
            cards = listOf(AgentCard.Briefing(listOf("기능" to title, "근거" to "차종 사용자 매뉴얼 (RAG 검색)", "표시" to "정차 시 단계별 안내 제공"))),
            latencyMs = 1210,
        )
    }

    private fun routine(utterance: String): ReasonerPlan {
        val hour = Regex("(\\d{1,2})시").find(utterance)?.groupValues?.get(1)
        val temp = Regex("(\\d{2})도").find(utterance)?.groupValues?.get(1)
        val triggers = buildList {
            if (utterance.contains("평일") || utterance.contains("매일")) add(if (utterance.contains("평일")) "평일" else "매일")
            if (hour != null) add("${hour}시 전후 출발")
            if (utterance.contains("충전")) add("충전 완료 시")
            if (utterance.contains("비")) add("강수 예보 있을 때")
            if (isEmpty()) add("D 기어 진입")
        }
        val actions = buildList {
            if (utterance.contains("회사")) add("회사로 길 안내")
            if (utterance.contains("집")) add("집으로 길 안내")
            if (temp != null) add("실내 ${temp}°C")
            if (utterance.contains("브리핑") || utterance.contains("일정")) add("브리핑 읽기")
            if (utterance.contains("음악") || utterance.contains("노래")) add("즐겨 듣는 음악 재생")
            if (isEmpty()) add("알림 보내기")
        }
        val r = Routine("ai_${System.currentTimeMillis()}", "AI가 만든 루틴", triggers, actions, enabled = true, createdByAi = true)
        return ReasonerPlan(
            reasoning = listOf("자연어 → 트리거/동작 구조화", "트리거 검증: 차량 신호로 감지 가능 (Gear · 시간 · 위치)", "동작 검증: Pleos SDK 지원 범위 내"),
            toolCalls = listOf(ToolCall("routine_create", mapOf("trigger" to triggers.joinToString(), "action" to actions.joinToString()), ToolStatus.PENDING_CONFIRM)),
            reply = "이렇게 루틴을 만들었어요. 조건: ${triggers.joinToString(" · ")} → 동작: ${actions.joinToString(" · ")}. 저장할까요?",
            speak = "루틴을 만들었어요. 저장할까요?",
            cards = listOf(AgentCard.RoutineDraft(r), AgentCard.Confirm("이 루틴을 저장할까요?", "루틴 저장", "save_routine")),
            latencyMs = 1450,
        )
    }

    private fun briefing(ctx: DriveContext): ReasonerPlan {
        val v = ctx.vehicle
        val r = ctx.route
        val lines = buildList {
            add("날씨" to "맑음 · 낮 최고 13°C · 퇴근길 강수 확률 10%")
            if (r.active) add("경로" to "${r.destination?.name ?: "목적지"}까지 ${r.remainingMin}분 · 평소 대비 +${r.trafficDelayMin}분")
            add("배터리" to "${v.socPercent.roundToInt()}% · 주행 가능 ${v.rangeKm}km · 오늘 동선 충분")
            add("일정" to "10:00 주간 회의 · 14:30 외부 미팅(판교역)")
            add("팁" to "회의 전 15분 여유 · 회사 주차장 B3 충전기 2대 비어 있어요")
        }
        return ReasonerPlan(
            reasoning = listOf("캘린더·날씨·교통·차량 상태 종합", "운전 중 → 30초 이내 음성 요약", "우선순위: 지연 여부 > 일정 > 배터리"),
            toolCalls = emptyList(),
            reply = "오늘 브리핑이에요. 회사까지 ${r.remainingMin}분, 첫 일정 10시 회의에는 15분 여유가 있어요. 배터리는 오늘 동선에 충분하고, 회사 B3 충전기 2대가 비어 있어요.",
            speak = "회사까지 ${r.remainingMin}분 걸려요. 열 시 회의까지 15분 여유가 있어요. 배터리는 충분해요.",
            cards = listOf(AgentCard.Briefing(lines)),
            latencyMs = 1320,
        )
    }

    private fun kidsQuiz(ctx: DriveContext): ReasonerPlan {
        val kid = ctx.profile.kids.firstOrNull()?.first ?: "친구"
        return ReasonerPlan(
            reasoning = listOf("동승 아동 연령(7·5세) 맞춤 난이도", "목적지(강릉 바다) 연계 주제 생성", "운전자 개입 없이 음성으로 진행"),
            toolCalls = listOf(ToolCall("kids_start_quiz", emptyMap(), ToolStatus.DONE, "퀴즈 시작")),
            reply = "${kid}야, 바다 동물 퀴즈 시작! 다리가 여덟 개이고 먹물을 쏘는 바다 동물은 누구일까?",
            speak = "${kid}야, 바다 동물 퀴즈 시작! 다리가 여덟 개이고 먹물을 쏘는 바다 동물은 누구일까?",
            cards = listOf(AgentCard.Game("강릉 가는 길 · 바다 동물 퀴즈 1/5", "다리가 8개, 먹물을 쏘는 동물은?", listOf("문어", "오징어", "꽃게"))),
            latencyMs = 980,
        )
    }

    private fun wakeTalk(ctx: DriveContext): ReasonerPlan = ReasonerPlan(
        reasoning = listOf("졸음 위험 ${ctx.fatigue.score}점 (${ctx.fatigue.levelLabel})", "각성 유지 대화: 짧은 질문 · 운전자 관심사 기반", "다음 휴식 지점 병행 제안"),
        toolCalls = listOf(ToolCall("hvac_fresh_air", emptyMap(), ToolStatus.DONE, "외기 유입 · 풍량 5단"), ToolCall("hvac_set_temperature", mapOf("celsius" to "21"), ToolStatus.DONE, "21°C")),
        reply = "창문 대신 바깥 공기를 넣고 온도를 21도로 낮췄어요. 잠 깨기 퀴즈 하나! 지난 주말 강릉에서 아이들이 제일 좋아했던 음식은 뭐였죠? 대답하시는 동안 다음 졸음쉼터까지 4.2km 남았어요.",
        speak = "바깥 공기를 넣고 온도를 낮췄어요. 지난 주말 강릉에서 아이들이 제일 좋아했던 음식은 뭐였죠?",
        cards = listOf(AgentCard.Confirm("4.2km 앞 졸음쉼터에서 쉬어 갈까요?", "졸음쉼터 경유", "add_waypoint:shelter")),
        latencyMs = 1050,
    )

    private fun openTalk(utterance: String, ctx: DriveContext): ReasonerPlan = ReasonerPlan(
        reasoning = listOf("의도 불명확 → 대화형 응답", "현재 맥락(${ctx.vehicle.driveMode.label}) 반영", "후속 행동 제안"),
        toolCalls = emptyList(),
        reply = if (ctx.vehicle.driveMode.restricted)
            "운전 중이라 짧게 말씀드릴게요. '$utterance'에 대해 정차하시면 화면으로 자세히 정리해 드릴게요."
        else "좋아요. '$utterance'에 대해 지금 차 상태와 일정을 함께 고려해서 정리해 드릴게요. 원하시면 바로 실행할 수 있는 행동도 제안할게요.",
        speak = "정차하시면 자세히 정리해 드릴게요.",
        cards = listOf(AgentCard.VehicleCheck(listOf(
            CheckItem("주행 상태", ctx.vehicle.driveMode.label, true),
            CheckItem("배터리", "${ctx.vehicle.socPercent.roundToInt()}%", ctx.vehicle.socPercent > 20),
        ))),
        latencyMs = 1100,
    )

    companion object {
        val chargerOptions = AgentCard.ChargerOptions(Chargers.routeToGangneung, recommendedId = "hoengseong")
    }
}
