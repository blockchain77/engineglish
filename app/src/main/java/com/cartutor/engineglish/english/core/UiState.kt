package com.cartutor.engineglish.english.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * 엔진글리시 — UI 상태 계약 (코치가 만들고 UI 가 그린다).
 * 코치(english/coach) 는 EnglishUiState 하나만 내보내고, UI(ui/english) 는 이것만 보고 그린다.
 * 명세: docs/ENGLISH_APP_SPEC.md §7 · §8
 */

// ───────────────────────── 화면 이동 ─────────────────────────

/**
 * 화면 경로. adb `screen=<이름>` 도 이 이름을 쓴다.
 * @param drivingAllowed 주행 중에도 조작 가능한 화면 (DRIVE 뿐)
 */
enum class EnglishRoute(val ko: String, val drivingAllowed: Boolean) {
    HOME("홈", false),
    DRIVE("주행 학습", true),
    LESSON("학습", false),
    REVIEW("복습", false),
    REPORT("리포트", false),
    SETTINGS("설정", false),
    ONBOARDING("시작하기", false),
    PLACEMENT("레벨 테스트", false),
}

/** 사용자가 고른 화면 (실제로 보이는 화면은 UI 가 상태와 합쳐 정한다 — 명세 §8.1) */
class EnglishNavigator(initial: EnglishRoute = EnglishRoute.HOME) {
    private val _route = MutableStateFlow(initial)
    val route: StateFlow<EnglishRoute> = _route.asStateFlow()
    fun go(route: EnglishRoute) { _route.value = route }
}

// ───────────────────────── 코치 단계 ─────────────────────────

/** 코치 상태 기계의 단계 (명세 §6) */
enum class CoachPhase(val ko: String) {
    BOOTING("준비 중"),
    ONBOARDING("시작하기"),
    PLACEMENT("레벨 테스트"),
    IDLE("대기"),
    BRIEFING("세션 안내"),
    PROMPTING("문제 읽는 중"),
    LISTENING("듣는 중"),
    JUDGING("채점 중"),
    FEEDBACK("피드백"),
    HOLD("잠시 멈춤 · 운전 집중"),
    PAUSED("일시정지"),
    CONFIRM("확인 질문"),
    SUMMARY("세션 요약");

    /** 세션 진행 중 단계 */
    val inSession: Boolean
        get() = this == BRIEFING || this == PROMPTING || this == LISTENING || this == JUDGING ||
            this == FEEDBACK || this == HOLD || this == PAUSED || this == CONFIRM
}

enum class MicState { OFF, PREPARING, LISTENING, PROCESSING }

enum class HudIcon { NONE, LISTENING, CORRECT, RETRY, HOLD, PAUSED }

enum class SuggestionKind { START_COMMUTE_SESSION, SWITCH_TRACK_TRAVEL, CONTEXT_HOOK, PRON_DRILL, PLACEMENT_NEEDED, REVIEW_DUE }

// ───────────────────────── 조각 상태 ─────────────────────────

/** 경로 띠 한 칸 (출퇴근 지도의 구간) */
data class RouteStripItem(
    val segmentId: String,
    val nameKo: String,
    val road: RoadKind,
    val current: Boolean,
    val passed: Boolean,
)

data class DriveStatus(
    val origin: SignalOrigin = SignalOrigin.SIMULATOR,
    val gear: GearPosition = GearPosition.P,
    val speedKph: Int = 0,
    val parked: Boolean = true,
    val uxRestricted: Boolean = false,
    val charging: Boolean = false,
    val socPercent: Int? = null,
    val slot: CommuteSlot? = null,
    val routeRemainingMin: Int? = null,
    val destinationKo: String? = null,
    val segmentKo: String? = null,
    val routeStrip: List<RouteStripItem> = emptyList(),
)

data class SessionUi(
    val planId: String,
    /** "출근길 32분 → 새 표현 6개 + 복습 10개" */
    val titleKo: String,
    val slot: CommuteSlot,
    val mode: SlotMode,
    val track: Track,
    /** 지금 문항 번호 (1부터) */
    val index: Int,
    val total: Int,
    val answered: Int,
    val correct: Int,
    val partial: Int,
    val newDone: Int,
    val reviewDone: Int,
    val interrupted: Int,
    val xpGained: Int,
    val startedAtMs: Long,
    /** 지금 이어지고 있는 연속 정답 수 (5의 배수마다 연속 정답 효과음) */
    val streak: Int = 0,
)

/**
 * 지금 문제.
 * @param titleKo 문항 종류 꼬리표 ("새 표현", "복습", "오늘 틀린 표현", "방금 그 상황")
 * @param displayKo 크게 보일 한국어 (운전 중 화면: 34sp 이상, 짧게)
 * @param displayEn 화면에 보일 영어 — 정차 중일 때만 (주행 중엔 null, 영어는 귀로만 + HUD 힌트 한 단어)
 * @param choices 정차 중 화면 보기 (운전 중엔 빈 목록)
 * @param attempt 1 또는 2
 */
data class PromptUi(
    val itemId: String,
    val skill: Skill,
    val kind: PlanKind,
    val titleKo: String,
    val displayKo: String,
    val displayEn: String?,
    val choices: List<String> = emptyList(),
    val attempt: Int = 1,
    val hintShown: Boolean = false,
    val explanationKo: String? = null,
)

data class ListeningUi(
    val mic: MicState = MicState.OFF,
    /** 부분 인식 결과 — 정차 중에만 화면에 보인다 */
    val partial: String = "",
    /** 듣기 마감 시각 (Clock 기준), 없으면 null */
    val deadlineMs: Long? = null,
)

data class FeedbackUi(
    val itemId: String,
    val verdict: Verdict,
    val score: Int,
    /** "정답이에요!" / "거의 맞았어요" / "이렇게 말해요" */
    val headlineKo: String,
    val heard: String,
    val modelAnswer: String,
    val alignment: List<WordAlign> = emptyList(),
    val pronIssues: List<PronIssue> = emptyList(),
    val fluency: FluencyMetrics? = null,
    val grade: Grade? = null,
    val xp: Int = 0,
    val atMs: Long,
)

/** 앞유리 HUD 시뮬레이터 — 듣는 중에만 힌트 한 단어 */
data class HudUi(
    val hint: String? = null,
    val icon: HudIcon = HudIcon.NONE,
    val load: LoadLevel = LoadLevel.PARKED,
)

data class PlacementUi(
    /** 0부터, 지금 문항 번호 */
    val index: Int,
    val total: Int,
    val item: PlacementItem?,
    val currentLevel: Level,
    /** 방금 답이 맞았나 (다음 문항으로 넘어가기 전 0.8초 표시용) */
    val lastCorrect: Boolean? = null,
    val result: PlacementResult? = null,
)

data class SuggestionUi(
    val id: String,
    val kind: SuggestionKind,
    val titleKo: String,
    val bodyKo: String,
    val acceptLabelKo: String,
    val dismissLabelKo: String = "괜찮아요",
)

data class ToastUi(val id: Long, val textKo: String)

data class SessionSummaryUi(
    /** "출근길 세션 완료" */
    val titleKo: String,
    val minutes: Int,
    val answered: Int,
    val correct: Int,
    val partial: Int,
    val skipped: Int,
    val interrupted: Int,
    val xpGained: Int,
    val newLearned: Int,
    val reviewed: Int,
    /** 오늘의 한 문장 */
    val bestSentence: String?,
    val pronTips: List<PronIssue> = emptyList(),
    val fluency: FluencySummary? = null,
    val events: List<ProgressEvent> = emptyList(),
)

/** 표현 한 줄 (복습·리포트·대시보드 목록) — tagKo: "오늘 틀림", "복습 예정", "저장함", "거의 맞음" 등 */
data class ExpressionUi(val itemId: String?, val textEn: String, val meaningKo: String, val tagKo: String)

data class PronTipUi(val type: PronIssueType, val count: Int, val tipKo: String, val pairs: List<MinimalPair>)

/** 정차 화면(복습·리포트)용 목록 — 코치가 학습자 상태 + 콘텐츠로 만든다 (정차 중 상태가 바뀔 때 갱신) */
data class LibraryUi(
    val mistakesToday: List<ExpressionUi> = emptyList(),
    /** 복습 예정 (가까운 순 최대 20) */
    val dueSoon: List<ExpressionUi> = emptyList(),
    val saved: List<ExpressionUi> = emptyList(),
    val pronTips: List<PronTipUi> = emptyList(),
    val recentSessions: List<SessionRecord> = emptyList(),
)

// ───────────────────────── 퇴근길 학습 질문 ─────────────────────────

/**
 * 퇴근길 학습 질문 카드 (명세 §7.1) — 퇴근길이 감지되면 바로 시작하지 않고 먼저 묻는다.
 * 음성("응", "네", "좋아", "할게", "yes", "sure", "okay" / "아니", "괜찮아", "쉴게", "다음에", "no", "not today")
 * 또는 큰 버튼 두 개([EnglishCoach.answerEveningAsk])로 답한다.
 * 10초 안에 답이 없거나 부하가 FOCUS 이상이 되면 카드를 내리고, 부하가 NORMAL 이하일 때 한 번만 다시 묻는다.
 *
 * @param visible 카드를 보여 줄 때만 true (부하가 FOCUS 이상이면 false)
 * @param attempt 1 = 첫 질문, 2 = 다시 묻기 (안 보일 땐 0)
 * @param listening 대답을 듣는 중 (마이크 표시)
 * @param deadlineMs 대답 마감 시각 (Clock 기준) — 질문을 말하는 동안엔 null
 */
data class EveningAskUi(
    val visible: Boolean = false,
    val attempt: Int = 0,
    val questionKo: String = "퇴근길에도 영어 복습할까요?",
    val yesLabelKo: String = "할게요",
    val noLabelKo: String = "오늘은 쉴게요",
    val listening: Boolean = false,
    val deadlineMs: Long? = null,
)

// ───────────────────────── 주차 대시보드 ─────────────────────────

/** 이번 세션의 발음 약점 한 가지 — exampleKo: "‘right’가 ‘light’로 들렸어요" */
data class PronWeakPointUi(
    val type: PronIssueType,
    /** 이번 세션에서 헷갈린 횟수 */
    val count: Int,
    val tipKo: String,
    val expectedWord: String,
    val heardWord: String,
    val exampleKo: String,
)

/**
 * 주차 대시보드 (명세 §7.2) — 주행 학습을 마치고 P 단에 넣으면 전체 화면으로 뜨는 학습 결과 + 진행률.
 * 세션이 끝날 때마다 채워지고(답한 문항이 1개 이상일 때), [EnglishUiState.showParkedDashboard] 가 true 일 때 자동으로 띄운다.
 * 닫은 뒤에도 남아 홈의 '지난 학습 결과'에서 다시 볼 수 있다.
 *
 * 진행률 값은 모두 '세션 전(before) → 세션 후(after)' 쌍이다 — 막대·링·XP 숫자가 차오르는 애니메이션용.
 *
 * @param headlineKo 큰 인사말 ("오늘도 수고했어요!")
 * @param subtitleKo "출근길 12분 동안 8문장을 연습했어요"
 * @param accuracyPercent 정확도 0..100 (정답 1 · 거의 맞음 0.5)
 * @param avgResponseMs 평균 응답 시간 — 말로 답한 문항만, 없으면 null
 * @param bestStreak 이번 세션 최고 연속 정답
 * @param mistakes 이번 세션에서 틀렸거나 거의 맞은 표현 ('틀린 표현 복습' 재료)
 * @param bestSentence 오늘의 한 문장 (가장 약했던 짧은 문장)
 * @param xpBefore 세션 전 누적 XP → [xpAfter] 까지 숫자가 올라간다 ([xpGained] = 차이)
 * @param leveledUp 이번 세션에서 레벨 업 — 막대를 끝(1.0)까지 채운 뒤 새 레벨([level])의 [levelProgressAfter] 로
 * @param levelProgressBefore 레벨 업까지 진행률 0..1 (세션 전)
 * @param levelGoalKo 막대 옆 설명 ("중급까지" · 최고 레벨이면 "고급 과정")
 * @param unitTitleKo 세션을 시작할 때 공부하던 유닛 이름 (없으면 null)
 * @param unitProgressBefore 그 유닛을 푼 비율 0..1 (세션 전) → [unitProgressAfter]
 * @param unitPassed 그 유닛을 통과했나 (세션 후)
 * @param nextUnitTitleKo 유닛을 통과해 새로 열린 다음 유닛 이름
 * @param goalBefore 오늘 답한 문장 수 (세션 전) → [goalDone], 목표는 [goalTarget]
 * @param streakDays 연속 학습 일수 (세션 후), [streakExtended] = 오늘 이어 붙였나
 */
data class ParkedDashboardState(
    val sessionId: String,
    val slot: CommuteSlot,
    val endedAtMs: Long,
    val headlineKo: String,
    val subtitleKo: String,
    // 학습 결과
    val minutes: Int,
    val answered: Int,
    val correct: Int,
    val partial: Int,
    val wrong: Int,
    val skipped: Int,
    val interrupted: Int,
    val accuracyPercent: Int,
    val avgResponseMs: Long?,
    val newExpressions: Int,
    val reviewed: Int,
    val bestStreak: Int,
    val pronWeakPoint: PronWeakPointUi? = null,
    val mistakes: List<ExpressionUi> = emptyList(),
    val bestSentence: String? = null,
    // 진행률
    val xpGained: Int,
    val xpBefore: Int,
    val xpAfter: Int,
    val level: Level? = null,
    val leveledUp: Boolean = false,
    val levelProgressBefore: Float = 0f,
    val levelProgressAfter: Float = 0f,
    val levelGoalKo: String = "",
    val unitTitleKo: String? = null,
    val unitProgressBefore: Float = 0f,
    val unitProgressAfter: Float = 0f,
    val unitPassed: Boolean = false,
    val nextUnitTitleKo: String? = null,
    val goalBefore: Int = 0,
    val goalDone: Int = 0,
    val goalTarget: Int = 0,
    val streakDays: Int = 0,
    val streakExtended: Boolean = false,
    val events: List<ProgressEvent> = emptyList(),
) {
    /** 오늘 목표 달성률 0..1 (세션 전 → 후) */
    val goalProgressBefore: Float get() = if (goalTarget > 0) (goalBefore.toFloat() / goalTarget).coerceIn(0f, 1f) else 0f
    val goalProgressAfter: Float get() = if (goalTarget > 0) (goalDone.toFloat() / goalTarget).coerceIn(0f, 1f) else 0f
    val goalReached: Boolean get() = goalTarget > 0 && goalDone >= goalTarget
}

// ───────────────────────── 전체 UI 상태 ─────────────────────────

/**
 * 코치가 내보내는 단 하나의 UI 상태.
 * @param ready 학습자 저장소를 읽었는가 (false 면 UI 는 스플래시)
 * @param profile null = 첫 실행 → 온보딩
 * @param screenMode FULL = 정차(전체 학습 화면), DRIVE = 주행(주행 학습 화면만 조작 가능)
 * @param session 세션 중이 아니면 null
 * @param eveningAsk 퇴근길 학습 질문 카드 (visible 일 때만 그린다)
 * @param dashboard 마지막 세션의 학습 결과·진행률 (없으면 null) — 홈의 '지난 학습 결과'
 * @param showParkedDashboard true 면 [dashboard] 를 전체 화면으로 띄운다 (주행 학습 뒤 P 단 · '지난 학습 결과')
 * @param pulse 마지막 앰비언트 펄스 (UI 는 id 가 바뀔 때 1초 화면 가장자리 빛)
 * @param demoEnabled 디버그 빌드 또는 ADB 켜짐 → 데모 패널 보임
 */
data class EnglishUiState(
    val ready: Boolean = false,
    val profile: LearnerProfile? = null,
    val settings: LearnerSettings = LearnerSettings(),
    val load: LoadAssessment = LoadAssessment(),
    val rules: LoadRules? = null,
    val drive: DriveStatus = DriveStatus(),
    val screenMode: ScreenMode = ScreenMode.FULL,
    val phase: CoachPhase = CoachPhase.BOOTING,
    val session: SessionUi? = null,
    val prompt: PromptUi? = null,
    val listening: ListeningUi = ListeningUi(),
    val feedback: FeedbackUi? = null,
    val hud: HudUi = HudUi(),
    val pulse: AmbientPulse? = null,
    val placement: PlacementUi? = null,
    val suggestion: SuggestionUi? = null,
    val eveningAsk: EveningAskUi = EveningAskUi(),
    val summary: SessionSummaryUi? = null,
    val dashboard: ParkedDashboardState? = null,
    val showParkedDashboard: Boolean = false,
    /** 다음 세션 미리보기 (홈 화면: "출근길 32분 → 새 표현 6개 + 복습 10개") */
    val nextPlanPreviewKo: String? = null,
    val progress: ProgressSnapshot = ProgressSnapshot(),
    val toast: ToastUi? = null,
    val speech: SpeechStatus = SpeechStatus(),
    val storage: StorageInfo? = null,
    val library: LibraryUi = LibraryUi(),
    val demoEnabled: Boolean = false,
)
