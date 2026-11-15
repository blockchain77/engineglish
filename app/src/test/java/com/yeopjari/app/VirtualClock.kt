package com.yeopjari.app

import com.yeopjari.app.ai.personal.PersonalService
import com.yeopjari.app.core.gateway.Source
import com.yeopjari.app.core.gateway.SpeechInput
import com.yeopjari.app.core.gateway.SpeechOutput
import com.yeopjari.app.core.gateway.SttEvent
import com.yeopjari.app.core.model.RouteState
import com.yeopjari.app.core.model.Speaker
import com.yeopjari.app.sim.DriveSimulator
import com.yeopjari.app.sim.ScenarioId
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/**
 * 가상 시계 디스패처 — 저장소(YeopjariStore)의 대화 타이머(10초 되돌리기 창, 25초 질문 제한, 듣기 감시)를
 * 실제로 기다리지 않고 JVM 테스트에서 돌린다. 앱의 Dispatchers.Main.immediate처럼 같은 스레드에서 바로 이어 실행하고,
 * delay·withTimeout만 가상 시각으로 미룬다. (kotlinx-coroutines-test가 테스트 의존성에 없어 직접 둔다)
 */
@OptIn(InternalCoroutinesApi::class)
internal class VirtualClock : CoroutineDispatcher(), Delay {
    var now = 0L
        private set
    private var seq = 0L

    private class Task(val at: Long, val seq: Long, val block: Runnable)

    private val queue = PriorityQueue<Task>(compareBy<Task>({ it.at }, { it.seq }))

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = false

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.add(Task(now, seq++, block))
    }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val t = Task(now + timeMillis.coerceAtLeast(0), seq++, Runnable { continuation.resume(Unit) })
        queue.add(t)
        continuation.invokeOnCancellation { queue.remove(t) }
    }

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
        val t = Task(now + timeMillis.coerceAtLeast(0), seq++, block)
        queue.add(t)
        return DisposableHandle { queue.remove(t) }
    }

    /** 가상 시각을 [ms]만큼 흘리며 그 사이에 예정된 일을 모두 실행한다 */
    fun advanceBy(ms: Long) {
        val end = now + ms
        while (true) {
            val t = queue.peek() ?: break
            if (t.at > end) break
            queue.poll()
            now = t.at
            t.block.run()
        }
        now = end
    }
}

/** 시험용 마이크 — 시작하면 '준비'만 알리고, 결과·끝 알림은 테스트가 직접 보낸다 (끝 알림을 안 보내면 멈춘 STT 세션) */
internal class FakeMic : SpeechInput {
    override val available = true
    override val events = MutableSharedFlow<SttEvent>(extraBufferCapacity = 64)
    var starts = 0
    var stops = 0
    override fun start() { starts++; events.tryEmit(SttEvent.Ready) }
    override fun stop() { stops++ }
}

/** 시험용 TTS — 글자 수에 비례한 시간 동안 '말하는 중' */
internal class FakeTts(private val scope: CoroutineScope) : SpeechOutput {
    override val available = true
    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking
    val spoken = mutableListOf<String>()
    private var job: Job? = null
    override fun speak(text: String, utteranceId: String) {
        job?.cancel()
        spoken += text
        _speaking.value = true
        job = scope.launch { delay(500L + text.length * 50L); _speaking.value = false }
    }
    override fun stop() { job?.cancel(); _speaking.value = false }
}

/**
 * 실제 저장소 + 시뮬레이터 + 개인화 서비스(암호화 금고 없음)를 가상 시계 위에서 돌리는 하네스.
 * 운전자의 말은 [hear](자동으로 열린 마이크의 인식 결과) · [tapAndSay](말하기 버튼) · [send](화면 버튼)로 넣는다.
 */
internal class StoreHarness(scenario: ScenarioId, sourceMode: Source = Source.SIMULATOR) {
    val clock = VirtualClock()
    val scope = CoroutineScope(SupervisorJob() + clock)
    val sim = DriveSimulator(scope)
    val mic = FakeMic()
    val tts = FakeTts(scope)
    val personal = PersonalService(null)
    val source = MutableStateFlow(sourceMode)
    val store = YeopjariStore(
        scope, sim, sim.vehicle, sim.navi, mic, tts, MutableStateFlow(false), source, flowOf(false), {},
        personal, sim.location.location,
    )

    init {
        store.playScenario(scenario)
        adv(1_500)
    }

    fun adv(ms: Long) = clock.advanceBy(ms)

    /** 열린 마이크가 [text]를 알아듣고 세션을 끝낸다 */
    fun hear(text: String) {
        mic.events.tryEmit(SttEvent.Final(text))
        mic.events.tryEmit(SttEvent.End)
    }

    /** 말하기 버튼을 누르고 [text]라고 말한다 */
    fun tapAndSay(text: String) {
        store.startVoice(openPanel = false)
        adv(200)
        hear(text)
    }

    fun send(text: String) = store.send(text)

    val route: RouteState get() = sim.navi.route.value
    val destination: String? get() = route.destination?.id
    val learnCount: Int get() = personal.brain.learnCount
    val clarify: Clarify? get() = store.clarify.value
    val lastAgentLine: String? get() = store.turns.value.lastOrNull { it.speaker == Speaker.AGENT }?.text

    fun close() = scope.cancel()
}
