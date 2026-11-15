package com.yeopjari.app.english.learn

import com.yeopjari.app.english.core.CommuteSlot
import com.yeopjari.app.english.core.ContentSource
import com.yeopjari.app.english.core.HookTrigger
import com.yeopjari.app.english.core.Ids
import com.yeopjari.app.english.core.Kst
import com.yeopjari.app.english.core.LearnThresholds
import com.yeopjari.app.english.core.LearnerState
import com.yeopjari.app.english.core.LearningItem
import com.yeopjari.app.english.core.Level
import com.yeopjari.app.english.core.LoadLevel
import com.yeopjari.app.english.core.PlanKind
import com.yeopjari.app.english.core.PlanRequest
import com.yeopjari.app.english.core.PlannedItem
import com.yeopjari.app.english.core.Progression
import com.yeopjari.app.english.core.Scheduler
import com.yeopjari.app.english.core.SessionPlan
import com.yeopjari.app.english.core.SessionPlanner
import com.yeopjari.app.english.core.Skill
import com.yeopjari.app.english.core.SlotMode
import com.yeopjari.app.english.core.Track
import com.yeopjari.app.english.core.UnitKey
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * 출퇴근 세션 계획 (명세 §6.4).
 *
 *  예산: 경로가 있고 주행 중이면 남은 시간 × 0.35, 아니면 600초 (최소 180초).
 *  새 표현 몫: 새 표현 모드 0.55 · 섞어서 0.4 · 맞춤 복습 0.2 (복습만 = 0). 새 표현 1문항 60초, 복습 30초로 셈.
 *    → 출근길 32분(새 표현 모드) = 새 표현 6개 + 복습 10개, 퇴근길 32분(맞춤 복습) = 복습 18개 + 새 표현 2개.
 *  복습 재료: (맞춤 복습·퇴근길) 오늘 틀린 표현 → 복습 때가 된 카드(많이 밀린 순) → 부족하면 ease 낮은 카드.
 *  새 표현: 지금 유닛의 안 본 문항 → 다음 유닛 → 같은 레벨 다른 유닛 → (모두 봤으면) 이웃 레벨.
 *  한쪽이 모자라면 남는 시간을 다른 쪽으로 돌린다 (복습할 카드가 없는 첫날 → 새 표현을 더).
 *  주행 계획: 정차 전용(PARKED) 문항은 빼고, maxLoad = NORMAL 문항이 70% 이상이 되게 한다.
 *  순서: 새 표현·섞어서 = 복습 2개로 몸풀기 → 새 표현 1 : 복습 2 반복 / 맞춤 복습 = 틀린 표현 → 복습 → 새 표현.
 *  세션 결과에 보여 줄 '오늘의 한 문장' 후보 = 처음 나오는 새 표현 중 문장형(없으면 틀린 표현 → 복습 → 아무 문항).
 *  상황 훅 문항은 주행 중 코치가 큐 앞에 끼워 넣는다 ([pickHookItem] 보조 함수 제공).
 */
class CommuteSessionPlanner(
    private val scheduler: Scheduler,
    private val progression: Progression,
) : SessionPlanner {

    override fun plan(request: PlanRequest, content: ContentSource): SessionPlan {
        val routeMinutes = routeMinutes(request.routeRemainingSec, request.parked)
        val budget = budgetSec(request.routeRemainingSec, request.parked)
        request.pronDrill?.let { return drillPlan(request, content, routeMinutes, budget) }

        val driving = !request.parked
        val (newTarget, reviewTarget) = targets(budget, request.mode, request.reviewOnly)
        val newFocus = !request.reviewOnly && request.mode != SlotMode.REVIEW
        val reviewCands = reviewCandidates(request, content, request.exclude, driving)
        val newCands = if (request.reviewOnly) emptyList()
        else newCandidates(request.state, content, request.track, request.level, request.exclude, driving).map { Candidate(it, PlanKind.NEW) }

        fun choose(maxRelaxed: Int) = select(newCands, reviewCands, newTarget, reviewTarget, newFocus, !request.reviewOnly, driving, maxRelaxed)

        // 주행 중: NORMAL 문항 70% 이상 — RELAXED 허용 수를 줄여 가며 다시 고른다 (NORMAL 로 채워짐)
        var maxRelaxed = if (driving) floor((newTarget + reviewTarget) * (1.0 - SHARE) + 1e-6).toInt() else Int.MAX_VALUE
        var chosen = choose(maxRelaxed)
        if (driving) {
            while (true) {
                val all = chosen.first + chosen.second
                val relaxed = all.count { !driveFriendly(it.item) }
                if (relaxed == 0 || all.size - relaxed >= SHARE * all.size - 1e-9) break
                maxRelaxed = relaxed - 1
                chosen = choose(maxRelaxed)
            }
        }
        val (news, reviews) = chosen

        val ordered = if (newFocus) interleave(news, reviews) else reviews + news
        val mistakeCount = reviews.count { it.kind == PlanKind.MISTAKE }
        return SessionPlan(
            id = Ids.next("plan", request.nowMs),
            slot = request.slot,
            mode = request.mode,
            track = request.track,
            level = request.level,
            routeMinutes = routeMinutes,
            budgetSec = budget,
            items = ordered.map { PlannedItem(it.item.id, it.kind) },
            newCount = news.size,
            reviewCount = reviews.size,
            titleKo = title(request, routeMinutes, news.size, mistakeCount, reviews.size),
            wrapUpItemId = wrapUpCandidate(ordered),
            createdMs = request.nowMs,
        )
    }

    override fun extend(plan: SessionPlan, request: PlanRequest, content: ContentSource, count: Int): SessionPlan {
        if (count <= 0) return plan
        val driving = !request.parked
        val exclude = request.exclude + plan.items.map { it.itemId }
        val reviews = reviewCandidates(request, content, exclude, driving)
        val news = if (request.reviewOnly || request.pronDrill != null) emptyList()
        else newCandidates(request.state, content, request.track, request.level, exclude, driving)

        var total = plan.items.size
        var normal = plan.items.count { p -> content.item(p.itemId)?.let { driveFriendly(it) } ?: true }
        val added = ArrayList<PlannedItem>()
        var addedNew = 0
        var addedReview = 0
        fun offer(item: LearningItem, kind: PlanKind): Boolean {
            if (added.size >= count) return false
            val friendly = driveFriendly(item)
            // RELAXED 문항은 넣어도 NORMAL 비율이 70% 이상일 때만
            if (driving && !friendly && normal < SHARE * (total + 1) - 1e-9) return false
            added += PlannedItem(item.id, kind)
            total++
            if (friendly) normal++
            return true
        }
        reviews.forEach { if (offer(it.item, it.kind)) addedReview++ }
        news.forEach { if (offer(it, PlanKind.NEW)) addedNew++ }
        if (added.isEmpty()) return plan
        return plan.copy(
            items = plan.items + added,
            newCount = plan.newCount + addedNew,
            reviewCount = plan.reviewCount + addedReview,
            wrapUpItemId = plan.wrapUpItemId ?: added.firstOrNull()?.itemId,
        )
    }

    // ───────────── 후보 ─────────────

    private data class Candidate(val item: LearningItem, val kind: PlanKind)

    /** 복습 후보 (우선순위 순): 오늘 틀린 표현(맞춤 복습·퇴근길) → 때가 된 카드 → ease 낮은 카드 */
    private fun reviewCandidates(request: PlanRequest, content: ContentSource, exclude: Set<String>, driving: Boolean): List<Candidate> {
        val state = request.state
        val out = LinkedHashMap<String, Candidate>()
        fun add(id: String, kind: PlanKind) {
            if (id in exclude || id in out) return
            val item = content.item(id) ?: return
            if (driving && !drivable(item)) return
            out[id] = Candidate(item, kind)
        }
        val mistakesFirst = request.reviewOnly || request.mode == SlotMode.REVIEW || request.slot == CommuteSlot.EVENING
        if (mistakesFirst) {
            val today = Kst.epochDay(request.nowMs)
            state.mistakes.filter { it.day == today }.sortedBy { it.atMs }.forEach { add(it.itemId, PlanKind.MISTAKE) }
        }
        scheduler.due(state.cards.values, request.nowMs).forEach { add(it.itemId, PlanKind.REVIEW) }
        state.cards.values
            .sortedWith(compareBy({ it.ease }, { it.dueAtMs }, { it.itemId }))
            .forEach { add(it.itemId, PlanKind.REVIEW) }
        return out.values.toList()
    }

    /** 새 표현 후보: 지금 유닛 → 뒤 유닛 → 앞 유닛 → 다음 레벨 → 이전 레벨 (안 본 문항만) */
    private fun newCandidates(
        state: LearnerState,
        content: ContentSource,
        track: Track,
        level: Level,
        exclude: Set<String>,
        driving: Boolean,
    ): List<LearningItem> {
        val out = LinkedHashMap<String, LearningItem>()
        fun addUnit(key: UnitKey) {
            for (item in content.unitItems(key)) {
                if (item.id in exclude || item.id in state.cards || item.id in out) continue
                if (driving && !drivable(item)) continue
                out[item.id] = item
            }
        }
        val units = content.units(track, level).map { it.key }.sortedBy { it.unit }
        val current = firstOpenUnit(state, progression.unlockedUnits(state, content, track, level)) ?: units.firstOrNull()
        if (current != null) {
            addUnit(current)
            units.filter { it.unit > current.unit }.forEach(::addUnit)
            units.filter { it.unit < current.unit }.forEach(::addUnit)
        }
        listOfNotNull(level.next, level.prev).forEach { lv ->
            content.units(track, lv).map { it.key }.sortedBy { it.unit }.forEach(::addUnit)
        }
        return out.values.toList()
    }

    /** 목표 수만큼 고르기 (+ 모자란 쪽 시간을 다른 쪽으로). driving 이면 RELAXED 문항은 maxRelaxed 개까지 */
    private fun select(
        newCands: List<Candidate>,
        reviewCands: List<Candidate>,
        newTarget: Int,
        reviewTarget: Int,
        newFocus: Boolean,
        allowNew: Boolean,
        driving: Boolean,
        maxRelaxed: Int,
    ): Pair<List<Candidate>, List<Candidate>> {
        val news = ArrayList<Candidate>()
        val reviews = ArrayList<Candidate>()
        val used = HashSet<String>()
        var relaxed = 0
        fun take(cands: List<Candidate>, target: Int, sink: MutableList<Candidate>) {
            for (c in cands) {
                if (sink.size >= target) return
                if (c.item.id in used) continue
                if (driving && !driveFriendly(c.item)) {
                    if (relaxed >= maxRelaxed) continue
                    relaxed++
                }
                sink += c
                used += c.item.id
            }
        }
        if (newFocus) {
            take(newCands, newTarget, news)
            take(reviewCands, reviewTarget, reviews)
        } else {
            take(reviewCands, reviewTarget, reviews)
            take(newCands, newTarget, news)
        }
        // 복습할 카드가 모자라면 남는 시간만큼 새 표현을 더 (복습 2개 = 새 표현 1개)
        if (allowNew && reviews.size < reviewTarget) {
            val extra = (reviewTarget - reviews.size) * LearnThresholds.COST_REVIEW_SEC / LearnThresholds.COST_NEW_SEC
            take(newCands, min(LearnThresholds.MAX_NEW_PER_SESSION, maxOf(newTarget, news.size) + extra), news)
        }
        // 새 표현이 바닥났으면 남는 시간만큼 복습을 더
        if (news.size < newTarget) {
            val extra = (newTarget - news.size) * LearnThresholds.COST_NEW_SEC / LearnThresholds.COST_REVIEW_SEC
            take(reviewCands, min(LearnThresholds.MAX_REVIEW_PER_SESSION, reviews.size + extra), reviews)
        }
        return news to reviews
    }

    /** 복습 2개로 몸풀기 → 새 표현 1 : 복습 2 반복 → 남은 것 */
    private fun interleave(news: List<Candidate>, reviews: List<Candidate>): List<Candidate> {
        val out = ArrayList<Candidate>(news.size + reviews.size)
        var n = 0
        var r = 0
        repeat(WARM_UP_REVIEWS) { if (r < reviews.size) out += reviews[r++] }
        while (n < news.size || r < reviews.size) {
            if (n < news.size) out += news[n++]
            repeat(REVIEWS_PER_NEW) { if (r < reviews.size) out += reviews[r++] }
        }
        return out
    }

    private fun drillPlan(request: PlanRequest, content: ContentSource, routeMinutes: Int?, budget: Int): SessionPlan {
        val type = request.pronDrill!!
        val cards = request.state.cards
        val driving = !request.parked
        val drills = content.pronDrills(type)
            .filter { it.id !in request.exclude && (!driving || drivable(it)) }
            .distinctBy { it.id }
            // 처음 보는 짝 먼저 (콘텐츠 순서 유지), 그다음 복습 때가 이른 순
            .sortedWith(compareBy({ cards.containsKey(it.id) }, { cards[it.id]?.dueAtMs ?: 0L }))
            .take(DRILL_ITEMS)
        val fresh = drills.count { !cards.containsKey(it.id) }
        val title = if (drills.isEmpty()) "발음 연습 · ${type.ko} → 준비된 연습이 없어요"
        else "발음 연습 · ${type.ko} → 짝 단어 ${drills.size}개"
        return SessionPlan(
            id = Ids.next("plan", request.nowMs),
            slot = request.slot,
            mode = request.mode,
            track = request.track,
            level = request.level,
            routeMinutes = routeMinutes,
            budgetSec = budget,
            items = drills.map { PlannedItem(it.id, PlanKind.DRILL) },
            newCount = fresh,
            reviewCount = drills.size - fresh,
            titleKo = title,
            wrapUpItemId = drills.firstOrNull()?.id,
            createdMs = request.nowMs,
        )
    }

    private fun title(request: PlanRequest, routeMinutes: Int?, newCount: Int, mistakeCount: Int, reviewCount: Int): String {
        val prefix = when {
            routeMinutes != null -> "${request.slot.ko} ${routeMinutes}분"
            request.reviewOnly -> "오늘의 복습"
            else -> "오늘의 세션"
        }
        val parts = ArrayList<String>(3)
        if (request.reviewOnly || request.mode == SlotMode.REVIEW) {
            if (mistakeCount > 0) parts += "오늘 틀린 표현 ${mistakeCount}개"
            if (reviewCount - mistakeCount > 0) parts += "복습 ${reviewCount - mistakeCount}개"
            if (newCount > 0) parts += "새 표현 ${newCount}개"
        } else {
            if (newCount > 0) parts += "새 표현 ${newCount}개"
            if (reviewCount > 0) parts += "복습 ${reviewCount}개"
        }
        val body = when {
            parts.isNotEmpty() -> parts.joinToString(" + ")
            request.reviewOnly -> "지금 복습할 표현이 없어요"
            else -> "준비된 문항이 없어요"
        }
        return "$prefix → $body"
    }

    /** 세션 결과의 '오늘의 한 문장'으로 보여 주기 좋은 문장형 문항 우선 */
    private fun wrapUpCandidate(ordered: List<Candidate>): String? {
        fun sentenceLike(c: Candidate) = c.item.skill in SENTENCE_SKILLS && c.item.answerWords in 3..12 && driveFriendly(c.item)
        fun first(kind: PlanKind, pred: (Candidate) -> Boolean = { true }) = ordered.firstOrNull { it.kind == kind && pred(it) }
        return (
            first(PlanKind.NEW, ::sentenceLike)
                ?: first(PlanKind.MISTAKE, ::sentenceLike)
                ?: first(PlanKind.REVIEW, ::sentenceLike)
                ?: first(PlanKind.NEW)
                ?: first(PlanKind.MISTAKE)
                ?: ordered.firstOrNull()
            )?.item?.id
    }

    companion object {
        private val SHARE: Double = LearnThresholds.DRIVING_NORMAL_ITEM_SHARE.toDouble()
        private val SENTENCE_SKILLS = setOf(Skill.EXPRESSION, Skill.SENTENCE_BUILD, Skill.LISTEN_REPEAT)
        const val WARM_UP_REVIEWS = 2
        const val REVIEWS_PER_NEW = 2
        /** 발음 드릴 세션 문항 수 */
        const val DRILL_ITEMS = 6

        /** 주행 중 낼 수 있나 (정차 전용 문항 제외) */
        fun drivable(item: LearningItem): Boolean = LoadLevel.RELAXED.isAtMost(item.maxLoad)

        /** 보통 부하(NORMAL)에서도 낼 수 있나 — 주행 계획 70% 기준 */
        fun driveFriendly(item: LearningItem): Boolean = LoadLevel.NORMAL.isAtMost(item.maxLoad)

        /** 학습 예산(초): 경로가 있고 주행 중이면 남은 시간 × 0.35, 아니면 600초. 최소 180초 */
        fun budgetSec(routeRemainingSec: Int?, parked: Boolean): Int {
            val raw = if (!parked && routeRemainingSec != null && routeRemainingSec > 0) {
                (routeRemainingSec * LearnThresholds.ROUTE_STUDY_RATIO.toDouble()).roundToInt()
            } else LearnThresholds.NO_ROUTE_BUDGET_SEC
            return raw.coerceAtLeast(LearnThresholds.MIN_BUDGET_SEC)
        }

        /** 화면·안내용 경로 분 (정차 중이거나 경로가 없으면 null) */
        fun routeMinutes(routeRemainingSec: Int?, parked: Boolean): Int? =
            if (parked || routeRemainingSec == null || routeRemainingSec <= 0) null
            else ((routeRemainingSec + 30) / 60).coerceAtLeast(1)

        /** 목표 (새 표현 수, 복습 수) — 실제 수는 재료가 모자라면 달라진다 */
        fun targets(budgetSec: Int, mode: SlotMode, reviewOnly: Boolean): Pair<Int, Int> {
            val share = when {
                reviewOnly -> 0.0
                mode == SlotMode.NEW_EXPRESSIONS -> LearnThresholds.NEW_SHARE_NEW_MODE.toDouble()
                mode == SlotMode.MIXED -> LearnThresholds.NEW_SHARE_MIXED.toDouble()
                else -> LearnThresholds.NEW_SHARE_REVIEW_MODE.toDouble()
            }
            val raw = floor(budgetSec * share / LearnThresholds.COST_NEW_SEC + 1e-6).toInt()
            val newCount = when {
                reviewOnly -> 0
                mode == SlotMode.REVIEW -> raw.coerceIn(0, LearnThresholds.MAX_NEW_IN_REVIEW_MODE)
                else -> raw.coerceIn(1, LearnThresholds.MAX_NEW_PER_SESSION)
            }
            val review = floor((budgetSec - newCount * LearnThresholds.COST_NEW_SEC).toDouble() / LearnThresholds.COST_REVIEW_SEC + 1e-6)
                .toInt().coerceIn(0, LearnThresholds.MAX_REVIEW_PER_SESSION)
            return newCount to review
        }

        /** 홈 화면 '다음 세션' 미리보기 시간대: KST 12시 전 = 출근길, 이후 = 퇴근길 */
        fun previewSlot(nowMs: Long): CommuteSlot = if (Kst.minuteOfDay(nowMs) < 12 * 60) CommuteSlot.MORNING else CommuteSlot.EVENING

        /** 도착 1분 전 → 더 묻지 않고 주행 학습을 끝내는 시점 */
        fun isLessonEndTime(routeRemainingSec: Int?): Boolean =
            routeRemainingSec != null && routeRemainingSec <= LearnThresholds.END_BEFORE_ARRIVAL_SEC

        /**
         * 상황 훅 문항 고르기 (코치 보조): 그 트리거 문항 중 [allowed](보통 policy.allows) 를 통과하고,
         * 카드가 없는 문항 먼저, 없으면 가장 오래전에 본 문항.
         */
        fun pickHookItem(
            content: ContentSource,
            state: LearnerState,
            trigger: HookTrigger,
            exclude: Set<String> = emptySet(),
            allowed: (LearningItem) -> Boolean = { true },
        ): LearningItem? {
            val items = content.hook(trigger)?.items.orEmpty().filter { it.id !in exclude && allowed(it) }
            return items.firstOrNull { it.id !in state.cards } ?: items.minByOrNull { state.cards[it.id]?.lastReviewedMs ?: 0L }
        }
    }
}
