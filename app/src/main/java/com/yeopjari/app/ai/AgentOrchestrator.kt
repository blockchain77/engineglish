package com.yeopjari.app.ai

import com.yeopjari.app.ai.cloud.CloudReasoner
import com.yeopjari.app.ai.cloud.MinimizedContext
import com.yeopjari.app.ai.cloud.ReasonerRequest
import com.yeopjari.app.ai.context.DriveContext
import com.yeopjari.app.ai.nlu.Intent
import com.yeopjari.app.ai.nlu.NluResult
import com.yeopjari.app.ai.nlu.OnDeviceIntentEngine
import com.yeopjari.app.ai.nlu.RouteHint
import com.yeopjari.app.ai.tools.ToolRegistry
import com.yeopjari.app.core.model.AgentCard
import com.yeopjari.app.core.model.AgentPhase
import com.yeopjari.app.core.model.AgentTurn
import com.yeopjari.app.core.model.AiTier
import com.yeopjari.app.core.model.CheckItem
import com.yeopjari.app.core.model.Speaker
import com.yeopjari.app.core.model.Telltale
import com.yeopjari.app.core.model.ToolCall
import com.yeopjari.app.core.model.ToolStatus
import com.yeopjari.app.sim.Chargers
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** 응답 결과 + 음성으로 읽을 문장 */
data class AgentResult(val turn: AgentTurn, val speak: String)

/**
 * 하이브리드 AI 오케스트레이터.
 *
 *  발화(STT SDK) ─▶ ① 안전 정책(규칙) ─▶ ② 온디바이스 의도 분석(SLM)
 *                    │                      │
 *                    │       단순·고신뢰 ◀───┴───▶ 복합·저신뢰
 *                    ▼              ▼                    ▼
 *                 차단/안내   ③ 엣지 즉시 실행     ④ 개인정보 최소화 → 클라우드 추론 LLM
 *                                    │                    │ (Tool 호출 계획)
 *                                    └──── ⑤ Tool 실행 (Pleos SDK) ◀┘
 *                                                   ▼
 *                                    ⑥ 응답 카드 + 음성(TTS SDK)
 */
class AgentOrchestrator(
    private val nlu: OnDeviceIntentEngine,
    private val reasoner: CloudReasoner,
    private val tools: ToolRegistry,
) {
    private var seq = 1000L
    private fun nextId() = ++seq

    suspend fun handle(
        utterance: String,
        ctx: DriveContext,
        history: List<AgentTurn>,
        cloudEnabled: Boolean,
        onPhase: (AgentPhase, String?) -> Unit,
    ): AgentResult {
        onPhase(AgentPhase.ROUTING, "온디바이스 의도 분석")
        val parsed = nlu.parse(utterance, ctx)
        delay(parsed.latencyMs.toLong())

        // ① 안전 정책: 주행 중 운전자 대상 영상 차단 등
        if (parsed.intent == Intent.VIDEO_REQUEST && ctx.vehicle.driveMode.restricted) {
            val text = "주행 중에는 운전자 화면에서 영상을 재생할 수 없어요. 대신 오디오로 들을 수 있는 콘텐츠를 찾아 드릴까요? 정차하면 바로 이어서 볼 수 있게 준비해 둘게요."
            return AgentResult(
                AgentTurn(nextId(), Speaker.AGENT, text, AiTier.RULE, parsed.latencyMs,
                    reasoning = listOf("주행 상태: ${ctx.vehicle.speedKph.roundToInt()} km/h · 기어 ${ctx.vehicle.gear.label}", "정책: 운전자 주의분산 콘텐츠 차단"),
                    toolCalls = listOf(ToolCall("media_play_video", emptyMap(), ToolStatus.BLOCKED, "주행 중 차단"))),
                "주행 중에는 영상을 재생할 수 없어요. 정차하면 이어서 볼 수 있게 준비해 둘게요.",
            )
        }

        val goCloud = parsed.intent.tier == RouteHint.CLOUD || parsed.complex || parsed.confidence < 0.6f
        if (!goCloud) return edge(parsed, ctx)

        if (!cloudEnabled || !ctx.flags.online) {
            onPhase(AgentPhase.THINKING, "오프라인 · 온디바이스 대체 응답")
            delay(220)
            val fallback = edgeFallback(parsed, ctx)
            if (fallback != null) return fallback
            val text = "지금은 네트워크가 연결되지 않아 간단한 차량 명령만 처리할 수 있어요. 연결되면 이어서 자세히 계획해 드릴게요."
            return AgentResult(AgentTurn(nextId(), Speaker.AGENT, text, AiTier.ON_DEVICE, 220, reasoning = listOf("클라우드 미연결 → 온디바이스 대체")), text)
        }

        // ④ 클라우드 추론
        val minimized = PrivacyGuard.minimize(ctx)
        onPhase(AgentPhase.THINKING, "클라우드 추론 · 컨텍스트 ${minimized.sent.size}개 항목 전송")
        val plan = reasoner.plan(ReasonerRequest(utterance, parsed, minimized, tools.specs, history.takeLast(6)), ctx)
        // 추론 단계를 순차적으로 노출 (스트리밍 UX)
        plan.reasoning.forEach { step ->
            onPhase(AgentPhase.THINKING, step)
            delay((plan.latencyMs / (plan.reasoning.size + 1)).toLong())
        }
        // ⑤ 확인이 필요 없는 툴 즉시 실행
        val executed = plan.toolCalls.map { call ->
            if (call.status == ToolStatus.DONE && call.resultSummary.isNotEmpty() && call.name in setOf("hvac_fresh_air", "hvac_set_temperature", "kids_start_quiz")) {
                tools.execute(call.name, call.args)
            }
            call
        }
        return AgentResult(
            AgentTurn(
                nextId(), Speaker.AGENT, plan.reply, AiTier.CLOUD, plan.latencyMs + parsed.latencyMs,
                reasoning = listOf("의도: ${parsed.intent.label} (신뢰도 ${(parsed.confidence * 100).roundToInt()}%${if (parsed.complex) " · 복합 요청" else ""}) → 클라우드 라우팅") + plan.reasoning,
                toolCalls = executed, cards = plan.cards,
                privacyNote = "전송: " + minimized.sent.joinToString(" · ") { it.first } + "  |  미전송: " + minimized.withheld.joinToString(" · "),
            ),
            plan.speak,
        )
    }

    /** ③ 엣지 즉시 처리 — 단순 명령은 네트워크 없이 300ms 이내 */
    private suspend fun edge(p: NluResult, ctx: DriveContext): AgentResult {
        val v = ctx.vehicle
        val r = ctx.route
        val calls = mutableListOf<ToolCall>()
        val cards = mutableListOf<AgentCard>()
        suspend fun run(name: String, args: Map<String, String> = emptyMap()): String {
            val res = tools.execute(name, args)
            calls += ToolCall(name, args, if (res.isSuccess) ToolStatus.DONE else ToolStatus.BLOCKED, res.getOrElse { it.message ?: "실패" })
            return res.getOrDefault("")
        }

        val text: String = when (p.intent) {
            Intent.HVAC_SET_TEMP -> { val t = p.slots["temp"] ?: "22"; run("hvac_set_temperature", mapOf("celsius" to t)); "실내 온도를 ${t}도로 맞출게요." }
            Intent.HVAC_WARMER -> { val t = (v.hvac.setTempC + 1.5f); run("hvac_set_temperature", mapOf("celsius" to t.toString())); "조금 더 따뜻하게, ${"%.1f".format(t)}도로 올렸어요." }
            Intent.HVAC_COOLER -> { val t = (v.hvac.setTempC - 1.5f); run("hvac_set_temperature", mapOf("celsius" to t.toString())); "시원하게 ${"%.1f".format(t)}도로 낮췄어요." }
            Intent.HVAC_FRESH_AIR -> { run("hvac_fresh_air"); "바깥 공기를 들여올게요. 풍량도 조금 높였어요." }
            Intent.NAV_HOME -> { run("navi_set_destination", mapOf("place_id" to "home")); "${ctx.profile.homeLabel}으로 안내를 시작할게요." }
            Intent.NAV_WORK -> { run("navi_set_destination", mapOf("place_id" to "work")); "${ctx.profile.workLabel}로 안내를 시작할게요." }
            Intent.ADD_REST_STOP -> {
                val place = if (r.isHighway) "hoengseong" else "cafe"
                calls += ToolCall("navi_add_waypoint", mapOf("place_id" to place), ToolStatus.PENDING_CONFIRM)
                cards += AgentCard.Confirm("다음 휴게소를 경유지로 추가할까요?", "경유지 추가", "add_waypoint:$place")
                "다음 휴게소까지 18분이에요. 경유지로 추가할까요?"
            }
            Intent.FIND_CHARGER -> {
                run("ev_find_chargers")
                cards += AgentCard.ChargerOptions(Chargers.routeToGangneung, "hoengseong")
                "경로상 급속 충전소 세 곳을 찾았어요. 횡성휴게소 E-pit가 가장 빠르고 지금 4대 비어 있어요."
            }
            Intent.CHARGE_STATUS -> {
                val arr = ctx.energy.arrivalSoc
                cards += AgentCard.VehicleCheck(listOf(
                    CheckItem("배터리", "${v.socPercent.roundToInt()}%", v.socPercent > 20),
                    CheckItem("주행 가능", "${v.rangeKm} km", v.rangeKm > 60),
                    CheckItem("도착 예상", arr?.let { "${it.roundToInt().coerceAtLeast(0)}%" } ?: "-", (arr ?: 50f) > 10f),
                ))
                "배터리 ${v.socPercent.roundToInt()}%, ${v.rangeKm}킬로미터 갈 수 있어요." + (arr?.let { " 목적지에는 ${it.roundToInt().coerceAtLeast(0)}%로 도착해요." } ?: "")
            }
            Intent.ETA_QUERY -> if (r.active) "${r.destination?.name}까지 ${r.remainingKm.roundToInt()}킬로미터, ${r.remainingMin}분 남았어요." else "지금은 안내 중인 목적지가 없어요."
            Intent.KIDS_ETA -> {
                val mins = r.remainingMin
                val episodes = (mins / 12).coerceAtLeast(1)
                "${ctx.profile.kids.first().first}야, 만화 ${episodes}편 보면 도착해! 지금 바다까지 ${(r.progress * 100).roundToInt()}퍼센트 왔어."
            }
            Intent.PET_MODE_ON -> {
                if (v.driveMode.restricted) "펫 케어 모드는 정차 후에 켤 수 있어요. 주차하면 바로 켜 드릴게요."
                else { run("care_pet_mode", mapOf("celsius" to "22")); "펫 케어 모드를 켰어요. 실내를 22도로 유지하고, 화면에 보호자 부재 안내를 띄울게요." }
            }
            Intent.PET_MODE_OFF -> { run("care_pet_mode_stop"); "펫 케어 모드를 종료했어요." }
            Intent.REST_MODE -> {
                if (v.driveMode.restricted) "쉼 모드는 정차 중에만 쓸 수 있어요. 가까운 휴게소를 찾아 드릴까요?"
                else { run("care_rest_mode", mapOf("minutes" to "15")); "15분 쉼 모드를 시작할게요. 끝나면 부드럽게 깨워 드릴게요." }
            }
            Intent.VEHICLE_CHECK -> {
                run("vehicle_health_check")
                val tpms = Telltale.TPMS in v.telltales
                cards += AgentCard.VehicleCheck(listOf(
                    CheckItem("타이어 (TPMS)", if (tpms) "경고등 점등" else "정상", !tpms),
                    CheckItem("고전압 배터리", "${v.socPercent.roundToInt()}% · ${v.rangeKm}km", v.socPercent > 15),
                    CheckItem("12V 배터리", if (Telltale.LOW_12V_BATTERY in v.telltales) "전압 저하" else "정상", Telltale.LOW_12V_BATTERY !in v.telltales),
                    CheckItem("워셔액", if (Telltale.LOW_WASHER_FLUID in v.telltales) "부족" else "충분", Telltale.LOW_WASHER_FLUID !in v.telltales),
                    CheckItem("도어/트렁크", if (v.doors.anyOpen) "열림 있음" else "모두 닫힘", !v.doors.anyOpen),
                ))
                if (tpms) "타이어 공기압 경고등이 켜져 있어요. 나머지는 정상이에요." else "차량 상태는 모두 정상이에요."
            }
            Intent.PARKING_MEMO -> {
                val note = p.slots["note"] ?: ""
                run("parking_save", mapOf("note" to note))
                "주차 위치를 기억했어요. '$note' — 돌아오실 때 다시 알려 드릴게요."
            }
            Intent.CHARGE_LIMIT -> {
                val pct = p.slots["percent"] ?: "90"
                run("ev_set_charge_limit", mapOf("percent" to pct))
                "충전 한도를 ${pct}%로 설정했어요." + if (pct.toIntOrNull() ?: 100 <= 90) " 아파트 지하주차장 기준도 지킬 수 있어요." else ""
            }
            Intent.DROWSY -> {
                run("hvac_fresh_air"); run("hvac_set_temperature", mapOf("celsius" to "20"))
                cards += AgentCard.Confirm("4.2km 앞 졸음쉼터에서 15분 쉬어 갈까요?", "졸음쉼터 경유", "add_waypoint:shelter")
                "바깥 공기를 넣고 20도로 낮췄어요. 4.2킬로미터 앞 졸음쉼터에서 잠깐 쉬어 가는 게 좋겠어요."
            }
            else -> "알겠어요."
        }
        return AgentResult(
            AgentTurn(nextId(), Speaker.AGENT, text, AiTier.ON_DEVICE, p.latencyMs,
                reasoning = listOf("의도: ${p.intent.label} · 신뢰도 ${(p.confidence * 100).roundToInt()}% → 엣지 처리 (네트워크 불필요)"),
                toolCalls = calls, cards = cards),
            text,
        )
    }

    private suspend fun edgeFallback(p: NluResult, ctx: DriveContext): AgentResult? = when (p.intent) {
        Intent.TRIP_PLAN, Intent.DROWSY -> edge(p.copy(intent = if (p.intent == Intent.DROWSY) Intent.DROWSY else Intent.FIND_CHARGER), ctx)
        Intent.DIAGNOSE -> edge(p.copy(intent = Intent.VEHICLE_CHECK), ctx)
        else -> null
    }
}

/**
 * 개인정보 최소화 — 클라우드로 보내기 전 컨텍스트를 추상화한다.
 * 음성 원본은 차량 밖으로 나가지 않으며(STT SDK 온보드 처리), 정밀 좌표는 구간 단위로 일반화한다.
 */
object PrivacyGuard {
    fun minimize(ctx: DriveContext): MinimizedContext {
        val v = ctx.vehicle
        val r = ctx.route
        val sent = buildList {
            add("주행 상태" to v.driveMode.label)
            add("배터리" to "${v.socPercent.roundToInt()}%")
            if (r.active) add("경로" to "${r.roadName} · 잔여 ${r.remainingKm.roundToInt()}km")
            add("동승 구성" to if (ctx.hasRearPassengers) "성인·아동" else "성인")
            add("시간대" to "${ctx.hour}시")
            if (ctx.fatigue.level > 0) add("졸음 위험" to ctx.fatigue.levelLabel)
        }
        return MinimizedContext(sent, listOf("음성 원본", "정밀 GPS", "실명·연락처", "차대번호"))
    }
}
