package com.yeopjari.app.english.coach

import com.yeopjari.app.english.core.CoachDeps
import com.yeopjari.app.english.core.CommuteContext
import com.yeopjari.app.english.core.DrivingSignals
import com.yeopjari.app.english.core.EnglishUiState
import com.yeopjari.app.english.core.JudgeResult
import com.yeopjari.app.english.core.LearnerSettings
import com.yeopjari.app.english.core.LearnerState
import com.yeopjari.app.english.core.LearningItem
import com.yeopjari.app.english.core.LoadAssessment
import com.yeopjari.app.english.core.LoadLevel
import com.yeopjari.app.english.core.LoadRules
import com.yeopjari.app.english.core.PlanKind
import com.yeopjari.app.english.core.ProgressEvent
import com.yeopjari.app.english.core.ProgressSnapshot
import com.yeopjari.app.english.core.PronIssue
import com.yeopjari.app.english.core.PronIssueType
import com.yeopjari.app.english.core.SessionPlan
import com.yeopjari.app.english.core.SessionRequest
import com.yeopjari.app.english.core.SoundCue
import com.yeopjari.app.english.core.SpeakOutcome
import com.yeopjari.app.english.core.SpeechDurations
import com.yeopjari.app.english.core.SpeechLang
import com.yeopjari.app.english.core.SpeechRate
import com.yeopjari.app.english.core.ToastUi
import com.yeopjari.app.english.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/** 지금 말하고 있는 TTS — FOCUS 때 '2초 안에 끝나는가' 판단용 */
internal class SpeakingInfo(val text: String, val lang: SpeechLang, val rate: SpeechRate, val startedMs: Long) {
    fun remainingMs(nowMs: Long): Long = SpeechDurations.estimateMs(text, lang, rate) - (nowMs - startedMs)
}

/** 코치 부품들이 함께 쓰는 상태 (모두 메인 스레드 한 곳에서만 바뀐다) */
internal class CoachContext(val deps: CoachDeps, val scope: CoroutineScope) {
    val ui = MutableStateFlow(EnglishUiState(demoEnabled = deps.demoEnabled))
    val load = MutableStateFlow(LoadAssessment())
    /** 기다리는 턴을 깨우는 신호 (부하·훅 변화) */
    val wake = MutableStateFlow(0L)
    var signals: DrivingSignals = deps.signals.signals.value
    var speaking: SpeakingInfo? = null
    private var toastSeq = 0L

    fun now(): Long = deps.clock.nowMs()
    val learner: LearnerState get() = deps.repository.state.value
    val settings: LearnerSettings get() = learner.settings
    val level: LoadLevel get() = load.value.level
    val rules: LoadRules get() = deps.policy.rules(level)
    val parked: Boolean get() = level == LoadLevel.PARKED
    /** 말하기·듣기를 해도 되는 부하 (≤ NORMAL) */
    val loadOk: Boolean get() = level.isAtMost(LoadLevel.NORMAL)

    fun emit(f: (EnglishUiState) -> EnglishUiState) {
        ui.value = f(ui.value)
    }

    fun poke() {
        wake.value = wake.value + 1
    }

    fun toast(text: String) {
        toastSeq++
        val t = ToastUi(toastSeq, text)
        emit { it.copy(toast = t) }
    }

    /**
     * 효과음 — 설정 '효과음'이 켜져 있고 부하가 CRITICAL 이 아닐 때만 낸다 (급제동·내비 안내 중엔 어떤 소리도 보태지 않는다).
     * 오디오 포커스를 잡지 않는 SoundPool 이라 말하고 있는 TTS 를 끊지 않는다.
     * @return 실제로 냈으면 true
     */
    fun cue(cue: SoundCue): Boolean {
        if (!settings.soundEffects || level == LoadLevel.CRITICAL) return false
        deps.sound.play(cue)
        return true
    }

    /** TTS 한 번 (빈 문자열은 건너뜀). 말하는 동안 [speaking] 을 기록한다 */
    suspend fun say(text: String, lang: SpeechLang = SpeechLang.KO, rate: SpeechRate = SpeechRate.NORMAL): SpeakOutcome {
        if (text.isBlank()) return SpeakOutcome.DONE
        val info = SpeakingInfo(text, lang, rate, now())
        speaking = info
        try {
            return deps.speech.speak(text, lang, rate)
        } finally {
            if (speaking === info) speaking = null
        }
    }
}

/** 세션 대기열 한 칸 */
internal data class QueueEntry(
    val itemId: String,
    val kind: PlanKind,
    /** 두 번 침묵해서 끝으로 다시 보낸 문항 (또 침묵하면 AGAIN) */
    val requeued: Boolean = false,
    /** 처음 낼 때 한 번 말하는 안내 (상황 훅) */
    val introKo: String? = null,
)

/** 지금 묻고 있는 문항 (부하로 끊겨도 그대로 남아 다시 묻는다) */
internal class ActiveItem(val item: LearningItem, val entry: QueueEntry) {
    val kind: PlanKind get() = entry.kind
    var attempt = 1
    var silences = 0
    var hintUsed = false
    var introDone = false
    var shownMs = 0L
    var lastResult: JudgeResult? = null
}

/** 세션 하나의 실행 상태 */
internal class SessionRun(
    val id: String,
    val request: SessionRequest,
    val startedMs: Long,
    var plan: SessionPlan,
    val commute: CommuteContext,
    val startedParked: Boolean,
) {
    var track: Track = plan.track
    val queue = ArrayDeque<QueueEntry>()
    /** 이번 세션에 이미 낸 문항 (extend·훅 제외용) */
    val asked = LinkedHashSet<String>()
    var current: ActiveItem? = null
    var briefed = false
    var holding = false
    var paused = false
    var finished = false
    var waitingConfirm = false
    var sawRoute = false
    /** 이 세션 동안 한 번이라도 달렸나 (P 단이 아니었나) — 주차 대시보드 조건 */
    var drove = !startedParked
    /** 세션을 시작할 때의 진행 요약 — 대시보드의 '세션 전' 값 */
    var before: ProgressSnapshot = ProgressSnapshot()
    var answered = 0
    var correct = 0
    var partial = 0
    var skipped = 0
    var interrupted = 0
    var newDone = 0
    var reviewDone = 0
    var xp = 0
    /** 지금 이어지는 연속 정답 · 이번 세션 최고 기록 */
    var streak = 0
    var bestStreak = 0
    /** 이번 세션에서 틀렸거나 거의 맞은 문항 (나중에 맞히면 빠진다) — 대시보드 '틀린 표현 복습' */
    val mistakes = LinkedHashMap<String, com.yeopjari.app.english.core.Verdict>()
    val events = mutableListOf<ProgressEvent>()
    val pronIssues = mutableListOf<PronIssue>()
    val tipsSpoken = mutableSetOf<PronIssueType>()
    /** itemId → 마지막 점수 (결과 화면의 '오늘의 한 문장' 고르기) */
    val scores = LinkedHashMap<String, Int>()
    val latencies = mutableListOf<Long>()
    val wpms = mutableListOf<Int>()
    var extendCount = 0
    var airportHandled = false
    var lastItem: LearningItem? = null
    /** 음성 인식을 쓸 수 없다는 안내를 이번 세션에 이미 했나 */
    var sttWarned = false
}
