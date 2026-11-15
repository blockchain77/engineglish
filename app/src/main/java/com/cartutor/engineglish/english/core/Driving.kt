package com.cartutor.engineglish.english.core

/*
 * 엔진글리시 — 운전 신호 · 운전 부하 · 출퇴근 지도 · 상황 훅 · 데모 모델.
 * 순수 Kotlin (JVM 단위 테스트 대상). 명세: docs/ENGLISH_APP_SPEC.md §4
 */

// ───────────────────────── 운전 부하 ─────────────────────────

/**
 * 운전 부하 단계. ordinal 순서가 곧 부하 크기 (PARKED < RELAXED < NORMAL < FOCUS < CRITICAL).
 * 문항의 maxLoad 와 비교할 때 [isAtMost] 를 쓴다.
 */
enum class LoadLevel(val ko: String, val bandStart: Int, val bandEnd: Int) {
    PARKED("정차", 0, 9),
    RELAXED("여유", 10, 34),
    NORMAL("보통", 35, 59),
    FOCUS("집중", 60, 84),
    CRITICAL("위험", 85, 100);

    fun isAtMost(other: LoadLevel): Boolean = ordinal <= other.ordinal
    fun isAbove(other: LoadLevel): Boolean = ordinal > other.ordinal

    companion object {
        fun max(a: LoadLevel, b: LoadLevel): LoadLevel = if (a.ordinal >= b.ordinal) a else b
    }
}

/** 부하 이유 코드. level = 이 이유 하나만으로 올라가는 단계 */
enum class ReasonCode(val level: LoadLevel) {
    PARKED(LoadLevel.PARKED),
    CHARGING(LoadLevel.PARKED),
    CRUISE(LoadLevel.RELAXED),
    STEADY_HIGHWAY(LoadLevel.RELAXED),
    CITY(LoadLevel.NORMAL),
    STOPPED(LoadLevel.NORMAL),
    TRAFFIC_JAM(LoadLevel.NORMAL),
    RAIN(LoadLevel.NORMAL),
    NIGHT(LoadLevel.NORMAL),
    UX_RESTRICTED(LoadLevel.NORMAL),
    TURN_SIGNAL(LoadLevel.FOCUS),
    MANEUVER(LoadLevel.FOCUS),
    STEERING(LoadLevel.FOCUS),
    CAMERA(LoadLevel.FOCUS),
    SCHOOL_ZONE(LoadLevel.FOCUS),
    CAUTION_ZONE(LoadLevel.FOCUS),
    RAIN_NIGHT(LoadLevel.FOCUS),
    REVERSE(LoadLevel.FOCUS),
    HARD_BRAKE(LoadLevel.CRITICAL),
    OVERSPEED(LoadLevel.CRITICAL),
    NAV_PROMPT(LoadLevel.CRITICAL),
    AUDIO_FOCUS(LoadLevel.CRITICAL),
}

/** 사용자에게 보여 주는 이유 — textKo 예: "교차로 접근 120m", "방향지시등" */
data class LoadReason(val code: ReasonCode, val textKo: String)

/**
 * 운전 부하 판단 결과.
 * @param level 히스테리시스 적용 후 단계 (정책은 이것만 본다)
 * @param index 0..100, level.bandStart..bandEnd 안의 값
 * @param reasons 표시용 이유 — 높은 단계 이유부터, 최대 3개
 * @param rawLevel 이번 신호만으로 본 즉시 단계
 * @param sinceMs 현재 level 이 시작된 시각
 * @param atMs 판단한 신호 시각 (DrivingSignals.atMs)
 */
data class LoadAssessment(
    val level: LoadLevel = LoadLevel.PARKED,
    val index: Int = 0,
    val reasons: List<LoadReason> = emptyList(),
    val rawLevel: LoadLevel = level,
    val sinceMs: Long = 0L,
    val atMs: Long = 0L,
)

/** FOCUS 진입 때 말하던 TTS 처리 */
enum class TtsRule { FULL, FINISH_SHORT, STOP_NOW }

enum class ScreenMode { FULL, DRIVE }

/** 부하 단계별 학습 정책 (LoadPolicy.rules) */
data class LoadRules(
    val level: LoadLevel,
    /** 새 문제를 낼 수 있나 */
    val newQuestions: Boolean,
    /** 마이크(STT)를 열 수 있나 */
    val listening: Boolean,
    val tts: TtsRule,
    val skills: Set<Skill>,
    /** 모범 답안 최대 단어 수 */
    val maxAnswerWords: Int,
    /** 듣기 시간 상한(초) */
    val maxListenSec: Int,
    val screen: ScreenMode,
    /** 화면 보기 탭 허용 (정차만) */
    val onScreenChoices: Boolean,
    val hudHint: Boolean,
    val ambientPulse: Boolean,
)

// ───────────────────────── 운전 신호 ─────────────────────────

enum class GearPosition(val label: String) { P("P"), R("R"), N("N"), D("D"), UNKNOWN("-") }

enum class TurnSignalState(val ko: String) { OFF("꺼짐"), LEFT("왼쪽"), RIGHT("오른쪽") }

/**
 * 다음 안내 지점 유형 (NaviHelper TurnType 묶음).
 * @param demanding true 면 300m/8초 안에서 FOCUS 사유
 */
enum class ManeuverType(val ko: String, val demanding: Boolean) {
    STRAIGHT("직진", false),
    LEFT("좌회전", true),
    RIGHT("우회전", true),
    KEEP_LEFT("왼쪽 방향", true),
    KEEP_RIGHT("오른쪽 방향", true),
    U_TURN("유턴", true),
    EXIT("나들목 진출", true),
    ENTER("진입로", true),
    MERGE("합류", true),
    ROUNDABOUT("회전교차로", true),
    TOLL("요금소", true),
    TUNNEL("터널", false),
    ARRIVE("목적지", true),
    REST_AREA("휴게소 진입", true),
    OTHER("안내 지점", false),
}

/** 다음 안내 지점. distanceM = 남은 거리 */
data class Maneuver(val type: ManeuverType, val distanceM: Int, val descriptionKo: String = "")

enum class CameraKind(val ko: String) {
    SPEED_FIXED("고정식 단속"),
    SPEED_MOBILE("이동식 단속"),
    SECTION("구간 단속"),
    SIGNAL("신호 단속"),
    SCHOOL_ZONE("어린이 보호구역 단속"),
    OTHER("단속"),
}

data class CameraAhead(val kind: CameraKind, val distanceM: Int, val limitKph: Int? = null)

enum class CautionKind(val ko: String) {
    SCHOOL_ZONE("어린이 보호구역"),
    SPEED_BUMP("과속방지턱"),
    ACCIDENT_AREA("사고 다발 구간"),
    RAILROAD("철길 건널목"),
    SLIPPERY("미끄럼 주의"),
    CONSTRUCTION("공사 구간"),
    OTHER("주의 구간"),
}

/** inside = 이미 구역 안 (distanceM = 0) */
data class CautionAhead(val kind: CautionKind, val distanceM: Int, val inside: Boolean = false)

enum class DestinationCategory(val ko: String) { HOME("집"), OFFICE("회사"), AIRPORT("공항"), CHARGER("충전소"), OTHER("목적지") }

enum class SignalOrigin(val ko: String) { PLEOS("Pleos 차량"), SIMULATOR("시뮬레이터") }

data class LatLng(val lat: Double, val lng: Double)

/**
 * 한 시점의 운전 신호 묶음 (SignalSource 가 최소 500ms 마다 atMs 를 갱신해 내보낸다).
 * 모든 필드 기본값이 있으므로 테스트에서 `DrivingSignals(atMs = t, gear = GearPosition.D, speedKph = 50f)` 처럼 만든다.
 *
 * @param accelMps2 최근 약 1초 평균 가속도 (+가속 / −감속). 급제동 = accelMps2 <= −3.5
 * @param steeringDeg 핸들 각도 (+오른쪽 / −왼쪽)
 * @param steeringRateDps 핸들 각속도 절댓값 (deg/s)
 * @param headlightsOn 전조등(로빔) 켜짐 — 야간 근사
 * @param uxRestricted AAOS CarUxRestrictions.isRequiresDistractionOptimization
 * @param navVoicePrompt 내비 안내 음성이 재생 중 (USAGE_ASSISTANCE_NAVIGATION_GUIDANCE 또는 시뮬레이터 주입)
 * @param audioFocusLost 우리 앱이 오디오 포커스를 일시적으로 잃음 (전화·다른 안내)
 * @param destinationPlaceId 출퇴근 지도 장소 id ("place.home" 등) — 알 때만
 * @param segmentId 출퇴근 지도 구간 id — 지도 위일 때만
 * @param trafficJam 정체 중 (시속 15km 미만이 30초 이상, 또는 내비 정체 HEAVY 구간)
 * @param arrived 목적지 도착 직후 약 5초간 true
 * @param driveId 이번 주행 id (P → 주행 시작 때 새로 발급). 정차 중이면 마지막 주행 id 또는 null
 */
data class DrivingSignals(
    val atMs: Long = 0L,
    val origin: SignalOrigin = SignalOrigin.SIMULATOR,
    val gear: GearPosition = GearPosition.P,
    val speedKph: Float = 0f,
    val speedLimitKph: Int? = null,
    val accelMps2: Float = 0f,
    val brakePedal: Boolean = false,
    val turnSignal: TurnSignalState = TurnSignalState.OFF,
    val steeringDeg: Float = 0f,
    val steeringRateDps: Float = 0f,
    val cruiseActive: Boolean = false,
    val wipersOn: Boolean = false,
    val headlightsOn: Boolean = false,
    val uxRestricted: Boolean = false,
    val maneuver: Maneuver? = null,
    val camera: CameraAhead? = null,
    val caution: CautionAhead? = null,
    val navVoicePrompt: Boolean = false,
    val audioFocusLost: Boolean = false,
    val charging: Boolean = false,
    val socPercent: Int? = null,
    val routeActive: Boolean = false,
    val routeRemainingSec: Int? = null,
    val routeRemainingM: Int? = null,
    val destinationName: String? = null,
    val destinationCategory: DestinationCategory? = null,
    val destinationPlaceId: String? = null,
    val originPlaceId: String? = null,
    val location: LatLng? = null,
    val segmentId: String? = null,
    val segmentName: String? = null,
    val roadKind: RoadKind? = null,
    val inTunnel: Boolean = false,
    val trafficJam: Boolean = false,
    val arrived: Boolean = false,
    val driveId: String? = null,
) {
    val parked: Boolean get() = gear == GearPosition.P
    val moving: Boolean get() = speedKph >= 1f
}

// ───────────────────────── 출퇴근 지도 (가상, 공공 랜드마크 · 좌표 소수점 3자리) ─────────────────────────

enum class RoadKind(val ko: String) {
    CITY("시내 도로"),
    RIVERSIDE("강변 도로"),
    TUNNEL("터널"),
    HIGHWAY("고속화 도로"),
    EXPRESSWAY_EXIT("나들목"),
    SCHOOL_ZONE("어린이 보호구역"),
    JAM("상습 정체 구간"),
    LOCAL("이면 도로"),
}

enum class SegmentEventKind { MANEUVER, SPEED_CAMERA, SCHOOL_ZONE, JAM, TUNNEL, CAUTION }

/**
 * 구간 안의 이벤트 지점 (구간 시작에서 atM 미터).
 * @param lengthM 구역형 이벤트(어린이 보호구역·정체·터널)의 길이
 */
data class SegmentEvent(
    val kind: SegmentEventKind,
    val atM: Int,
    val lengthM: Int = 0,
    val maneuver: ManeuverType? = null,
    val textKo: String = "",
    val limitKph: Int? = null,
)

/**
 * 이름 있는 경로 구간 (경로 띠 · 시뮬레이터).
 * @param id 불변 ("seg.tancheon" 등)
 * @param typicalSpeedKph 시뮬레이터 목표 속도
 */
data class RouteSegment(
    val id: String,
    val nameKo: String,
    val road: RoadKind,
    val start: LatLng,
    val end: LatLng,
    val lengthM: Int,
    val speedLimitKph: Int,
    val typicalSpeedKph: Int,
    val events: List<SegmentEvent> = emptyList(),
)

data class CommutePlace(val id: String, val nameKo: String, val category: DestinationCategory, val point: LatLng)

/** 장소 from → to 를 잇는 구간 순서 */
data class CommuteRoute(val id: String, val fromPlaceId: String, val toPlaceId: String, val segmentIds: List<String>)

data class CommuteMap(
    val id: String,
    val version: Int,
    val nameKo: String,
    val places: List<CommutePlace>,
    val segments: List<RouteSegment>,
    val routes: List<CommuteRoute>,
) {
    fun place(id: String?): CommutePlace? = places.firstOrNull { it.id == id }
    fun segment(id: String?): RouteSegment? = segments.firstOrNull { it.id == id }
    fun route(id: String?): CommuteRoute? = routes.firstOrNull { it.id == id }
    fun routeBetween(fromPlaceId: String?, toPlaceId: String?): CommuteRoute? =
        routes.firstOrNull { it.fromPlaceId == fromPlaceId && it.toPlaceId == toPlaceId }
    fun segmentsOf(route: CommuteRoute): List<RouteSegment> = route.segmentIds.mapNotNull { segment(it) }
}

/** 지금 주행의 출퇴근 판단 (CommuteDetector) */
data class CommuteContext(
    val slot: CommuteSlot,
    val fromPlaceId: String? = null,
    val toPlaceId: String? = null,
    val routeId: String? = null,
    val routeRemainingSec: Int? = null,
    val destinationCategory: DestinationCategory? = null,
    /** 0..1 */
    val confidence: Float = 0f,
)

// ───────────────────────── 상황 훅 ─────────────────────────

/** 방금 겪은 상황 → '지금 이 순간의 표현' */
enum class HookTrigger(val ko: String) {
    JAM_EXIT("정체를 빠져나옴"),
    RAIN("비가 내림"),
    TUNNEL("터널 통과"),
    ARRIVING_OFFICE("회사 도착 무렵"),
    ARRIVING_HOME("집 도착 무렵"),
    AIRPORT("공항 가는 길"),
    CHARGING("충전 중"),
    NIGHT_DRIVE("밤 운전"),
    CRUISE("고속 주행"),
    SCHOOL_ZONE("어린이 보호구역 통과"),
}

data class HookEvent(val trigger: HookTrigger, val atMs: Long, val detailKo: String = "")

// ───────────────────────── 차량 피드백 · 핸들 버튼 ─────────────────────────

enum class PulseKind(val ko: String, val argb: Long) { GREEN("정답", 0xFF2EE66BL), AMBER("다시", 0xFFFFB020L) }

/** 앰비언트 라이트 펄스 이벤트 (화면 가장자리 빛으로도 보여 준다) */
data class AmbientPulse(val id: Long, val kind: PulseKind, val atMs: Long, val durationMs: Long = 1_000L)

/** 핸들 미디어 버튼. 다음 = 넘기기, 이전 = 다시, 재생/일시정지 = 잠깐/계속 */
enum class MediaKey(val ko: String) {
    NEXT("넘기기"),
    PREVIOUS("다시"),
    PLAY_PAUSE("잠깐/계속");

    companion object {
        // android.view.KeyEvent 값 (core 는 안드로이드 의존 없이 숫자로 둔다)
        const val KEYCODE_HEADSETHOOK = 79
        const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        const val KEYCODE_MEDIA_NEXT = 87
        const val KEYCODE_MEDIA_PREVIOUS = 88
        const val KEYCODE_MEDIA_PLAY = 126
        const val KEYCODE_MEDIA_PAUSE = 127

        fun fromKeyCode(code: Int): MediaKey? = when (code) {
            KEYCODE_MEDIA_NEXT -> NEXT
            KEYCODE_MEDIA_PREVIOUS -> PREVIOUS
            KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_MEDIA_PLAY, KEYCODE_MEDIA_PAUSE, KEYCODE_HEADSETHOOK -> PLAY_PAUSE
            else -> null
        }
    }
}

// ───────────────────────── 데모 / PT (디버그 빌드 또는 ADB 켜짐일 때만) ─────────────────────────

enum class DemoScenario(val ko: String) {
    MORNING("출근길 · 잠실 → 판교"),
    EVENING("퇴근길 · 판교 → 잠실"),
    CHARGING("충전소 정차 · 충전 중"),
    PARKED("집 앞 정차"),
}

/** adb `signal=` 값 = 앞의 11개 이름. 나머지는 데모 패널 추가 버튼 */
enum class DemoSignal(val ko: String) {
    TURN_ON("방향지시등 켜기"),
    TURN_OFF("방향지시등 끄기"),
    INTERSECTION("교차로 접근"),
    HARD_BRAKE("급제동"),
    RAIN_ON("비 시작 (와이퍼)"),
    RAIN_OFF("비 그침"),
    JAM("정체 진입"),
    CRUISE_ON("크루즈 켜기"),
    CRUISE_OFF("크루즈 끄기"),
    NAV_PROMPT("내비 음성 안내"),
    ARRIVE("목적지 도착"),
    NEAR_ARRIVAL("도착 1분 전으로"),
    TUNNEL("터널 진입"),
    SCHOOL_ZONE("어린이 보호구역"),
    NIGHT_ON("전조등 켜기 (밤)"),
    NIGHT_OFF("전조등 끄기"),
    AIRPORT_DESTINATION("목적지를 공항으로"),
}

/** 데모 패널 '답 주입' 종류 — 현재 문항 기준으로 문장을 만든다 */
enum class DemoAnswerKind(val ko: String) {
    CORRECT("정답"),
    PARTIAL("부분 정답"),
    WRONG("오답"),
    L1_ERROR("한국어식 발음 오류"),
    DONT_KNOW("모르겠어요"),
}
