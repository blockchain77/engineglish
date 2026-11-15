package com.yeopjari.app.english.coach

import com.yeopjari.app.english.core.CommandParser
import com.yeopjari.app.english.core.EveningAskUi
import com.yeopjari.app.english.core.LearnThresholds
import com.yeopjari.app.english.core.LearnerCommand
import com.yeopjari.app.english.core.ListenContext
import com.yeopjari.app.english.core.ListenOutcome
import com.yeopjari.app.english.core.ListeningUi
import com.yeopjari.app.english.core.LoadLevel
import com.yeopjari.app.english.core.MicState
import com.yeopjari.app.english.core.SpeechLang
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * 퇴근길 학습 질문의 대답 해석 (명세 §7.1) — 음성·타이핑·adb 모두 이 함수로.
 * 한국어 STT 로 듣기 때문에 영어 대답이 한글로 받아 적힌 꼴("예스", "오케이", "노")도 받는다.
 * 거절 표현이 하나라도 있으면 거절이 이긴다 ("다음에 할게요" · "안 할게" → 쉴게요).
 */
internal object EveningAnswers {
    private val SPLIT = Regex("[\\s,.!?~…]+")

    /** 띄어쓰기 없이 견주는 한국어 거절 문구 */
    private val NO_KO = listOf(
        "아니", "아뇨", "괜찮아", "괜찮습니다", "쉴게", "쉴께", "쉴래", "쉬자", "쉬고싶", "쉬어", "다음에", "나중에", "안할", "안해",
        "하지마", "하지말", "말자", "싫어", "됐어", "피곤", "힘들", "노우", "낫투데이",
    )
    private val NO_KO_WORDS = setOf("노", "노노")
    private val YES_KO = listOf(
        "좋아", "좋습니다", "좋죠", "할게", "할께", "할래", "하자", "해줘", "해주세요", "그래", "그럼요", "물론", "당연", "오케이", "오케",
        "예스", "슈어", "시작", "복습해", "부탁",
    )
    private val YES_KO_WORDS = setOf("응", "응응", "웅", "엉", "네", "네네", "넵", "넹", "네에", "예", "예예", "옙", "콜")

    private val NO_EN = listOf(
        "not today", "not now", "no thanks", "no thank you", "maybe later", "next time", "not really", "i am good", "i'm good",
        "i am tired", "i'm tired", "do not", "don't",
    )
    private val NO_EN_WORDS = setOf("no", "nope", "nah", "later", "pass")
    private val YES_EN = listOf("of course", "sounds good", "let's do it", "lets do it", "why not", "go ahead", "all right", "let's go", "lets go")
    private val YES_EN_WORDS = setOf("yes", "yeah", "yep", "yup", "sure", "okay", "ok", "alright", "absolutely", "definitely")
    private val YES_DESPITE_NO = listOf("no problem", "why not")

    /** true = 할게요, false = 쉴게요, null = 알아듣지 못함 */
    fun parse(text: String, commands: CommandParser): Boolean? {
        val raw = text.trim().lowercase().replace('’', '\'')
        if (raw.isEmpty()) return null
        val words = raw.split(SPLIT).map { w -> w.filter { it.isLetterOrDigit() || it == '\'' } }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val compact = words.joinToString("")
        val spaced = words.joinToString(" ")
        fun word(set: Set<String>) = words.any { it in set || it.removeSuffix("요") in set }

        // "no problem" 은 승낙
        if (YES_DESPITE_NO.any { it in spaced }) return true

        if (NO_KO.any { it in compact } || NO_EN.any { it in spaced } || word(NO_KO_WORDS) || word(NO_EN_WORDS)) return false
        if (YES_KO.any { it in compact } || YES_EN.any { it in spaced } || word(YES_KO_WORDS) || word(YES_EN_WORDS)) return true
        return when (commands.parse(text, ListenContext.CONFIRM)) {
            LearnerCommand.YES -> true
            LearnerCommand.NO -> false
            else -> null
        }
    }
}

/**
 * 퇴근길 학습 질문 (명세 §7.1) — 퇴근길이 감지되면 바로 시작하지 않고 먼저 묻는다.
 *
 *  질문("퇴근길에도 영어 복습할까요?")을 말하고 화면에 카드(할게요 / 오늘은 쉴게요)를 띄운 뒤 10초 동안 대답을 듣는다.
 *  - 할게요 → [onAnswer] (true) : 코치가 세션을 시작한다
 *  - 쉴게요 → [onAnswer] (false) : 짧게 대답하고 끝
 *  - 10초 안에 답이 없거나 부하가 FOCUS 이상이 되면 → 카드를 내리고, 부하가 NORMAL 이하일 때 한 번만 다시 묻는다
 *  - 다시 물어도 답이 없으면 조용히 그만둔다 (세션 없음, 아무 말도 하지 않는다)
 *
 * 부하가 FOCUS 이상일 땐 말하지도 듣지도 않는다 (질문 중이었으면 바로 멈춘다).
 */
internal class EveningAskFlow(private val ctx: CoachContext, private val onAnswer: (Boolean) -> Unit) {
    private enum class Outcome { YES, NO, NONE, INTERRUPTED }

    private val deps get() = ctx.deps
    private var job: Job? = null
    private var waiter: CompletableDeferred<Boolean>? = null
    private var interrupt: CompletableDeferred<Unit>? = null
    private var asking = false

    /** 질문이 걸려 있는 주행 (대답했거나 그만두면 null) */
    var driveId: String? = null
        private set

    /** 질문 도중 예상하지 못한 오류 (테스트 확인용 — 사용자 데이터는 담지 않는다) */
    var lastError: Throwable? = null
        private set

    /** 질문 중이거나 다시 물을 차례를 기다리는 중 */
    val active: Boolean get() = driveId != null

    fun begin(driveId: String) {
        if (active) return
        this.driveId = driveId
        job = ctx.scope.launch {
            try {
                run()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // 음성 엔진 오류 등 — 질문을 조용히 접는다 (앱이 죽지 않게)
                lastError = t
                end()
            }
        }
    }

    private suspend fun run() {
        var attempt = 0
        while (attempt < LearnThresholds.EVENING_ASK_MAX_ATTEMPTS) {
            if (attempt > 0) delay(REASK_GAP_MS)
            // 부하가 NORMAL 이하일 때만 묻는다
            ctx.load.first { it.level.isAtMost(LoadLevel.NORMAL) }
            attempt++
            when (askOnce(attempt)) {
                Outcome.YES -> {
                    end()
                    onAnswer(true)
                    return
                }
                Outcome.NO -> {
                    end()
                    onAnswer(false)
                    if (ctx.loadOk) ctx.say(Say.EVENING_NO)
                    return
                }
                Outcome.NONE, Outcome.INTERRUPTED -> hide()
            }
        }
        // 두 번 물어도 답이 없다 → 조용히 그만둔다
        end()
    }

    /** 한 번 묻기: 음성 대답 · 버튼 · 부하 상승 가운데 먼저 온 것 */
    private suspend fun askOnce(attempt: Int): Outcome {
        val button = CompletableDeferred<Boolean>()
        val stop = CompletableDeferred<Unit>()
        waiter = button
        interrupt = stop
        asking = true
        show(attempt, listening = false, deadlineMs = null)
        try {
            return coroutineScope {
                val voice = async { voiceAnswer(attempt) }
                val outcome = select<Outcome> {
                    button.onAwait { if (it) Outcome.YES else Outcome.NO }
                    stop.onAwait { Outcome.INTERRUPTED }
                    voice.onAwait { v ->
                        when (v) {
                            true -> Outcome.YES
                            false -> Outcome.NO
                            null -> Outcome.NONE
                        }
                    }
                }
                voice.cancel()
                // 버튼·부하로 끊겼으면 말하던 질문과 마이크를 바로 멈춘다
                if (outcome == Outcome.INTERRUPTED || button.isCompleted) deps.speech.stopAll()
                outcome
            }
        } finally {
            asking = false
            if (waiter === button) waiter = null
            if (interrupt === stop) interrupt = null
        }
    }

    /** 질문을 말하고 10초 동안 듣는다. 알아듣지 못한 말은 넘기고 남은 시간 동안 다시 듣는다. null = 답 없음 */
    private suspend fun voiceAnswer(attempt: Int): Boolean? {
        ctx.say(Say.EVENING_ASK)
        val deadline = ctx.now() + LearnThresholds.EVENING_ASK_TIMEOUT_MS
        show(attempt, listening = true, deadlineMs = deadline)
        while (true) {
            val left = deadline - ctx.now()
            if (left <= 0L) return null
            when (val o = deps.speech.listen(SpeechLang.KO, left)) {
                is ListenOutcome.Heard -> EveningAnswers.parse(o.text, deps.commands)?.let { return it }
                ListenOutcome.Silence -> return null
                ListenOutcome.Stopped, is ListenOutcome.Failed -> {
                    // 음성 인식을 쓸 수 없어도 버튼으로 답할 시간은 그대로 준다
                    show(attempt, listening = false, deadlineMs = deadline)
                    delay((deadline - ctx.now()).coerceAtLeast(0L))
                    return null
                }
            }
        }
    }

    /** 버튼(할게요 / 오늘은 쉴게요) · 타이핑 · 핸들 버튼 대답. 질문이 걸려 있지 않으면 false */
    fun answer(yes: Boolean): Boolean {
        if (!active) return false
        val w = waiter
        if (w != null) {
            w.complete(yes)
            return true
        }
        // 카드를 내리고 다시 물을 차례를 기다리는 사이에 온 대답도 받는다
        cancel()
        onAnswer(yes)
        return true
    }

    /** 부하 단계가 바뀔 때마다 — FOCUS 이상이면 질문을 바로 멈춘다 (부하가 내려가면 한 번 다시 묻는다) */
    fun onLoad(level: LoadLevel) {
        if (!level.isAtMost(LoadLevel.NORMAL)) interrupt?.complete(Unit)
    }

    /** 질문을 없던 일로 (세션을 직접 시작함 · 주차 · 새 주행 · 기록 삭제). 아무 말도 하지 않는다 */
    fun cancel() {
        if (!active && job == null) return
        val wasAsking = asking
        job?.cancel()
        job = null
        if (wasAsking) deps.speech.stopAll()
        end()
    }

    private fun end() {
        driveId = null
        waiter = null
        interrupt = null
        asking = false
        hide()
    }

    private fun show(attempt: Int, listening: Boolean, deadlineMs: Long?) {
        ctx.emit {
            it.copy(
                eveningAsk = EveningAskUi(
                    visible = true, attempt = attempt, questionKo = Say.EVENING_ASK, listening = listening, deadlineMs = deadlineMs,
                ),
                listening = if (listening) ListeningUi(mic = MicState.LISTENING, deadlineMs = deadlineMs) else ListeningUi(),
            )
        }
    }

    private fun hide() {
        ctx.emit { if (it.eveningAsk.visible) it.copy(eveningAsk = EveningAskUi(), listening = ListeningUi()) else it }
    }

    companion object {
        /** 답이 없거나 부하로 끊긴 뒤 다시 묻기까지 (그 뒤에도 부하가 NORMAL 이하가 될 때까지 기다린다) */
        const val REASK_GAP_MS = 2_000L
    }
}
