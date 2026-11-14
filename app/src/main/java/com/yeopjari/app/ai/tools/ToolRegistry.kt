package com.yeopjari.app.ai.tools

import com.yeopjari.app.core.gateway.NaviGateway
import com.yeopjari.app.core.gateway.VehicleGateway
import com.yeopjari.app.core.model.Routine
import com.yeopjari.app.sim.Places

/** 툴 파라미터 스펙 — LLM Function Calling 스키마 & Gleo App Schema 공용 */
data class ParamSpec(val name: String, val type: String, val description: String, val required: Boolean = true, val enumValues: List<String> = emptyList())

data class ToolSpec(
    val name: String,
    val title: String,
    val description: String,
    val params: List<ParamSpec>,
    /** 실행 전 사용자 확인 필요 (경로 변경 등) */
    val requiresConfirm: Boolean = false,
    /** 주행 중 실행 허용 여부 (주의분산 정책) */
    val allowedWhileDriving: Boolean = true,
    /** 사용하는 Pleos SDK */
    val sdk: String,
    /** 차량 기본 AI(Gleo)에 App Schema로 노출할지 */
    val exposeToGleo: Boolean = false,
)

/** 툴 실행 호스트 — 앱 상태(펫/쉼/루틴 등)와 차량 게이트웨이를 툴에 연결 */
interface ToolHost {
    val vehicle: VehicleGateway
    val navi: NaviGateway
    fun startPetMode(tempC: Float)
    fun stopPetMode()
    fun startRestMode(minutes: Int)
    fun startKidsGame()
    fun addRoutine(routine: Routine)
    fun saveParking(note: String)
}

class ToolRegistry(private val host: ToolHost) {

    val specs: List<ToolSpec> = listOf(
        ToolSpec("navi_set_destination", "목적지 설정", "차량 내비게이션의 목적지를 설정하고 안내를 시작한다",
            listOf(ParamSpec("place_id", "string", "POI 식별자")), requiresConfirm = false, sdk = "NaviHelper SDK", exposeToGleo = true),
        ToolSpec("navi_add_waypoint", "경유지 추가", "현재 경로에 경유지(충전소·휴게소·졸음쉼터 등)를 추가한다",
            listOf(ParamSpec("place_id", "string", "POI 식별자")), requiresConfirm = true, sdk = "NaviHelper SDK", exposeToGleo = true),
        ToolSpec("hvac_set_temperature", "실내 온도 설정", "공조 설정 온도를 변경한다",
            listOf(ParamSpec("celsius", "number", "16~30")), sdk = "Vehicle SDK · HVAC", exposeToGleo = false),
        ToolSpec("hvac_fresh_air", "환기", "외기 유입으로 전환하고 풍량을 높인다", emptyList(), sdk = "Vehicle SDK · HVAC"),
        ToolSpec("ev_find_chargers", "경로상 충전소 탐색", "도착 SOC 예측을 바탕으로 경로상 충전소를 추천한다",
            listOf(ParamSpec("min_kw", "number", "최소 충전 출력", required = false)), sdk = "Vehicle SDK · EV + NaviHelper + 충전소 Open API", exposeToGleo = true),
        ToolSpec("care_pet_mode", "펫 케어 모드", "정차 중 반려동물을 위해 실내 온도를 유지하고 보호자 부재 안내를 표시한다",
            listOf(ParamSpec("celsius", "number", "유지 온도", required = false)), requiresConfirm = false, allowedWhileDriving = false, sdk = "Vehicle SDK · HVAC/Gear", exposeToGleo = true),
        ToolSpec("care_pet_mode_stop", "펫 케어 종료", "펫 케어 모드를 종료하고 공조를 이전 상태로 되돌린다", emptyList(), sdk = "Vehicle SDK · HVAC"),
        ToolSpec("care_rest_mode","쉼 모드", "정차/충전 중 휴식 타이머와 공조를 설정한다",
            listOf(ParamSpec("minutes", "number", "휴식 시간")), allowedWhileDriving = false, sdk = "Vehicle SDK · HVAC + TTS", exposeToGleo = true),
        ToolSpec("kids_start_quiz", "아이 퀴즈", "동승 아이를 위한 음성 퀴즈를 시작한다", emptyList(), sdk = "STT · TTS SDK", exposeToGleo = true),
        ToolSpec("vehicle_health_check", "차량 점검", "타이어·배터리·도어 등 차량 상태를 점검한다", emptyList(), sdk = "Vehicle SDK", exposeToGleo = true),
        ToolSpec("routine_create", "루틴 생성", "조건과 동작으로 이루어진 자동화 루틴을 만든다",
            listOf(ParamSpec("trigger", "string", "조건"), ParamSpec("action", "string", "동작")), requiresConfirm = true, allowedWhileDriving = false, sdk = "Vehicle + NaviHelper SDK", exposeToGleo = true),
        ToolSpec("ev_set_charge_limit", "충전 한도 설정", "충전 목표 SOC 를 설정한다 (예: 지하주차장 90% 제한)",
            listOf(ParamSpec("percent", "number", "50~100")), sdk = "Vehicle SDK · EvBattery.setEvChargePercentLimit"),
        ToolSpec("parking_save", "주차 위치 메모", "층·구역·기둥 등 주차 위치를 기억한다 (지하주차장 GPS 상실 시)",
            listOf(ParamSpec("note", "string", "주차 위치 설명")), sdk = "Gear + FusedLocation + STT SDK"),
        ToolSpec("media_play_video","영상 재생", "영상 콘텐츠 재생 (주행 중 운전자 대상 차단)", listOf(ParamSpec("title", "string", "콘텐츠")), allowedWhileDriving = false, sdk = "— (정책 차단)"),
    )

    fun spec(name: String) = specs.first { it.name == name }

    suspend fun execute(name: String, args: Map<String, String>): Result<String> = runCatching {
        when (name) {
            "navi_set_destination" -> {
                val p = Places.byId(args["place_id"] ?: "") ?: error("알 수 없는 장소")
                host.navi.setDestination(p).getOrThrow(); "${p.name}로 안내 시작"
            }
            "navi_add_waypoint" -> {
                val p = Places.byId(args["place_id"] ?: "") ?: error("알 수 없는 장소")
                host.navi.addWaypoint(p).getOrThrow(); "${p.name} 경유지 추가"
            }
            "hvac_set_temperature" -> {
                val c = args["celsius"]?.toFloatOrNull() ?: 22f
                host.vehicle.setHvacTemperature(c).getOrThrow(); "설정 온도 ${"%.1f".format(c)}°C"
            }
            "hvac_fresh_air" -> {
                host.vehicle.setRecirculation(false).getOrThrow()
                host.vehicle.setFanSpeed(5).getOrThrow(); "외기 유입 · 풍량 5단"
            }
            "ev_find_chargers" -> "경로상 급속 충전소 3곳 탐색"
            "care_pet_mode" -> { host.startPetMode(args["celsius"]?.toFloatOrNull() ?: 22f); "펫 케어 모드 시작" }
            "care_pet_mode_stop" -> { host.stopPetMode(); "펫 케어 모드 종료" }
            "care_rest_mode" -> { host.startRestMode(args["minutes"]?.toIntOrNull() ?: 15); "쉼 모드 ${args["minutes"] ?: 15}분" }
            "kids_start_quiz" -> { host.startKidsGame(); "퀴즈 시작" }
            "vehicle_health_check" -> "타이어·배터리·도어 점검 완료"
            "routine_create" -> "루틴 초안 생성"
            "ev_set_charge_limit" -> {
                val p = args["percent"]?.toIntOrNull() ?: 90
                host.vehicle.setChargeLimit(p).getOrThrow(); "충전 한도 ${p}%"
            }
            "parking_save" -> { host.saveParking(args["note"] ?: ""); "주차 위치 저장" }
            "media_play_video" -> error("주행 중 영상 재생 차단")
            else -> error("unknown tool $name")
        }
    }

    /** Gleo App Schema 내보내기 형태 (docs/gleo-app-schema.json 과 동일 구조) */
    fun gleoExposed(): List<ToolSpec> = specs.filter { it.exposeToGleo }
}
