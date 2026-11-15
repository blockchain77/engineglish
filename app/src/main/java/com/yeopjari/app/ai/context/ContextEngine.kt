package com.yeopjari.app.ai.context

import com.yeopjari.app.ai.personal.Hangul
import com.yeopjari.app.core.model.AiTier
import com.yeopjari.app.core.model.DriveMode
import com.yeopjari.app.core.model.Gear
import com.yeopjari.app.core.model.Moment
import com.yeopjari.app.core.model.MomentAction
import com.yeopjari.app.core.model.MomentType
import com.yeopjari.app.core.model.PlaceKind
import com.yeopjari.app.core.model.Telltale
import com.yeopjari.app.core.model.Urgency
import kotlin.math.roundToInt

/** 컨텍스트 엔진이 참고하는 앱 내부 상태 */
data class AppSignals(
    val petModeActive: Boolean = false,
    val parkingSaved: Boolean = false,
)

/**
 * 컨텍스트 엔진 — 차량 이벤트(기어·도어·SOC·충전·경고등·위치 신호)로 "지금 필요한 것"을 먼저 찾는다.
 * 사용자가 말하기 전에 움직이는 것이 옆자리의 핵심 (음성 비서는 쓰다 말지만, 이벤트는 매일 발생한다).
 *
 * 현재 구현: 설명 가능한 규칙 기반 트리거 (AI 미탑재 상태의 대체 구현)
 * 목표 구현: 트리거 후보를 온디바이스 SLM 이 사용자 이력·선호로 재순위화하고 문구를 개인화
 */
object ContextEngine {

    fun evaluate(ctx: DriveContext, app: AppSignals): List<Moment> {
        val v = ctx.vehicle
        val r = ctx.route
        val out = mutableListOf<Moment>()

        // 1) 뒷좌석 확인 — 안전 최우선 (규칙 엔진, 출발 시 뒷문 개폐 이력 기반)
        if (ctx.flags.rearOpenedAtStart && v.gear == Gear.P && v.doors.driver && !v.doors.rearOpen) {
            // 이름 뒤 조사는 받침에 맞춘다: 하율 → '하율이가', 아이 → '아이가'
            val kid = Hangul.nameSubject(ctx.profile.kids.firstOrNull()?.first ?: "아이")
            out += Moment(
                "rear", MomentType.REAR_SEAT, Urgency.CRITICAL,
                "뒷좌석에 $kid 있지 않나요?",
                "출발할 때 뒷문이 열렸지만 도착 후에는 열리지 않았어요. 내리기 전에 뒷좌석을 꼭 확인해 주세요.",
                listOf(MomentAction("rear_ok", "확인했어요", true), MomentAction("rear_horn", "계속 알려줘")),
                speak = "잠깐만요! 뒷좌석에 $kid 있는지 확인해 주세요.",
                tier = AiTier.RULE, deferrable = false,
            )
        }

        // 2) 펫 케어 — 반려동물 동승 + 정차 + 극한 외기온
        if (ctx.flags.petOnBoard && v.gear == Gear.P && !app.petModeActive && (v.outsideTempC > 25f || v.outsideTempC < 5f)) {
            val pet = Hangul.nameStem(ctx.flags.petName) // 콩이 → 콩이, 초롱 → 초롱이
            out += Moment(
                "pet", MomentType.PET_CARE, if (v.doors.driver) Urgency.CRITICAL else Urgency.HIGH,
                "${pet}와 잠시 내리시나요?",
                "바깥이 ${v.outsideTempC.roundToInt()}°C예요. 펫 케어 모드를 켜면 실내를 22°C로 유지하고, 화면에 '반려견이 안전하게 있어요' 안내를 띄워요. 배터리 20% 아래로는 내려가지 않게 지킬게요.",
                listOf(MomentAction("pet_on", "펫 케어 켜기", true), MomentAction("pet_no", "함께 내려요")),
                speak = "${pet}를 차에 두고 내리시면 펫 케어 모드를 켜 드릴까요?",
                tier = AiTier.ON_DEVICE, metric = "${v.outsideTempC.roundToInt()}°C", metricLabel = "외기온", deferrable = false,
            )
        }

        // 3) 졸음 위험 — 단계별 케어 (환기 → 대화 → 휴식 경유)
        if (v.driveMode == DriveMode.DRIVING && ctx.fatigue.level >= 1) {
            val f = ctx.fatigue
            val (title, body, actions) = when (f.level) {
                1 -> Triple("조금 피곤해지실 시간이에요", "연속 ${v.continuousDriveMin}분째 운전 중이에요. 바깥 공기를 넣고 온도를 살짝 낮출까요?",
                    listOf(MomentAction("fresh_air", "환기 + 시원하게", true), MomentAction("later", "괜찮아요")))
                2 -> Triple("졸음 위험이 높아지고 있어요", "${f.factors.take(2).joinToString(" · ") { it.label }}. 잠깐 대화하며 깨어 있을까요? 다음 쉼터도 준비해 둘게요.",
                    listOf(MomentAction("wake_talk", "대화로 깨우기", true), MomentAction("rest_stop", "쉼터 경유"), MomentAction("fresh_air", "환기")))
                else -> Triple("지금은 쉬어 가는 게 안전해요", "4.2km 앞 졸음쉼터에서 15분만 쉬어 가요. 쉼 모드로 시원하게 깨워 드릴게요.",
                    listOf(MomentAction("rest_stop", "졸음쉼터 경유", true), MomentAction("wake_talk", "대화로 깨우기")))
            }
            out += Moment(
                "fatigue${f.level}", MomentType.FATIGUE, if (f.level >= 3) Urgency.CRITICAL else if (f.level == 2) Urgency.HIGH else Urgency.NORMAL,
                title, body, actions,
                speak = if (f.level >= 2) title + ". " + body.substringBefore(".") + "." else null,
                tier = AiTier.ON_DEVICE, metric = "${f.score}", metricLabel = "졸음 위험", deferrable = f.level < 3,
            )
        }

        // 4) 충전 필요 — 도착 SOC 예측 (자체 모델 + NaviHelper TripInfo 교차검증)
        val e = ctx.energy
        if (r.active && e.arrivalSoc != null && e.needsCharge && !v.isCharging && r.waypoints.none { it.kind == PlaceKind.CHARGER || it.kind == PlaceKind.REST_AREA }) {
            val arrival = e.arrivalSoc.roundToInt()
            val kids = ctx.hasRearPassengers
            out += Moment(
                "charge", MomentType.CHARGE_NEEDED, if (arrival < 5) Urgency.HIGH else Urgency.NORMAL,
                if (arrival <= 0) "이대로는 목적지까지 못 가요" else "도착하면 배터리가 ${arrival}%예요",
                if (kids) "118km 앞 횡성휴게소 E-pit(350kW, 신뢰도 높음)에서 18분 충전하면 아이들 간식·화장실 시간과 딱 맞아요. 예비 충전소도 함께 잡아 둘게요."
                else "118km 앞 횡성휴게소 E-pit(350kW)에서 18분 충전하면 여유 있게 도착해요. 예비 충전소도 함께 잡아 둘게요.",
                listOf(MomentAction("charge_plan", "충전+휴식 계획", true), MomentAction("charge_options", "충전소 비교")),
                speak = "지금 배터리로는 도착할 때 ${arrival.coerceAtLeast(0)}퍼센트 남아요. 횡성휴게소에서 충전하고 가는 걸 추천해요.",
                tier = AiTier.CLOUD, metric = "${arrival.coerceAtLeast(0)}%", metricLabel = "도착 예상 SOC",
            )
        }

        // 5) 충전 세션 + 급속충전 1시간 과태료 타이머
        if (v.isCharging) {
            val remainPct = (v.chargeLimitPercent - v.socPercent).coerceAtLeast(0f)
            val minutes = ((remainPct / 100f * v.batteryKwh) / (v.chargePowerKw.coerceAtLeast(30f)) * 60f).roundToInt()
            val elapsed = v.chargeStartedSec?.let { (((ctx.clockSec - it) + 86400f) % 86400f / 60f).roundToInt() } ?: 0
            val fineLeft = 60 - elapsed
            out += Moment(
                "charging", MomentType.CHARGING_SESSION, if (fineLeft in 0..10) Urgency.HIGH else Urgency.NORMAL,
                if (remainPct <= 0.5f) "충전 완료! 출발 준비됐어요" else "${minutes}분 뒤 ${v.chargeLimitPercent}% 완료",
                buildString {
                    append(if (fineLeft > 0) "급속충전 과태료 기준(1시간)까지 ${fineLeft}분 남았어요. " else "급속충전 1시간이 지났어요. 차를 옮겨 주세요. ")
                    append(if (ctx.hasRearPassengers) "기다리는 동안 아이들과 '바다 동물 퀴즈'를 해 보세요." else "기다리는 동안 쉼 모드로 10분 쉬어 보세요.")
                },
                listOf(MomentAction("rest_mode", "쉼 모드", true), MomentAction("kids_game", "퀴즈 시작")),
                speak = if (fineLeft in 1..10) "급속충전 시작 후 1시간이 다 되어 가요. ${fineLeft}분 안에 차를 옮겨 주세요." else null,
                tier = AiTier.ON_DEVICE, metric = "${v.socPercent.roundToInt()}%", metricLabel = "${v.chargePowerKw.roundToInt()} kW",
            )
        }

        // 6) 가족 휴식
        if (ctx.hasRearPassengers && v.driveMode == DriveMode.DRIVING && v.continuousDriveMin >= 80 && r.remainingKm > 30f && out.none { it.type == MomentType.CHARGE_NEEDED }) {
            out += Moment(
                "family", MomentType.FAMILY_BREAK, Urgency.NORMAL,
                "아이들이 지루해할 시간이에요",
                "출발 ${v.continuousDriveMin}분째. 다음 휴게소에는 키즈 놀이공간이 있어요. 15분 쉬어 갈까요?",
                listOf(MomentAction("rest_stop_family", "휴게소 들르기", true), MomentAction("kids_game", "차에서 퀴즈")),
                tier = AiTier.ON_DEVICE,
            )
        }

        // 7) 차량 이상 — TPMS 경고등 (Safety.getTelltalesInfo)
        if (Telltale.TPMS in v.telltales) {
            val cold = v.outsideTempC < 3f
            out += Moment(
                "tpms", MomentType.VEHICLE_ISSUE, Urgency.HIGH,
                "타이어 공기압 경고등이 켜졌어요",
                if (cold) "기온이 ${v.outsideTempC.roundToInt()}°C로 크게 떨어진 아침엔 공기압이 함께 내려가 경고가 뜨는 경우가 많아요. 다만 주행 중 갑자기 켜졌다면 펑크일 수 있어요."
                else "주행 중 켜진 경고라 서서히 새는 펑크 가능성이 있어요. 급제동을 피하고 가까운 곳에서 점검해 주세요.",
                listOf(MomentAction("diagnose_tire", "AI 진단 보기", true), MomentAction("tire_shop", "공기 주입소 경유")),
                speak = "타이어 공기압 경고등이 켜졌어요. 원인을 함께 살펴볼게요.",
                tier = AiTier.CLOUD, metric = "TPMS", metricLabel = "경고등", deferrable = true,
            )
        }

        // 8) 주차 위치 기억 — P 기어 + 위치 신호 상실(지하주차장)
        if (v.gear == Gear.P && !v.gpsGood && !app.parkingSaved) {
            out += Moment(
                "parking", MomentType.PARKING, Urgency.NORMAL,
                "지하주차장이네요. 주차 위치를 기억할까요?",
                "GPS가 끊겨 지도 위치가 부정확해요. '지하 3층 C-12'처럼 말해 주시면 층·기둥을 메모해 두고, 휴대폰으로도 보내 드릴게요.",
                listOf(MomentAction("parking_voice", "말로 메모하기", true), MomentAction("parking_skip", "괜찮아요")),
                speak = "주차 위치를 기억해 둘까요? 층과 기둥 번호를 말씀해 주세요.",
                tier = AiTier.ON_DEVICE,
            )
        }

        // 9) 교통 변화
        if (r.active && r.trafficDelayMin >= 10 && !r.isHighway) {
            out += Moment(
                "traffic", MomentType.TRAFFIC, Urgency.NORMAL,
                "앞쪽 정체가 ${r.trafficDelayMin}분 늘었어요",
                "탄천변 사고로 막히고 있어요. 분당수서로로 우회하면 6분 빨라요. 10:00 주간 회의에는 늦지 않아요.",
                listOf(MomentAction("reroute", "우회 경로로", true), MomentAction("keep", "그대로 가기")),
                speak = "앞쪽에 사고로 정체가 늘었어요. 우회하면 6분 빨라요.",
                tier = AiTier.CLOUD, metric = "+${r.trafficDelayMin}분",
            )
        }

        // 10) 출근 브리핑
        if (ctx.hour in 7..9 && r.active && r.destination?.id == "work") {
            out += Moment(
                "commute", MomentType.COMMUTE_BRIEFING, Urgency.LOW,
                "좋은 아침이에요, ${ctx.profile.name}님",
                "회사까지 ${r.remainingMin}분 · 평소보다 ${r.trafficDelayMin}분 더 걸려요. 첫 일정은 10:00 주간 회의. 배터리 ${v.socPercent.roundToInt()}%로 퇴근까지 충분해요.",
                listOf(MomentAction("briefing", "브리핑 듣기", true)),
                speak = "좋은 아침이에요. 회사까지 ${r.remainingMin}분 걸려요. 첫 일정은 열 시 주간 회의예요.",
                tier = AiTier.CLOUD, metric = "${r.remainingMin}분", metricLabel = "도착까지",
            )
        }

        // 11) 출근 전 예열 (P · 추운 실내)
        if (v.gear == Gear.P && !v.isCharging && v.cabinTempC < 12f && !v.hvac.power && ctx.hour in 6..9) {
            out += Moment(
                "precond", MomentType.ROUTINE, Urgency.LOW,
                "실내가 ${v.cabinTempC.roundToInt()}°C로 차가워요",
                "출근 루틴대로 ${ctx.profile.preferredTempC}°C로 예열할까요? 주행 중 히터를 켜는 것보다 전비에 유리해요.",
                listOf(MomentAction("preheat", "예열 시작", true)),
                tier = AiTier.ON_DEVICE,
            )
        }

        return out.sortedByDescending { it.urgency.weight }
    }
}
