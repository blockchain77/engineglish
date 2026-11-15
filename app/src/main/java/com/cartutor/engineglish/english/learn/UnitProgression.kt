package com.cartutor.engineglish.english.learn

import com.cartutor.engineglish.english.core.AnswerOutcome
import com.cartutor.engineglish.english.core.ContentSource
import com.cartutor.engineglish.english.core.DayStat
import com.cartutor.engineglish.english.core.FluencyDay
import com.cartutor.engineglish.english.core.FluencySummary
import com.cartutor.engineglish.english.core.Grade
import com.cartutor.engineglish.english.core.Kst
import com.cartutor.engineglish.english.core.LearnThresholds
import com.cartutor.engineglish.english.core.LearnerState
import com.cartutor.engineglish.english.core.Level
import com.cartutor.engineglish.english.core.Mistake
import com.cartutor.engineglish.english.core.PronIssueRecord
import com.cartutor.engineglish.english.core.PronIssueType
import com.cartutor.engineglish.english.core.PronProfile
import com.cartutor.engineglish.english.core.PronStat
import com.cartutor.engineglish.english.core.ProgressEvent
import com.cartutor.engineglish.english.core.ProgressSnapshot
import com.cartutor.engineglish.english.core.ProgressUpdate
import com.cartutor.engineglish.english.core.Progression
import com.cartutor.engineglish.english.core.Scheduler
import com.cartutor.engineglish.english.core.SessionRecord
import com.cartutor.engineglish.english.core.Track
import com.cartutor.engineglish.english.core.UnitInfo
import com.cartutor.engineglish.english.core.UnitKey
import com.cartutor.engineglish.english.core.UnitProgress
import com.cartutor.engineglish.english.core.UnitRecord
import com.cartutor.engineglish.english.core.Verdict
import kotlin.math.ceil
import kotlin.math.min

/*
 * 학습 진행 (명세 §6.3) — XP · 스트릭(KST 달력 날짜) · 일일 통계 · 유닛 체크포인트 · 레벨 업 · SRS 카드 ·
 * 오늘 틀린 표현 · 발음 프로필 · 유창성 기록. 모두 순수 함수: LearnerState → 새 LearnerState.
 */
class UnitProgression(private val scheduler: Scheduler) : Progression {

    override fun record(state: LearnerState, outcome: AnswerOutcome, content: ContentSource, nowMs: Long): ProgressUpdate {
        val today = Kst.epochDay(nowMs)
        val item = outcome.item
        val verdict = outcome.result.verdict
        val events = ArrayList<ProgressEvent>()
        val p = state.progress

        // 1. 스트릭 — 같은 날 유지, 어제 이어서 +1, 그 밖은 1부터
        val last = p.lastActiveDay
        val streak = when {
            last != null && last >= today -> p.streakDays.coerceAtLeast(1)
            last == today - 1 -> (p.streakDays + 1).also { events += ProgressEvent.StreakExtended(it) }
            else -> 1
        }
        val lastActive = if (last != null && last > today) last else today

        // 3. XP (체크포인트·레벨 업 보너스는 아래에서 더함)
        var xp = when (verdict) {
            Verdict.CORRECT -> LearnThresholds.XP_CORRECT
            Verdict.PARTIAL -> LearnThresholds.XP_PARTIAL
            else -> 0
        }
        if (verdict == Verdict.CORRECT && outcome.grade == Grade.EASY) xp += LearnThresholds.XP_EASY_BONUS

        // 4. SRS 카드
        val card = scheduler.schedule(state.cards[item.id], item.id, outcome.grade, nowMs)

        // 5. 유닛 기록 · 체크포인트
        var units = p.units
        val key = item.unitKey
        if (key != null) {
            val prev = units[key.id] ?: UnitRecord(unitId = key.id)
            var rec = prev.copy(scores = prev.scores + (item.id to unitScore(verdict)))
            if (!rec.passed) {
                val ids = content.unit(key)?.itemIds.orEmpty()
                val accuracy = checkpointAccuracy(rec, ids)
                if (accuracy != null && accuracy + EPS >= LearnThresholds.CHECKPOINT_ACCURACY) {
                    rec = rec.copy(passed = true, passedAtMs = nowMs)
                    xp += LearnThresholds.XP_CHECKPOINT
                    events += ProgressEvent.CheckpointPassed(key, accuracy)
                    val next = UnitKey(key.track, key.level, key.unit + 1)
                    if (content.unit(next) != null) events += ProgressEvent.UnitUnlocked(next)
                }
            }
            units = units + (key.id to rec)
        }

        // 6. 레벨 업 — 프로필 트랙·레벨 문항이고, 그 레벨에서 통과한 유닛이 min(3, 유닛 수) 이상
        var profile = state.profile
        if (profile != null && item.track == profile.track && item.level == profile.level) {
            val nextLevel = profile.level.next
            val levelUnits = content.units(profile.track, profile.level)
            val need = min(LearnThresholds.UNITS_FOR_LEVEL_UP, levelUnits.size)
            val passed = levelUnits.count { units[it.key.id]?.passed == true }
            if (nextLevel != null && need > 0 && passed >= need) {
                events += ProgressEvent.LevelUp(profile.track, profile.level, nextLevel)
                xp += LearnThresholds.XP_LEVEL_UP
                profile = profile.copy(level = nextLevel)
            }
        }

        // 2. 오늘 통계
        val todayStat = p.days.firstOrNull { it.day == today } ?: DayStat(day = today)
        val isCorrect = verdict == Verdict.CORRECT
        val newToday = todayStat.copy(
            answered = todayStat.answered + 1,
            correct = todayStat.correct + if (isCorrect) 1 else 0,
            partial = todayStat.partial + if (verdict == Verdict.PARTIAL) 1 else 0,
            newLearned = todayStat.newLearned + if (outcome.isNew && outcome.grade != Grade.AGAIN) 1 else 0,
            reviewed = todayStat.reviewed + if (outcome.isNew) 0 else 1,
            xp = todayStat.xp + xp,
        )
        val days = keepRecentDays(p.days.filter { it.day != today } + newToday, today)

        // 7. 틀린 표현 (문항당 하루 1개, 7일 보관)
        var mistakes = state.mistakes
        if (!isCorrect) {
            mistakes = mistakes.filterNot { it.itemId == item.id && it.day == today } +
                Mistake(itemId = item.id, day = today, verdict = verdict, atMs = nowMs)
        }
        mistakes = mistakes.filter { it.day > today - LearnThresholds.MISTAKE_KEEP_DAYS }

        // 발음 프로필
        var pron = state.pron
        val issues = outcome.result.pronIssues
        if (issues.isNotEmpty()) {
            val counts = LinkedHashMap(pron.counts)
            issues.forEach { counts[it.type] = (counts[it.type] ?: 0) + 1 }
            val recent = (pron.recent + issues.map { PronIssueRecord(it.type, it.expectedWord, it.heardWord, nowMs) })
                .takeLast(LearnThresholds.PRON_RECENT_KEEP)
            pron = PronProfile(counts = counts, recent = recent)
        }

        // 유창성 — 응답 지연을 알 때만 오늘 평균 갱신
        var fluency = state.fluency
        val f = outcome.fluency
        val latency = f?.latencyMs
        if (f != null && latency != null && latency >= 0) {
            val fd = fluency.firstOrNull { it.day == today } ?: FluencyDay(today, 0, 0L, 0, 0f)
            val n = fd.samples
            val wpm = f.wordsPerMinute
            val updated = FluencyDay(
                day = today,
                samples = n + 1,
                avgLatencyMs = (fd.avgLatencyMs * n + latency) / (n + 1),
                avgWpm = when {
                    wpm == null -> fd.avgWpm
                    fd.avgWpm <= 0 -> wpm
                    else -> ((fd.avgWpm.toLong() * n + wpm) / (n + 1)).toInt()
                },
                avgPauses = (fd.avgPauses * n + f.pauseCount) / (n + 1),
            )
            fluency = (fluency.filter { it.day != today } + updated)
                .filter { it.day > today - LearnThresholds.DAY_STAT_KEEP }
                .sortedBy { it.day }
        }

        // 8. 하루 목표 달성 (이번 답으로 처음 넘었을 때만)
        val goal = profile?.goal?.items
        if (goal != null && todayStat.answered < goal && newToday.answered >= goal) {
            events += ProgressEvent.DailyGoalReached(goal)
        }

        val progress = p.copy(
            xp = p.xp + xp,
            streakDays = streak,
            bestStreak = maxOf(p.bestStreak, streak),
            lastActiveDay = lastActive,
            days = days,
            units = units,
            totalAnswered = p.totalAnswered + 1,
            totalCorrect = p.totalCorrect + if (isCorrect) 1 else 0,
        )
        val next = state.copy(
            profile = profile,
            progress = progress,
            cards = state.cards + (item.id to card),
            pron = pron,
            fluency = fluency,
            mistakes = mistakes,
        )
        return ProgressUpdate(state = next, xpGained = xp, events = events)
    }

    override fun recordSession(state: LearnerState, record: SessionRecord): LearnerState {
        val already = state.sessions.any { it.id == record.id }
        val sessions = (state.sessions.filterNot { it.id == record.id } + record).takeLast(LearnThresholds.SESSION_KEEP)
        if (already) return state.copy(sessions = sessions) // 같은 세션 두 번 기록 → 운전 시간은 한 번만
        val day = Kst.epochDay(record.endedMs)
        val minutes = (((record.endedMs - record.startedMs).coerceAtLeast(0L) + 30_000L) / 60_000L).toInt()
        val p = state.progress
        val stat = p.days.firstOrNull { it.day == day } ?: DayStat(day = day)
        val days = keepRecentDays(p.days.filter { it.day != day } + stat.copy(driveMinutes = stat.driveMinutes + minutes), maxOf(day, p.lastActiveDay ?: day))
        return state.copy(sessions = sessions, progress = p.copy(days = days))
    }

    override fun unlockedUnits(state: LearnerState, content: ContentSource, track: Track, level: Level): List<UnitKey> {
        val units = content.units(track, level).sortedBy { it.key.unit }
        val result = ArrayList<UnitKey>()
        units.forEachIndexed { i, u ->
            val prevPassed = i == 0 || state.passed(units[i - 1].key)
            if (prevPassed || state.passed(u.key)) result += u.key
        }
        return result
    }

    override fun currentUnit(state: LearnerState, content: ContentSource): UnitKey? {
        val profile = state.profile ?: return null
        return currentUnitFor(state, content, profile.track, profile.level)
    }

    /** 트랙·레벨을 직접 지정한 현재 유닛 (세션 트랙이 프로필과 다를 때) */
    fun currentUnitFor(state: LearnerState, content: ContentSource, track: Track, level: Level): UnitKey? =
        firstOpenUnit(state, unlockedUnits(state, content, track, level))

    override fun snapshot(state: LearnerState, content: ContentSource, nowMs: Long): ProgressSnapshot {
        val today = Kst.epochDay(nowMs)
        val p = state.progress
        val profile = state.profile
        val todayStat = p.days.firstOrNull { it.day == today }
        val last = p.lastActiveDay
        val streak = if (last != null && last >= today - 1) p.streakDays else 0

        var units = emptyList<UnitProgress>()
        var current: UnitProgress? = null
        var levelProgress = 0f
        var totalItems = 0
        if (profile != null) {
            val infos = content.units(profile.track, profile.level).sortedBy { it.key.unit }
            val unlocked = unlockedUnits(state, content, profile.track, profile.level).toSet()
            units = infos.map { unitProgress(state, it, it.key in unlocked) }
            val currentKey = firstOpenUnit(state, infos.map { it.key }.filter { it in unlocked })
            current = units.firstOrNull { it.key == currentKey }
            val need = min(LearnThresholds.UNITS_FOR_LEVEL_UP, infos.size)
            if (need > 0) levelProgress = (units.count { it.passed }.toFloat() / need).coerceIn(0f, 1f)
            totalItems = content.items().count { it.track == profile.track && it.unit > 0 }
        }

        val week = (6 downTo 0).map { back ->
            val day = today - back
            p.days.firstOrNull { it.day == day } ?: DayStat(day = day)
        }
        val pronTop = state.pron.counts.entries
            .filter { it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<PronIssueType, Int>> { it.value }.thenBy { it.key.ordinal })
            .take(3)
            .map { PronStat(it.key, it.value) }

        return ProgressSnapshot(
            track = profile?.track,
            level = profile?.level,
            goal = profile?.goal,
            xp = p.xp,
            streakDays = streak,
            todayAnswered = todayStat?.answered ?: 0,
            todayGoal = profile?.goal?.items ?: 0,
            todayAccuracy = todayStat?.takeIf { it.answered > 0 }?.let { it.correct.toFloat() / it.answered },
            dueReviews = scheduler.due(state.cards.values, nowMs).size,
            learnedItems = state.cards.values.count { it.reps >= 1 },
            totalItems = totalItems,
            currentUnit = current,
            units = units,
            levelProgress = levelProgress,
            week = week,
            pronTop = pronTop,
            fluency = fluencySummary(state.fluency, today),
            savedExpressions = state.saved.size,
        )
    }

    // ───────────── 내부 ─────────────

    private fun unitScore(verdict: Verdict): Float = when (verdict) {
        Verdict.CORRECT -> 1f
        Verdict.PARTIAL -> 0.5f
        else -> 0f
    }

    /** 유닛 문항이 충분히(기준 비율 이상) 채점됐으면 평균, 아니면 null */
    private fun checkpointAccuracy(rec: UnitRecord, unitItemIds: List<String>): Float? {
        if (unitItemIds.isEmpty()) return null
        val scored = unitItemIds.mapNotNull { rec.scores[it] }
        val needed = ceil(unitItemIds.size * LearnThresholds.CHECKPOINT_MIN_COVERAGE - EPS).toInt().coerceAtLeast(1)
        if (scored.size < needed) return null
        return scored.sum() / scored.size
    }

    private fun unitProgress(state: LearnerState, info: UnitInfo, unlocked: Boolean): UnitProgress {
        val rec = state.progress.units[info.key.id]
        val scored = info.itemIds.mapNotNull { rec?.scores?.get(it) }
        return UnitProgress(
            key = info.key,
            titleKo = info.titleKo,
            answered = scored.size,
            total = info.itemIds.size,
            accuracy = if (scored.isEmpty()) 0f else scored.sum() / scored.size,
            passed = rec?.passed == true,
            unlocked = unlocked,
        )
    }

    private fun keepRecentDays(days: List<DayStat>, today: Long): List<DayStat> =
        days.filter { it.day > today - LearnThresholds.DAY_STAT_KEEP }.sortedBy { it.day }

    /** 이번 주(최근 7일) 평균과 지난주 대비 지연 변화. 이번 주 기록이 없으면 보관된 전체로 평균 */
    private fun fluencySummary(days: List<FluencyDay>, today: Long): FluencySummary? {
        val withSamples = days.filter { it.samples > 0 }
        if (withSamples.isEmpty()) return null
        val thisWeek = withSamples.filter { it.day > today - 7 && it.day <= today }
        val lastWeek = withSamples.filter { it.day > today - 14 && it.day <= today - 7 }
        val base = thisWeek.ifEmpty { withSamples }
        val latency = weightedLatency(base)
        val trend = if (thisWeek.isNotEmpty() && lastWeek.isNotEmpty()) latency - weightedLatency(lastWeek) else 0L
        val wpmDays = base.filter { it.avgWpm > 0 }
        val wpm = if (wpmDays.isEmpty()) 0 else (wpmDays.sumOf { it.avgWpm.toLong() * it.samples } / wpmDays.sumOf { it.samples }).toInt()
        return FluencySummary(avgLatencyMs = latency, avgWpm = wpm, latencyTrendMs = trend, samples = base.sumOf { it.samples })
    }

    private fun weightedLatency(days: List<FluencyDay>): Long {
        val n = days.sumOf { it.samples.toLong() }
        return if (n == 0L) 0L else days.sumOf { it.avgLatencyMs * it.samples } / n
    }

    private companion object {
        const val EPS = 1e-4f
    }
}

/** 유닛 통과 여부 */
internal fun LearnerState.passed(key: UnitKey): Boolean = progress.units[key.id]?.passed == true

/** 열린 유닛 중 통과 안 한 첫 유닛, 모두 통과면 마지막 */
internal fun firstOpenUnit(state: LearnerState, unlocked: List<UnitKey>): UnitKey? =
    unlocked.firstOrNull { !state.passed(it) } ?: unlocked.lastOrNull()
