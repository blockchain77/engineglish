package com.yeopjari.app.english.coach

import com.yeopjari.app.english.core.ActionResult
import com.yeopjari.app.english.core.CoachDeps
import com.yeopjari.app.english.core.CoachPhase
import com.yeopjari.app.english.core.CommuteSlot
import com.yeopjari.app.english.core.DailyGoal
import com.yeopjari.app.english.core.DemoAnswerKind
import com.yeopjari.app.english.core.DriveStatus
import com.yeopjari.app.english.core.DrivingSignals
import com.yeopjari.app.english.core.EnglishCoach
import com.yeopjari.app.english.core.EnglishUiState
import com.yeopjari.app.english.core.EveningStudy
import com.yeopjari.app.english.core.HookEvent
import com.yeopjari.app.english.core.HookTrigger
import com.yeopjari.app.english.core.Kst
import com.yeopjari.app.english.core.LearnThresholds
import com.yeopjari.app.english.core.LearnerCommand
import com.yeopjari.app.english.core.LearnerProfile
import com.yeopjari.app.english.core.LearnerSettings
import com.yeopjari.app.english.core.LearnerState
import com.yeopjari.app.english.core.Level
import com.yeopjari.app.english.core.ListenContext
import com.yeopjari.app.english.core.LoadAssessment
import com.yeopjari.app.english.core.LoadLevel
import com.yeopjari.app.english.core.MediaKey
import com.yeopjari.app.english.core.MicState
import com.yeopjari.app.english.core.PlanRequest
import com.yeopjari.app.english.core.PronIssueType
import com.yeopjari.app.english.core.SavedExpression
import com.yeopjari.app.english.core.ScreenMode
import com.yeopjari.app.english.core.SessionOrigin
import com.yeopjari.app.english.core.SessionRequest
import com.yeopjari.app.english.core.SoundCue
import com.yeopjari.app.english.core.SuggestionKind
import com.yeopjari.app.english.core.SuggestionUi
import com.yeopjari.app.english.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 옆자리 영어 코치 — UI 가 쓰는 단 하나의 진입점 (명세 §7).
 * 운전 신호 → 부하 판단 → 정책, 세션 상태 기계(SessionEngine), 레벨 테스트, 퇴근길 학습 질문, 주차 대시보드,
 * 제안 카드, 정차 화면 목록을 묶는다.
 * 모든 함수는 메인 스레드에서 불리고 바로 돌아온다. 시간은 deps.clock, 코루틴은 [scope] (테스트는 가상 시간).
 */
class DefaultEnglishCoach(private val deps: CoachDeps, private val scope: CoroutineScope) : EnglishCoach {
    private val ctx = CoachContext(deps, scope)
    private val session = SessionEngine(ctx) { r, reason -> onSessionEnded(r, reason) }
    private val placement = PlacementFlow(ctx)
    private val eveningAsk = EveningAskFlow(ctx) { yes -> onEveningAnswer(yes) }

    override val state: StateFlow<EnglishUiState> = ctx.ui.asStateFlow()

    private var started = false
    private var ready = false
    private val jobs = mutableListOf<Job>()
    private var lastSignals: DrivingSignals = deps.signals.signals.value
    private var lastDriveId: String? = null
    /** 새 주행에서 자동 시작(출근길) · 학습 질문(퇴근길)을 기다리는 driveId */
    private var autoCandidate: String? = null
    private var movingSinceMs: Long? = null
    /** 오늘 퇴근길 질문에 한 대답 (KST epochDay → 할게요/쉴게요) — 같은 날엔 다시 묻지 않고 그대로 따른다 */
    private var eveningAnswer: Pair<Long, Boolean>? = null
    /** 주행 학습이 끝났다 → P 단에 넣으면(정차 화면이 되면) 대시보드를 띄운다 */
    private var dashboardDue = false
    private var dashboardLine: String? = null
    private var greetJob: Job? = null
    private val dismissed = mutableSetOf<String>()
    /** 세션 밖에서 생긴 상황 훅 (제안 카드) */
    private var pendingHook: HookEvent? = null

    private var collectorError: Throwable? = null
    private var storageJob: Job? = null

    /** 세션 턴·구독에서 난 예상 밖 오류 (테스트 확인용 — 사용자 데이터는 담지 않는다) */
    internal val lastError: Throwable? get() = session.lastError ?: eveningAsk.lastError ?: collectorError

    /** 한 신호·한 상태 처리에서 난 오류가 구독 전체(=부하 판단)를 멈추지 않게 */
    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            collectorError = t
        }
    }

    // ───────────── 시작 · 해제 ─────────────

    override fun start() {
        if (started) return
        started = true
        lastSignals = deps.signals.signals.value
        lastDriveId = lastSignals.driveId
        jobs += scope.launch { deps.signals.signals.collect { guard { onSignals(it) } } }
        jobs += scope.launch { deps.mediaKeys.keys.collect { guard { onMediaKey(it) } } }
        jobs += scope.launch { deps.speech.status.collect { st -> ctx.emit { it.copy(speech = st) } } }
        jobs += scope.launch {
            deps.speech.partial.collect { p ->
                ctx.emit { s -> if (s.listening.mic == MicState.OFF) s else s.copy(listening = s.listening.copy(partial = p)) }
            }
        }
        jobs += scope.launch { deps.feedback.lastPulse.collect { p -> ctx.emit { it.copy(pulse = p) } } }
        jobs += scope.launch {
            deps.repository.load()
            ready = true
            deps.repository.state.collect { guard { onLearner(it) } }
        }
    }

    override fun release() {
        storageJob?.cancel()
        greetJob?.cancel()
        eveningAsk.cancel()
        session.release()
        placement.reset()
        jobs.forEach { it.cancel() }
        jobs.clear()
        started = false
        deps.mediaKeys.setActive(false)
        deps.speech.stopAll()
        deps.feedback.showHudHint(null)
    }

    // ───────────── 학습자 상태 → 화면 ─────────────

    private fun onLearner(st: LearnerState) {
        if (!ready) return
        val now = ctx.now()
        val snapshot = deps.progression.snapshot(st, deps.content, now)
        // 저장소 크기는 정차 화면(설정)에서만 보이므로 주행 중엔 다시 읽지 않는다
        val storage = if (ctx.parked || ctx.ui.value.storage == null) deps.repository.storageInfo() else ctx.ui.value.storage
        ctx.emit {
            val phase = when {
                st.profile == null && !session.active -> CoachPhase.ONBOARDING
                it.phase == CoachPhase.BOOTING || it.phase == CoachPhase.ONBOARDING -> CoachPhase.IDLE
                else -> it.phase
            }
            it.copy(ready = true, profile = st.profile, settings = st.settings, progress = snapshot, storage = storage, phase = phase)
        }
        if (ctx.parked) {
            ctx.emit { it.copy(library = CoachViews.library(st, deps.content, deps.pronunciation, now)) }
            refreshStorageLater()
        }
        refreshIdleExtras()
    }

    /** 저장은 최대 1초 늦게 끝난다 → 저장이 끝난 뒤 한 번 더 읽어 설정 화면의 용량·마지막 저장 시각을 맞춘다 */
    private fun refreshStorageLater() {
        storageJob?.cancel()
        storageJob = scope.launch {
            delay(STORAGE_REFRESH_MS)
            ctx.emit { it.copy(storage = deps.repository.storageInfo()) }
        }
    }

    /** 세션 밖: 제안 카드 · 다음 세션 미리보기 */
    private fun refreshIdleExtras() {
        if (!ready || session.active) return
        val st = ctx.learner
        ctx.emit { it.copy(suggestion = computeSuggestion(st), nextPlanPreviewKo = preview(st)) }
    }

    private fun preview(st: LearnerState): String? {
        val p = st.profile ?: return null
        val now = ctx.now()
        val slot = if (Kst.minuteOfDay(now) < 12 * 60) CommuteSlot.MORNING else CommuteSlot.EVENING
        return runCatching {
            deps.planner.plan(
                PlanRequest(st, slot, st.settings.modeFor(slot), p.track, p.level, DEFAULT_ROUTE_MIN * 60, parked = false, nowMs = now),
                deps.content,
            ).titleKo
        }.getOrNull()
    }

    private fun computeSuggestion(st: LearnerState): SuggestionUi? {
        val p = st.profile ?: return null
        val now = ctx.now()
        val day = Kst.epochDay(now)
        val list = mutableListOf<SuggestionUi>()
        pendingHook?.let { hookSuggestion(it) }?.let { list += it }
        if (ctx.parked) {
            if (!p.placementDone) {
                list += SuggestionUi("placement", SuggestionKind.PLACEMENT_NEEDED, "레벨 테스트를 해 볼까요?",
                    "12문항으로 시작 레벨을 맞춰 드려요. 정차 중에 3분이면 돼요.", "레벨 테스트")
            }
            commuteSoon(st, now)?.let { slot ->
                val auto = if (slot == CommuteSlot.MORNING) st.settings.autoStartOnCommute else st.settings.eveningStudy == EveningStudy.ALWAYS
                val body = when {
                    auto -> "출발하면 ${slot.ko} 세션을 바로 시작할게요. 지금 미리 시작할 수도 있어요."
                    slot == CommuteSlot.EVENING && st.settings.eveningStudy == EveningStudy.ASK ->
                        "출발하면 복습할지 먼저 물어볼게요. 지금 미리 시작할 수도 있어요."
                    else -> "지금 미리 시작할 수도 있어요."
                }
                list += SuggestionUi("commute-$day-${slot.name}", SuggestionKind.START_COMMUTE_SESSION, "곧 ${slot.ko} 시간이에요", body, "지금 시작")
            }
            val due = deps.scheduler.due(st.cards.values, now).size
            if (due >= REVIEW_DUE_MIN) {
                list += SuggestionUi("review-$day", SuggestionKind.REVIEW_DUE, "복습할 표현이 ${due}개 있어요",
                    "잊기 전에 한 번 더 말해 보면 오래 기억해요.", "복습 시작")
            }
            st.pron.counts.entries.filter { it.value >= PRON_DRILL_MIN }.maxByOrNull { it.value }?.let { (type, n) ->
                list += SuggestionUi("pron-${type.name}-$day", SuggestionKind.PRON_DRILL, "${type.ko} 연습을 해 볼까요?",
                    "최근 ${n}번 헷갈렸어요. ${type.example} 같은 짝 단어로 연습해요.", "발음 연습")
            }
        }
        return list.firstOrNull { it.id !in dismissed }
    }

    private fun hookSuggestion(e: HookEvent): SuggestionUi? = when (e.trigger) {
        HookTrigger.AIRPORT -> SuggestionUi("travel-${e.atMs}", SuggestionKind.SWITCH_TRACK_TRAVEL, "공항 가시는 길이네요",
            "오늘은 여행 영어로 연습해 볼까요?", "여행 영어로")
        else -> if (ctx.parked) {
            val intro = deps.content.hook(e.trigger)?.introKo ?: e.trigger.ko
            SuggestionUi("hook-${e.trigger.name}-${e.atMs}", SuggestionKind.CONTEXT_HOOK, "방금 그 상황, 영어로 말해 볼까요?", intro, "한 문장 해 보기")
        } else {
            null
        }
    }

    /** 평소 출발 시각 15분 전 ~ 출발 시각 (분 단위 평균) */
    private fun commuteSoon(st: LearnerState, now: Long): CommuteSlot? {
        val minute = Kst.minuteOfDay(now)
        val slot = if (minute < 12 * 60) CommuteSlot.MORNING else CommuteSlot.EVENING
        val list = if (slot == CommuteSlot.MORNING) st.commute.morningDepartures else st.commute.eveningDepartures
        if (list.size < 2) return null
        val avg = list.average().roundToInt()
        return if (minute in (avg - COMMUTE_SOON_MIN)..avg) slot else null
    }

    // ───────────── 운전 신호 ─────────────

    private fun onSignals(s: DrivingSignals) {
        val prev = lastSignals
        lastSignals = s
        ctx.signals = s
        val prevLoad = ctx.load.value
        val a = deps.loadEngine.update(s)
        ctx.load.value = a
        val rules = deps.policy.rules(a.level)
        val full = a.level == LoadLevel.PARKED && !s.uxRestricted
        val st = ctx.learner
        val dashboardWasOpen = ctx.ui.value.showParkedDashboard
        ctx.emit {
            it.copy(
                load = a, rules = rules, drive = driveStatus(s, a, st),
                screenMode = if (full) ScreenMode.FULL else ScreenMode.DRIVE,
                hud = it.hud.copy(load = a.level),
                // 다시 출발하면 대시보드는 내린다
                showParkedDashboard = it.showParkedDashboard && full,
            )
        }
        if (dashboardWasOpen && !full) stopGreeting()
        val events = deps.hooks.update(s, a)
        if (st.settings.contextHooks) events.forEach { onHook(it) }
        if (a.level != prevLoad.level) onLevelChanged(prevLoad.level, a.level)
        session.onSignals(prev, s)
        if (eveningAsk.active) {
            // 주차했거나 곧 도착이면 질문을 접는다 (아무 말 없이)
            val rem = s.routeRemainingSec
            if (s.parked || (s.routeActive && rem != null && rem < LearnThresholds.MIN_ROUTE_FOR_AUTO_SEC)) eveningAsk.cancel()
        }
        commuteStart(s, a)
        showDashboardIfParked()
    }

    private fun driveStatus(s: DrivingSignals, a: LoadAssessment, st: LearnerState): DriveStatus {
        val run = session.run
        val routeId = run?.commute?.routeId
        val route = CoachViews.routeFor(deps.map, routeId, s)
        val slot = run?.plan?.slot ?: if (s.routeActive) deps.commute.detect(s, st.commute, ctx.now()).slot else null
        return DriveStatus(
            origin = s.origin,
            gear = s.gear,
            speedKph = s.speedKph.roundToInt(),
            parked = a.level == LoadLevel.PARKED,
            uxRestricted = s.uxRestricted,
            charging = s.charging,
            socPercent = s.socPercent,
            slot = slot,
            routeRemainingMin = s.routeRemainingSec?.takeIf { s.routeActive }?.let { (it + 59) / 60 },
            destinationKo = s.destinationName ?: deps.map.place(s.destinationPlaceId)?.nameKo,
            segmentKo = s.segmentName ?: deps.map.segment(s.segmentId)?.nameKo,
            routeStrip = CoachViews.routeStrip(deps.map, route, s.segmentId),
        )
    }

    private fun onLevelChanged(prev: LoadLevel, level: LoadLevel) {
        session.onLoadChanged(prev, level)
        eveningAsk.onLoad(level)
        if (level != LoadLevel.PARKED && placement.showing) {
            placement.suspendForDriving()
            ctx.emit { it.copy(phase = if (it.profile == null) CoachPhase.ONBOARDING else CoachPhase.IDLE) }
            ctx.toast(Say.PARKED_ONLY)
        }
        if ((level == LoadLevel.PARKED) != (prev == LoadLevel.PARKED)) {
            if (level == LoadLevel.PARKED && ready) {
                val st = ctx.learner
                ctx.emit { it.copy(library = CoachViews.library(st, deps.content, deps.pronunciation, ctx.now())) }
            }
            refreshIdleExtras()
        }
    }

    private fun onHook(e: HookEvent) {
        if (session.active) {
            session.enqueueHook(e)
            return
        }
        if (e.trigger == HookTrigger.AIRPORT || (ctx.parked && e.trigger == HookTrigger.CHARGING)) {
            pendingHook = e
            refreshIdleExtras()
        }
    }

    /**
     * 출퇴근(신뢰도 ≥ 0.7) 새 주행 → 3초 이상 달리고 부하 ≤ NORMAL 이면
     *  - 출근길: 설정 '출근길 자동 시작'이 켜져 있으면 세션 자동 시작
     *  - 퇴근길: 설정 '퇴근길 학습' — 물어보기(기본)면 먼저 묻고, 항상 시작이면 바로 시작, 안 함이면 아무것도 하지 않는다
     * 주행마다 한 번만 판단한다. 도착까지 3분도 안 남았으면 시작하지도 묻지도 않는다.
     */
    private fun commuteStart(s: DrivingSignals, a: LoadAssessment) {
        val driveId = s.driveId
        if (driveId != null && driveId != lastDriveId) {
            lastDriveId = driveId
            autoCandidate = driveId
            movingSinceMs = null
            eveningAsk.cancel()
        }
        val cand = autoCandidate ?: return
        if (session.active || cand != s.driveId) {
            autoCandidate = null
            return
        }
        val st = ctx.learner
        if (!ready || st.profile == null) return
        if (!s.moving || a.level == LoadLevel.PARKED) {
            movingSinceMs = null
            return
        }
        val now = ctx.now()
        val since = movingSinceMs ?: now.also { movingSinceMs = it }
        if (now - since < AUTO_START_MOVING_MS || !a.level.isAtMost(LoadLevel.NORMAL)) return
        val c = deps.commute.detect(s, st.commute, now)
        if (c.slot == CommuteSlot.OTHER || c.confidence < AUTO_START_CONFIDENCE) return
        autoCandidate = null
        val rem = s.routeRemainingSec
        if (s.routeActive && rem != null && rem < LearnThresholds.MIN_ROUTE_FOR_AUTO_SEC) return
        val start = when (c.slot) {
            CommuteSlot.MORNING -> st.settings.autoStartOnCommute
            CommuteSlot.EVENING -> when (st.settings.eveningStudy) {
                EveningStudy.ALWAYS -> true
                EveningStudy.NEVER -> false
                EveningStudy.ASK -> {
                    // 오늘 이미 답했으면 다시 묻지 않는다 (중간에 잠깐 세웠다가 다시 출발한 경우)
                    val answered = eveningAnswer?.takeIf { it.first == Kst.epochDay(now) }?.second
                    if (answered == null) {
                        eveningAsk.begin(cand)
                        return
                    }
                    answered
                }
            }
            CommuteSlot.OTHER -> false
        }
        if (start) startCommuteSession()
    }

    private fun startCommuteSession() {
        placement.suspendForDriving()
        stopGreeting()
        session.start(SessionRequest(origin = SessionOrigin.AUTO_COMMUTE))
    }

    /** 퇴근길 학습 질문의 대답 (음성 · 버튼 · 핸들 버튼) */
    private fun onEveningAnswer(yes: Boolean) {
        eveningAnswer = Kst.epochDay(ctx.now()) to yes
        if (yes && !session.active && ctx.learner.profile != null) startCommuteSession()
    }

    private fun onSessionEnded(r: SessionRun, reason: EndReason) {
        // 달리면서 한 학습(답한 문항 1개 이상) → P 단에 넣으면 대시보드
        if (r.drove && r.answered >= 1) {
            dashboardDue = true
            val arrived = reason == EndReason.ARRIVED || reason == EndReason.NEAR_ARRIVAL
            dashboardLine = Say.dashboardLine(arrived, r.answered, r.correct)
            showDashboardIfParked()
        }
        // 경로 중간에 잠깐 세워서 끝난 세션: 같은 주행으로 다시 출발하면 출퇴근 학습을 이어 간다 (출근길 자동 시작 · 퇴근길은 오늘 한 대답대로)
        if (reason == EndReason.PARKED) {
            autoCandidate = ctx.signals.driveId
            movingSinceMs = null
        }
        refreshIdleExtras()
    }

    // ───────────── 주차 대시보드 ─────────────

    /**
     * 주행 학습이 끝났고 정차 화면(P 단 · 부하 PARKED · 주의분산 제한 없음)이 되면 대시보드를 전체 화면으로 띄운다.
     * 완료 징글을 울리고, 징글이 끝날 무렵 한마디 건넨다. 옛 요약 시트와 겹치지 않게 요약은 내린다.
     */
    private fun showDashboardIfParked() {
        if (!dashboardDue || session.active) return
        val ui = ctx.ui.value
        if (ui.screenMode != ScreenMode.FULL || !ctx.signals.parked || ui.dashboard == null) return
        dashboardDue = false
        ctx.emit {
            it.copy(showParkedDashboard = true, summary = null, phase = if (it.phase == CoachPhase.SUMMARY) CoachPhase.IDLE else it.phase)
        }
        val jingle = ctx.cue(SoundCue.SESSION_COMPLETE)
        val line = dashboardLine
        dashboardLine = null
        stopGreeting()
        if (line != null) {
            greetJob = scope.launch {
                delay(if (jingle) GREET_AFTER_JINGLE_MS else GREET_DELAY_MS)
                if (ctx.ui.value.showParkedDashboard && !session.active && ctx.loadOk) ctx.say(line)
            }
        }
    }

    private fun stopGreeting() {
        greetJob?.cancel()
        greetJob = null
    }

    override fun dismissDashboard() {
        stopGreeting()
        ctx.emit { it.copy(showParkedDashboard = false) }
        refreshIdleExtras()
    }

    override fun showDashboard(): ActionResult {
        val ui = ctx.ui.value
        val d = ui.dashboard
        val (ko, en) = when {
            d == null -> Say.NO_DASHBOARD to "No results yet."
            ui.screenMode != ScreenMode.FULL -> Say.PARKED_ONLY to "Available only while parked."
            session.active -> Say.SESSION_RUNNING to "Finish the current session first."
            else -> {
                // 옛 요약 시트와 겹치지 않게 요약은 내린다
                ctx.emit {
                    it.copy(showParkedDashboard = true, summary = null, phase = if (it.phase == CoachPhase.SUMMARY) CoachPhase.IDLE else it.phase)
                }
                return ActionResult(true, d.headlineKo, "Showing your last session results.")
            }
        }
        ctx.toast(ko)
        return ActionResult(false, ko, en)
    }

    override fun reviewMistakes(): ActionResult {
        val ids = ctx.ui.value.dashboard?.mistakes.orEmpty().mapNotNull { it.itemId }.filter { deps.content.item(it) != null }
        dismissDashboard()
        return if (ids.isEmpty()) startReview() else startSession(SessionRequest(reviewOnly = true, itemIds = ids))
    }

    // ───────────── 퇴근길 학습 질문 ─────────────

    override fun answerEveningAsk(yes: Boolean) {
        eveningAsk.answer(yes)
    }

    // ───────────── 첫 실행 · 레벨 테스트 ─────────────

    override fun completeOnboarding(track: Track, goal: DailyGoal) {
        val day = Kst.epochDay(ctx.now())
        deps.repository.update { st ->
            val p = st.profile?.copy(track = track, goal = goal)
                ?: LearnerProfile(track = track, goal = goal, level = Level.INTERMEDIATE, placementDone = false, createdDay = day)
            st.copy(profile = p)
        }
        ctx.emit { if (it.phase == CoachPhase.ONBOARDING || it.phase == CoachPhase.BOOTING) it.copy(phase = CoachPhase.IDLE) else it }
    }

    override fun startPlacement() {
        val p = ctx.learner.profile
        when {
            p == null -> ctx.toast(Say.CHOOSE_TRACK_FIRST)
            !ctx.parked -> ctx.toast(Say.PARKED_ONLY)
            session.active -> ctx.toast(Say.SESSION_RUNNING)
            else -> placement.start(p.track)
        }
    }

    override fun choosePlacementAnswer(index: Int) = placement.choose(index)

    override fun skipPlacement(level: Level) {
        placement.reset()
        deps.repository.update { st ->
            val p = st.profile ?: return@update st
            st.copy(profile = p.copy(level = level, placementDone = true, placementCorrect = null))
        }
        dismissed += "placement"
        ctx.emit { it.copy(placement = null, phase = if (it.phase == CoachPhase.PLACEMENT) CoachPhase.IDLE else it.phase) }
        refreshIdleExtras()
    }

    override fun finishPlacement() {
        placement.finish()
        ctx.emit { it.copy(placement = null, phase = if (it.phase == CoachPhase.PLACEMENT) CoachPhase.IDLE else it.phase) }
        refreshIdleExtras()
    }

    // ───────────── 세션 ─────────────

    override fun startSession(request: SessionRequest): ActionResult {
        if (!ready) return ActionResult(false, "준비 중이에요", "Still loading.")
        if (session.active) return ActionResult(true, "이미 학습 중이에요.", "A session is already running.")
        if (placement.showing) placement.suspendForDriving()
        // 직접 시작하면 퇴근길 질문은 접고, 대시보드 인사도 멈춘다
        eveningAsk.cancel()
        stopGreeting()
        val res = session.start(request)
        if (!res.ok && ctx.learner.profile == null) ctx.toast(res.messageKo)
        return res
    }

    override fun stopSession() = session.finish(EndReason.USER)

    override fun dismissSummary() {
        ctx.emit { it.copy(summary = null, phase = if (it.phase == CoachPhase.SUMMARY) CoachPhase.IDLE else it.phase) }
        refreshIdleExtras()
    }

    override fun pause() = onCommand(LearnerCommand.PAUSE)

    override fun resume() = onCommand(LearnerCommand.RESUME)

    override fun onCommand(command: LearnerCommand) {
        when {
            session.active -> session.command(command)
            eveningAsk.active && (command == LearnerCommand.YES || command == LearnerCommand.NO) ->
                eveningAsk.answer(command == LearnerCommand.YES)
            placement.showing && command == LearnerCommand.REPEAT -> placement.repeatAudio()
            else -> Unit
        }
    }

    override fun onAnswerText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (eveningAsk.active && !session.active) {
            // 퇴근길 질문에 타이핑·adb 로 답함
            val yes = EveningAnswers.parse(t, deps.commands)
            if (yes != null) eveningAsk.answer(yes) else ctx.toast(Say.EVENING_HINT)
            return
        }
        if (session.answerText(t)) return
        val cmd = deps.commands.parse(t, ListenContext.ANSWER)
        if (cmd != null && session.active) {
            session.command(cmd)
            return
        }
        if (cmd != null && placement.showing && cmd == LearnerCommand.REPEAT) {
            placement.repeatAudio()
            return
        }
        ctx.toast(Say.NOT_ACCEPTING)
    }

    override fun chooseOption(index: Int) {
        when {
            placement.showing -> placement.choose(index)
            session.active -> session.choose(index)
        }
    }

    override fun onMediaKey(key: MediaKey) {
        when (key) {
            MediaKey.NEXT -> onCommand(LearnerCommand.SKIP)
            MediaKey.PREVIOUS -> onCommand(LearnerCommand.REPEAT)
            MediaKey.PLAY_PAUSE -> {
                val run = session.run
                if (run != null) {
                    onCommand(if (run.paused) LearnerCommand.RESUME else LearnerCommand.PAUSE)
                } else if (eveningAsk.active) {
                    // 퇴근길 질문 중 재생 버튼 = '할게요'
                    eveningAsk.answer(true)
                } else if (ctx.ui.value.phase == CoachPhase.IDLE && ctx.learner.profile != null) {
                    startSession(SessionRequest())
                }
            }
        }
    }

    override fun injectDemoAnswer(kind: DemoAnswerKind) {
        if (!deps.demoEnabled) return
        val ui = ctx.ui.value
        if (placement.showing) {
            val item = ui.placement?.item ?: return
            val idx = if (kind == DemoAnswerKind.CORRECT) item.answerIndex else item.choices.indices.firstOrNull { it != item.answerIndex } ?: return
            placement.choose(idx)
            return
        }
        if (ui.phase == CoachPhase.CONFIRM) {
            onAnswerText(if (kind == DemoAnswerKind.CORRECT) "yes" else "no")
            return
        }
        if (eveningAsk.active && !session.active) {
            eveningAsk.answer(kind == DemoAnswerKind.CORRECT)
            return
        }
        val item = session.run?.current?.item ?: deps.content.item(ui.prompt?.itemId ?: "") ?: return
        onAnswerText(DemoAnswers.make(item, kind))
    }

    // ───────────── 정차 전용 ─────────────

    override fun startReview(): ActionResult = startSession(SessionRequest(reviewOnly = true))

    override fun startPronDrill(type: PronIssueType): ActionResult = startSession(SessionRequest(pronDrill = type))

    // ───────────── 제안 카드 ─────────────

    override fun acceptSuggestion(id: String) {
        if (id.startsWith("confirm-")) {
            session.confirmWaiter?.complete(true)
            return
        }
        dismissed += id
        when {
            id == "placement" -> startPlacement()
            id.startsWith("review-") -> startReview()
            id.startsWith("pron-") -> {
                val type = PronIssueType.entries.firstOrNull { id.startsWith("pron-${it.name}-") }
                if (type != null) startPronDrill(type)
            }
            id.startsWith("commute-") -> startSession(SessionRequest())
            id.startsWith("travel-") -> {
                pendingHook = null
                val run = session.run
                if (run != null) session.switchTrack(run, Track.TRAVEL) else startSession(SessionRequest(track = Track.TRAVEL))
            }
            id.startsWith("hook-") -> {
                val e = pendingHook
                pendingHook = null
                if (e != null) session.enqueueHook(e)
                startSession(SessionRequest())
            }
        }
        refreshIdleExtras()
    }

    override fun dismissSuggestion(id: String) {
        if (id.startsWith("confirm-")) {
            session.confirmWaiter?.complete(false)
            return
        }
        dismissed += id
        if (id.startsWith("travel-") || id.startsWith("hook-")) pendingHook = null
        ctx.emit { if (it.suggestion?.id == id) it.copy(suggestion = null) else it }
        refreshIdleExtras()
    }

    // ───────────── Gleo · 설정 · 개인정보 ─────────────

    override fun saveExpression(text: String?): ActionResult {
        val given = text?.trim()?.takeIf { it.isNotEmpty() }
        val item = if (given == null) {
            session.currentItem() ?: deps.content.item(ctx.ui.value.prompt?.itemId ?: ctx.ui.value.feedback?.itemId ?: "")
        } else {
            deps.content.items().firstOrNull { it.answers.any { a -> a.trim().equals(given, ignoreCase = true) } }
        }
        val t = given ?: item?.modelAnswer?.trim()?.takeIf { it.isNotEmpty() }
        if (t == null) {
            ctx.toast(Say.NOTHING_TO_SAVE)
            return ActionResult(false, Say.NOTHING_TO_SAVE, "There is no expression to save.")
        }
        val now = ctx.now()
        deps.repository.update { st ->
            val rest = st.saved.filterNot { it.text.equals(t, ignoreCase = true) }
            st.copy(saved = (rest + SavedExpression(t, item?.id, now)).takeLast(MAX_SAVED))
        }
        ctx.toast(Say.SAVED)
        return ActionResult(true, Say.SAVED, "Saved the expression.")
    }

    override fun reviewNow(): ActionResult = if (session.active) session.reviewNow() else startReview()

    override fun updateSettings(transform: (LearnerSettings) -> LearnerSettings) {
        deps.repository.update { it.copy(settings = transform(it.settings)) }
    }

    override fun changeTrack(track: Track) {
        deps.repository.update { st -> st.profile?.let { st.copy(profile = it.copy(track = track)) } ?: st }
    }

    override fun changeGoal(goal: DailyGoal) {
        deps.repository.update { st -> st.profile?.let { st.copy(profile = it.copy(goal = goal)) } ?: st }
    }

    override fun eraseAllData() {
        session.finish(EndReason.ERASED)
        eveningAsk.cancel()
        stopGreeting()
        placement.reset()
        dismissed.clear()
        pendingHook = null
        autoCandidate = null
        eveningAnswer = null
        dashboardDue = false
        dashboardLine = null
        deps.repository.eraseAll()
        ctx.emit {
            EnglishUiState(
                ready = ready, phase = CoachPhase.ONBOARDING, load = it.load, rules = it.rules, drive = it.drive,
                screenMode = it.screenMode, speech = it.speech, demoEnabled = deps.demoEnabled,
                storage = deps.repository.storageInfo(), pulse = it.pulse,
            )
        }
        ctx.toast(Say.ERASED)
    }

    companion object {
        const val AUTO_START_MOVING_MS = 3_000L
        const val AUTO_START_CONFIDENCE = 0.7f
        const val DEFAULT_ROUTE_MIN = 32
        const val REVIEW_DUE_MIN = 10
        const val PRON_DRILL_MIN = 3
        const val COMMUTE_SOON_MIN = 15
        const val MAX_SAVED = 100
        const val STORAGE_REFRESH_MS = 1_500L
        /** 대시보드 한마디: 완료 징글(2.5초 이하)이 끝날 무렵 / 효과음이 꺼져 있을 때 */
        const val GREET_AFTER_JINGLE_MS = 2_400L
        const val GREET_DELAY_MS = 400L
    }
}
