package com.yeopjari.app.core.model

/** 변속 상태 (Pleos Vehicle SDK · Gear 도메인 VehicleGear 매핑) */
enum class Gear(val label: String) { P("P"), R("R"), N("N"), D("D"), UNKNOWN("-") }

/** 주행 맥락 — UX 제한(Distraction Optimization) 판단의 기준 */
enum class DriveMode(val label: String) {
    PARKED("정차"),
    STOPPED("일시 정지"),
    DRIVING("주행 중"),
    CHARGING("충전 중");

    /** 운전자 주의분산 제한이 필요한 상태인가 */
    val restricted: Boolean get() = this == DRIVING
}

enum class Seat(val label: String) { DRIVER("운전석"), PASSENGER("조수석"), REAR_LEFT("뒷좌석 왼쪽"), REAR_RIGHT("뒷좌석 오른쪽") }

data class Doors(
    val driver: Boolean = false,
    val passenger: Boolean = false,
    val rearLeft: Boolean = false,
    val rearRight: Boolean = false,
    val trunk: Boolean = false,
) {
    val anyOpen: Boolean get() = driver || passenger || rearLeft || rearRight || trunk
    val rearOpen: Boolean get() = rearLeft || rearRight
}

data class Hvac(
    val power: Boolean = true,
    val setTempC: Float = 22f,
    val fan: Int = 3,
    val ac: Boolean = true,
    val recirculation: Boolean = false,
)

/**
 * 계기판 경고등 (Vehicle SDK · Safety.getTelltalesInfo / TelltaleType 매핑).
 * 개별 타이어 압력 값은 SDK에서 제공되지 않으므로 TPMS 경고등 + 맥락(외기온 변화 등)으로 추론한다.
 */
enum class Telltale(val label: String, val level: WarningLevel) {
    TPMS("타이어 공기압 경고", WarningLevel.CAUTION),
    LOW_WASHER_FLUID("워셔액 부족", WarningLevel.INFO),
    LOW_12V_BATTERY("12V 배터리 전압 저하", WarningLevel.CAUTION),
    LOW_EV_BATTERY("구동 배터리 부족", WarningLevel.CRITICAL),
    SEAT_BELT("안전벨트 미착용", WarningLevel.CAUTION),
}

enum class WarningLevel { INFO, CAUTION, CRITICAL }

/**
 * 차량 스냅샷 — Pleos Vehicle SDK(EvBattery·Gear·HVAC·Door·Seat·Safety·Odometer)와
 * AAOS CarPropertyManager(속도·외기온·주행가능거리·충전 속도)를 하나의 불변 상태로 합친 것.
 * 모든 화면과 AI 컨텍스트 엔진은 이 스냅샷만 바라본다.
 */
data class VehicleSnapshot(
    val model: String = "Pleos Connect EV",
    val gear: Gear = Gear.P,
    val speedKph: Float = 0f,
    val socPercent: Float = 72f,
    val rangeKm: Int = 318,
    val batteryKwh: Float = 84f,
    val efficiencyKmPerKwh: Float = 5.2f,
    val isCharging: Boolean = false,
    val chargePowerKw: Float = 0f,
    val chargeLimitPercent: Int = 80,
    /** 충전 시작 시각 (하루 중 초) — 급속충전 1시간 초과 과태료 타이머 */
    val chargeStartedSec: Float? = null,
    val cabinTempC: Float = 23f,
    val outsideTempC: Float = 14f,
    val hvac: Hvac = Hvac(),
    val doors: Doors = Doors(),
    val telltales: Set<Telltale> = emptySet(),
    val odometerKm: Int = 18_432,
    /** 연속 주행 시간 (Odometer.getTripCurrentDrivingTime) */
    val continuousDriveMin: Int = 0,
    val occupants: Set<Seat> = setOf(Seat.DRIVER),
    /** 위치 신호 품질 — 지하주차장 진입 감지에 사용 (FusedLocation confidence) */
    val gpsGood: Boolean = true,
) {
    val driveMode: DriveMode
        get() = when {
            isCharging -> DriveMode.CHARGING
            gear == Gear.P -> DriveMode.PARKED
            speedKph < 1f -> DriveMode.STOPPED
            else -> DriveMode.DRIVING
        }
}
