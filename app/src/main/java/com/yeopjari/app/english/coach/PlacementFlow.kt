package com.yeopjari.app.english.coach

import com.yeopjari.app.english.core.CoachPhase
import com.yeopjari.app.english.core.PlacementItem
import com.yeopjari.app.english.core.PlacementResult
import com.yeopjari.app.english.core.PlacementState
import com.yeopjari.app.english.core.PlacementUi
import com.yeopjari.app.english.core.PulseKind
import com.yeopjari.app.english.core.SpeechLang
import com.yeopjari.app.english.core.Track
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 레벨 테스트 (정차 중, 화면 보기). 출발하면 멈췄다가 다시 정차하면 이어서 한다 */
internal class PlacementFlow(private val ctx: CoachContext) {
    private val deps get() = ctx.deps
    var state: PlacementState? = null
        private set
    private var pool: List<PlacementItem> = emptyList()
    private var job: Job? = null
    private var revealing = false

    val showing: Boolean get() = ctx.ui.value.phase == CoachPhase.PLACEMENT

    fun start(track: Track) {
        val existing = state
        if (existing != null && existing.track == track) {
            show(existing)
            speakAudio(existing.current)
            return
        }
        pool = deps.content.placement(track)
        val st = deps.placement.start(track, pool, ctx.now())
        state = st
        show(st)
        speakAudio(st.current)
    }

    private fun show(st: PlacementState) {
        val result = if (st.finished) deps.placement.result(st) else null
        ctx.emit {
            it.copy(
                phase = CoachPhase.PLACEMENT,
                placement = PlacementUi(
                    index = st.index.coerceAtMost(st.total), total = st.total, item = if (st.finished) null else st.current,
                    currentLevel = st.currentLevel, lastCorrect = null, result = result,
                ),
            )
        }
    }

    fun choose(index: Int) {
        val st = state ?: return
        if (revealing || st.finished || !showing) return
        val shown = st.current ?: return
        if (index !in shown.choices.indices) return
        val next = deps.placement.answer(st, pool, index, ctx.now())
        state = next
        val correct = next.answers.lastOrNull()?.correct
        revealing = true
        ctx.emit {
            it.copy(placement = PlacementUi(st.index, st.total, shown, st.currentLevel, lastCorrect = correct, result = null))
        }
        if (ctx.settings.ambientPulse && ctx.rules.ambientPulse && correct != null) {
            deps.feedback.pulse(if (correct) PulseKind.GREEN else PulseKind.AMBER)
        }
        deps.speech.stopAll()
        job?.cancel()
        job = ctx.scope.launch {
            delay(REVEAL_MS)
            revealing = false
            if (state === next && showing) {
                show(next)
                if (!next.finished) speakAudioNow(next.current)
            }
        }
    }

    /** 듣기 문항 다시 듣기 (REPEAT) */
    fun repeatAudio() {
        val st = state ?: return
        if (!showing || st.finished) return
        speakAudio(st.current)
    }

    private fun speakAudio(item: PlacementItem?) {
        val text = item?.audioEn?.takeIf { it.isNotBlank() } ?: return
        job?.cancel()
        job = ctx.scope.launch { ctx.say(text, SpeechLang.EN) }
    }

    private suspend fun speakAudioNow(item: PlacementItem?) {
        val text = item?.audioEn?.takeIf { it.isNotBlank() } ?: return
        ctx.say(text, SpeechLang.EN)
    }

    /**
     * 결과 확정. 끝까지 풀었거나 4문항 이상 풀었으면 그 결과로 레벨을 정하고, 그보다 적으면 레벨은 그대로 둔다.
     * @return 적용한 결과 (없으면 null)
     */
    fun finish(): PlacementResult? {
        val st = state ?: return null
        cancel()
        state = null
        val res = deps.placement.result(st)
        val apply = st.finished || st.answers.size >= MIN_ANSWERS_TO_APPLY
        if (apply) {
            deps.repository.update { s ->
                val p = s.profile ?: return@update s
                s.copy(profile = p.copy(level = res.level, placementDone = true, placementCorrect = res.correct))
            }
        }
        ctx.emit { it.copy(placement = null) }
        return if (apply) res else null
    }

    /** 출발 → 레벨 테스트 화면을 닫고 상태는 보관 (정차 후 이어서) */
    fun suspendForDriving() {
        cancel()
        ctx.emit { it.copy(placement = null) }
    }

    fun reset() {
        cancel()
        state = null
        pool = emptyList()
    }

    private fun cancel() {
        job?.cancel()
        job = null
        revealing = false
        deps.speech.stopAll()
    }

    companion object {
        const val REVEAL_MS = 800L
        const val MIN_ANSWERS_TO_APPLY = 4
    }
}
