package com.yeopjari.app.english.core

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * 옆자리 영어 — 모듈 간 인터페이스 계약.
 * 각 구현은 담당 패키지에 있다 (docs/ENGLISH_APP_SPEC.md §2 소유권 표).
 * 코치(english/coach) 는 아래 인터페이스에만 의존한다 → 모든 엔진은 가짜로 바꿔 JVM 에서 시험할 수 있다.
 */

// ───────────────────────── 시간 ─────────────────────────

/** 모든 시각은 이 시계로 잰다 (테스트는 가상 시계). epoch ms. 테스트에서 `Clock { t }` 처럼 람다로도 만든다 */
fun interface Clock {
    fun nowMs(): Long
}

// ───────────────────────── 콘텐츠 (content 담당) ─────────────────────────

/**
 * 학습 콘텐츠 — assets/content 폴더의 JSON 파일들을 읽는다 ([ContentFiles]). 읽은 뒤에는 불변.
 * 모든 목록은 결정적 순서 (파일 순서 → 유닛 번호 → 유닛 안 순서).
 */
interface ContentSource {
    /** 콘텐츠 버전 문자열 (파일들의 schema/개수 요약) */
    val version: String
    /** 전체 문항 (트랙 파일 + 상황 훅 + 발음 드릴) */
    fun items(): List<LearningItem>
    fun item(id: String): LearningItem?
    /** 트랙·레벨의 유닛, 유닛 번호 오름차순 */
    fun units(track: Track, level: Level): List<UnitInfo>
    fun unit(key: UnitKey): UnitInfo?
    /** 유닛 문항 (UnitInfo.itemIds 순서) */
    fun unitItems(key: UnitKey): List<LearningItem>
    /** 레벨 테스트 문항 풀 — 공통(track=null) + 해당 트랙 */
    fun placement(track: Track): List<PlacementItem>
    fun hook(trigger: HookTrigger): ContextHookContent?
    /** 발음 드릴 (MINIMAL_PAIR, pronFocus = type) */
    fun pronDrills(type: PronIssueType): List<LearningItem>
    /** 보기 2개 이상(distractors)인 문항 — 정차 화면 보기 탭용 */
    fun quizItems(track: Track, level: Level): List<LearningItem>
}

// ───────────────────────── 학습 엔진 (learn 담당) ─────────────────────────

/** 간격 반복 스케줄러 (SM-2 계열, 순수 함수) */
interface Scheduler {
    /** 등급을 반영한 새 카드. card=null 이면 새 카드로 시작 */
    fun schedule(card: CardState?, itemId: String, grade: Grade, nowMs: Long): CardState
    /** dueAtMs <= now 인 카드, 많이 밀린 순 */
    fun due(cards: Collection<CardState>, nowMs: Long): List<CardState>
    /**
     * 채점 결과 → 등급 (순수 매핑). CORRECT & latency < 1500ms → EASY, CORRECT → GOOD,
     * PARTIAL → HARD, WRONG/NO_ANSWER → AGAIN. 부하로 끊긴 시도는 코치가 아예 부르지 않는다.
     */
    fun gradeFor(verdict: Verdict, latencyMs: Long?): Grade
}

/** 적응형 레벨 테스트 (순수 함수, 상태는 PlacementState 값) */
interface PlacementEngine {
    /** 12문항, 중급에서 시작. pool 이 비면 current=null 인 끝난 상태 */
    fun start(track: Track, pool: List<PlacementItem>, nowMs: Long): PlacementState
    /** 답 반영 → 다음 문항 (맞히면 한 단계 위, 틀리면 아래, 같은 문항 반복 없음) */
    fun answer(state: PlacementState, pool: List<PlacementItem>, choiceIndex: Int, nowMs: Long): PlacementState
    /** 끝난(또는 중간) 상태의 결과 */
    fun result(state: PlacementState): PlacementResult
}

/** 진행 (XP · 스트릭 · 일일 통계 · 유닛 체크포인트 · 레벨 업 · SRS 카드 · 실수 · 발음 · 유창성 기록) */
interface Progression {
    /**
     * 채점된 답 하나를 학습자 상태 전체에 반영한다 (순수 함수).
     * cards[item.id] 를 Scheduler 로 갱신하고, 날짜가 바뀌었으면 스트릭을 계산한다.
     */
    fun record(state: LearnerState, outcome: AnswerOutcome, content: ContentSource, nowMs: Long): ProgressUpdate
    /** 세션 종료 기록 (sessions 최근 30개 유지, 일일 driveMinutes 누적) */
    fun recordSession(state: LearnerState, record: SessionRecord): LearnerState
    /** 열린 유닛 (1번은 항상 열림, n번 통과 → n+1 열림) */
    fun unlockedUnits(state: LearnerState, content: ContentSource, track: Track, level: Level): List<UnitKey>
    /** 지금 공부할 유닛 = 열린 유닛 중 통과 안 한 첫 유닛 (모두 통과면 마지막) */
    fun currentUnit(state: LearnerState, content: ContentSource): UnitKey?
    fun snapshot(state: LearnerState, content: ContentSource, nowMs: Long): ProgressSnapshot
}

/** 출퇴근 시간에 맞춘 세션 계획 */
interface SessionPlanner {
    fun plan(request: PlanRequest, content: ContentSource): SessionPlan
    /** 계획이 바닥났는데 시간이 남을 때 count 개 더 (복습 우선, 없으면 새 문항) */
    fun extend(plan: SessionPlan, request: PlanRequest, content: ContentSource, count: Int): SessionPlan
}

/** 암호화 파일 저장소 (SecureVault 어댑터 · 테스트용 메모리 구현) */
interface SecureStore {
    fun write(name: String, bytes: ByteArray): Boolean
    fun read(name: String): ByteArray?
    fun delete(name: String)
    fun size(name: String): Long
    /** 실제로 암호화되는가 (Keystore 사용 가능) */
    val encrypted: Boolean
}

/** 학습자 상태 저장소 — 기기 안에만, 암호화해서 */
interface LearnerRepository {
    val state: StateFlow<LearnerState>
    val loaded: StateFlow<Boolean>
    /** 금고에서 읽기 (여러 번 불러도 한 번만 읽음). 실패/없음 → 빈 상태 */
    suspend fun load()
    /** 원자적 변경 → 새 상태 반환. 저장은 비동기(최대 1초 디바운스) */
    fun update(transform: (LearnerState) -> LearnerState): LearnerState
    /** 대기 중인 저장을 즉시 */
    suspend fun flush()
    /** 파일 삭제 + 빈 상태 (profile = null) */
    fun eraseAll()
    fun storageInfo(): StorageInfo
}

// ───────────────────────── 말하기 · 채점 (speech 담당) ─────────────────────────

/** 영어 문장 정규화 — 소문자, 문장부호 제거, 축약형 풀기, 숫자 → 단어, 군말(um/uh/filler like) 제거 */
interface TextNormalizer {
    fun normalize(text: String): String
    fun tokens(text: String): List<String>
}

interface AnswerJudge {
    /**
     * 채점 (명세 §5.2). 빈 문자열/군말뿐 → NO_ANSWER.
     * 여러 허용 정답 중 가장 가까운 것과 단어 정렬, 발음 문제는 PronunciationAnalyzer 로 채운다.
     */
    fun judge(item: LearningItem, heard: String): JudgeResult
    /** 정차 화면 보기 탭 채점 (choice 가 정답 중 하나면 CORRECT, 아니면 WRONG) */
    fun judgeChoice(item: LearningItem, choice: String): JudgeResult
}

interface CommandParser {
    /**
     * 발화가 명령이면 그 명령, 답이면 null.
     * ANSWER 문맥: 정규화한 발화 '전체'가 명령 문구일 때만 (예: "next" O, "the next meeting" X).
     * CONFIRM 문맥: YES/NO 도 인식 ("네", "응", "좋아", "yes", "sure" / "아니", "no", "괜찮아").
     */
    fun parse(text: String, context: ListenContext = ListenContext.ANSWER): LearnerCommand?
}

interface PronunciationAnalyzer {
    /** 정렬의 SUB 쌍에서 한국어 화자 발음 문제 추정 (같은 단어 중복 없이, 확신 높은 순 최대 2개) */
    fun analyze(item: LearningItem, alignment: List<WordAlign>): List<PronIssue>
    /** 유형별 한국어 팁과 짝 단어 (리포트·드릴 화면용) */
    fun tip(type: PronIssueType): String
    fun pairs(type: PronIssueType): List<MinimalPair>
}

interface FluencyMeter {
    fun measure(timing: SpeechTiming, heard: String): FluencyMetrics
}

enum class SpeechLang(val tag: String) { KO("ko-KR"), EN("en-US") }

enum class SpeechRate { NORMAL, SLOW }

enum class SpeakOutcome { DONE, STOPPED, FAILED }

sealed interface ListenOutcome {
    data class Heard(val text: String, val timing: SpeechTiming) : ListenOutcome
    /** 시간 안에 말이 없음 */
    data object Silence : ListenOutcome
    /** stopAll() 로 멈춤 */
    data object Stopped : ListenOutcome
    /** 엔진 오류 (code = SDK ErrorType 이름 등). reasonKo 는 화면 표시용 */
    data class Failed(val code: String, val reasonKo: String) : ListenOutcome
}

/** 음성 엔진 상태 (설정·데모 화면 표시용) */
data class SpeechStatus(
    val sttReady: Boolean = false,
    /** Pleos STT 가 en-US 를 받아들였나 (null = 아직 모름, false = UNAVAILABLE_LANGUAGE) */
    val sttEnglish: Boolean? = null,
    val ttsKoEngine: String = "",
    val ttsEnEngine: String = "",
    val lastErrorKo: String? = null,
)

/**
 * TTS + STT 포트. 차량 마이크 음성은 반드시 Pleos SpeechToText SDK 로만 (RECORD_AUDIO·AudioRecord·SpeechRecognizer 금지).
 * 취소 규칙: speak/listen 을 부른 코루틴이 취소되면 구현은 100ms 안에 TTS/STT 를 멈추고 CancellationException 을 그대로 던진다.
 * 한 번에 하나만: speak 중 새 speak → 앞의 것 STOPPED. speak 와 listen 은 동시에 하지 않는다 (코치가 순서대로 부름).
 */
interface SpeechPort {
    val status: StateFlow<SpeechStatus>
    val speaking: StateFlow<Boolean>
    val listening: StateFlow<Boolean>
    /** 부분 인식 결과 (듣는 중에만, 끝나면 "") */
    val partial: StateFlow<String>
    /** 말하고 끝날 때까지 기다린다. KO = Pleos TTS(대체: Android TTS ko-KR), EN = 명세 §5.6 엔진 선택 */
    suspend fun speak(text: String, lang: SpeechLang, rate: SpeechRate = SpeechRate.NORMAL): SpeakOutcome
    /** 한 번 듣는다. timeoutMs 안에 최종 결과가 없으면 부분 결과(있으면 Heard) 또는 Silence */
    suspend fun listen(lang: SpeechLang, timeoutMs: Long): ListenOutcome
    /** 데모·타이핑 답: 듣는 중이면 즉시 Heard 로 끝내고, 아니면 15초 동안 다음 listen 에 바로 돌려준다 (timing.injected = true) */
    fun inject(text: String)
    /** 말하기·듣기 모두 즉시 멈춤 (CRITICAL) */
    fun stopAll()
}

/** 오디오 환경 — 내비 안내 음성 · 오디오 포커스 (AudioManager 표준 API) */
interface AudioEnvironment {
    /** 다른 앱의 내비 안내(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE) 재생 중 */
    val navPromptActive: StateFlow<Boolean>
    /** 우리 앱이 포커스를 일시적으로 잃음 (손실 후 최소 3초, 다시 얻으면 false) */
    val focusLost: StateFlow<Boolean>
}

// ───────────────────────── 운전 (drive 담당) ─────────────────────────

/** 운전 신호 — Pleos 차량 + 시뮬레이터 + 오디오 환경을 합친 것. atMs 를 최소 500ms 마다 갱신 (하트비트) */
interface SignalSource {
    val signals: StateFlow<DrivingSignals>
}

/** 운전 부하 엔진 (명세 §4.2 규칙) */
interface DrivingLoadEngine {
    /** 순수: 신호 하나로 즉시 판단 (히스테리시스 없음, rawLevel == level) */
    fun instant(signals: DrivingSignals): LoadAssessment
    /** 상태 있음: 즉시 올리고, 낮은 단계가 4초 안정돼야 내림 (CRITICAL 은 원인 해소 후 3초 뒤 FOCUS 로). 시간은 signals.atMs */
    fun update(signals: DrivingSignals): LoadAssessment
    fun reset()
}

/** 부하 단계별 학습 정책 (명세 §4.3 표) */
interface LoadPolicy {
    fun rules(level: LoadLevel): LoadRules
    /** level ≤ item.maxLoad && skill 허용 && 단어 수 ≤ maxAnswerWords && newQuestions */
    fun allows(item: LearningItem, level: LoadLevel): Boolean
}

/** 방금 겪은 상황 감지 (트리거별 주행당 1회, 같은 트리거 10분 쿨다운) */
interface ContextHookDetector {
    fun update(signals: DrivingSignals, load: LoadAssessment): List<HookEvent>
    /** 새 주행 시작 */
    fun reset()
}

/** 출퇴근 판단 — 경로 방향(집→회사 = 출근, 회사→집 = 퇴근)이 우선, 시각은 보조 */
interface CommuteDetector {
    fun detect(signals: DrivingSignals, pattern: CommutePattern, nowMs: Long): CommuteContext
    /** 출발 시각(분)을 패턴에 더한다 (좌표 저장 없음) */
    fun learn(pattern: CommutePattern, context: CommuteContext, departedMs: Long): CommutePattern
}

/** 차량 피드백 — 앰비언트 라이트 펄스(Vehicle SDK Light) · HUD 힌트 */
interface VehicleFeedback {
    /** 실제 차량 앰비언트 라이트를 바꿀 수 있나 (에뮬레이터·권한 없음 = false → 화면 표시만) */
    val ambientSupported: StateFlow<Boolean>
    val lastPulse: StateFlow<AmbientPulse?>
    val hudHint: StateFlow<String?>
    /** 1초 색 펄스 후 원래 색 복원. 코치는 부하 ≤ NORMAL · 설정 켬일 때만 부른다 */
    fun pulse(kind: PulseKind)
    fun showHudHint(text: String?)
}

// ───────────────────────── 효과음 ─────────────────────────

/**
 * 효과음 종류. 코치가 채점 직후·연속 정답·레벨 업·주차 대시보드에서 [SoundPort.play] 로 울린다.
 * 소리 파일은 우리가 직접 합성한 짧은 WAV (res/raw) — 정답 0.6초 이하, 세션 완료 2.5초 이하.
 */
enum class SoundCue {
    /** 정답 — 밝은 두세 음 마림바/벨 아르페지오 */
    CORRECT,
    /** 거의 맞음 — 부드러운 중립 톤 */
    PARTIAL,
    /** 오답 — 낮고 순한 한 음 (거슬리지 않게) */
    WRONG,
    /** 연속 정답 5개마다 */
    STREAK,
    LEVEL_UP,
    /** 주차 대시보드가 뜰 때의 짧은 징글 */
    SESSION_COMPLETE,
}

/**
 * 효과음 재생 포트 — 구현은 SoundPool + USAGE_ASSISTANCE_SONIFICATION (오디오 포커스를 잡지 않아 TTS 를 끊지 않는다).
 * 코치는 설정 '효과음'이 켜져 있고 부하가 CRITICAL 이 아닐 때만 부른다. 메인 스레드에서 불리며 즉시 반환해야 한다.
 */
interface SoundPort {
    fun play(cue: SoundCue)
}

/** 소리 없는 구현 (테스트·대체) */
object NoSound : SoundPort {
    override fun play(cue: SoundCue) = Unit
}

/** 핸들 미디어 버튼 — Activity 키 이벤트 + android.media.session.MediaSession */
interface MediaKeyPort {
    val keys: SharedFlow<MediaKey>
    /** MainActivity.onKeyDown/onKeyUp 에서 부른다. 미디어 키를 처리했으면 true */
    fun onKeyEvent(keyCode: Int, down: Boolean): Boolean
    /** 데모 주입 */
    fun inject(key: MediaKey)
    /** 세션 중에만 MediaSession 을 활성화 (핸들 버튼이 우리 앱으로 오게) */
    fun setActive(active: Boolean)
}

/**
 * 데모 제어 (시뮬레이터). 디버그 빌드 또는 ADB 켜짐일 때만 화면·adb 에 노출.
 * scenario != null 인 동안 신호 원천은 시뮬레이터, null 이면 실차(Pleos 연결 시) 또는 집 앞 정차 시뮬레이터.
 */
interface DemoControl {
    val scenario: StateFlow<DemoScenario?>
    /** 시뮬레이터 경로 진행 배속 (1 = 실시간) */
    val timeScale: StateFlow<Float>
    fun play(scenario: DemoScenario)
    fun inject(signal: DemoSignal)
    fun setTimeScale(scale: Float)
    /** 시나리오 끝 → scenario = null (실차 신호로 돌아감, 없으면 집 앞 정차) */
    fun stop()
}

// ───────────────────────── 코치 (coach 담당) — UI 가 쓰는 API ─────────────────────────

/**
 * 영어 코치 — 세션 상태 기계 (명세 §6). UI 는 [state] 만 그리고 아래 함수만 부른다.
 * 모든 함수는 메인 스레드에서 불리며 즉시 반환한다 (긴 일은 코치 스코프에서).
 */
interface EnglishCoach {
    val state: StateFlow<EnglishUiState>

    /** 앱 시작 때 한 번: 저장소 로드, 신호·미디어 키 구독 (여러 번 불러도 안전) */
    fun start()
    fun release()

    // 첫 실행
    fun completeOnboarding(track: Track, goal: DailyGoal)
    /** 정차 중에만. 아니면 토스트 "정차 후 이용할 수 있어요" */
    fun startPlacement()
    fun choosePlacementAnswer(index: Int)
    /** 레벨 테스트 없이 직접 레벨 고르기 */
    fun skipPlacement(level: Level)
    /** 결과 화면의 "시작하기" → 레벨 확정, IDLE */
    fun finishPlacement()

    // 세션
    fun startSession(request: SessionRequest = SessionRequest()): ActionResult
    /** 세션 끝 → SUMMARY */
    fun stopSession()
    fun dismissSummary()
    fun pause()
    fun resume()
    fun onCommand(command: LearnerCommand)
    /** 타이핑·adb hear·데모 주입 답 — STT 결과와 같은 길로 (SpeechPort.inject) */
    fun onAnswerText(text: String)
    /** 정차 화면 보기 탭 */
    fun chooseOption(index: Int)
    fun onMediaKey(key: MediaKey)
    /** 데모 패널: 현재 문항 기준 정답/부분/오답/L1 오류 문장을 만들어 onAnswerText 로 */
    fun injectDemoAnswer(kind: DemoAnswerKind)

    // 정차 전용
    fun startReview(): ActionResult
    fun startPronDrill(type: PronIssueType): ActionResult

    // 퇴근길 학습 질문 (명세 §7.1) — 화면 카드의 큰 버튼 두 개 (할게요 / 오늘은 쉴게요). 음성 대답도 같은 길로 온다
    fun answerEveningAsk(yes: Boolean)

    // 주차 대시보드 (명세 §7.2)
    /** 대시보드 닫기 ('홈으로') — 결과([EnglishUiState.dashboard])는 '지난 학습 결과'용으로 남는다 */
    fun dismissDashboard()
    /** 홈의 '지난 학습 결과' — 마지막 세션 결과를 다시 띄운다 (정차 중에만, 결과가 없으면 토스트) */
    fun showDashboard(): ActionResult
    /** 대시보드의 '틀린 표현 복습' — 대시보드를 닫고 그 세션에서 틀린 표현으로 복습 세션 시작 (없으면 일반 복습) */
    fun reviewMistakes(): ActionResult

    // 제안 카드
    fun acceptSuggestion(id: String)
    fun dismissSuggestion(id: String)

    // Gleo · 설정 · 개인정보
    /** text=null 이면 지금(또는 방금) 문항의 모범 답안을 저장 */
    fun saveExpression(text: String? = null): ActionResult
    /** 세션 중이면 복습 문항을 앞으로, 아니면 복습 세션 시작 */
    fun reviewNow(): ActionResult
    fun updateSettings(transform: (LearnerSettings) -> LearnerSettings)
    fun changeTrack(track: Track)
    fun changeGoal(goal: DailyGoal)
    /** 모든 학습 기록 삭제 → 온보딩부터 */
    fun eraseAllData()
}

/**
 * 코치 의존성 묶음 — 통합(AppContainer) 이 각 모듈 구현을 넣어 DefaultEnglishCoach(deps, scope) 를 만든다.
 * 코치 테스트는 가짜 구현으로 채운다.
 */
data class CoachDeps(
    val clock: Clock,
    val content: ContentSource,
    val repository: LearnerRepository,
    val scheduler: Scheduler,
    val placement: PlacementEngine,
    val progression: Progression,
    val planner: SessionPlanner,
    val judge: AnswerJudge,
    val commands: CommandParser,
    val pronunciation: PronunciationAnalyzer,
    val fluency: FluencyMeter,
    val speech: SpeechPort,
    val signals: SignalSource,
    val loadEngine: DrivingLoadEngine,
    val policy: LoadPolicy,
    val hooks: ContextHookDetector,
    val commute: CommuteDetector,
    val feedback: VehicleFeedback,
    val mediaKeys: MediaKeyPort,
    val map: CommuteMap,
    val demoEnabled: Boolean,
    /** 효과음 (기본: 소리 없음 — 통합이 SoundPool 구현을 넣는다) */
    val sound: SoundPort = NoSound,
)
