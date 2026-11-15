package com.cartutor.engineglish.english.core

/*
 * 엔진글리시 — 공유 도메인 모델 (학습 · 채점 · 진행 · 학습자 상태).
 * 순수 Kotlin. 안드로이드 의존 금지 (JVM 단위 테스트 대상).
 * 명세: docs/ENGLISH_APP_SPEC.md — 필드를 지우거나 이름을 바꾸지 말 것 (아키텍트 소유).
 */

// ───────────────────────── 커리큘럼 ─────────────────────────

/** 학습 트랙 (목적) */
enum class Track(val ko: String, val en: String) {
    BUSINESS("비즈니스", "Business"),
    DAILY("일상", "Daily"),
    TRAVEL("여행", "Travel"),
    TOEIC_SPEAKING("토익 스피킹", "TOEIC Speaking");

    companion object {
        /** "business" · "BUSINESS" · "toeic speaking" · "토익" 등 느슨한 이름 → Track (Gleo 파라미터용) */
        fun parse(raw: String?): Track? {
            val s = raw?.trim()?.lowercase()?.replace(' ', '_')?.replace('-', '_') ?: return null
            if (s.isEmpty()) return null
            entries.firstOrNull { it.name.lowercase() == s }?.let { return it }
            return when {
                s.startsWith("biz") || "business" in s || "비즈" in s -> BUSINESS
                "daily" in s || "일상" in s -> DAILY
                "travel" in s || "trip" in s || "여행" in s -> TRAVEL
                "toeic" in s || "토익" in s -> TOEIC_SPEAKING
                else -> null
            }
        }
    }
}

enum class Level(val ko: String) {
    BEGINNER("초급"),
    INTERMEDIATE("중급"),
    ADVANCED("고급");

    val next: Level? get() = entries.getOrNull(ordinal + 1)
    val prev: Level? get() = entries.getOrNull(ordinal - 1)
}

/**
 * 문제 유형.
 * @param defaultListenSec 답을 듣는 기본 시간(초). 실제 값 = min(item.listenSec ?: 이 값, LoadRules.maxListenSec)
 */
enum class Skill(val ko: String, val defaultListenSec: Int) {
    /** 한국어 뜻 → 영어 단어 */
    VOCAB("단어 떠올리기", 7),
    /** 한국어 상황/뜻 → 짧은 영어 표현(관용 표현) */
    EXPRESSION("표현 말하기", 10),
    /** 한국어 문장 → 영어 문장 (정답 여러 개 허용) */
    SENTENCE_BUILD("문장 만들기", 12),
    /** 영어 문장 듣고 그대로 따라 말하기 */
    LISTEN_REPEAT("듣고 따라 말하기", 10),
    /** 토익 스피킹식 열린 답변 — 필수 키워드 + 길이 + 문법 휴리스틱으로 채점 */
    TOEIC_QA("토익 스피킹 답변", 45),
    /** 한국어 화자가 헷갈리는 발음 짝 연습 (right / light) */
    MINIMAL_PAIR("발음 짝 연습", 6),
}

/** 하루 목표 (첫 실행 때 고름) */
enum class DailyGoal(val ko: String, val items: Int) {
    LIGHT("가볍게 · 하루 10문장", 10),
    STEADY("꾸준히 · 하루 20문장", 20),
    INTENSIVE("집중 · 하루 30문장", 30),
}

/** 트랙·레벨 안의 유닛 (10문항 묶음). unit 은 1부터 */
data class UnitKey(val track: Track, val level: Level, val unit: Int) {
    /** 저장·맵 키: "BUSINESS:BEGINNER:1" */
    val id: String get() = "${track.name}:${level.name}:$unit"

    companion object {
        fun parse(id: String): UnitKey? {
            val p = id.split(':')
            if (p.size != 3) return null
            val t = Track.entries.firstOrNull { it.name == p[0] } ?: return null
            val l = Level.entries.firstOrNull { it.name == p[1] } ?: return null
            val u = p[2].toIntOrNull() ?: return null
            return UnitKey(t, l, u)
        }
    }
}

data class UnitInfo(
    val key: UnitKey,
    val titleKo: String,
    val titleEn: String,
    /** 유닛 문항 id (보통 10개, 순서대로) */
    val itemIds: List<String>,
)

/**
 * 학습 문항 하나. content/<track>.json · context_hooks.json · pronunciation.json 에서 온다.
 *
 * @param id 전역 고유·불변 (SRS 카드 키). 예: "biz.b1.03"
 * @param unit 1부터. 유닛에 속하지 않는 문항(상황 훅·발음 드릴)은 0
 * @param promptKo TTS(ko-KR)로 읽는 한국어 지시/문제. 운전 중 화면에도 크게 표시
 * @param promptEn 영어로 들려줄 문장 (LISTEN_REPEAT 대상 문장, TOEIC 질문, MINIMAL_PAIR 목표 단어). 없으면 null
 * @param answers 허용 정답 (원문 그대로, 첫 번째 = 모범 답안). TOEIC_QA 는 모범 답안 예시
 * @param keywords 필수 내용어 (TOEIC_QA 채점 기준, 나머지 유형은 부분 점수·힌트 근거). 소문자 원형
 * @param hintWord HUD 에 띄울 한 단어 (힌트 명령 때도 사용)
 * @param meaningKo 목표 표현의 한국어 뜻 ("뜻" 명령)
 * @param explanationKo 짧은 한국어 해설 (정차 화면 · 오답 뒤)
 * @param maxLoad 이 문항을 낼 수 있는 가장 높은 운전 부하 (PARKED·RELAXED·NORMAL 중 하나)
 * @param distractors 정차 화면 보기 탭용 오답 보기 (2~3개)
 * @param contextHook 이 문항이 '방금 그 상황' 표현이면 그 트리거
 * @param minWords TOEIC_QA 최소 단어 수 (그 외 0)
 * @param listenSec 듣기 시간(초) 재정의. null 이면 [Skill.defaultListenSec]
 * @param contrast MINIMAL_PAIR 의 헷갈리는 짝 단어 ("light")
 * @param pronFocus MINIMAL_PAIR 가 겨냥하는 발음 문제
 */
data class LearningItem(
    val id: String,
    val track: Track,
    val level: Level,
    val unit: Int,
    val skill: Skill,
    val promptKo: String,
    val promptEn: String? = null,
    val answers: List<String>,
    val keywords: List<String> = emptyList(),
    val hintWord: String? = null,
    val meaningKo: String,
    val explanationKo: String? = null,
    val maxLoad: LoadLevel = LoadLevel.NORMAL,
    val tags: List<String> = emptyList(),
    val distractors: List<String> = emptyList(),
    val contextHook: HookTrigger? = null,
    val minWords: Int = 0,
    val listenSec: Int? = null,
    val contrast: String? = null,
    val pronFocus: PronIssueType? = null,
) {
    val modelAnswer: String get() = answers.firstOrNull().orEmpty()
    /** 모범 답안 단어 수 — 부하별 최대 단어 수 판단에 쓴다 */
    val answerWords: Int get() = wordCount(modelAnswer)
    val listenSeconds: Int get() = listenSec ?: skill.defaultListenSec
    val unitKey: UnitKey? get() = if (unit > 0) UnitKey(track, level, unit) else null
}

/** 상황 훅 콘텐츠 — "비가 쏟아지네요. 이럴 땐 이렇게 말해요." + 문항들 */
data class ContextHookContent(
    val trigger: HookTrigger,
    val introKo: String,
    val items: List<LearningItem>,
)

// ───────────────────────── 레벨 테스트 ─────────────────────────

/**
 * 레벨 테스트 문항 (정차 중, 화면 보기 선택).
 * @param track null = 모든 트랙 공통
 * @param questionEn 영어 지문/빈칸 문장 (없으면 null)
 * @param audioEn TTS 로 들려줄 영어 (듣기 문항), 없으면 null
 */
data class PlacementItem(
    val id: String,
    val track: Track?,
    val level: Level,
    val skill: Skill,
    val questionKo: String,
    val questionEn: String? = null,
    val choices: List<String>,
    val answerIndex: Int,
    val explanationKo: String? = null,
    val audioEn: String? = null,
)

data class PlacementAnswer(
    val itemId: String,
    val level: Level,
    val choiceIndex: Int,
    val correct: Boolean,
    val latencyMs: Long,
)

/** 레벨 테스트 진행 상태 (순수 값 — PlacementEngine 이 다음 상태를 만든다) */
data class PlacementState(
    val track: Track,
    val total: Int,
    /** 지금 난이도 (계단식: 맞히면 올라가고 틀리면 내려감) */
    val currentLevel: Level,
    val current: PlacementItem?,
    val answers: List<PlacementAnswer> = emptyList(),
    val startedMs: Long = 0L,
    /** 지금 문항을 보여 준 시각 — 응답 지연 계산용 */
    val currentShownMs: Long = startedMs,
) {
    val index: Int get() = answers.size
    val finished: Boolean get() = current == null || answers.size >= total
}

data class PlacementResult(
    val level: Level,
    val correct: Int,
    val total: Int,
    /** 레벨별 (맞힌 수, 푼 수) */
    val perLevel: Map<Level, Pair<Int, Int>>,
    /** "중급부터 시작할게요. 기본 표현은 탄탄해요." 같은 1~2문장 */
    val summaryKo: String,
)

// ───────────────────────── 채점 ─────────────────────────

enum class Verdict(val ko: String) {
    CORRECT("정답"),
    PARTIAL("거의 맞음"),
    WRONG("오답"),
    NO_ANSWER("응답 없음"),
}

enum class AlignOp { MATCH, SUB, DEL, INS }

/** 단어 정렬 한 칸. DEL = 정답 단어가 빠짐(heard=null), INS = 불필요한 단어(expected=null) */
data class WordAlign(val expected: String?, val heard: String?, val op: AlignOp)

/**
 * 채점 결과 (AnswerJudge 가 만든다).
 * @param score 0..100
 * @param heard STT 원문 그대로
 * @param normalized 정규화된 들린 문장
 * @param matchedAnswer 가장 가까운 허용 정답 (원문 형태), 없으면 null
 * @param feedbackKo TTS 로 읽을 짧은 한국어 한 문장 ("거의 맞았어요. 'postpone'이 빠졌어요.")
 */
data class JudgeResult(
    val verdict: Verdict,
    val score: Int,
    val heard: String,
    val normalized: String,
    val matchedAnswer: String?,
    val alignment: List<WordAlign> = emptyList(),
    val missingKeywords: List<String> = emptyList(),
    val pronIssues: List<PronIssue> = emptyList(),
    val feedbackKo: String,
    val grammarNotesKo: List<String> = emptyList(),
)

/** 한국어 화자 L1 발음 문제 */
enum class PronIssueType(val ko: String, val example: String) {
    R_L("R / L 구분", "right ↔ light"),
    F_P("F / P 구분", "fan ↔ pan"),
    V_B("V / B 구분", "very ↔ berry"),
    TH_S("TH / S 구분", "think ↔ sink"),
    TH_D("TH / D 구분", "they ↔ day"),
    Z_J("Z / J 구분", "zoo ↔ joo"),
    SH_S("SH / S 구분", "she ↔ see"),
    FINAL_CONSONANT_DROP("끝소리 탈락", "book ↔ boo"),
}

data class MinimalPair(val target: String, val contrast: String)

/**
 * 발음 문제 하나 (정렬의 단어 치환에서 추정).
 * @param confidence 0..1 — STT 오인식일 수도 있으므로 피드백은 "…로 들렸어요" 톤
 * @param tipKo 한국어 교정 팁 한 문장
 */
data class PronIssue(
    val type: PronIssueType,
    val expectedWord: String,
    val heardWord: String,
    val confidence: Float,
    val tipKo: String,
    val drill: MinimalPair? = null,
)

// ───────────────────────── 말하기 시간 · 유창성 ─────────────────────────

data class RmsSample(val atMs: Long, val rms: Double)

/**
 * 한 번의 듣기에서 측정한 시간 (모두 Clock.nowMs 기준 ms).
 * @param listenStartMs 듣기 준비 완료 (STT onReady) 시각
 * @param speechStartMs 첫 발화 (EPD on / 첫 부분 결과 / RMS 상승) — 없으면 null
 * @param speechEndMs 발화 끝 (EPD off / 마지막 부분 결과)
 * @param finalMs 최종 결과 도착 시각
 * @param injected 데모·타이핑 주입 결과 (시간은 추정치)
 */
data class SpeechTiming(
    val listenStartMs: Long,
    val speechStartMs: Long? = null,
    val speechEndMs: Long? = null,
    val finalMs: Long,
    val rms: List<RmsSample> = emptyList(),
    val injected: Boolean = false,
)

enum class FluencyRating(val ko: String) { SMOOTH("매끄러워요"), OK("괜찮아요"), HESITANT("머뭇거렸어요") }

data class FluencyMetrics(
    /** 듣기 시작 → 첫 발화 */
    val latencyMs: Long?,
    /** 첫 발화 → 발화 끝 */
    val speechMs: Long?,
    val wordsPerMinute: Int?,
    val pauseCount: Int,
    val longestPauseMs: Long,
    val rating: FluencyRating,
    /** true = 주입 답변 등 추정치 */
    val estimated: Boolean,
    /** "1.2초 만에 답했어요" 같은 짧은 한국어 */
    val noteKo: String,
)

// ───────────────────────── 음성 명령 ─────────────────────────

/** 학습자 명령 (영어·한국어 음성, 핸들 버튼, 화면 버튼) */
enum class LearnerCommand(val ko: String) {
    REPEAT("다시"),
    SLOWER("천천히"),
    SKIP("넘기기"),
    HINT("힌트"),
    MEANING("뜻"),
    PAUSE("잠깐"),
    RESUME("계속"),
    DONT_KNOW("모르겠어요"),
    END_SESSION("그만"),
    YES("네"),
    NO("아니요"),
}

/** 명령 해석 문맥: ANSWER = 답을 듣는 중(명령 문구가 발화 전체일 때만), CONFIRM = 예/아니요 질문 */
enum class ListenContext { ANSWER, CONFIRM }

// ───────────────────────── 간격 반복 (SRS) ─────────────────────────

/** SRS 등급. EASY = 정답 + 응답 지연 1.5초 미만 */
enum class Grade(val ko: String, val quality: Int) {
    AGAIN("다시", 0),
    HARD("어려움", 3),
    GOOD("좋음", 4),
    EASY("쉬움", 5),
}

/** 문항별 카드 상태 (SM-2 계열) */
data class CardState(
    val itemId: String,
    val reps: Int = 0,
    val lapses: Int = 0,
    val ease: Double = 2.5,
    val intervalDays: Double = 0.0,
    val dueAtMs: Long = 0L,
    val lastReviewedMs: Long = 0L,
    val lastGrade: Grade? = null,
    val introducedMs: Long = 0L,
)

// ───────────────────────── 출퇴근 · 세션 ─────────────────────────

enum class CommuteSlot(val ko: String) { MORNING("출근길"), EVENING("퇴근길"), OTHER("주행") }

/** 시간대별 학습 방식 (설정에서 바꿀 수 있다) */
enum class SlotMode(val ko: String) { NEW_EXPRESSIONS("새 표현"), REVIEW("맞춤 복습"), MIXED("섞어서") }

enum class SessionOrigin { USER, AUTO_COMMUTE, GLEO, DEMO }

/** 세션 시작 요청. null 필드는 프로필·설정·출퇴근 판단으로 채운다 */
data class SessionRequest(
    val track: Track? = null,
    val mode: SlotMode? = null,
    val origin: SessionOrigin = SessionOrigin.USER,
    /** 복습만 (REVIEW_NOW · 복습 화면) */
    val reviewOnly: Boolean = false,
    /** 발음 드릴만 */
    val pronDrill: PronIssueType? = null,
    /** 이 문항들만 차례로 (주차 대시보드의 '틀린 표현 복습'). 비어 있으면 계획기가 고른다 */
    val itemIds: List<String> = emptyList(),
)

enum class PlanKind(val ko: String) {
    NEW("새 표현"),
    REVIEW("복습"),
    MISTAKE("오늘 틀린 표현"),
    HOOK("방금 그 상황"),
    DRILL("발음 연습"),
}

data class PlannedItem(val itemId: String, val kind: PlanKind)

/**
 * 세션 계획 (SessionPlanner 가 만든다).
 * @param titleKo "출근길 32분 → 새 표현 6개 + 복습 10개"
 * @param budgetSec 학습에 쓸 예상 시간(초)
 * @param wrapUpItemId 세션 결과에 보여 줄 '오늘의 한 문장' 기본 후보 (코치가 그 세션에서 가장 약했던 문장으로 바꿀 수 있음)
 */
data class SessionPlan(
    val id: String,
    val slot: CommuteSlot,
    val mode: SlotMode,
    val track: Track,
    val level: Level,
    val routeMinutes: Int?,
    val budgetSec: Int,
    val items: List<PlannedItem>,
    val newCount: Int,
    val reviewCount: Int,
    val titleKo: String,
    val wrapUpItemId: String?,
    val createdMs: Long,
)

data class PlanRequest(
    val state: LearnerState,
    val slot: CommuteSlot,
    val mode: SlotMode,
    val track: Track,
    val level: Level,
    /** 경로 남은 시간(초). 경로 없음/정차면 null → 기본 20분 예산 */
    val routeRemainingSec: Int?,
    val parked: Boolean,
    val nowMs: Long,
    val reviewOnly: Boolean = false,
    val pronDrill: PronIssueType? = null,
    /** 이미 이번 세션에 낸 문항 (extend 때 제외) */
    val exclude: Set<String> = emptySet(),
)

// ───────────────────────── 학습자 상태 (기기 안 암호화 저장) ─────────────────────────

data class LearnerProfile(
    val track: Track,
    val goal: DailyGoal,
    val level: Level,
    val placementDone: Boolean = false,
    /** 레벨 테스트 맞힌 수 (12문항 중) */
    val placementCorrect: Int? = null,
    /** 프로필 생성일 (KST epochDay) */
    val createdDay: Long,
)

/** 설정 '퇴근길 학습' — 퇴근길이 감지됐을 때 */
enum class EveningStudy(val ko: String) {
    /** 먼저 물어본다 ("퇴근길에도 영어 복습할까요?") — 기본 */
    ASK("물어보기"),
    /** 묻지 않고 바로 시작 */
    ALWAYS("항상 시작"),
    /** 묻지도 시작하지도 않는다 */
    NEVER("안 함"),
}

data class LearnerSettings(
    val morningMode: SlotMode = SlotMode.NEW_EXPRESSIONS,
    val eveningMode: SlotMode = SlotMode.REVIEW,
    val otherMode: SlotMode = SlotMode.MIXED,
    /** 출근길이 감지되면 세션을 자동 시작 (음성 안내 후). 퇴근길은 [eveningStudy] 를 따른다 */
    val autoStartOnCommute: Boolean = true,
    /** 퇴근길 학습: 물어보기(기본) · 항상 시작 · 안 함 */
    val eveningStudy: EveningStudy = EveningStudy.ASK,
    /** 효과음 (정답·오답·연속 정답·레벨 업·세션 완료) */
    val soundEffects: Boolean = true,
    /** 정답/오답 앰비언트 라이트 1초 펄스 */
    val ambientPulse: Boolean = true,
    /** 앞유리 HUD 힌트 한 단어 */
    val hudHint: Boolean = true,
    /** 영어 음성 속도 (Android TTS 대체 엔진에만 적용, 0.75~1.1) */
    val englishRate: Float = 1.0f,
    /** 방금 그 상황 표현 */
    val contextHooks: Boolean = true,
    /** 시뮬레이터 경로를 실제 차량 내비에도 반영 — 기본 꺼짐 (실차 내비 오염 방지) */
    val mirrorSimRouteToCar: Boolean = false,
) {
    fun modeFor(slot: CommuteSlot): SlotMode = when (slot) {
        CommuteSlot.MORNING -> morningMode
        CommuteSlot.EVENING -> eveningMode
        CommuteSlot.OTHER -> otherMode
    }
}

/** 하루 통계 (KST epochDay 기준) */
data class DayStat(
    val day: Long,
    val answered: Int = 0,
    val correct: Int = 0,
    val partial: Int = 0,
    val newLearned: Int = 0,
    val reviewed: Int = 0,
    val xp: Int = 0,
    val driveMinutes: Int = 0,
)

/** 유닛 기록 — scores: itemId → 최근 채점 점수 (CORRECT 1.0 · PARTIAL 0.5 · 그 외 0.0) */
data class UnitRecord(
    val unitId: String,
    val scores: Map<String, Float> = emptyMap(),
    val passed: Boolean = false,
    val passedAtMs: Long? = null,
)

data class ProgressState(
    val xp: Int = 0,
    val streakDays: Int = 0,
    val bestStreak: Int = 0,
    val lastActiveDay: Long? = null,
    /** 최근 30일, day 오름차순 */
    val days: List<DayStat> = emptyList(),
    /** key = UnitKey.id */
    val units: Map<String, UnitRecord> = emptyMap(),
    val totalAnswered: Int = 0,
    val totalCorrect: Int = 0,
)

data class PronIssueRecord(val type: PronIssueType, val expected: String, val heard: String, val atMs: Long)

/** 발음 프로필 — counts: 유형별 누적 횟수, recent: 최근 20개 */
data class PronProfile(
    val counts: Map<PronIssueType, Int> = emptyMap(),
    val recent: List<PronIssueRecord> = emptyList(),
)

/** 유창성 일별 평균 (최근 30일) */
data class FluencyDay(val day: Long, val samples: Int, val avgLatencyMs: Long, val avgWpm: Int, val avgPauses: Float)

/** 출퇴근 패턴 — 좌표는 저장하지 않고 출발 시각(분)만 (최근 10회) */
data class CommutePattern(
    val morningDepartures: List<Int> = emptyList(),
    val eveningDepartures: List<Int> = emptyList(),
    val trips: Int = 0,
)

data class SavedExpression(val text: String, val itemId: String?, val savedMs: Long)

/** 틀리거나 부분 정답이었던 문항 (최근 7일) — 퇴근길 맞춤 복습 재료 */
data class Mistake(val itemId: String, val day: Long, val verdict: Verdict, val atMs: Long)

data class SessionRecord(
    val id: String,
    val startedMs: Long,
    val endedMs: Long,
    val slot: CommuteSlot,
    val mode: SlotMode,
    val track: Track,
    val answered: Int,
    val correct: Int,
    val partial: Int,
    val interrupted: Int,
    val xp: Int,
)

/**
 * 학습자 상태 전체 — LearnerRepository 가 SecureVault(AES-GCM, Android Keystore)로 암호화 저장한다.
 * 서버로 보내지 않는다. 로그에 남기지 않는다.
 */
data class LearnerState(
    val schema: Int = 1,
    /** null = 첫 실행 (온보딩 필요) */
    val profile: LearnerProfile? = null,
    val settings: LearnerSettings = LearnerSettings(),
    val progress: ProgressState = ProgressState(),
    /** key = itemId */
    val cards: Map<String, CardState> = emptyMap(),
    val pron: PronProfile = PronProfile(),
    val fluency: List<FluencyDay> = emptyList(),
    val commute: CommutePattern = CommutePattern(),
    val saved: List<SavedExpression> = emptyList(),
    val mistakes: List<Mistake> = emptyList(),
    /** 최근 30개 */
    val sessions: List<SessionRecord> = emptyList(),
)

// ───────────────────────── 진행 이벤트 · 요약 ─────────────────────────

/**
 * 채점된 답 하나 (Progression.record 입력).
 * @param grade 코치가 시도 규칙까지 반영해 정한 최종 등급
 * @param isNew 이 문항을 처음 보는가 (카드 없음)
 * @param driving 주행 중 답했는가
 */
data class AnswerOutcome(
    val item: LearningItem,
    val result: JudgeResult,
    val grade: Grade,
    val isNew: Boolean,
    val fluency: FluencyMetrics?,
    val driving: Boolean,
    val sessionId: String,
    val latencyMs: Long?,
)

sealed interface ProgressEvent {
    data class CheckpointPassed(val unit: UnitKey, val accuracy: Float) : ProgressEvent
    data class UnitUnlocked(val unit: UnitKey) : ProgressEvent
    data class LevelUp(val track: Track, val from: Level, val to: Level) : ProgressEvent
    data class StreakExtended(val days: Int) : ProgressEvent
    data class DailyGoalReached(val goal: Int) : ProgressEvent
}

data class ProgressUpdate(val state: LearnerState, val xpGained: Int, val events: List<ProgressEvent>)

data class UnitProgress(
    val key: UnitKey,
    val titleKo: String,
    val answered: Int,
    val total: Int,
    val accuracy: Float,
    val passed: Boolean,
    val unlocked: Boolean,
)

data class PronStat(val type: PronIssueType, val count: Int)

data class FluencySummary(
    val avgLatencyMs: Long,
    val avgWpm: Int,
    /** 지난주 대비 지연 변화(ms). 음수 = 빨라짐 */
    val latencyTrendMs: Long,
    val samples: Int,
)

/** 화면용 진행 요약 (Progression.snapshot) */
data class ProgressSnapshot(
    val track: Track? = null,
    val level: Level? = null,
    val goal: DailyGoal? = null,
    val xp: Int = 0,
    val streakDays: Int = 0,
    val todayAnswered: Int = 0,
    val todayGoal: Int = 0,
    val todayAccuracy: Float? = null,
    val dueReviews: Int = 0,
    /** reps ≥ 1 인 카드 수 */
    val learnedItems: Int = 0,
    /** 현재 트랙 전체 문항 수 */
    val totalItems: Int = 0,
    val currentUnit: UnitProgress? = null,
    /** 현재 트랙·레벨의 유닛들 */
    val units: List<UnitProgress> = emptyList(),
    /** 레벨 업까지 진행률 0..1 */
    val levelProgress: Float = 0f,
    /** 최근 7일 (빈 날은 0으로 채움), 오래된 날 → 오늘 */
    val week: List<DayStat> = emptyList(),
    /** 많은 순 최대 3개 */
    val pronTop: List<PronStat> = emptyList(),
    val fluency: FluencySummary? = null,
    val savedExpressions: Int = 0,
)

/** 개인정보 화면용 저장소 정보 */
data class StorageInfo(
    val bytes: Long,
    val files: List<String>,
    val encrypted: Boolean,
    val lastSavedMs: Long?,
)

/** 외부 진입(Gleo·버튼) 결과 — Gleo 콜백 7초 이내 응답용 */
data class ActionResult(val ok: Boolean, val messageKo: String, val messageEn: String)

// ───────────────────────── 작은 공용 함수 ─────────────────────────

/** 공백 기준 단어 수 */
fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
