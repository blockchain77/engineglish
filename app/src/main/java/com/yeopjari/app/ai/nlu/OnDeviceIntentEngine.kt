package com.yeopjari.app.ai.nlu

import com.yeopjari.app.ai.context.DriveContext

enum class Intent(val label: String, val tier: RouteHint) {
    HVAC_SET_TEMP("온도 설정", RouteHint.EDGE),
    HVAC_WARMER("따뜻하게", RouteHint.EDGE),
    HVAC_COOLER("시원하게", RouteHint.EDGE),
    HVAC_FRESH_AIR("환기", RouteHint.EDGE),
    NAV_HOME("집으로 안내", RouteHint.EDGE),
    NAV_WORK("회사로 안내", RouteHint.EDGE),
    ADD_REST_STOP("휴게소 경유", RouteHint.EDGE),
    FIND_CHARGER("충전소 찾기", RouteHint.EDGE),
    CHARGE_STATUS("배터리 상태", RouteHint.EDGE),
    ETA_QUERY("도착 시간", RouteHint.EDGE),
    KIDS_ETA("아이 질문: 언제 도착", RouteHint.EDGE),
    PET_MODE_ON("펫 케어 켜기", RouteHint.EDGE),
    PET_MODE_OFF("펫 케어 끄기", RouteHint.EDGE),
    REST_MODE("쉼 모드", RouteHint.EDGE),
    VEHICLE_CHECK("차량 점검", RouteHint.EDGE),
    DROWSY("졸음 호소", RouteHint.EDGE),
    VIDEO_REQUEST("영상 재생", RouteHint.EDGE),
    PARKING_MEMO("주차 위치 메모", RouteHint.EDGE),
    CHARGE_LIMIT("충전 한도 설정", RouteHint.EDGE),
    KIDS_GAME("아이 퀴즈", RouteHint.CLOUD),
    DIAGNOSE("증상 진단", RouteHint.CLOUD),
    MANUAL_QA("차량 기능 질문", RouteHint.CLOUD),
    ROUTINE_CREATE("루틴 만들기", RouteHint.CLOUD),
    BRIEFING("브리핑", RouteHint.CLOUD),
    TRIP_PLAN("여정 계획", RouteHint.CLOUD),
    OPEN_TALK("자유 대화", RouteHint.CLOUD),
}

enum class RouteHint { EDGE, CLOUD }

data class NluResult(
    val intent: Intent,
    val slots: Map<String, String>,
    val confidence: Float,
    /** 다중 제약/다단계 추론이 필요한 발화인가 → 클라우드 라우팅 근거 */
    val complex: Boolean,
    val latencyMs: Int,
)

/**
 * 온디바이스 의도 분석 엔진 인터페이스.
 *
 * 목표 구현: 차량 NPU에서 동작하는 한국어 차량 도메인 특화 SLM
 *   - 1~3B 파라미터, 4bit 양자화(GGUF/QNN), 의도 분류 + 슬롯 추출 + 라우팅 판단을 단일 프롬프트로 수행
 *   - 응답 목표 < 300ms, 네트워크 없이 동작
 * 현재 구현: [KeywordIntentEngine] — 동일 인터페이스의 규칙 기반 대체물 (AI 미탑재)
 */
interface OnDeviceIntentEngine {
    val modelName: String
    suspend fun parse(utterance: String, ctx: DriveContext): NluResult
}

class KeywordIntentEngine : OnDeviceIntentEngine {
    override val modelName = "Edge-SLM 자리표시자 (규칙 기반)"

    private val tempRegex = Regex("(\\d{2}(?:\\.5)?)\\s*도")
    private val connectors = listOf("그리고", "중간에", "하면서", "고려", "까지", "들러", "랑 같이", "이랑", "말고", "대신", "근데")

    override suspend fun parse(utterance: String, ctx: DriveContext): NluResult {
        val u = utterance.replace(" ", "")
        val slots = mutableMapOf<String, String>()
        fun has(vararg k: String) = k.any { u.contains(it.replace(" ", "")) }

        val pct = Regex("(\\d{2,3})\\s*(%|퍼센트|프로)").find(utterance)?.groupValues?.get(1)
        val intent = when {
            has("영상", "유튜브", "넷플릭스", "동영상", "드라마틀어") -> Intent.VIDEO_REQUEST
            has("층") && has("기둥", "구역", "번", "주차") && !has("어디") -> { slots["note"] = utterance.trim(); Intent.PARKING_MEMO }
            has("충전") && has("한도", "까지만", "제한") && pct != null -> { slots["percent"] = pct; Intent.CHARGE_LIMIT }
            has("여행", "코스", "일정짜", "계획세워", "계획짜", "들러서", "들렀다") -> Intent.TRIP_PLAN
            has("루틴", "할때마다", "자동으로", "매일아침", "매일저녁") -> Intent.ROUTINE_CREATE
            has("경고등", "소리가", "이상해", "떨려", "진동", "냄새", "공기압", "타이어") -> Intent.DIAGNOSE
            has("어떻게켜", "어떻게해", "방법", "어떻게써", "뭐야?", "기능이뭐", "오토홀드", "회생제동", "v2l", "V2L") -> Intent.MANUAL_QA
            has("퀴즈", "게임", "끝말잇기", "심심") -> Intent.KIDS_GAME
            has("아직멀었", "다왔어", "언제와", "언제도착해?") && ctx.hasRearPassengers -> Intent.KIDS_ETA
            has("졸려", "졸음", "피곤", "잠와", "잠이와") -> Intent.DROWSY
            has("펫모드꺼", "펫케어꺼", "펫케어종료") -> Intent.PET_MODE_OFF
            has("펫", "강아지", "반려견", "고양이", ctx.flags.petName) -> Intent.PET_MODE_ON
            has("쉬고싶", "낮잠", "쉼모드", "명상", "휴식모드") -> Intent.REST_MODE
            has("브리핑", "오늘일정", "오늘어때", "오늘뭐") -> Intent.BRIEFING
            tempRegex.containsMatchIn(utterance) -> { slots["temp"] = tempRegex.find(utterance)!!.groupValues[1]; Intent.HVAC_SET_TEMP }
            has("추워", "춥다", "따뜻하게", "히터") -> Intent.HVAC_WARMER
            has("더워", "덥다", "시원하게", "에어컨") -> Intent.HVAC_COOLER
            has("환기", "답답", "외기") -> Intent.HVAC_FRESH_AIR
            has("집으로", "집에가", "집까지") -> Intent.NAV_HOME
            has("회사로", "출근", "회사까지") -> Intent.NAV_WORK
            has("휴게소", "쉬었다", "쉬어가", "화장실") -> Intent.ADD_REST_STOP
            has("충전소") -> Intent.FIND_CHARGER
            has("배터리", "충전얼마", "주행가능", "몇키로갈") -> Intent.CHARGE_STATUS
            has("언제도착", "얼마나남", "도착시간", "몇분남") -> Intent.ETA_QUERY
            has("점검", "상태어때", "차괜찮", "차상태") -> Intent.VEHICLE_CHECK
            else -> Intent.OPEN_TALK
        }
        val complex = utterance.length > 26 && connectors.any { utterance.contains(it) }
        val confidence = if (intent == Intent.OPEN_TALK) 0.41f else if (complex) 0.72f else 0.94f
        return NluResult(intent, slots, confidence, complex, latencyMs = 90 + utterance.length * 3)
    }
}
