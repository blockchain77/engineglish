package com.yeopjari.app.english.learn

import com.yeopjari.app.english.core.LearnThresholds
import com.yeopjari.app.english.core.Level
import com.yeopjari.app.english.core.PlacementAnswer
import com.yeopjari.app.english.core.PlacementEngine
import com.yeopjari.app.english.core.PlacementItem
import com.yeopjari.app.english.core.PlacementResult
import com.yeopjari.app.english.core.PlacementState
import com.yeopjari.app.english.core.Track
import kotlin.math.abs
import kotlin.math.min

/*
 * 적응형 레벨 테스트 (명세 §6.2). 순수 함수 — 상태는 PlacementState 값으로만 오간다.
 *
 *  - 12문항, 중급에서 시작. 맞히면 한 단계 위(최대 고급), 틀리면 한 단계 아래(최소 초급).
 *  - 같은 문항은 다시 내지 않는다. 고르는 순서는 결정적: 트랙 문항과 공통 문항을 번갈아, id 순.
 *  - 그 레벨 문항이 바닥나면 가장 가까운 레벨에서 고른다 (같은 거리면 방금 움직인 방향 우선).
 *  - 결과 레벨 = 2문항 이상 풀고 60% 이상 맞힌 가장 높은 레벨, 없으면 초급.
 */
class AdaptivePlacement : PlacementEngine {

    override fun start(track: Track, pool: List<PlacementItem>, nowMs: Long): PlacementState {
        val eligible = eligible(track, pool)
        val total = min(LearnThresholds.PLACEMENT_ITEMS, eligible.size)
        val first = if (total > 0) pick(track, eligible, START_LEVEL, emptySet(), index = 0, lastCorrect = null) else null
        return PlacementState(
            track = track,
            total = total,
            currentLevel = first?.level ?: START_LEVEL,
            current = first,
            answers = emptyList(),
            startedMs = nowMs,
            currentShownMs = nowMs,
        )
    }

    override fun answer(state: PlacementState, pool: List<PlacementItem>, choiceIndex: Int, nowMs: Long): PlacementState {
        val cur = state.current ?: return state
        if (state.finished) return state
        val correct = choiceIndex == cur.answerIndex
        val answers = state.answers + PlacementAnswer(
            itemId = cur.id,
            level = cur.level,
            choiceIndex = choiceIndex,
            correct = correct,
            latencyMs = (nowMs - state.currentShownMs).coerceAtLeast(0L),
        )
        if (answers.size >= state.total) {
            return state.copy(answers = answers, current = null, currentShownMs = nowMs)
        }
        // 계단: 방금 푼 문항의 난이도 기준으로 한 단계 위/아래
        val target = if (correct) cur.level.next ?: Level.ADVANCED else cur.level.prev ?: Level.BEGINNER
        val used = answers.mapTo(HashSet()) { it.itemId }
        val next = pick(state.track, eligible(state.track, pool), target, used, answers.size, correct)
            // 풀이 바닥남 → 지금까지 푼 만큼으로 끝
            ?: return state.copy(answers = answers, current = null, total = answers.size, currentShownMs = nowMs)
        return state.copy(answers = answers, current = next, currentLevel = next.level, currentShownMs = nowMs)
    }

    override fun result(state: PlacementState): PlacementResult {
        val perLevel = LinkedHashMap<Level, Pair<Int, Int>>()
        for (level in Level.entries) {
            val atLevel = state.answers.filter { it.level == level }
            if (atLevel.isNotEmpty()) perLevel[level] = atLevel.count { it.correct } to atLevel.size
        }
        val level = resultLevel(perLevel)
        val correct = state.answers.count { it.correct }
        val total = state.answers.size
        val summary = if (total == 0) {
            "레벨 테스트 문항이 없어서 ${level.ko}부터 시작할게요."
        } else {
            "${total}문항 중 ${correct}개 정답 · ${level.ko}부터 시작할게요. ${encouragement(level)}"
        }
        return PlacementResult(level = level, correct = correct, total = total, perLevel = perLevel, summaryKo = summary)
    }

    /**
     * 결과의 확신도 0..1 (화면 보조 표시용, 인터페이스 밖 추가 기능).
     * 결과 레벨에서 푼 문항 수(4개면 충분) · 60%를 넘긴 여유 · 한 단계 위 레벨에서 막혔는지를 합친다.
     */
    fun confidence(state: PlacementState): Float {
        val r = result(state)
        val (c, n) = r.perLevel[r.level] ?: return 0f
        val sample = min(1f, n / 4f)
        val acc = c.toFloat() / n
        if (!qualifies(c, n)) return 0.5f * sample * acc // 어느 레벨도 통과 못 함 → 초급 (근거 약함)
        val margin = ((acc - PASS_RATIO) / (1f - PASS_RATIO)).coerceIn(0f, 1f)
        val upper = r.level.next?.let { up ->
            val (uc, un) = r.perLevel[up] ?: return@let 0.5f
            if (un < MIN_ANSWERS) 0.5f else (1f - (uc.toFloat() / un) / PASS_RATIO).coerceIn(0f, 1f)
        } ?: 1f
        return (0.5f * sample + 0.25f * margin + 0.25f * upper).coerceIn(0f, 1f)
    }

    // ───────────── 내부 ─────────────

    private fun eligible(track: Track, pool: List<PlacementItem>): List<PlacementItem> =
        pool.asSequence()
            .filter { it.track == null || it.track == track }
            .filter { it.choices.size >= 2 && it.answerIndex in it.choices.indices }
            .distinctBy { it.id }
            .toList()

    /** 목표 레벨부터 가까운 순으로, 트랙 문항과 공통 문항을 번갈아 id 순서로 고른다 */
    private fun pick(
        track: Track,
        eligible: List<PlacementItem>,
        target: Level,
        used: Set<String>,
        index: Int,
        lastCorrect: Boolean?,
    ): PlacementItem? {
        val preferTrack = index % 2 == 0
        for (level in levelOrder(target, lastCorrect)) {
            val fresh = eligible.filter { it.level == level && it.id !in used }
            if (fresh.isEmpty()) continue
            val trackItems = fresh.filter { it.track == track }.sortedBy { it.id }
            val general = fresh.filter { it.track == null }.sortedBy { it.id }
            val chosen = if (preferTrack) trackItems.firstOrNull() ?: general.firstOrNull()
            else general.firstOrNull() ?: trackItems.firstOrNull()
            if (chosen != null) return chosen
        }
        return null
    }

    /** 목표 레벨 → 거리 1 → 거리 2. 같은 거리면 방금 맞혔으면 위, 아니면 아래 먼저 */
    private fun levelOrder(target: Level, lastCorrect: Boolean?): List<Level> {
        val upFirst = lastCorrect == true
        return Level.entries.sortedWith(
            compareBy<Level>({ abs(it.ordinal - target.ordinal) }, { if (upFirst) -it.ordinal else it.ordinal }),
        )
    }

    private fun resultLevel(perLevel: Map<Level, Pair<Int, Int>>): Level =
        Level.entries.reversed().firstOrNull { level ->
            val (c, n) = perLevel[level] ?: return@firstOrNull false
            qualifies(c, n)
        } ?: Level.BEGINNER

    /** 2문항 이상 · 60% 이상 (정수 비교로 반올림 오차 없음) */
    private fun qualifies(correct: Int, answered: Int): Boolean = answered >= MIN_ANSWERS && correct * 10 >= answered * 6

    private fun encouragement(level: Level): String = when (level) {
        Level.BEGINNER -> "기초 표현부터 차근차근 다져 볼게요."
        Level.INTERMEDIATE -> "기본 표현은 탄탄해요."
        Level.ADVANCED -> "어려운 표현도 잘 알고 있어요."
    }

    companion object {
        val START_LEVEL = Level.INTERMEDIATE
        const val MIN_ANSWERS = 2
        const val PASS_RATIO = 0.6f
    }
}
