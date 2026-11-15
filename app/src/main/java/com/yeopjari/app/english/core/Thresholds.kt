package com.yeopjari.app.english.core

/*
 * 옆자리 영어 — 모든 모듈이 같은 숫자를 쓰도록 모은 기준값.
 * 값을 바꾸면 명세(docs/ENGLISH_APP_SPEC.md)와 테스트도 함께 바꾼다 (아키텍트 소유).
 */

/** 운전 부하 판단 기준 (명세 §4.2) */
object LoadThresholds {
    /** 이 속도 미만이면 '멈춤' */
    const val STOPPED_SPEED_KPH = 1f

    // RELAXED
    const val RELAXED_MIN_SPEED_KPH = 60f
    /** 조건(고속·직진·이벤트 없음)이 이만큼 이어져야 RELAXED */
    const val RELAXED_STEADY_MS = 15_000L
    /** RELAXED 직진 판단: |핸들| 이하 */
    const val RELAXED_MAX_STEERING_DEG = 8f
    /** RELAXED 판단: 다음 안내 지점이 이보다 멀어야 */
    const val RELAXED_MANEUVER_CLEAR_M = 1_000
    /** RELAXED 판단: |가속도| 이하 */
    const val RELAXED_MAX_ACCEL_MPS2 = 1.0f
    /** RELAXED 판단: 단속 카메라가 이보다 멀어야 */
    const val RELAXED_CAMERA_CLEAR_M = 500
    /** 크루즈 RELAXED 최소 속도 */
    const val CRUISE_MIN_SPEED_KPH = 30f

    // FOCUS
    const val MANEUVER_FOCUS_M = 300
    const val MANEUVER_FOCUS_SEC = 8
    const val STEERING_FOCUS_DEG = 25f
    const val STEERING_RATE_FOCUS_DPS = 60f
    const val CAMERA_FOCUS_M = 200
    /** 어린이 보호구역·주의 구간: 이 거리 안이거나 구역 안 */
    const val CAUTION_FOCUS_M = 200

    // CRITICAL
    /** 감속 3.5 m/s² 초과 = 급제동 (accelMps2 <= -3.5) */
    const val HARD_DECEL_MPS2 = 3.5f
    const val OVERSPEED_MARGIN_KPH = 10

    // NORMAL 보조
    const val JAM_SPEED_KPH = 15f
    const val JAM_MIN_MS = 30_000L

    // 히스테리시스
    /** 낮은 단계가 이만큼 안정돼야 내린다 */
    const val DEESCALATE_STABLE_MS = 4_000L
    /** CRITICAL 원인이 사라진 뒤 최소 유지 시간 → 그다음 FOCUS 로 */
    const val CRITICAL_MIN_HOLD_MS = 3_000L

    /** SignalSource 하트비트 (최대 간격) */
    const val HEARTBEAT_MS = 500L
}

/** 학습 진행 기준 (명세 §3) */
object LearnThresholds {
    const val UNIT_SIZE = 10
    /** 유닛 체크포인트 통과 정확도 (CORRECT 1 · PARTIAL 0.5 평균) */
    const val CHECKPOINT_ACCURACY = 0.8f
    /** 유닛의 이 비율 이상을 풀었을 때만 체크포인트 판단 */
    const val CHECKPOINT_MIN_COVERAGE = 1.0f
    /** 현재 레벨에서 이만큼 유닛을 통과하면 레벨 업 (레벨의 유닛 수가 더 적으면 전부) */
    const val UNITS_FOR_LEVEL_UP = 3
    const val PLACEMENT_ITEMS = 12

    const val XP_CORRECT = 10
    const val XP_PARTIAL = 5
    const val XP_EASY_BONUS = 2
    const val XP_CHECKPOINT = 50
    const val XP_LEVEL_UP = 100

    /** 경로 남은 시간이 이 이하가 되면 주행 학습을 끝낸다 — 더 묻지 않고 "곧 도착해요. 오늘 학습은 여기까지 할게요." */
    const val END_BEFORE_ARRIVAL_SEC = 60
    /** 남은 시간이 이보다 짧으면 자동 시작도, 퇴근길 질문도 하지 않는다 (시작하자마자 끝나지 않게) */
    const val MIN_ROUTE_FOR_AUTO_SEC = 180
    /** 퇴근길 학습 질문: 질문을 다 말한 뒤 대답을 기다리는 시간 */
    const val EVENING_ASK_TIMEOUT_MS = 10_000L
    /** 퇴근길 학습 질문 최대 횟수 (첫 질문 + 다시 묻기 한 번) */
    const val EVENING_ASK_MAX_ATTEMPTS = 2
    /** 연속 정답이 이 수의 배수가 될 때마다 연속 정답 효과음 */
    const val STREAK_CUE_EVERY = 5
    /** 경로가 없을 때(정차·자유 주행) 세션 예산(초) */
    const val NO_ROUTE_BUDGET_SEC = 600
    /** 경로 남은 시간 중 학습 예산 비율 (부하 대기·피드백·마무리 제외). 32분 → 672초 → 새 6 + 복습 10 */
    const val ROUTE_STUDY_RATIO = 0.35f
    const val MIN_BUDGET_SEC = 180
    const val COST_NEW_SEC = 60
    const val COST_REVIEW_SEC = 30
    /** 예산 중 새 표현 몫 (모드별) */
    const val NEW_SHARE_NEW_MODE = 0.55f
    const val NEW_SHARE_MIXED = 0.4f
    const val NEW_SHARE_REVIEW_MODE = 0.2f
    /** REVIEW 모드 새 표현 상한 */
    const val MAX_NEW_IN_REVIEW_MODE = 2
    const val MAX_NEW_PER_SESSION = 8
    const val MAX_REVIEW_PER_SESSION = 30
    /** 주행 세션에서 maxLoad=NORMAL 문항의 최소 비율 */
    const val DRIVING_NORMAL_ITEM_SHARE = 0.7f

    const val MISTAKE_KEEP_DAYS = 7
    const val SESSION_KEEP = 30
    const val DAY_STAT_KEEP = 30
    const val PRON_RECENT_KEEP = 20
    /** 같은 상황 훅 쿨다운 */
    const val HOOK_COOLDOWN_MS = 10 * 60 * 1000L
}

/** 말하기 · 채점 기준 (명세 §5) */
object SpeechThresholds {
    /** EASY = 정답 + 이 지연 미만 */
    const val EASY_LATENCY_MS = 1_500L
    /** FOCUS 진입 시 남은 TTS 가 이보다 짧으면 끝까지 말한다 */
    const val FOCUS_FINISH_TTS_MS = 2_000L
    /** 판정 점수 경계 (EXPRESSION · SENTENCE_BUILD) */
    const val CORRECT_SCORE = 85
    const val PARTIAL_SCORE = 50
    const val LISTEN_REPEAT_CORRECT_SCORE = 80
    const val TOEIC_CORRECT_SCORE = 70
    const val TOEIC_PARTIAL_SCORE = 40
    /** TOEIC_QA 이보다 단어가 적으면 NO_ANSWER */
    const val TOEIC_MIN_ANSWER_WORDS = 3
    /** VOCAB 변형(-s/-ed/-ing)·L1 혼동 부분 점수 */
    const val VOCAB_PARTIAL_SCORE = 70
    /** 쉼 판단: RMS 가 낮은 구간이 이보다 길면 '쉼' */
    const val PAUSE_MIN_MS = 400L
    /** 주입 답 보관 시간 */
    const val INJECT_TTL_MS = 15_000L
    /** 듣기 준비(onReady) 대기 최대 */
    const val LISTEN_READY_TIMEOUT_MS = 3_000L
    /** 문항당 최대 시도 (2번째 시도 뒤엔 정답을 들려준다) */
    const val MAX_ATTEMPTS = 2
    /** TTS 길이 추정 */
    const val KO_CHARS_PER_SEC = 7.0
    const val EN_WORDS_PER_SEC = 2.6
}

/** 상황 훅 감지 기준 (명세 §4.4) — 시간은 DrivingSignals.atMs (실시간) */
object HookThresholds {
    /** 정체가 이만큼 이어진 뒤 */
    const val JAM_MIN_MS = 20_000L
    /** 정체가 풀리고 시속 30km 이상이 이만큼 이어지면 JAM_EXIT */
    const val JAM_EXIT_CLEAR_MS = 5_000L
    const val JAM_EXIT_SPEED_KPH = 30f
    const val RAIN_MIN_MS = 10_000L
    const val CHARGING_MIN_MS = 5_000L
    const val NIGHT_MIN_MS = 60_000L
    const val CRUISE_MIN_MS = 20_000L
    const val SCHOOL_ZONE_LEFT_MS = 5_000L
    /** 회사·집 도착 몇 초 전 */
    const val ARRIVING_SEC = 300
}

/** 콘텐츠 파일 경로 (assets 기준) — content 담당이 만들고, 통합이 AssetManager 로 읽어 넘긴다 */
object ContentFiles {
    val TRACKS: Map<Track, String> = linkedMapOf(
        Track.BUSINESS to "content/business.json",
        Track.DAILY to "content/daily.json",
        Track.TRAVEL to "content/travel.json",
        Track.TOEIC_SPEAKING to "content/toeic_speaking.json",
    )
    const val PLACEMENT = "content/placement.json"
    const val HOOKS = "content/context_hooks.json"
    const val PRONUNCIATION = "content/pronunciation.json"

    val ALL: List<String> get() = TRACKS.values.toList() + listOf(PLACEMENT, HOOKS, PRONUNCIATION)
}

/** TTS 말하기 시간 추정 — FOCUS 때 '2초 안에 끝나는가' 판단에 쓴다 */
object SpeechDurations {
    fun estimateMs(text: String, lang: SpeechLang, rate: SpeechRate = SpeechRate.NORMAL): Long {
        val base = when (lang) {
            SpeechLang.KO -> text.count { !it.isWhitespace() } / SpeechThresholds.KO_CHARS_PER_SEC * 1000.0
            SpeechLang.EN -> wordCount(text) / SpeechThresholds.EN_WORDS_PER_SEC * 1000.0
        }
        val scaled = if (rate == SpeechRate.SLOW) base * 1.35 else base
        return (scaled + 250.0).toLong()
    }
}
