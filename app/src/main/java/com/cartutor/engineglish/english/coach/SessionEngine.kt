package com.cartutor.engineglish.english.coach

import com.cartutor.engineglish.english.core.ActionResult
import com.cartutor.engineglish.english.core.CoachPhase
import com.cartutor.engineglish.english.core.CommuteSlot
import com.cartutor.engineglish.english.core.DrivingSignals
import com.cartutor.engineglish.english.core.EveningAskUi
import com.cartutor.engineglish.english.core.FluencySummary
import com.cartutor.engineglish.english.core.HookEvent
import com.cartutor.engineglish.english.core.HudIcon
import com.cartutor.engineglish.english.core.HudUi
import com.cartutor.engineglish.english.core.Ids
import com.cartutor.engineglish.english.core.Kst
import com.cartutor.engineglish.english.core.LearnThresholds
import com.cartutor.engineglish.english.core.LearnerCommand
import com.cartutor.engineglish.english.core.LearningItem
import com.cartutor.engineglish.english.core.ListeningUi
import com.cartutor.engineglish.english.core.LoadLevel
import com.cartutor.engineglish.english.core.ParkedDashboardState
import com.cartutor.engineglish.english.core.PlanKind
import com.cartutor.engineglish.english.core.PlanRequest
import com.cartutor.engineglish.english.core.PlannedItem
import com.cartutor.engineglish.english.core.SessionRecord
import com.cartutor.engineglish.english.core.SessionRequest
import com.cartutor.engineglish.english.core.SessionSummaryUi
import com.cartutor.engineglish.english.core.SessionUi
import com.cartutor.engineglish.english.core.SpeechThresholds
import com.cartutor.engineglish.english.core.TtsRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 세션이 끝난 이유.
 * NEAR_ARRIVAL = 도착 1분 전이라 더 묻지 않고 끝냄, PARKED = 달리다가 경로 중간에 P 단 (답한 문항이 있을 때),
 * ARRIVED = 도착 (내비 도착 신호 · 경로 끝 무렵 P 단).
 */
internal enum class EndReason { ARRIVED, NEAR_ARRIVAL, PARKED, USER, PLAN_DONE, ERASED, RELEASED }

/** 턴을 (다시) 시작하는 이유 — 지금 문항에 무엇부터 할지 */
internal sealed interface Entry {
    data object Fresh : Entry
    data object ResumeHeld : Entry
    data object ResumePaused : Entry
    data object Repeat : Entry
    data object RepeatModel : Entry
    data object Slower : Entry
    data object Hint : Entry
    data object Meaning : Entry
    data object ListenNow : Entry
    data object DontKnow : Entry
    data object Skip : Entry
    data class Choice(val index: Int) : Entry
}

/**
 * 세션 상태 기계 (명세 §7). 한 세션에 '턴' 코루틴 하나 — 부하·명령·답 주입이 오면 턴을 취소하고
 * 남은 상태(SessionRun)에서 새 턴을 이어 간다. 턴 본문은 SessionTurns.kt.
 */
internal class SessionEngine(internal val ctx: CoachContext, private val onEnded: (SessionRun, EndReason) -> Unit) {
    internal val deps get() = ctx.deps
    var run: SessionRun? = null
        private set
    internal var turnJob: Job? = null
    private var sideJob: Job? = null
    internal var confirmWaiter: CompletableDeferred<Boolean?>? = null
    internal val hookQueue = ArrayDeque<HookEvent>()
    /** 턴에서 예상하지 못한 오류 (테스트 확인용 — 사용자 데이터는 담지 않는다) */
    var lastError: Throwable? = null
        private set

    val active: Boolean get() = run != null

    // ───────────── 시작 · 끝 ─────────────

    fun start(request: SessionRequest): ActionResult {
        val profile = ctx.learner.profile
            ?: return ActionResult(false, Say.NEED_PROFILE, "Please finish the first setup while parked.")
        if (run != null) return ActionResult(true, "이미 학습 중이에요.", "A session is already running.")
        val now = ctx.now()
        val s = ctx.signals
        val st = ctx.learner
        val commute = deps.commute.detect(s, st.commute, now)
        val mode = request.mode ?: st.settings.modeFor(commute.slot)
        val track = request.track ?: profile.track
        val parked = ctx.parked
        val remaining = if (!parked && s.routeActive) (s.routeRemainingSec ?: commute.routeRemainingSec) else null
        // 도착 1분 전부터는 주행 학습을 하지 않는다 (시작하자마자 끝나지 않게)
        if (!s.parked && remaining != null && remaining <= LearnThresholds.END_BEFORE_ARRIVAL_SEC) {
            ctx.toast(Say.ARRIVING_TOO_SOON)
            return ActionResult(false, Say.ARRIVING_TOO_SOON, "Almost there. Please start after you arrive.")
        }
        var plan = deps.planner.plan(
            PlanRequest(
                state = st, slot = commute.slot, mode = mode, track = track, level = profile.level,
                routeRemainingSec = remaining, parked = parked, nowMs = now,
                reviewOnly = request.reviewOnly, pronDrill = request.pronDrill,
            ),
            deps.content,
        )
        // 대시보드의 '틀린 표현 복습' — 고른 문항만 차례로
        val chosen = request.itemIds.distinct().filter { deps.content.item(it) != null }
        if (chosen.isNotEmpty()) {
            plan = plan.copy(
                items = chosen.map { PlannedItem(it, PlanKind.MISTAKE) }, newCount = 0, reviewCount = chosen.size,
                titleKo = "틀린 표현 ${chosen.size}개 다시 보기", wrapUpItemId = null,
            )
        }
        if (plan.items.isEmpty()) {
            val msg = if (request.reviewOnly) Say.NOTHING_TO_REVIEW else Say.NO_ITEMS
            ctx.toast(msg)
            return ActionResult(false, msg, if (request.reviewOnly) "Nothing to review right now." else "No questions are available right now.")
        }
        val r = SessionRun(Ids.next("s", now), request, now, plan, commute, parked)
        plan.items.forEach { r.queue.addLast(QueueEntry(it.itemId, it.kind)) }
        r.sawRoute = s.routeActive && !parked
        r.drove = !s.parked
        // 대시보드의 '세션 전' 진행률
        r.before = deps.progression.snapshot(st, deps.content, now)
        // 10분 넘은 상황 훅은 버린다
        hookQueue.removeAll { now - it.atMs > LearnThresholds.HOOK_COOLDOWN_MS }
        run = r
        deps.mediaKeys.setActive(true)
        ctx.emit {
            it.copy(
                phase = CoachPhase.BRIEFING, session = sessionUi(r), prompt = null, feedback = null,
                summary = null, suggestion = null, listening = ListeningUi(),
                eveningAsk = EveningAskUi(), showParkedDashboard = false,
            )
        }
        launchTurn(Entry.Fresh)
        return ActionResult(true, plan.titleKo, "English session started: ${plan.newCount} new and ${plan.reviewCount} review items.")
    }

    fun finish(reason: EndReason) {
        val r = run ?: return
        if (r.finished) return
        r.finished = true
        val old = turnJob
        old?.cancel()
        turnJob = null
        sideJob?.cancel()
        confirmWaiter = null
        deps.speech.stopAll()
        deps.feedback.showHudHint(null)
        deps.mediaKeys.setActive(false)
        hookQueue.clear()
        run = null
        val now = ctx.now()
        if (reason == EndReason.ERASED || reason == EndReason.RELEASED) return
        val record = SessionRecord(
            id = r.id, startedMs = r.startedMs, endedMs = now, slot = r.plan.slot, mode = r.plan.mode, track = r.track,
            answered = r.answered, correct = r.correct, partial = r.partial, interrupted = r.interrupted, xp = r.xp,
        )
        val arriving = reason == EndReason.ARRIVED || reason == EndReason.NEAR_ARRIVAL
        deps.repository.update { st ->
            var next = deps.progression.recordSession(st, record)
            if (arriving && r.commute.slot != CommuteSlot.OTHER) {
                next = next.copy(commute = deps.commute.learn(next.commute, r.commute, r.startedMs))
            }
            next
        }
        val summary = buildSummary(r, now)
        // 주차 대시보드 재료 — 답한 문항이 있을 때만 새로 만든다 (없으면 지난 결과를 그대로 둔다)
        val dashboard = if (r.answered >= 1) buildDashboard(r, now, summary) else null
        ctx.emit {
            it.copy(
                phase = CoachPhase.SUMMARY, summary = summary, dashboard = dashboard ?: it.dashboard, session = null,
                prompt = null, feedback = null, listening = ListeningUi(), hud = HudUi(load = ctx.level),
            )
        }
        // 주행 학습 뒤 P 단에서 대시보드가 뜰 세션은 인사를 대시보드와 함께 한다 (코치가 징글 뒤에 말한다)
        val greetWithDashboard = dashboard != null && r.drove && (reason == EndReason.ARRIVED || reason == EndReason.PARKED)
        val line = when {
            greetWithDashboard -> null
            reason == EndReason.NEAR_ARRIVAL -> Say.ARRIVING_SOON
            reason == EndReason.ARRIVED -> Say.arrival(r.answered, r.correct)
            else -> Say.ended(r.answered, r.correct)
        }
        // 마무리 인사는 부하가 내려갈 때까지 잠깐 기다렸다가 (HOLD 중 '그만'은 말없이 요약만)
        if (line != null && (ctx.loadOk || arriving)) {
            sideJob = ctx.scope.launch {
                old?.join()
                if (!ctx.loadOk) {
                    withTimeoutOrNull(ARRIVAL_LINE_WAIT_MS) { ctx.load.first { it.level.isAtMost(LoadLevel.NORMAL) } } ?: return@launch
                }
                if (ctx.ui.value.phase == CoachPhase.SUMMARY) ctx.say(line)
            }
        }
        onEnded(r, reason)
    }

    // ───────────── 턴 실행 ─────────────

    internal fun launchTurn(entry: Entry, delayMs: Long = 0L) {
        val r = run ?: return
        val old = turnJob
        old?.cancel()
        val job = ctx.scope.launch(start = CoroutineStart.LAZY) {
            old?.join()
            if (delayMs > 0) delay(delayMs)
            try {
                turnLoop(r, entry)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
                recoverFromError(r)
            }
        }
        turnJob = job
        job.start()
    }

    private fun recoverFromError(r: SessionRun) {
        if (run !== r) return
        ctx.toast("문제가 생겨 세션을 정리했어요")
        finish(EndReason.USER)
    }

    /** 부하가 올라감 → 말하기·듣기를 멈추고 HOLD. 지금 문항은 채점하지 않고 그대로 둔다 */
    fun hold(stopNow: Boolean) {
        val r = run ?: return
        if (r.finished) return
        turnJob?.cancel()
        turnJob = null
        if (stopNow) deps.speech.stopAll()
        if (r.paused) return
        if (!r.holding) {
            r.holding = true
            if (r.current != null) r.interrupted++
        }
        deps.feedback.showHudHint(null)
        ctx.emit {
            it.copy(
                phase = CoachPhase.HOLD, prompt = null, listening = ListeningUi(),
                hud = HudUi(icon = HudIcon.HOLD, load = ctx.level), session = sessionUi(r),
            )
        }
    }

    private fun scheduleResume() {
        val r = run ?: return
        if (!r.holding || r.paused || r.finished) return
        launchTurn(Entry.ResumeHeld, RESUME_DELAY_MS)
    }

    /** 부하 단계가 바뀔 때마다 (명세 §7 Load interruption) */
    fun onLoadChanged(prev: LoadLevel, level: LoadLevel) {
        val r = run ?: return
        if (r.finished) return
        val rules = deps.policy.rules(level)
        if (!rules.newQuestions || !rules.listening) {
            when {
                rules.tts == TtsRule.STOP_NOW || r.paused -> hold(stopNow = true)
                r.holding -> hold(stopNow = false)
                else -> {
                    val phase = ctx.ui.value.phase
                    val sp = ctx.speaking
                    val finishShort = rules.tts == TtsRule.FINISH_SHORT && sp != null && phase != CoachPhase.LISTENING &&
                        sp.remainingMs(ctx.now()) < SpeechThresholds.FOCUS_FINISH_TTS_MS
                    // 2초 안에 끝나는 말은 마저 하고, 다음 단계의 gate() 가 HOLD 로 보낸다
                    if (!finishShort) hold(stopNow = true)
                }
            }
            return
        }
        if (r.holding) {
            if (!r.paused) scheduleResume()
            return
        }
        if ((prev == LoadLevel.PARKED) != (level == LoadLevel.PARKED)) onParkedChanged(r, level)
        ctx.poke()
    }

    /** 정차 ↔ 주행: 보기·영문 표시를 다시 만들고, 주행 중 낼 수 없는 문항이면 대기열로 돌린다 */
    private fun onParkedChanged(r: SessionRun, level: LoadLevel) {
        val cur = r.current
        if (cur != null && level != LoadLevel.PARKED && !deps.policy.allows(cur.item, level)) {
            turnJob?.cancel()
            turnJob = null
            deps.speech.stopAll()
            r.queue.addFirst(cur.entry)
            r.current = null
            launchTurn(Entry.Fresh)
            return
        }
        refreshPrompt()
    }

    internal fun refreshPrompt() {
        val r = run ?: return
        val cur = r.current ?: return
        if (ctx.ui.value.prompt?.itemId != cur.item.id) return
        ctx.emit { it.copy(prompt = promptFor(cur, explain = cur.attempt > 1)) }
    }

    internal fun promptFor(a: ActiveItem, explain: Boolean = false) = CoachViews.prompt(
        item = a.item, kind = a.kind, titleKo = a.kind.ko, parked = ctx.parked, rules = ctx.rules,
        attempt = a.attempt, hintShown = a.hintUsed, explain = explain,
    )

    /** 운전 신호 (세션 관련: 도착 · 도착 1분 전 마무리 · 주차) */
    fun onSignals(prev: DrivingSignals, s: DrivingSignals) {
        val r = run ?: return
        if (r.finished) return
        if (!s.parked) r.drove = true
        if (s.routeActive && !s.parked) r.sawRoute = true
        // 도착: 내비 도착 신호, 또는 경로 끝 무렵(또는 경로가 끝난 뒤) P 단. 경로 중간에 잠깐 세운 건 도착이 아니다
        val parkedNow = s.parked && !prev.parked
        val nearEnd = !s.routeActive || (prev.routeRemainingSec ?: Int.MAX_VALUE) <= ARRIVAL_NEAR_SEC
        if ((s.arrived && !prev.arrived) || (r.sawRoute && parkedNow && nearEnd)) {
            finish(EndReason.ARRIVED)
            return
        }
        // 달리다가 경로 중간에 세움: 답한 문항이 있으면 여기서 끝내고 주차 대시보드로 (없으면 정차 학습으로 이어 간다)
        if (parkedNow && r.drove && r.answered >= 1) {
            finish(EndReason.PARKED)
            return
        }
        // 도착 1분 전: 더 묻지 않고 끝낸다 — "곧 도착해요. 오늘 학습은 여기까지 할게요."
        val rem = s.routeRemainingSec
        if (s.routeActive && !s.parked && rem != null && rem <= LearnThresholds.END_BEFORE_ARRIVAL_SEC) {
            finish(EndReason.NEAR_ARRIVAL)
        }
    }

    fun enqueueHook(e: HookEvent) {
        if (hookQueue.any { it.trigger == e.trigger }) return
        hookQueue.addLast(e)
        while (hookQueue.size > 3) hookQueue.removeFirst()
        ctx.poke()
    }

    // ───────────── 명령 · 답 ─────────────

    fun command(cmd: LearnerCommand) {
        val r = run ?: return
        if (r.finished) return
        val phase = ctx.ui.value.phase
        if (cmd == LearnerCommand.END_SESSION) {
            finish(EndReason.USER)
            return
        }
        if (phase == CoachPhase.CONFIRM && (cmd == LearnerCommand.YES || cmd == LearnerCommand.NO)) {
            confirmWaiter?.complete(cmd == LearnerCommand.YES)
            return
        }
        if (r.paused) {
            if (cmd == LearnerCommand.RESUME) resumeFromPause()
            return
        }
        if (r.holding) {
            // HOLD 중엔 아무 말도 하지 않는다: 잠깐 → 조용히 일시정지, 나머지는 무시 (부하가 내려가면 자동으로 이어감)
            if (cmd == LearnerCommand.PAUSE) pauseNow(speak = false)
            return
        }
        val cur = r.current
        when (cmd) {
            LearnerCommand.PAUSE -> pauseNow(speak = true)
            LearnerCommand.RESUME, LearnerCommand.YES, LearnerCommand.NO, LearnerCommand.END_SESSION -> Unit
            LearnerCommand.SKIP -> {
                if (cur != null) relaunch(Entry.Skip)
                else if (phase == CoachPhase.BRIEFING || phase == CoachPhase.FEEDBACK) {
                    r.briefed = true
                    relaunch(Entry.Fresh)
                }
            }
            LearnerCommand.REPEAT -> when {
                cur != null -> relaunch(Entry.Repeat)
                r.lastItem != null && phase == CoachPhase.FEEDBACK -> relaunch(Entry.RepeatModel)
            }
            LearnerCommand.SLOWER -> if (cur != null) relaunch(Entry.Slower)
            LearnerCommand.HINT -> if (cur != null) relaunch(Entry.Hint)
            LearnerCommand.MEANING -> if (cur != null) relaunch(Entry.Meaning)
            LearnerCommand.DONT_KNOW -> if (cur != null) relaunch(Entry.DontKnow)
        }
    }

    private fun relaunch(entry: Entry) {
        // 턴을 먼저 취소해야 멈춘 듣기(Stopped)를 '침묵'으로 오해하지 않는다
        turnJob?.cancel()
        deps.speech.stopAll()
        launchTurn(entry)
    }

    private fun pauseNow(speak: Boolean) {
        val r = run ?: return
        r.paused = true
        val old = turnJob
        old?.cancel()
        turnJob = null
        deps.speech.stopAll()
        deps.feedback.showHudHint(null)
        ctx.emit {
            it.copy(
                phase = CoachPhase.PAUSED, listening = ListeningUi(),
                hud = HudUi(icon = HudIcon.PAUSED, load = ctx.level), session = sessionUi(r),
            )
        }
        sideJob?.cancel()
        if (speak && ctx.loadOk) {
            sideJob = ctx.scope.launch {
                old?.join()
                ctx.say(Say.PAUSED)
            }
        }
    }

    private fun resumeFromPause() {
        val r = run ?: return
        r.paused = false
        sideJob?.cancel()
        if (!ctx.loadOk) {
            // 아직 운전 집중 구간 → HOLD 로 (부하가 내려가면 자동으로 이어감)
            r.holding = true
            hold(stopNow = false)
            return
        }
        r.holding = false
        launchTurn(Entry.ResumePaused)
    }

    /** 타이핑·adb·데모 답. 처리했으면 true (false 면 코치가 명령/토스트로 처리) */
    fun answerText(text: String): Boolean {
        val r = run ?: return false
        if (r.finished) return false
        when (ctx.ui.value.phase) {
            CoachPhase.LISTENING, CoachPhase.CONFIRM -> {
                deps.speech.inject(text)
                return true
            }
            CoachPhase.PROMPTING, CoachPhase.FEEDBACK, CoachPhase.JUDGING, CoachPhase.BRIEFING -> {
                if (r.current == null || r.holding || r.paused) return false
                // 말하는 중에 끼어든 답 → TTS 멈추고 바로 듣기로
                turnJob?.cancel()
                deps.speech.stopAll()
                deps.speech.inject(text)
                launchTurn(Entry.ListenNow)
                return true
            }
            else -> return false
        }
    }

    fun choose(index: Int) {
        val r = run ?: return
        if (r.current == null || r.holding || r.paused || !ctx.parked) return
        val choices = ctx.ui.value.prompt?.choices.orEmpty()
        if (index !in choices.indices) return
        relaunch(Entry.Choice(index))
    }

    fun reviewNow(): ActionResult {
        val r = run ?: return ActionResult(false, Say.NOTHING_TO_REVIEW, "Nothing to review right now.")
        val now = ctx.now()
        val front = mutableListOf<QueueEntry>()
        val queued = r.queue.filter { it.kind == PlanKind.REVIEW || it.kind == PlanKind.MISTAKE }
        r.queue.removeAll(queued)
        front += queued
        val curId = r.current?.item?.id
        deps.scheduler.due(ctx.learner.cards.values, now).map { it.itemId }
            .filter { id -> id != curId && id !in r.asked && front.none { it.itemId == id } && r.queue.none { it.itemId == id } }
            .filter { deps.content.item(it) != null }
            .take(5)
            .forEach { front += QueueEntry(it, PlanKind.REVIEW) }
        if (front.isEmpty()) {
            ctx.toast(Say.NOTHING_TO_REVIEW)
            return ActionResult(false, Say.NOTHING_TO_REVIEW, "Nothing to review right now.")
        }
        front.asReversed().forEach { r.queue.addFirst(it) }
        publishSession()
        ctx.toast(Say.REVIEW_FIRST)
        return ActionResult(true, "복습 ${front.size}개를 먼저 할게요.", "Moved ${front.size} review items to the front.")
    }

    /** 저장할 표현 — 지금 문항, 없으면 방금 문항 */
    fun currentItem(): LearningItem? = run?.current?.item ?: run?.lastItem

    fun release() {
        finish(EndReason.RELEASED)
        sideJob?.cancel()
    }

    // ───────────── 화면 조각 ─────────────

    internal fun sessionUi(r: SessionRun): SessionUi {
        val index = r.asked.size
        val total = maxOf(index, r.asked.size + r.queue.count { it.itemId !in r.asked })
        return SessionUi(
            planId = r.plan.id, titleKo = r.plan.titleKo, slot = r.plan.slot, mode = r.plan.mode, track = r.track,
            index = index.coerceAtLeast(if (total > 0) 1 else 0), total = total,
            answered = r.answered, correct = r.correct, partial = r.partial,
            newDone = r.newDone, reviewDone = r.reviewDone, interrupted = r.interrupted, xpGained = r.xp,
            startedAtMs = r.startedMs, streak = r.streak,
        )
    }

    internal fun publishSession() {
        val r = run ?: return
        ctx.emit { it.copy(session = sessionUi(r)) }
    }

    private fun buildSummary(r: SessionRun, now: Long): SessionSummaryUi {
        val minutes = CoachViews.sessionMinutes(r.startedMs, now, r.answered)
        return SessionSummaryUi(
            titleKo = Say.summaryTitle(r.plan.slot),
            minutes = minutes,
            answered = r.answered,
            correct = r.correct,
            partial = r.partial,
            skipped = r.skipped,
            interrupted = r.interrupted,
            xpGained = r.xp,
            newLearned = r.newDone,
            reviewed = r.reviewDone,
            bestSentence = pickWrapUpItem(r)?.modelAnswer,
            pronTips = r.pronIssues.distinctBy { it.type }.take(3),
            fluency = sessionFluency(r, now),
            events = r.events.toList(),
        )
    }

    /** 주차 대시보드 — 세션 결과 + 세션 전 → 후 진행률 (학습자 상태는 이미 이 세션의 답을 반영한 뒤) */
    private fun buildDashboard(r: SessionRun, now: Long, summary: SessionSummaryUi): ParkedDashboardState =
        CoachViews.dashboard(
            r = r, endedMs = now, after = deps.progression.snapshot(ctx.learner, deps.content, now),
            content = deps.content, pron = deps.pronunciation, bestSentence = summary.bestSentence,
        )

    private fun sessionFluency(r: SessionRun, now: Long): FluencySummary? {
        if (r.latencies.isEmpty()) return null
        val avg = r.latencies.average().toLong()
        val today = Kst.epochDay(now)
        val prev = ctx.learner.fluency.filter { it.day < today && it.samples > 0 }
        val prevSamples = prev.sumOf { it.samples }
        val trend = if (prevSamples > 0) avg - prev.sumOf { it.avgLatencyMs * it.samples } / prevSamples else 0L
        return FluencySummary(
            avgLatencyMs = avg,
            avgWpm = if (r.wpms.isEmpty()) 0 else r.wpms.average().toInt(),
            latencyTrendMs = trend,
            samples = r.latencies.size,
        )
    }

    /** 결과 화면의 '오늘의 한 문장' — 이번 세션에서 점수가 가장 낮았던 짧은 문장, 없으면 계획의 후보 (주행 중엔 따로 묻지 않는다) */
    internal fun pickWrapUpItem(r: SessionRun): LearningItem? {
        val short = { it: LearningItem -> it.modelAnswer.isNotBlank() && it.answerWords <= WRAP_MAX_WORDS }
        // '한 문장'이니 단어 하나짜리보다 따라 말할 수 있는 문장(3단어 이상)을 먼저 고른다
        val sentence = { it: LearningItem -> short(it) && it.answerWords >= WRAP_MIN_WORDS }
        val weakest = r.scores.entries.sortedBy { it.value }.mapNotNull { deps.content.item(it.key) }
        val planned = r.plan.wrapUpItemId?.let { deps.content.item(it) }
        val asked = r.asked.mapNotNull { deps.content.item(it) }
        for (ok in listOf(sentence, short)) {
            weakest.firstOrNull(ok)?.let { return it }
            planned?.takeIf(ok)?.let { return it }
            asked.firstOrNull(ok)?.let { return it }
        }
        return null
    }

    companion object {
        const val RESUME_DELAY_MS = 1_500L
        const val GAP_MS = 600L
        const val WRAP_MAX_WORDS = 12
        const val WRAP_MIN_WORDS = 3
        const val CONFIRM_LISTEN_MS = 8_000L
        const val EXTEND_COUNT = 5
        /** 경로 남은 시간이 이 이하에서 P 단이면 도착으로 본다 */
        const val ARRIVAL_NEAR_SEC = 300
        const val ARRIVAL_LINE_WAIT_MS = 15_000L
    }
}
