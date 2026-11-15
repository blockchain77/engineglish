package com.yeopjari.app.english.coach

import com.yeopjari.app.english.core.AmbientPulse
import com.yeopjari.app.english.core.AnswerJudge
import com.yeopjari.app.english.core.AnswerOutcome
import com.yeopjari.app.english.core.CardState
import com.yeopjari.app.english.core.Clock
import com.yeopjari.app.english.core.CommandParser
import com.yeopjari.app.english.core.CommuteContext
import com.yeopjari.app.english.core.CommuteDetector
import com.yeopjari.app.english.core.CommutePattern
import com.yeopjari.app.english.core.CommuteSlot
import com.yeopjari.app.english.core.ContentSource
import com.yeopjari.app.english.core.ContextHookContent
import com.yeopjari.app.english.core.ContextHookDetector
import com.yeopjari.app.english.core.DestinationCategory
import com.yeopjari.app.english.core.DrivingLoadEngine
import com.yeopjari.app.english.core.DrivingSignals
import com.yeopjari.app.english.core.FluencyMeter
import com.yeopjari.app.english.core.FluencyMetrics
import com.yeopjari.app.english.core.FluencyRating
import com.yeopjari.app.english.core.GearPosition
import com.yeopjari.app.english.core.Grade
import com.yeopjari.app.english.core.HookEvent
import com.yeopjari.app.english.core.HookTrigger
import com.yeopjari.app.english.core.Ids
import com.yeopjari.app.english.core.JudgeResult
import com.yeopjari.app.english.core.Kst
import com.yeopjari.app.english.core.LearnerCommand
import com.yeopjari.app.english.core.LearnerRepository
import com.yeopjari.app.english.core.LearnerState
import com.yeopjari.app.english.core.LearningItem
import com.yeopjari.app.english.core.Level
import com.yeopjari.app.english.core.ListenContext
import com.yeopjari.app.english.core.ListenOutcome
import com.yeopjari.app.english.core.LoadAssessment
import com.yeopjari.app.english.core.LoadLevel
import com.yeopjari.app.english.core.LoadPolicy
import com.yeopjari.app.english.core.LoadReason
import com.yeopjari.app.english.core.LoadRules
import com.yeopjari.app.english.core.MediaKey
import com.yeopjari.app.english.core.MediaKeyPort
import com.yeopjari.app.english.core.MinimalPair
import com.yeopjari.app.english.core.Mistake
import com.yeopjari.app.english.core.PlacementAnswer
import com.yeopjari.app.english.core.PlacementEngine
import com.yeopjari.app.english.core.PlacementItem
import com.yeopjari.app.english.core.PlacementResult
import com.yeopjari.app.english.core.PlacementState
import com.yeopjari.app.english.core.PlanKind
import com.yeopjari.app.english.core.PlanRequest
import com.yeopjari.app.english.core.PlannedItem
import com.yeopjari.app.english.core.Progression
import com.yeopjari.app.english.core.ProgressEvent
import com.yeopjari.app.english.core.ProgressSnapshot
import com.yeopjari.app.english.core.ProgressUpdate
import com.yeopjari.app.english.core.PronIssue
import com.yeopjari.app.english.core.PronIssueType
import com.yeopjari.app.english.core.PronunciationAnalyzer
import com.yeopjari.app.english.core.PulseKind
import com.yeopjari.app.english.core.ReasonCode
import com.yeopjari.app.english.core.ScreenMode
import com.yeopjari.app.english.core.Scheduler
import com.yeopjari.app.english.core.SessionPlan
import com.yeopjari.app.english.core.SessionPlanner
import com.yeopjari.app.english.core.SessionRecord
import com.yeopjari.app.english.core.Skill
import com.yeopjari.app.english.core.SlotMode
import com.yeopjari.app.english.core.SoundCue
import com.yeopjari.app.english.core.SoundPort
import com.yeopjari.app.english.core.SpeakOutcome
import com.yeopjari.app.english.core.SpeechDurations
import com.yeopjari.app.english.core.SpeechLang
import com.yeopjari.app.english.core.SpeechPort
import com.yeopjari.app.english.core.SpeechRate
import com.yeopjari.app.english.core.SpeechStatus
import com.yeopjari.app.english.core.SpeechTiming
import com.yeopjari.app.english.core.StorageInfo
import com.yeopjari.app.english.core.Track
import com.yeopjari.app.english.core.TtsRule
import com.yeopjari.app.english.core.TurnSignalState
import com.yeopjari.app.english.core.UnitInfo
import com.yeopjari.app.english.core.UnitKey
import com.yeopjari.app.english.core.UnitProgress
import com.yeopjari.app.english.core.Verdict
import com.yeopjari.app.english.core.VehicleFeedback
import com.yeopjari.app.english.core.wordCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/*
 * 코치 시나리오 테스트용 가짜 포트들 — 실제 모듈(learn·speech·drive)과 같은 계약을 단순하게 구현한다.
 */

// ───────────────────────── 말하기 ─────────────────────────

class FakeSpeech(private val clock: Clock) : SpeechPort {
    class Spoken(val text: String, val lang: SpeechLang, val rate: SpeechRate, val atMs: Long) {
        var stoppedAtMs: Long? = null
        var endedAtMs: Long? = null
        override fun toString() = "[$lang] $text"
    }

    val spoken = mutableListOf<Spoken>()
    val listens = mutableListOf<Pair<SpeechLang, Long>>()
    val injected = mutableListOf<String>()
    var stopAllCount = 0
    /** 주입 답의 응답 지연 (1500 이상 → GOOD, 미만 → EASY) */
    var injectLatencyMs = 2_000L
    /** 듣기 창이 끝날 때 Silence 대신 Failed(code) */
    var failListenCode: String? = null

    override val status = MutableStateFlow(SpeechStatus(sttReady = true, sttEnglish = true, ttsKoEngine = "fake-ko", ttsEnEngine = "fake-en"))
    override val speaking = MutableStateFlow(false)
    override val listening = MutableStateFlow(false)
    override val partial = MutableStateFlow("")

    private var speakWaiter: CompletableDeferred<SpeakOutcome>? = null
    private var current: Spoken? = null
    private var listenWaiter: CompletableDeferred<ListenOutcome>? = null
    private var listenStart = 0L
    private var pending: Pair<String, Long>? = null

    override suspend fun speak(text: String, lang: SpeechLang, rate: SpeechRate): SpeakOutcome {
        val rec = Spoken(text, lang, rate, clock.nowMs())
        spoken += rec
        speakWaiter?.complete(SpeakOutcome.STOPPED)
        val w = CompletableDeferred<SpeakOutcome>()
        speakWaiter = w
        current = rec
        speaking.value = true
        try {
            val out = withTimeoutOrNull(SpeechDurations.estimateMs(text, lang, rate)) { w.await() } ?: SpeakOutcome.DONE
            if (out == SpeakOutcome.DONE) rec.endedAtMs = clock.nowMs()
            return out
        } catch (e: CancellationException) {
            if (rec.stoppedAtMs == null && rec.endedAtMs == null) rec.stoppedAtMs = clock.nowMs()
            throw e
        } finally {
            if (speakWaiter === w) {
                speakWaiter = null
                current = null
                speaking.value = false
            }
        }
    }

    override suspend fun listen(lang: SpeechLang, timeoutMs: Long): ListenOutcome {
        listens += lang to timeoutMs
        val now = clock.nowMs()
        pending?.let { (text, at) ->
            pending = null
            if (now - at <= 15_000L) return heard(text, now)
        }
        val w = CompletableDeferred<ListenOutcome>()
        listenWaiter = w
        listenStart = now
        listening.value = true
        try {
            return withTimeoutOrNull(timeoutMs) { w.await() }
                ?: failListenCode?.let { ListenOutcome.Failed(it, "음성 인식을 쓸 수 없어요") }
                ?: ListenOutcome.Silence
        } finally {
            if (listenWaiter === w) {
                listenWaiter = null
                listening.value = false
            }
        }
    }

    private fun heard(text: String, start: Long): ListenOutcome.Heard {
        val speechStart = start + injectLatencyMs
        return ListenOutcome.Heard(
            text,
            SpeechTiming(start, speechStart, speechStart + 380L * wordCount(text), clock.nowMs(), injected = true),
        )
    }

    override fun inject(text: String) {
        injected += text
        val w = listenWaiter
        if (w != null) w.complete(heard(text, listenStart)) else pending = text to clock.nowMs()
    }

    override fun stopAll() {
        stopAllCount++
        current?.let { if (it.stoppedAtMs == null && it.endedAtMs == null) it.stoppedAtMs = clock.nowMs() }
        speakWaiter?.complete(SpeakOutcome.STOPPED)
        listenWaiter?.complete(ListenOutcome.Stopped)
    }

    val isListening: Boolean get() = listenWaiter != null
    fun texts(): List<String> = spoken.map { it.text }
    fun textsSince(index: Int): List<String> = spoken.drop(index).map { it.text }
}

// ───────────────────────── 채점 · 명령 · 발음 · 유창성 ─────────────────────────

class FakeJudge : AnswerJudge {
    private fun norm(s: String): List<String> =
        s.lowercase().replace('’', '\'').replace(Regex("[^a-z0-9' ]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    override fun judge(item: LearningItem, heard: String): JudgeResult {
        val h = norm(heard)
        if (h.isEmpty()) return JudgeResult(Verdict.NO_ANSWER, 0, heard, "", null, feedbackKo = "잘 안 들렸어요.")
        val answers = item.answers.map { it to norm(it) }
        answers.firstOrNull { it.second == h }?.let {
            return JudgeResult(Verdict.CORRECT, 100, heard, h.joinToString(" "), it.first, feedbackKo = "완벽해요!")
        }
        for ((raw, a) in answers) {
            if (a.size != h.size) continue
            val diffs = a.indices.filter { a[it] != h[it] }
            if (diffs.size == 1) {
                val e = a[diffs[0]]
                val w = h[diffs[0]]
                if (e.replace('r', 'l') == w) {
                    val issue = PronIssue(PronIssueType.R_L, e, w, 0.8f, "R은 혀끝을 말아요.", MinimalPair(e, w))
                    return JudgeResult(Verdict.PARTIAL, 70, heard, h.joinToString(" "), raw, pronIssues = listOf(issue),
                        feedbackKo = "‘$e’가 ‘$w’로 들렸어요.")
                }
            }
        }
        val best = answers.maxByOrNull { (_, a) -> a.count { it in h } } ?: return wrong(heard, h)
        val overlap = best.second.count { it in h }.toFloat() / best.second.size.coerceAtLeast(1)
        return if (overlap >= 0.5f) {
            JudgeResult(Verdict.PARTIAL, (overlap * 100).toInt().coerceAtMost(84), heard, h.joinToString(" "), best.first,
                missingKeywords = item.keywords.filter { it !in h }, feedbackKo = "거의 맞았어요.")
        } else {
            wrong(heard, h)
        }
    }

    private fun wrong(heard: String, h: List<String>) =
        JudgeResult(Verdict.WRONG, 10, heard, h.joinToString(" "), null, feedbackKo = "아쉬워요. 이렇게 말해요.")

    override fun judgeChoice(item: LearningItem, choice: String): JudgeResult =
        if (item.answers.any { norm(it) == norm(choice) }) {
            JudgeResult(Verdict.CORRECT, 100, choice, choice.lowercase(), item.modelAnswer, feedbackKo = "정확해요!")
        } else {
            JudgeResult(Verdict.WRONG, 0, choice, choice.lowercase(), null, feedbackKo = "아쉬워요.")
        }
}

class FakeCommands : CommandParser {
    private val en = mapOf(
        "repeat" to LearnerCommand.REPEAT, "again" to LearnerCommand.REPEAT, "one more time" to LearnerCommand.REPEAT,
        "slower" to LearnerCommand.SLOWER, "skip" to LearnerCommand.SKIP, "next" to LearnerCommand.SKIP, "pass" to LearnerCommand.SKIP,
        "hint" to LearnerCommand.HINT, "meaning" to LearnerCommand.MEANING, "pause" to LearnerCommand.PAUSE, "stop" to LearnerCommand.PAUSE,
        "resume" to LearnerCommand.RESUME, "continue" to LearnerCommand.RESUME, "i don't know" to LearnerCommand.DONT_KNOW,
        "i do not know" to LearnerCommand.DONT_KNOW, "end session" to LearnerCommand.END_SESSION,
    )
    private val ko = listOf(
        "다시 시작" to LearnerCommand.RESUME, "다시" to LearnerCommand.REPEAT, "천천히" to LearnerCommand.SLOWER, "넘겨" to LearnerCommand.SKIP,
        "힌트" to LearnerCommand.HINT, "뜻" to LearnerCommand.MEANING, "잠깐" to LearnerCommand.PAUSE, "계속" to LearnerCommand.RESUME,
        "모르겠" to LearnerCommand.DONT_KNOW, "그만" to LearnerCommand.END_SESSION,
    )

    override fun parse(text: String, context: ListenContext): LearnerCommand? {
        val n = text.lowercase().replace(Regex("[.!?,]"), "").trim()
        if (context == ListenContext.CONFIRM) {
            if (n in setOf("yes", "yeah", "sure", "ok", "okay") || n.startsWith("네") || n.startsWith("응") || n.startsWith("좋아")) return LearnerCommand.YES
            if (n in setOf("no", "nope", "not now") || n.startsWith("아니")) return LearnerCommand.NO
        }
        en[n]?.let { return it }
        if (n.any { it in '가'..'힣' }) return ko.firstOrNull { n.contains(it.first) }?.second
        return null
    }
}

class FakePron : PronunciationAnalyzer {
    override fun analyze(item: LearningItem, alignment: List<com.yeopjari.app.english.core.WordAlign>): List<PronIssue> = emptyList()
    override fun tip(type: PronIssueType): String = "팁: ${type.ko}"
    override fun pairs(type: PronIssueType): List<MinimalPair> = listOf(MinimalPair("right", "light"), MinimalPair("road", "load"))
}

class FakeFluency : FluencyMeter {
    override fun measure(timing: SpeechTiming, heard: String): FluencyMetrics {
        val latency = timing.speechStartMs?.let { it - timing.listenStartMs }
        return FluencyMetrics(latency, null, 120, 0, 0L, FluencyRating.OK, timing.injected, "추정")
    }
}

// ───────────────────────── 학습 ─────────────────────────

class FakeScheduler : Scheduler {
    override fun schedule(card: CardState?, itemId: String, grade: Grade, nowMs: Long): CardState {
        val c = card ?: CardState(itemId, introducedMs = nowMs)
        return if (grade == Grade.AGAIN) {
            c.copy(reps = 0, lapses = c.lapses + 1, intervalDays = 0.0, dueAtMs = nowMs + 600_000L, lastReviewedMs = nowMs, lastGrade = grade)
        } else {
            c.copy(reps = c.reps + 1, intervalDays = 1.0, dueAtMs = nowMs + 86_400_000L, lastReviewedMs = nowMs, lastGrade = grade)
        }
    }

    override fun due(cards: Collection<CardState>, nowMs: Long): List<CardState> =
        cards.filter { it.dueAtMs <= nowMs }.sortedBy { it.dueAtMs }

    override fun gradeFor(verdict: Verdict, latencyMs: Long?): Grade = when (verdict) {
        Verdict.CORRECT -> if (latencyMs != null && latencyMs < 1_500L) Grade.EASY else Grade.GOOD
        Verdict.PARTIAL -> Grade.HARD
        else -> Grade.AGAIN
    }
}

class FakeProgression(private val scheduler: Scheduler) : Progression {
    val outcomes = mutableListOf<AnswerOutcome>()
    val sessions = mutableListOf<SessionRecord>()
    /** 다음 record 가 돌려줄 진행 이벤트 */
    var nextEvents: List<ProgressEvent> = emptyList()

    override fun record(state: LearnerState, outcome: AnswerOutcome, content: ContentSource, nowMs: Long): ProgressUpdate {
        outcomes += outcome
        val v = outcome.result.verdict
        val xp = when (v) {
            Verdict.CORRECT -> 10
            Verdict.PARTIAL -> 5
            else -> 0
        }
        val id = outcome.item.id
        val card = scheduler.schedule(state.cards[id], id, outcome.grade, nowMs)
        val mistakes = if (v != Verdict.CORRECT) state.mistakes + Mistake(id, Kst.epochDay(nowMs), v, nowMs) else state.mistakes
        val pron = outcome.result.pronIssues.fold(state.pron) { p, i -> p.copy(counts = p.counts + (i.type to (p.counts[i.type] ?: 0) + 1)) }
        val progress = state.progress.copy(
            xp = state.progress.xp + xp,
            totalAnswered = state.progress.totalAnswered + 1,
            totalCorrect = state.progress.totalCorrect + if (v == Verdict.CORRECT) 1 else 0,
        )
        val ev = nextEvents
        nextEvents = emptyList()
        return ProgressUpdate(state.copy(cards = state.cards + (id to card), mistakes = mistakes, pron = pron, progress = progress), xp, ev)
    }

    override fun recordSession(state: LearnerState, record: SessionRecord): LearnerState {
        sessions += record
        return state.copy(sessions = state.sessions + record)
    }

    override fun unlockedUnits(state: LearnerState, content: ContentSource, track: Track, level: Level): List<UnitKey> =
        content.units(track, level).map { it.key }

    override fun currentUnit(state: LearnerState, content: ContentSource): UnitKey? =
        state.profile?.let { content.units(it.track, it.level).firstOrNull()?.key }

    /** 단순 규칙: 오늘 답한 수 = 누적 답 수, 유닛 진행 = 카드가 있는 문항 수, 레벨 진행 = 누적 정답 / 20 */
    override fun snapshot(state: LearnerState, content: ContentSource, nowMs: Long): ProgressSnapshot {
        val p = state.profile
        val units = p?.let { content.units(it.track, it.level) }.orEmpty().map { u ->
            val answered = u.itemIds.count { it in state.cards }
            UnitProgress(u.key, u.titleKo, answered, u.itemIds.size, 0f, passed = answered == u.itemIds.size, unlocked = true)
        }
        return ProgressSnapshot(
            track = p?.track, level = p?.level, goal = p?.goal, xp = state.progress.xp, streakDays = state.progress.streakDays,
            todayAnswered = state.progress.totalAnswered, todayGoal = p?.goal?.items ?: 0,
            dueReviews = scheduler.due(state.cards.values, nowMs).size, learnedItems = state.cards.size,
            currentUnit = units.firstOrNull { !it.passed } ?: units.lastOrNull(), units = units,
            levelProgress = (state.progress.totalCorrect / 20f).coerceIn(0f, 1f),
        )
    }
}

/** 명세 §6.4 예산 공식만 따르는 단순 계획기 (script 가 있으면 그대로) */
class FakePlanner(private val scheduler: Scheduler) : SessionPlanner {
    val requests = mutableListOf<PlanRequest>()
    var script: List<PlannedItem>? = null
    var extendScript: List<PlannedItem> = emptyList()

    override fun plan(request: PlanRequest, content: ContentSource): SessionPlan {
        requests += request
        val routeSec = if (!request.parked) request.routeRemainingSec else null
        val budget = maxOf(180, routeSec?.let { (it * 0.35).toInt() } ?: 600)
        val share = when {
            request.reviewOnly || request.pronDrill != null -> 0f
            request.mode == SlotMode.NEW_EXPRESSIONS -> 0.55f
            request.mode == SlotMode.MIXED -> 0.4f
            else -> 0.2f
        }
        val rawNew = (budget * share / 60).toInt()
        val nNew = when {
            share == 0f -> 0
            request.mode == SlotMode.REVIEW -> rawNew.coerceIn(0, 2)
            else -> rawNew.coerceIn(1, 8)
        }
        val nRev = ((budget - nNew * 60) / 30).coerceIn(0, 30)
        val items = script ?: run {
            val drills = request.pronDrill?.let { t -> content.pronDrills(t).take(6).map { PlannedItem(it.id, PlanKind.DRILL) } } ?: emptyList()
            val reviews = scheduler.due(request.state.cards.values, request.nowMs).map { it.itemId }
                .filter { it !in request.exclude }.take(nRev).map { PlannedItem(it, PlanKind.REVIEW) }
            val news = content.items().filter {
                it.unit > 0 && it.track == request.track && it.id !in request.state.cards && it.id !in request.exclude && it.maxLoad == LoadLevel.NORMAL
            }.take(nNew).map { PlannedItem(it.id, PlanKind.NEW) }
            drills + reviews + news
        }
        val newCount = items.count { it.kind == PlanKind.NEW }
        val reviewCount = items.count { it.kind != PlanKind.NEW }
        val minutes = routeSec?.let { (it + 30) / 60 }
        val title = if (minutes != null) "${request.slot.ko} ${minutes}분 → 새 표현 ${newCount}개 + 복습 ${reviewCount}개"
        else "오늘의 세션 → 새 표현 ${newCount}개 + 복습 ${reviewCount}개"
        return SessionPlan(
            id = Ids.next("plan", request.nowMs), slot = request.slot, mode = request.mode, track = request.track, level = request.level,
            routeMinutes = minutes, budgetSec = budget, items = items, newCount = newCount, reviewCount = reviewCount, titleKo = title,
            wrapUpItemId = items.firstOrNull { it.kind == PlanKind.NEW }?.itemId, createdMs = request.nowMs,
        )
    }

    override fun extend(plan: SessionPlan, request: PlanRequest, content: ContentSource, count: Int): SessionPlan =
        plan.copy(items = plan.items + extendScript.take(count))
}

class FakePlacement : PlacementEngine {
    override fun start(track: Track, pool: List<PlacementItem>, nowMs: Long): PlacementState =
        PlacementState(track, minOf(12, pool.size), Level.INTERMEDIATE, pick(pool, Level.INTERMEDIATE, emptySet()), startedMs = nowMs, currentShownMs = nowMs)

    override fun answer(state: PlacementState, pool: List<PlacementItem>, choiceIndex: Int, nowMs: Long): PlacementState {
        val cur = state.current ?: return state
        val correct = choiceIndex == cur.answerIndex
        val answers = state.answers + PlacementAnswer(cur.id, cur.level, choiceIndex, correct, nowMs - state.currentShownMs)
        val nextLevel = if (correct) state.currentLevel.next ?: Level.ADVANCED else state.currentLevel.prev ?: Level.BEGINNER
        val next = if (answers.size >= state.total) null else pick(pool, nextLevel, answers.map { it.itemId }.toSet())
        return state.copy(currentLevel = nextLevel, current = next, answers = answers, currentShownMs = nowMs)
    }

    override fun result(state: PlacementState): PlacementResult {
        val per = Level.entries.associateWith { l ->
            val a = state.answers.filter { it.level == l }
            a.count { it.correct } to a.size
        }
        val level = Level.entries.reversed().firstOrNull { l ->
            val (c, n) = per.getValue(l)
            n >= 2 && c.toFloat() / n >= 0.6f
        } ?: Level.BEGINNER
        val correct = state.answers.count { it.correct }
        return PlacementResult(level, correct, state.answers.size, per, "${state.answers.size}문항 중 ${correct}개 정답 · ${level.ko}부터 시작할게요.")
    }

    private fun pick(pool: List<PlacementItem>, level: Level, used: Set<String>): PlacementItem? {
        val rest = pool.filter { it.id !in used }
        return rest.firstOrNull { it.level == level } ?: rest.minByOrNull { abs(it.level.ordinal - level.ordinal) }
    }
}

class FakeRepository(initial: LearnerState) : LearnerRepository {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<LearnerState> = _state
    override val loaded = MutableStateFlow(false)
    var updates = 0
    var erased = 0

    override suspend fun load() {
        loaded.value = true
    }

    override fun update(transform: (LearnerState) -> LearnerState): LearnerState {
        updates++
        val next = transform(_state.value)
        _state.value = next
        return next
    }

    override suspend fun flush() = Unit

    override fun eraseAll() {
        erased++
        _state.value = LearnerState()
    }

    override fun storageInfo(): StorageInfo = StorageInfo(1234L, listOf("english_learner_v1.bin"), true, null)
}

// ───────────────────────── 운전 ─────────────────────────

/** 명세 §4.2 의 핵심만: 즉시 올리고, 4초 안정 뒤 내림 (CRITICAL 은 3초 뒤 FOCUS 로) */
class FakeLoadEngine : DrivingLoadEngine {
    private var current = LoadLevel.PARKED
    private var since = 0L
    private var windowStart: Long? = null
    private var windowMax = LoadLevel.PARKED
    private var reasons: List<LoadReason> = emptyList()

    private fun raw(s: DrivingSignals): Pair<LoadLevel, List<LoadReason>> {
        if (s.gear == GearPosition.P) return LoadLevel.PARKED to listOf(LoadReason(ReasonCode.PARKED, "정차 중"))
        val r = mutableListOf<LoadReason>()
        if (s.accelMps2 <= -3.5f) r += LoadReason(ReasonCode.HARD_BRAKE, "급제동")
        if (s.navVoicePrompt) r += LoadReason(ReasonCode.NAV_PROMPT, "내비 음성 안내 중")
        if (s.turnSignal != TurnSignalState.OFF) r += LoadReason(ReasonCode.TURN_SIGNAL, "방향지시등")
        val m = s.maneuver
        if (m != null && m.type.demanding && m.distanceM <= 300) r += LoadReason(ReasonCode.MANEUVER, "교차로 접근 ${m.distanceM / 10 * 10}m")
        if (abs(s.steeringDeg) > 25f) r += LoadReason(ReasonCode.STEERING, "핸들 조작")
        if (r.isEmpty()) {
            r += when {
                s.cruiseActive && s.speedKph >= 30f -> LoadReason(ReasonCode.CRUISE, "크루즈 주행")
                s.trafficJam -> LoadReason(ReasonCode.TRAFFIC_JAM, "정체 구간")
                else -> LoadReason(ReasonCode.CITY, "시내 주행")
            }
        }
        val sorted = r.sortedByDescending { it.code.level.ordinal }
        return sorted.first().code.level to sorted.take(3)
    }

    override fun instant(signals: DrivingSignals): LoadAssessment {
        val (lvl, rs) = raw(signals)
        return LoadAssessment(lvl, lvl.bandStart, rs, lvl, signals.atMs, signals.atMs)
    }

    override fun update(signals: DrivingSignals): LoadAssessment {
        val (r, rs) = raw(signals)
        val t = signals.atMs
        if (r.ordinal >= current.ordinal) {
            if (r != current) {
                current = r
                since = t
            }
            reasons = rs
            windowStart = null
        } else {
            val ws = windowStart ?: t.also {
                windowStart = it
                windowMax = r
            }
            windowMax = LoadLevel.max(windowMax, r)
            val need = if (current == LoadLevel.CRITICAL) 3_000L else 4_000L
            if (t - ws >= need) {
                current = if (current == LoadLevel.CRITICAL) LoadLevel.max(windowMax, LoadLevel.FOCUS) else windowMax
                since = t
                windowStart = null
                reasons = rs
            }
        }
        return LoadAssessment(current, current.bandStart, reasons, r, since, t)
    }

    override fun reset() {
        current = LoadLevel.PARKED
        windowStart = null
    }
}

class FakePolicy : LoadPolicy {
    private val drivingSkills = setOf(Skill.VOCAB, Skill.EXPRESSION, Skill.SENTENCE_BUILD, Skill.LISTEN_REPEAT, Skill.MINIMAL_PAIR)

    override fun rules(level: LoadLevel): LoadRules = when (level) {
        LoadLevel.PARKED -> LoadRules(level, newQuestions = true, listening = true, tts = TtsRule.FULL, skills = Skill.entries.toSet(),
            maxAnswerWords = 40, maxListenSec = 60, screen = ScreenMode.FULL, onScreenChoices = true, hudHint = false, ambientPulse = true)
        LoadLevel.RELAXED -> LoadRules(level, newQuestions = true, listening = true, tts = TtsRule.FULL, skills = drivingSkills + Skill.TOEIC_QA,
            maxAnswerWords = 20, maxListenSec = 45, screen = ScreenMode.DRIVE, onScreenChoices = false, hudHint = true, ambientPulse = true)
        LoadLevel.NORMAL -> LoadRules(level, newQuestions = true, listening = true, tts = TtsRule.FULL, skills = drivingSkills,
            maxAnswerWords = 12, maxListenSec = 12, screen = ScreenMode.DRIVE, onScreenChoices = false, hudHint = true, ambientPulse = true)
        LoadLevel.FOCUS -> LoadRules(level, newQuestions = false, listening = false, tts = TtsRule.FINISH_SHORT, skills = emptySet(),
            maxAnswerWords = 0, maxListenSec = 0, screen = ScreenMode.DRIVE, onScreenChoices = false, hudHint = false, ambientPulse = false)
        LoadLevel.CRITICAL -> LoadRules(level, newQuestions = false, listening = false, tts = TtsRule.STOP_NOW, skills = emptySet(),
            maxAnswerWords = 0, maxListenSec = 0, screen = ScreenMode.DRIVE, onScreenChoices = false, hudHint = false, ambientPulse = false)
    }

    override fun allows(item: LearningItem, level: LoadLevel): Boolean {
        val r = rules(level)
        return r.newQuestions && level.isAtMost(item.maxLoad) && item.skill in r.skills && item.answerWords <= r.maxAnswerWords
    }
}

/** 정체 탈출만 규칙대로, 나머지는 fire() 로 */
class FakeHooks : ContextHookDetector {
    private val scripted = mutableListOf<HookEvent>()
    private var jamSince: Long? = null
    private var jamLong = false
    private var clearSince: Long? = null
    private var firedJam = false

    fun fire(trigger: HookTrigger, atMs: Long) {
        scripted += HookEvent(trigger, atMs)
    }

    override fun update(signals: DrivingSignals, load: LoadAssessment): List<HookEvent> {
        val out = mutableListOf<HookEvent>()
        val t = signals.atMs
        if (signals.trafficJam) {
            val js = jamSince ?: t.also { jamSince = it }
            if (t - js >= 20_000L) jamLong = true
            clearSince = null
        } else {
            jamSince = null
            if (jamLong && signals.speedKph >= 30f) {
                val cs = clearSince ?: t.also { clearSince = it }
                if (t - cs >= 5_000L && !firedJam) {
                    firedJam = true
                    jamLong = false
                    out += HookEvent(HookTrigger.JAM_EXIT, t)
                }
            } else {
                clearSince = null
            }
        }
        out += scripted
        scripted.clear()
        return out
    }

    override fun reset() = Unit
}

class FakeCommute : CommuteDetector {
    val learned = mutableListOf<CommuteContext>()

    override fun detect(signals: DrivingSignals, pattern: CommutePattern, nowMs: Long): CommuteContext = when {
        signals.originPlaceId == "place.home" && signals.destinationPlaceId == "place.office" ->
            CommuteContext(CommuteSlot.MORNING, "place.home", "place.office", "route.morning", signals.routeRemainingSec, DestinationCategory.OFFICE, 0.9f)
        signals.originPlaceId == "place.office" && signals.destinationPlaceId == "place.home" ->
            CommuteContext(CommuteSlot.EVENING, "place.office", "place.home", "route.evening", signals.routeRemainingSec, DestinationCategory.HOME, 0.9f)
        else -> CommuteContext(CommuteSlot.OTHER, routeRemainingSec = signals.routeRemainingSec, confidence = 0.2f)
    }

    override fun learn(pattern: CommutePattern, context: CommuteContext, departedMs: Long): CommutePattern {
        learned += context
        return pattern.copy(trips = pattern.trips + 1)
    }
}

class FakeFeedback(private val clock: Clock) : VehicleFeedback {
    override val ambientSupported = MutableStateFlow(false)
    override val lastPulse = MutableStateFlow<AmbientPulse?>(null)
    override val hudHint = MutableStateFlow<String?>(null)
    val pulses = mutableListOf<PulseKind>()
    val hints = mutableListOf<String?>()
    private var id = 0L

    override fun pulse(kind: PulseKind) {
        pulses += kind
        id++
        lastPulse.value = AmbientPulse(id, kind, clock.nowMs())
    }

    override fun showHudHint(text: String?) {
        hints += text
        hudHint.value = text
    }
}

/** 울린 효과음을 차례로 적어 둔다 */
class FakeSound : SoundPort {
    val cues = mutableListOf<SoundCue>()

    override fun play(cue: SoundCue) {
        cues += cue
    }
}

class FakeMediaKeys : MediaKeyPort {
    private val flow = MutableSharedFlow<MediaKey>(extraBufferCapacity = 16)
    override val keys: SharedFlow<MediaKey> = flow
    val activeCalls = mutableListOf<Boolean>()

    override fun onKeyEvent(keyCode: Int, down: Boolean): Boolean {
        val k = MediaKey.fromKeyCode(keyCode) ?: return false
        if (down) flow.tryEmit(k)
        return true
    }

    override fun inject(key: MediaKey) {
        flow.tryEmit(key)
    }

    override fun setActive(active: Boolean) {
        activeCalls += active
    }
}

// ───────────────────────── 콘텐츠 ─────────────────────────

class FakeContent(
    private val all: List<LearningItem>,
    private val hooks: Map<HookTrigger, ContextHookContent>,
    private val placementItems: List<PlacementItem>,
) : ContentSource {
    override val version: String = "test"
    private val everything = all + hooks.values.flatMap { it.items }
    override fun items(): List<LearningItem> = everything
    override fun item(id: String): LearningItem? = everything.firstOrNull { it.id == id }
    override fun units(track: Track, level: Level): List<UnitInfo> =
        all.filter { it.track == track && it.level == level && it.unit > 0 }.groupBy { it.unit }.toSortedMap()
            .map { (u, list) -> UnitInfo(UnitKey(track, level, u), "유닛 $u", "Unit $u", list.map { it.id }) }
    override fun unit(key: UnitKey): UnitInfo? = units(key.track, key.level).firstOrNull { it.key == key }
    override fun unitItems(key: UnitKey): List<LearningItem> = unit(key)?.itemIds?.mapNotNull { item(it) } ?: emptyList()
    override fun placement(track: Track): List<PlacementItem> = placementItems.filter { it.track == null || it.track == track }
    override fun hook(trigger: HookTrigger): ContextHookContent? = hooks[trigger]
    override fun pronDrills(type: PronIssueType): List<LearningItem> = all.filter { it.skill == Skill.MINIMAL_PAIR && it.pronFocus == type }
    override fun quizItems(track: Track, level: Level): List<LearningItem> =
        all.filter { it.track == track && it.level == level && it.distractors.size >= 2 }
}
