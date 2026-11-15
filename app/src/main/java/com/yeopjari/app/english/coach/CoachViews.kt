package com.yeopjari.app.english.coach

import com.yeopjari.app.english.core.CommuteMap
import com.yeopjari.app.english.core.CommuteRoute
import com.yeopjari.app.english.core.ContentSource
import com.yeopjari.app.english.core.DrivingSignals
import com.yeopjari.app.english.core.ExpressionUi
import com.yeopjari.app.english.core.Kst
import com.yeopjari.app.english.core.LearnerState
import com.yeopjari.app.english.core.LearningItem
import com.yeopjari.app.english.core.LibraryUi
import com.yeopjari.app.english.core.LoadRules
import com.yeopjari.app.english.core.ParkedDashboardState
import com.yeopjari.app.english.core.PlanKind
import com.yeopjari.app.english.core.ProgressEvent
import com.yeopjari.app.english.core.ProgressSnapshot
import com.yeopjari.app.english.core.PromptUi
import com.yeopjari.app.english.core.PronTipUi
import com.yeopjari.app.english.core.PronWeakPointUi
import com.yeopjari.app.english.core.PronunciationAnalyzer
import com.yeopjari.app.english.core.RouteStripItem
import com.yeopjari.app.english.core.Skill
import com.yeopjari.app.english.core.UnitProgress
import com.yeopjari.app.english.core.Verdict
import kotlin.math.roundToInt

/** 코치 화면 조각 만들기 (순수 함수) */
internal object CoachViews {

    /** 정차 화면 보기 — 모범 답안 + 오답 보기, 문항마다 고정된 섞인 순서 */
    fun choices(item: LearningItem): List<String> =
        (listOf(item.modelAnswer) + item.distractors)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedBy { (item.id + "|" + it).hashCode() }

    /** 정차 중에만 보이는 영어 (LISTEN_REPEAT 는 따라 할 문장) */
    fun displayEn(item: LearningItem): String? =
        item.promptEn?.takeIf { it.isNotBlank() }
            ?: if (item.skill == Skill.LISTEN_REPEAT) item.modelAnswer.takeIf { it.isNotBlank() } else null

    fun prompt(
        item: LearningItem,
        kind: PlanKind,
        titleKo: String,
        parked: Boolean,
        rules: LoadRules?,
        attempt: Int,
        hintShown: Boolean,
        explain: Boolean,
    ): PromptUi = PromptUi(
        itemId = item.id,
        skill = item.skill,
        kind = kind,
        titleKo = titleKo,
        displayKo = item.promptKo,
        displayEn = if (parked) displayEn(item) else null,
        choices = if (parked && rules?.onScreenChoices == true && item.distractors.isNotEmpty()) choices(item) else emptyList(),
        attempt = attempt,
        hintShown = hintShown,
        explanationKo = if (parked && explain) item.explanationKo else null,
    )

    /** 지금 경로 — 세션의 경로 id → 출발·도착 장소 → 지금 구간이 들어 있는 경로 */
    fun routeFor(map: CommuteMap, routeId: String?, s: DrivingSignals): CommuteRoute? =
        map.route(routeId)
            ?: map.routeBetween(s.originPlaceId, s.destinationPlaceId)
            ?: s.segmentId?.let { seg -> map.routes.firstOrNull { seg in it.segmentIds } }

    fun routeStrip(map: CommuteMap, route: CommuteRoute?, segmentId: String?): List<RouteStripItem> {
        if (route == null) return emptyList()
        val segs = map.segmentsOf(route)
        val cur = segs.indexOfFirst { it.id == segmentId }
        return segs.mapIndexed { i, seg ->
            RouteStripItem(
                segmentId = seg.id,
                nameKo = seg.nameKo,
                road = seg.road,
                current = i == cur,
                passed = cur >= 0 && i < cur,
            )
        }
    }

    fun dueTag(dueAtMs: Long, nowMs: Long): String {
        val d = dueAtMs - nowMs
        return when {
            d <= 0 -> "지금 복습"
            d < HOUR -> "${maxOf(1L, d / MINUTE)}분 뒤 복습"
            d < DAY -> "${d / HOUR}시간 뒤 복습"
            else -> "${d / DAY}일 뒤 복습"
        }
    }

    /** 정차 화면(복습·리포트) 목록 */
    fun library(
        st: LearnerState,
        content: ContentSource,
        pron: PronunciationAnalyzer,
        nowMs: Long,
    ): LibraryUi {
        val today = Kst.epochDay(nowMs)
        fun expr(id: String, tag: String): ExpressionUi? =
            content.item(id)?.let { ExpressionUi(it.id, it.modelAnswer, it.meaningKo, tag) }

        val mistakes = st.mistakes.filter { it.day == today }
            .sortedByDescending { it.atMs }
            .map { it.itemId }
            .distinct()
            .mapNotNull { expr(it, "오늘 틀림") }
        val due = st.cards.values.sortedBy { it.dueAtMs }.take(20)
            .mapNotNull { expr(it.itemId, dueTag(it.dueAtMs, nowMs)) }
        val saved = st.saved.sortedByDescending { it.savedMs }.map { s ->
            ExpressionUi(s.itemId, s.text, s.itemId?.let { content.item(it)?.meaningKo }.orEmpty(), "저장함")
        }
        val tips = st.pron.counts.entries.filter { it.value >= 1 }
            .sortedWith(compareByDescending<Map.Entry<com.yeopjari.app.english.core.PronIssueType, Int>> { it.value }.thenBy { it.key.ordinal })
            .map { (type, n) -> PronTipUi(type, n, pron.tip(type), pron.pairs(type)) }
        return LibraryUi(
            mistakesToday = mistakes,
            dueSoon = due,
            saved = saved,
            pronTips = tips,
            recentSessions = st.sessions.sortedByDescending { it.startedMs }.take(10),
        )
    }

    // ───────────── 주차 대시보드 ─────────────

    /** 세션 시간(분) — 30초 이상은 올림, 답을 하나라도 했으면 최소 1분 */
    fun sessionMinutes(startedMs: Long, endedMs: Long, answered: Int): Int =
        ((endedMs - startedMs + 30_000L) / 60_000L).toInt().coerceAtLeast(if (answered > 0) 1 else 0)

    /** 정확도 0..100 (정답 1 · 거의 맞음 0.5) */
    fun accuracyPercent(answered: Int, correct: Int, partial: Int): Int =
        if (answered <= 0) 0 else (((correct + 0.5f * partial) / answered) * 100f).roundToInt().coerceIn(0, 100)

    private fun fraction(u: UnitProgress?): Float =
        if (u == null || u.total <= 0) 0f else (u.answered.toFloat() / u.total).coerceIn(0f, 1f)

    /**
     * 주차 대시보드 — 끝난 세션([r])의 결과와, 세션 전([SessionRun.before]) → 후([after]) 진행률.
     * 순수 함수: 같은 입력이면 같은 결과.
     */
    fun dashboard(
        r: SessionRun,
        endedMs: Long,
        after: ProgressSnapshot,
        content: ContentSource,
        pron: PronunciationAnalyzer,
        bestSentence: String?,
    ): ParkedDashboardState {
        val before = r.before
        val minutes = sessionMinutes(r.startedMs, endedMs, r.answered)
        // 발음 약점: 이번 세션에서 가장 자주 헷갈린 유형 하나 + 가장 확실했던 예
        val weak = r.pronIssues.groupBy { it.type }.entries
            .sortedWith(compareByDescending<Map.Entry<com.yeopjari.app.english.core.PronIssueType, List<com.yeopjari.app.english.core.PronIssue>>> { it.value.size }.thenBy { it.key.ordinal })
            .firstOrNull()
            ?.let { (type, list) ->
                val ex = list.maxByOrNull { it.confidence } ?: list.first()
                PronWeakPointUi(
                    type = type, count = list.size, tipKo = ex.tipKo.ifBlank { pron.tip(type) },
                    expectedWord = ex.expectedWord, heardWord = ex.heardWord, exampleKo = Say.heardAs(ex.expectedWord, ex.heardWord),
                )
            }
        val mistakes = r.mistakes.entries.mapNotNull { (id, verdict) ->
            content.item(id)?.let { ExpressionUi(it.id, it.modelAnswer, it.meaningKo, if (verdict == Verdict.PARTIAL) "거의 맞음" else "틀림") }
        }
        // 유닛: 세션을 시작할 때 공부하던 유닛을 끝까지 따라간다 (통과해서 다음 유닛으로 넘어갔어도 같은 유닛 기준)
        val unitBefore = before.currentUnit ?: after.currentUnit
        val unitAfter = unitBefore?.let { b -> after.units.firstOrNull { it.key == b.key } ?: after.currentUnit?.takeIf { it.key == b.key } }
        val movedOn = unitBefore != null && after.currentUnit != null && after.currentUnit.key != unitBefore.key
        val leveledUp = r.events.any { it is ProgressEvent.LevelUp } ||
            (before.level != null && after.level != null && after.level.ordinal > before.level.ordinal)
        val unitPassed = unitAfter?.passed ?: (movedOn || leveledUp)
        return ParkedDashboardState(
            sessionId = r.id,
            slot = r.plan.slot,
            endedAtMs = endedMs,
            headlineKo = Say.dashboardHeadline(r.plan.slot),
            subtitleKo = Say.dashboardSubtitle(r.plan.slot, minutes, r.answered),
            minutes = minutes,
            answered = r.answered,
            correct = r.correct,
            partial = r.partial,
            wrong = (r.answered - r.correct - r.partial).coerceAtLeast(0),
            skipped = r.skipped,
            interrupted = r.interrupted,
            accuracyPercent = accuracyPercent(r.answered, r.correct, r.partial),
            avgResponseMs = if (r.latencies.isEmpty()) null else r.latencies.average().toLong(),
            newExpressions = r.newDone,
            reviewed = r.reviewDone,
            bestStreak = r.bestStreak,
            pronWeakPoint = weak,
            mistakes = mistakes,
            bestSentence = bestSentence,
            xpGained = r.xp,
            xpBefore = (after.xp - r.xp).coerceAtLeast(0),
            xpAfter = after.xp,
            level = after.level,
            leveledUp = leveledUp,
            levelProgressBefore = before.levelProgress.coerceIn(0f, 1f),
            levelProgressAfter = after.levelProgress.coerceIn(0f, 1f),
            levelGoalKo = Say.levelGoal(after.level),
            unitTitleKo = unitBefore?.titleKo,
            unitProgressBefore = fraction(before.currentUnit),
            unitProgressAfter = if (unitAfter != null) fraction(unitAfter) else if (unitPassed) 1f else fraction(unitBefore),
            unitPassed = unitPassed,
            nextUnitTitleKo = if (movedOn) after.currentUnit?.titleKo else null,
            goalBefore = before.todayAnswered,
            goalDone = after.todayAnswered,
            goalTarget = if (after.todayGoal > 0) after.todayGoal else (after.goal?.items ?: 0),
            streakDays = after.streakDays,
            streakExtended = after.streakDays > before.streakDays || r.events.any { it is ProgressEvent.StreakExtended },
            events = r.events.toList(),
        )
    }

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
}
