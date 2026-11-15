package com.cartutor.engineglish.english.coach

import com.cartutor.engineglish.english.core.AnswerOutcome
import com.cartutor.engineglish.english.core.CoachPhase
import com.cartutor.engineglish.english.core.FeedbackUi
import com.cartutor.engineglish.english.core.FluencyMetrics
import com.cartutor.engineglish.english.core.Grade
import com.cartutor.engineglish.english.core.HookEvent
import com.cartutor.engineglish.english.core.HookTrigger
import com.cartutor.engineglish.english.core.HudIcon
import com.cartutor.engineglish.english.core.HudUi
import com.cartutor.engineglish.english.core.JudgeResult
import com.cartutor.engineglish.english.core.LearnThresholds
import com.cartutor.engineglish.english.core.LearnerCommand
import com.cartutor.engineglish.english.core.LearningItem
import com.cartutor.engineglish.english.core.ListenContext
import com.cartutor.engineglish.english.core.ListenOutcome
import com.cartutor.engineglish.english.core.ListeningUi
import com.cartutor.engineglish.english.core.MicState
import com.cartutor.engineglish.english.core.PlanKind
import com.cartutor.engineglish.english.core.PlanRequest
import com.cartutor.engineglish.english.core.ProgressEvent
import com.cartutor.engineglish.english.core.ProgressUpdate
import com.cartutor.engineglish.english.core.PulseKind
import com.cartutor.engineglish.english.core.SoundCue
import com.cartutor.engineglish.english.core.SpeakOutcome
import com.cartutor.engineglish.english.core.SpeechLang
import com.cartutor.engineglish.english.core.SpeechRate
import com.cartutor.engineglish.english.core.SuggestionKind
import com.cartutor.engineglish.english.core.SuggestionUi
import com.cartutor.engineglish.english.core.Track
import com.cartutor.engineglish.english.core.Verdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

/*
 * 세션 턴 본문 — SessionEngine.launchTurn 이 부른다. 모든 말하기·듣기 앞에서 gate() 로 부하를 확인하고,
 * 부하가 FOCUS 이상이면 HOLD 로 바꾼 뒤 이 턴을 끝낸다 (지금 문항은 SessionRun.current 에 남는다).
 */

internal suspend fun SessionEngine.turnLoop(r: SessionRun, entry: Entry) {
    r.holding = false
    var e = entry
    if (!r.briefed) {
        brief(r)
        r.briefed = true
        e = Entry.Fresh
    } else if (e == Entry.ResumeHeld || e == Entry.ResumePaused) {
        val cur = r.current
        if (cur == null) {
            speak(Say.CONTINUE)
        } else if (!deps.policy.allows(cur.item, ctx.level)) {
            r.queue.addFirst(cur.entry)
            r.current = null
            speak(Say.NEXT_INSTEAD)
            e = Entry.Fresh
        }
    } else if (e == Entry.RepeatModel) {
        r.lastItem?.let { speak(it.modelAnswer, SpeechLang.EN) }
        e = Entry.Fresh
    }
    while (true) {
        gate()
        val cur = r.current
        if (cur != null) {
            runItem(r, cur, e)
            e = Entry.Fresh
            if (r.finished) return
            delay(SessionEngine.GAP_MS)
            continue
        }
        e = Entry.Fresh
        if (!nextStep(r)) return
    }
}

/** 부하 확인: FOCUS 이상이면 HOLD 로 바꾸고 이 턴을 끝낸다 */
internal suspend fun SessionEngine.gate() {
    currentCoroutineContext().ensureActive()
    if (!ctx.loadOk) {
        hold(stopNow = false)
        throw CancellationException("load")
    }
}

internal suspend fun SessionEngine.speak(
    text: String,
    lang: SpeechLang = SpeechLang.KO,
    rate: SpeechRate = SpeechRate.NORMAL,
): SpeakOutcome {
    gate()
    return ctx.say(text, lang, rate)
}

private suspend fun SessionEngine.brief(r: SessionRun) {
    ctx.emit {
        it.copy(phase = CoachPhase.BRIEFING, session = sessionUi(r), prompt = null, feedback = null, listening = ListeningUi(), hud = HudUi(load = ctx.level))
    }
    speak(Say.briefing(r.plan, r.request))
}

/** 다음에 할 일 하나 (상황 훅 → 대기열). false = 세션 끝 */
private suspend fun SessionEngine.nextStep(r: SessionRun): Boolean {
    if (ctx.settings.contextHooks && hookQueue.isNotEmpty()) {
        deliverHook(r, hookQueue.removeFirst())
    }
    val next = pickAllowed(r)
    if (next != null) {
        val item = deps.content.item(next.itemId) ?: return true
        r.current = ActiveItem(item, next)
        r.asked += item.id
        publishSession()
        return true
    }
    if (r.queue.isNotEmpty()) {
        // 지금 부하에서 낼 수 있는 문항이 없다 → 조용히 기다렸다가 부하가 바뀌면 다시 본다
        waitForChange(r)
        return true
    }
    return planExhausted(r)
}

private fun SessionEngine.pickAllowed(r: SessionRun): QueueEntry? {
    r.queue.removeAll { deps.content.item(it.itemId) == null }
    val chosen = r.queue.firstOrNull { q ->
        val item = deps.content.item(q.itemId)
        item != null && deps.policy.allows(item, ctx.level)
    } ?: return null
    r.queue.remove(chosen)
    return chosen
}

private suspend fun SessionEngine.waitForChange(r: SessionRun) {
    val seen = ctx.wake.value
    ctx.emit {
        it.copy(
            phase = if (it.feedback != null) CoachPhase.FEEDBACK else CoachPhase.BRIEFING, prompt = null,
            listening = ListeningUi(), hud = HudUi(load = ctx.level), session = sessionUi(r),
        )
    }
    ctx.wake.first { it != seen }
}

/** 계획을 다 했을 때: 경로가 남았으면 더 할지 묻고, 아니면 요약 */
private suspend fun SessionEngine.planExhausted(r: SessionRun): Boolean {
    val s = ctx.signals
    val rem = s.routeRemainingSec
    if (ctx.parked || !s.routeActive || rem == null) {
        finish(EndReason.PLAN_DONE)
        return false
    }
    if (r.extendCount < MAX_EXTENDS && rem > LearnThresholds.END_BEFORE_ARRIVAL_SEC + EXTEND_MIN_EXTRA_SEC) {
        val yes = confirm(r, Say.EXTEND_Q, SuggestionKind.REVIEW_DUE)
        if (yes != true) {
            finish(EndReason.PLAN_DONE)
            return false
        }
        r.extendCount++
        if (extendPlan(r) > 0) {
            speak(Say.EXTEND_YES)
            return true
        }
        r.extendCount = MAX_EXTENDS
    }
    // 더 낼 문항이 없다 → 여기서 마무리 (주차하면 대시보드가 뜬다)
    finish(EndReason.PLAN_DONE)
    return false
}

private const val MAX_EXTENDS = 3
private const val EXTEND_MIN_EXTRA_SEC = 60

internal fun SessionEngine.planRequest(r: SessionRun): PlanRequest {
    val st = ctx.learner
    val s = ctx.signals
    return PlanRequest(
        state = st, slot = r.plan.slot, mode = r.plan.mode, track = r.track, level = st.profile?.level ?: r.plan.level,
        routeRemainingSec = if (!ctx.parked && s.routeActive) s.routeRemainingSec else null,
        parked = ctx.parked, nowMs = ctx.now(), reviewOnly = r.request.reviewOnly, pronDrill = r.request.pronDrill,
        exclude = r.asked + r.queue.map { it.itemId },
    )
}

private fun SessionEngine.extendPlan(r: SessionRun): Int {
    val ext = deps.planner.extend(r.plan, planRequest(r), deps.content, SessionEngine.EXTEND_COUNT)
    val known = r.asked + r.queue.map { it.itemId }
    val added = ext.items.filter { it.itemId !in known }.distinctBy { it.itemId }
    added.forEach { r.queue.addLast(QueueEntry(it.itemId, it.kind)) }
    r.plan = ext
    publishSession()
    return added.size
}

/** 예/아니요 질문 — 음성(ko, CONFIRM 문맥) 또는 버튼(onCommand YES/NO · 제안 카드). 답이 없으면 null */
private suspend fun SessionEngine.confirm(r: SessionRun, questionKo: String, kind: SuggestionKind): Boolean? {
    val waiter = CompletableDeferred<Boolean?>()
    confirmWaiter = waiter
    r.waitingConfirm = true
    val sid = "confirm-${r.id}-${kind.name}-${r.extendCount}"
    ctx.emit {
        it.copy(
            phase = CoachPhase.CONFIRM, prompt = null, listening = ListeningUi(),
            suggestion = SuggestionUi(sid, kind, questionKo, Say.CONFIRM_HINT, "네", "아니요"),
        )
    }
    try {
        // 음성(질문 → 듣기)과 버튼(네/아니요) 중 먼저 온 답. 버튼을 누르면 말하던 질문도 바로 멈춘다
        return coroutineScope {
            val voice = async {
                speak(questionKo)
                gate()
                ctx.emit { it.copy(listening = ListeningUi(mic = MicState.LISTENING, deadlineMs = ctx.now() + SessionEngine.CONFIRM_LISTEN_MS)) }
                val o = deps.speech.listen(SpeechLang.KO, SessionEngine.CONFIRM_LISTEN_MS)
                if (o is ListenOutcome.Heard) {
                    when (deps.commands.parse(o.text, ListenContext.CONFIRM)) {
                        LearnerCommand.YES -> true
                        LearnerCommand.NO -> false
                        else -> null
                    }
                } else {
                    null
                }
            }
            select<Boolean?> {
                waiter.onAwait { answer ->
                    voice.cancel()
                    answer
                }
                voice.onAwait { it }
            }
        }
    } finally {
        if (confirmWaiter === waiter) confirmWaiter = null
        r.waitingConfirm = false
        ctx.emit { it.copy(suggestion = if (it.suggestion?.id == sid) null else it.suggestion, listening = ListeningUi()) }
    }
}

// ───────────── 상황 훅 ─────────────

private suspend fun SessionEngine.deliverHook(r: SessionRun, ev: HookEvent) {
    val hook = deps.content.hook(ev.trigger) ?: return
    if (ev.trigger == HookTrigger.AIRPORT && !r.airportHandled && r.track != Track.TRAVEL) {
        try {
            val yes = confirm(r, Say.AIRPORT_Q, SuggestionKind.SWITCH_TRACK_TRAVEL)
            r.airportHandled = true
            if (yes == true) {
                switchTrack(r, Track.TRAVEL)
                speak(Say.TRAVEL_YES)
            } else {
                speak(Say.KEEP_TRACK)
            }
        } catch (c: CancellationException) {
            if (!r.airportHandled) hookQueue.addFirst(ev)
            throw c
        }
    }
    val cards = ctx.learner.cards
    val candidates = hook.items.filter { it.id !in r.asked && r.queue.none { q -> q.itemId == it.id } && deps.policy.allows(it, ctx.level) }
    val item = candidates.firstOrNull { cards[it.id] == null } ?: candidates.firstOrNull() ?: return
    r.queue.addFirst(QueueEntry(item.id, PlanKind.HOOK, introKo = hook.introKo))
    publishSession()
}

/** 세션 트랙 바꾸기 — 남은 새 표현만 새 트랙으로 다시 계획 (복습은 그대로) */
internal fun SessionEngine.switchTrack(r: SessionRun, track: Track) {
    if (r.track == track) return
    val plan = deps.planner.plan(planRequest(r).copy(track = track), deps.content)
    val fresh = plan.items.filter { it.kind == PlanKind.NEW && it.itemId !in r.asked }.map { QueueEntry(it.itemId, PlanKind.NEW) }
    val oldNew = r.queue.count { it.kind == PlanKind.NEW }
    val rest = r.queue.filter { it.kind != PlanKind.NEW }
    val news = ArrayDeque(fresh.take(maxOf(oldNew, 2)))
    r.queue.clear()
    // 새 1 : 복습 2 로 섞는다
    val restQ = ArrayDeque(rest)
    while (news.isNotEmpty() || restQ.isNotEmpty()) {
        news.removeFirstOrNull()?.let { r.queue.addLast(it) }
        repeat(2) { restQ.removeFirstOrNull()?.let { r.queue.addLast(it) } }
    }
    r.track = track
    publishSession()
}

// ───────────── 문항 하나 ─────────────

private suspend fun SessionEngine.runItem(r: SessionRun, a: ActiveItem, entry: Entry) {
    val item = a.item
    r.lastItem = item
    when (entry) {
        Entry.Skip -> {
            skipItem(r, a)
            return
        }
        Entry.DontKnow -> {
            revealAndGrade(r, a, dontKnowResult(item))
            return
        }
        is Entry.Choice -> {
            answerChoice(r, a, entry.index)
            return
        }
        Entry.ListenNow -> showPrompt(r, a)
        Entry.Hint -> {
            a.hintUsed = true
            showPrompt(r, a)
            speakHint(a)
        }
        Entry.Meaning -> {
            showPrompt(r, a)
            speak(Say.meaning(item.meaningKo))
        }
        Entry.Slower -> {
            showPrompt(r, a)
            speakSlow(a)
        }
        Entry.Repeat, Entry.RepeatModel -> {
            showPrompt(r, a)
            speakPrompt(a)
        }
        Entry.ResumeHeld -> {
            speak(Say.RESUME_HELD)
            showPrompt(r, a)
            speakPrompt(a)
        }
        Entry.ResumePaused -> {
            speak(Say.AGAIN)
            showPrompt(r, a)
            speakPrompt(a)
        }
        Entry.Fresh -> {
            showPrompt(r, a)
            if (!a.introDone) {
                a.introDone = true
                a.entry.introKo?.let { speak(it) }
            }
            speakPrompt(a)
        }
    }
    listenLoop(r, a)
}

private fun SessionEngine.showPrompt(r: SessionRun, a: ActiveItem) {
    a.shownMs = ctx.now()
    ctx.emit {
        it.copy(
            phase = CoachPhase.PROMPTING, prompt = promptFor(a, explain = a.attempt > 1),
            feedback = if (a.attempt > 1) it.feedback else null, listening = ListeningUi(),
            hud = HudUi(load = ctx.level), session = sessionUi(r),
        )
    }
}

private suspend fun SessionEngine.speakPrompt(a: ActiveItem) {
    speak(a.item.promptKo)
    CoachViews.displayEn(a.item)?.let { speak(it, SpeechLang.EN) }
}

private suspend fun SessionEngine.speakSlow(a: ActiveItem) {
    val en = CoachViews.displayEn(a.item)
    if (en != null) speak(en, SpeechLang.EN, SpeechRate.SLOW) else speak(a.item.promptKo)
}

private fun hintOf(item: LearningItem): String =
    item.hintWord?.takeIf { it.isNotBlank() }
        ?: item.keywords.firstOrNull()
        ?: item.modelAnswer.split(' ').firstOrNull { it.isNotBlank() }.orEmpty()

private fun lettersOf(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

/** 힌트 단어가 곧 정답인가 (단어 문제: ‘미루다’ → postpone) — 그대로 보이면 문제가 아니게 된다 */
private fun hintIsAnswer(item: LearningItem, hint: String): Boolean {
    val h = lettersOf(hint)
    return h.isNotEmpty() && item.answers.any { lettersOf(it) == h }
}

/** 앞 [shown]글자만 보이고 나머지는 칸으로 ("p _ _ _ _ _ _ _") */
private fun maskWord(word: String, shown: Int): String {
    val w = word.trim()
    val rest = w.drop(shown).map { c -> if (c.isLetterOrDigit()) "_" else c.toString() }.filter { it.isNotBlank() }
    return (listOf(w.take(shown)) + rest).joinToString(" ")
}

/**
 * HUD 에 띄울 힌트. 힌트가 정답 그 자체면 가린다 — 듣는 동안은 첫 글자만, 힌트를 요청하면 두 글자까지.
 * 문장 문제의 핵심 단어(push, window …)는 그대로 보여 준다.
 */
private fun hudHintOf(item: LearningItem, requested: Boolean): String {
    val hint = hintOf(item)
    return if (hintIsAnswer(item, hint)) maskWord(hint, if (requested) 2 else 1) else hint
}

private suspend fun SessionEngine.speakHint(a: ActiveItem) {
    val hint = hintOf(a.item)
    if (ctx.rules.hudHint) deps.feedback.showHudHint(hudHintOf(a.item, requested = true))
    speak(Say.HINT)
    // 단어 문제는 답을 말해 버리지 않고 첫소리만 들려준다
    if (hintIsAnswer(a.item, hint)) speak("It starts with ${hint.trim().take(1).uppercase()}.", SpeechLang.EN) else speak(hint, SpeechLang.EN)
}

private suspend fun SessionEngine.listenLoop(r: SessionRun, a: ActiveItem) {
    while (true) {
        when (val o = listenOnce(a)) {
            is ListenOutcome.Heard -> {
                val cmd = deps.commands.parse(o.text, ListenContext.ANSWER)
                if (cmd != null) {
                    if (spokenCommand(r, a, cmd)) return
                    continue
                }
                if (judgeAnswer(r, a, o)) return
            }
            else -> {
                if (o is ListenOutcome.Stopped) currentCoroutineContext().ensureActive()
                if (onSilence(r, a, o)) return
            }
        }
    }
}

private suspend fun SessionEngine.listenOnce(a: ActiveItem): ListenOutcome {
    gate()
    val rules = ctx.rules
    val sec = minOf(a.item.listenSeconds, rules.maxListenSec).coerceAtLeast(3)
    val timeoutMs = sec * 1000L
    val hint = if (rules.hudHint && (ctx.settings.hudHint || a.hintUsed)) hudHintOf(a.item, a.hintUsed).ifBlank { null } else null
    deps.feedback.showHudHint(hint)
    ctx.emit {
        it.copy(
            phase = CoachPhase.LISTENING,
            listening = ListeningUi(mic = MicState.LISTENING, deadlineMs = ctx.now() + timeoutMs),
            hud = HudUi(hint = hint, icon = HudIcon.LISTENING, load = ctx.level),
        )
    }
    try {
        return deps.speech.listen(SpeechLang.EN, timeoutMs)
    } finally {
        deps.feedback.showHudHint(null)
    }
}

/** 듣기를 마치고 다시 말하는 중 — 마이크 표시를 끈다 */
private fun SessionEngine.markSpeaking() {
    ctx.emit { it.copy(phase = CoachPhase.PROMPTING, listening = ListeningUi(), hud = HudUi(load = ctx.level)) }
}

/** 답하는 중 나온 음성 명령. true = 이 문항 끝 */
private suspend fun SessionEngine.spokenCommand(r: SessionRun, a: ActiveItem, cmd: LearnerCommand): Boolean {
    when (cmd) {
        LearnerCommand.REPEAT -> {
            showPrompt(r, a)
            speakPrompt(a)
        }
        LearnerCommand.SLOWER -> {
            markSpeaking()
            speakSlow(a)
        }
        LearnerCommand.HINT -> {
            a.hintUsed = true
            markSpeaking()
            ctx.emit { it.copy(prompt = promptFor(a, explain = a.attempt > 1)) }
            speakHint(a)
        }
        LearnerCommand.MEANING -> {
            markSpeaking()
            speak(Say.meaning(a.item.meaningKo))
        }
        LearnerCommand.SKIP -> {
            skipItem(r, a)
            return true
        }
        LearnerCommand.DONT_KNOW -> {
            revealAndGrade(r, a, dontKnowResult(a.item))
            return true
        }
        LearnerCommand.PAUSE, LearnerCommand.END_SESSION -> {
            command(cmd)
            throw CancellationException("command")
        }
        LearnerCommand.RESUME, LearnerCommand.YES, LearnerCommand.NO -> Unit
    }
    return false
}

/** 침묵·인식 실패. true = 이 문항 끝 */
private suspend fun SessionEngine.onSilence(r: SessionRun, a: ActiveItem, o: ListenOutcome): Boolean {
    a.silences++
    val engineFault = o is ListenOutcome.Failed
    if (o is ListenOutcome.Failed && !r.sttWarned) {
        // 에뮬레이터 등 영어 STT 를 못 쓸 때 — 한 번만 알린다 (데모 패널·adb 답 주입은 계속 된다)
        r.sttWarned = true
        ctx.toast(o.reasonKo)
    }
    if (a.attempt >= 2 && !engineFault) {
        // 한 번 답한 뒤의 두 번째 시도가 침묵 → 정답 들려주고 AGAIN
        val res = JudgeResult(Verdict.NO_ANSWER, 0, "", "", null, feedbackKo = "잘 안 들렸어요.")
        val upd = grade(r, a, res, Grade.AGAIN, null, null)
        showFeedback(a, res, Grade.AGAIN, null, upd?.xpGained ?: 0)
        pulse(PulseKind.AMBER)
        speak(Say.REVEAL)
        speak(a.item.modelAnswer, SpeechLang.EN)
        return true
    }
    if (a.silences == 1) {
        markSpeaking()
        speak(Say.SILENCE_RETRY)
        showPrompt(r, a)
        speakPrompt(a)
        return false
    }
    // 두 번 침묵: 정답만 들려주고 채점하지 않음 → 끝으로 한 번 다시 보냄 (그 사본이 또 두 번 침묵이면 AGAIN)
    if (a.entry.requeued && !engineFault) {
        val res = JudgeResult(Verdict.NO_ANSWER, 0, "", "", null, feedbackKo = "잘 안 들렸어요.")
        val upd = grade(r, a, res, Grade.AGAIN, null, null)
        showFeedback(a, res, Grade.AGAIN, null, upd?.xpGained ?: 0)
    } else {
        if (!a.entry.requeued) r.queue.addLast(a.entry.copy(requeued = true, introKo = null))
        r.skipped++
        r.current = null
        publishSession()
    }
    ctx.emit { it.copy(phase = CoachPhase.FEEDBACK, listening = ListeningUi(), hud = HudUi(icon = HudIcon.RETRY, load = ctx.level)) }
    speak(Say.REVEAL)
    speak(a.item.modelAnswer, SpeechLang.EN)
    return true
}

private fun dontKnowResult(item: LearningItem) =
    JudgeResult(Verdict.WRONG, 0, "", "", item.modelAnswer, feedbackKo = Say.REVEAL)

private suspend fun SessionEngine.revealAndGrade(r: SessionRun, a: ActiveItem, res: JudgeResult) {
    val upd = grade(r, a, res, Grade.AGAIN, null, null)
    showFeedback(a, res, Grade.AGAIN, null, upd?.xpGained ?: 0)
    pulse(PulseKind.AMBER)
    speak(Say.REVEAL)
    speak(a.item.modelAnswer, SpeechLang.EN)
    praise(upd)
}

private suspend fun SessionEngine.skipItem(r: SessionRun, a: ActiveItem) {
    r.skipped++
    r.current = null
    publishSession()
    ctx.emit { it.copy(phase = CoachPhase.FEEDBACK, prompt = null, listening = ListeningUi(), hud = HudUi(load = ctx.level)) }
    speak(Say.SKIPPED)
}

private fun capHint(a: ActiveItem, g: Grade): Grade =
    if (a.hintUsed && g.quality > Grade.HARD.quality) Grade.HARD else g

/** 들은 답 채점. true = 이 문항 끝, false = 두 번째 시도로 다시 듣기 */
private suspend fun SessionEngine.judgeAnswer(r: SessionRun, a: ActiveItem, o: ListenOutcome.Heard): Boolean {
    ctx.emit { it.copy(phase = CoachPhase.JUDGING, listening = ListeningUi(mic = MicState.PROCESSING)) }
    val res = deps.judge.judge(a.item, o.text)
    if (res.verdict == Verdict.NO_ANSWER) return onSilence(r, a, ListenOutcome.Silence)
    val flu = deps.fluency.measure(o.timing, o.text)
    val latency = flu.latencyMs
    r.pronIssues += res.pronIssues
    when {
        res.verdict == Verdict.CORRECT -> {
            val g = if (a.attempt == 1) capHint(a, deps.scheduler.gradeFor(Verdict.CORRECT, latency)) else Grade.HARD
            val upd = grade(r, a, res, g, flu, latency)
            showFeedback(a, res, g, flu, upd?.xpGained ?: 0)
            pulse(PulseKind.GREEN)
            answerCue(r, res.verdict)
            speak(res.feedbackKo.ifBlank { Say.headline(Verdict.CORRECT) })
            speakTipOnce(r, res)
            praise(upd)
            return true
        }
        a.attempt == 1 -> {
            a.attempt = 2
            a.lastResult = res
            showFeedback(a, res, null, flu, 0)
            pulse(PulseKind.AMBER)
            answerCue(r, res.verdict)
            ctx.emit { it.copy(prompt = promptFor(a, explain = true)) }
            speak(res.feedbackKo.ifBlank { Say.REVEAL })
            speak(a.item.modelAnswer, SpeechLang.EN)
            speakTipOnce(r, res)
            speak(Say.ONE_MORE)
            return false
        }
        else -> {
            val upd = grade(r, a, res, Grade.AGAIN, flu, latency)
            showFeedback(a, res, Grade.AGAIN, flu, upd?.xpGained ?: 0)
            pulse(PulseKind.AMBER)
            answerCue(r, res.verdict)
            speak(Say.REVEAL)
            speak(a.item.modelAnswer, SpeechLang.EN)
            praise(upd)
            return true
        }
    }
}

/** 정차 화면 보기 탭 — 틀리면 두 번째 기회 없이 정답을 보여 주고 AGAIN */
private suspend fun SessionEngine.answerChoice(r: SessionRun, a: ActiveItem, index: Int) {
    val shown = ctx.ui.value.prompt?.takeIf { it.itemId == a.item.id }?.choices?.takeIf { it.isNotEmpty() }
    val choice = (shown ?: CoachViews.choices(a.item)).getOrNull(index) ?: return listenLoop(r, a)
    val res = deps.judge.judgeChoice(a.item, choice)
    val latency = (ctx.now() - a.shownMs).coerceAtLeast(0L)
    if (res.verdict == Verdict.CORRECT) {
        val g = if (a.attempt == 1) capHint(a, deps.scheduler.gradeFor(Verdict.CORRECT, latency)) else Grade.HARD
        val upd = grade(r, a, res, g, null, latency)
        showFeedback(a, res, g, null, upd?.xpGained ?: 0)
        pulse(PulseKind.GREEN)
        answerCue(r, res.verdict)
        speak(Say.headline(Verdict.CORRECT))
        speak(a.item.modelAnswer, SpeechLang.EN)
        praise(upd)
    } else {
        val upd = grade(r, a, res, Grade.AGAIN, null, latency)
        showFeedback(a, res, Grade.AGAIN, null, upd?.xpGained ?: 0)
        pulse(PulseKind.AMBER)
        answerCue(r, res.verdict)
        speak(Say.REVEAL)
        speak(a.item.modelAnswer, SpeechLang.EN)
        praise(upd)
    }
}

/** 채점된 답 하나를 학습자 상태에 반영 (문항당 정확히 한 번) + 연속 정답 · 틀린 표현 기록 */
private fun SessionEngine.grade(
    r: SessionRun,
    a: ActiveItem,
    res: JudgeResult,
    g: Grade,
    flu: FluencyMetrics?,
    latency: Long?,
): ProgressUpdate? {
    val item = a.item
    val now = ctx.now()
    val isNew = ctx.learner.cards[item.id] == null
    val parked = ctx.parked
    val outcome = AnswerOutcome(item, res, g, isNew, flu, driving = !parked, sessionId = r.id, latencyMs = latency)
    var update: ProgressUpdate? = null
    deps.repository.update { st ->
        val u = deps.progression.record(st, outcome, deps.content, now)
        update = u
        u.state
    }
    r.answered++
    when (res.verdict) {
        Verdict.CORRECT -> {
            r.correct++
            // 연속 정답은 한 번에 맞힌 것만 센다 (두 번째 시도에서 맞히면 끊긴다)
            if (a.attempt == 1) {
                r.streak++
                r.bestStreak = maxOf(r.bestStreak, r.streak)
            } else {
                r.streak = 0
            }
            r.mistakes.remove(item.id)
        }
        Verdict.PARTIAL -> {
            r.partial++
            r.streak = 0
            r.mistakes[item.id] = Verdict.PARTIAL
        }
        else -> {
            r.streak = 0
            r.mistakes[item.id] = res.verdict
        }
    }
    if (isNew) {
        if (g != Grade.AGAIN) r.newDone++
    } else {
        r.reviewDone++
    }
    val u = update
    if (u != null) {
        r.xp += u.xpGained
        r.events += u.events
    }
    r.scores[item.id] = res.score
    // 말하기 유창성은 말로 답한 것만 (화면 보기를 누른 시간은 '답을 시작한 시간'이 아니다)
    if (flu != null) latency?.let { r.latencies += it }
    flu?.wordsPerMinute?.let { r.wpms += it }
    if (r.current === a) r.current = null
    publishSession()
    return u
}

private fun SessionEngine.showFeedback(a: ActiveItem, res: JudgeResult, g: Grade?, flu: FluencyMetrics?, xp: Int, phase: CoachPhase = CoachPhase.FEEDBACK) {
    val fb = FeedbackUi(
        itemId = a.item.id, verdict = res.verdict, score = res.score, headlineKo = Say.headline(res.verdict),
        heard = res.heard, modelAnswer = a.item.modelAnswer, alignment = res.alignment, pronIssues = res.pronIssues,
        fluency = flu, grade = g, xp = xp, atMs = ctx.now(),
    )
    val icon = if (res.verdict == Verdict.CORRECT) HudIcon.CORRECT else HudIcon.RETRY
    ctx.emit { it.copy(phase = phase, feedback = fb, listening = ListeningUi(), hud = HudUi(icon = icon, load = ctx.level)) }
}

/**
 * 채점 효과음 — 정답(연속 정답 5개마다 팡파르) · 거의 맞음 · 오답. 대답이 없었을 땐 내지 않는다.
 * 설정 '효과음'이 꺼져 있거나 부하가 CRITICAL 이면 [CoachContext.cue] 가 막는다. grade() 뒤에 부른다 (연속 정답 수 반영).
 */
private fun SessionEngine.answerCue(r: SessionRun, verdict: Verdict) {
    when (verdict) {
        Verdict.CORRECT -> {
            val fanfare = r.streak > 0 && r.streak % LearnThresholds.STREAK_CUE_EVERY == 0
            ctx.cue(if (fanfare) SoundCue.STREAK else SoundCue.CORRECT)
        }
        Verdict.PARTIAL -> ctx.cue(SoundCue.PARTIAL)
        Verdict.WRONG -> ctx.cue(SoundCue.WRONG)
        Verdict.NO_ANSWER -> Unit
    }
}

/** 앰비언트 라이트 펄스 — 설정 켬 + 부하 정책이 허용할 때만 */
internal fun SessionEngine.pulse(kind: PulseKind) {
    if (ctx.settings.ambientPulse && ctx.rules.ambientPulse) deps.feedback.pulse(kind)
}

private suspend fun SessionEngine.speakTipOnce(r: SessionRun, res: JudgeResult) {
    val issue = res.pronIssues.firstOrNull { it.type !in r.tipsSpoken } ?: return
    r.tipsSpoken += issue.type
    speak(issue.tipKo.ifBlank { deps.pronunciation.tip(issue.type) })
}

private suspend fun SessionEngine.praise(upd: ProgressUpdate?) {
    upd ?: return
    // 레벨 업을 먼저 — 팡파르는 그 한마디 바로 앞에 (정답 효과음과 겹치지 않게)
    val events = upd.events.sortedBy { if (it is ProgressEvent.LevelUp) 0 else 1 }
    if (ctx.loadOk && events.any { it is ProgressEvent.LevelUp }) ctx.cue(SoundCue.LEVEL_UP)
    events.mapNotNull { Say.praise(it) }.take(2).forEach { line ->
        if (!ctx.loadOk) return
        speak(line)
    }
}
