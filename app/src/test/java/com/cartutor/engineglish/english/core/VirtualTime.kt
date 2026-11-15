package com.cartutor.engineglish.english.core

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/**
 * 가상 시간 — 코루틴 디스패처 + [Clock] 을 하나로 (kotlinx-coroutines-test 가 의존성에 없어 직접 둔다).
 * delay·withTimeout 은 가상 시각으로 미루고, 나머지는 같은 스레드에서 바로 실행한다.
 *
 * ```
 * val t = VirtualTime()
 * val scope = t.scope()
 * scope.launch { delay(4_000); done = true }
 * t.advanceBy(4_000)   // done == true, t.nowMs() == START_MS + 4000
 * ```
 * english 모듈의 모든 JVM 테스트(코치·운전·말하기)가 함께 쓴다.
 */
@OptIn(InternalCoroutinesApi::class)
class VirtualTime(startMs: Long = START_MS) : CoroutineDispatcher(), Delay, Clock {
    var now: Long = startMs
        private set
    private var seq = 0L

    private class Task(val at: Long, val seq: Long, val block: Runnable)

    private val queue = PriorityQueue<Task>(compareBy<Task>({ it.at }, { it.seq }))

    override fun nowMs(): Long = now

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

    /** 가상 시각을 [ms] 만큼 흘리며 그 사이 예정된 일을 모두 실행 */
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

    /** 지금 시각에 밀린 일만 실행 */
    fun runCurrent() = advanceBy(0)

    /** 이 가상 시간 위에서 도는 스코프 */
    fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + this)

    companion object {
        /** 2026-11-16(월) 07:40 KST — 출근 시간대 */
        val START_MS: Long = ZonedDateTime.of(2026, 11, 16, 7, 40, 0, 0, ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
    }
}

/** 테스트용 신호 원천 — set { it.copy(...) } 로 바꾸고, tick() 으로 하트비트 */
class MutableSignalSource(private val clock: Clock, initial: DrivingSignals = DrivingSignals(atMs = clock.nowMs())) : SignalSource {
    private val _signals = MutableStateFlow(initial)
    override val signals: StateFlow<DrivingSignals> = _signals.asStateFlow()

    val value: DrivingSignals get() = _signals.value

    fun set(transform: (DrivingSignals) -> DrivingSignals) {
        _signals.value = transform(_signals.value).copy(atMs = clock.nowMs())
    }

    /** 내용은 그대로, atMs 만 지금으로 */
    fun tick() = set { it }
}
