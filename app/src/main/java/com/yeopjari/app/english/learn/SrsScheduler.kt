package com.yeopjari.app.english.learn

import com.yeopjari.app.english.core.CardState
import com.yeopjari.app.english.core.Grade
import com.yeopjari.app.english.core.Scheduler
import com.yeopjari.app.english.core.SpeechThresholds
import com.yeopjari.app.english.core.Verdict
import kotlin.math.roundToLong

/*
 * 간격 반복 스케줄러 — SM-2 간소판 (명세 §6.1). 순수 함수, 안드로이드 의존 없음.
 *
 *  ease' = (ease + 0.1 − (5−q)(0.08 + (5−q)·0.02)).coerceIn(1.3, 3.0)      q = Grade.quality
 *  AGAIN → reps 0, lapses+1, 10분 뒤 다시
 *  그 외 → reps+1, 간격(일): 1회차 HARD 0.5 / GOOD 1 / EASY 2, 2회차 HARD 2 / GOOD 3 / EASY 4,
 *          3회차부터 직전 간격 × ease' × (HARD 0.6 / GOOD 1.0 / EASY 1.3), 1~180일
 */
class SrsScheduler : Scheduler {

    override fun schedule(card: CardState?, itemId: String, grade: Grade, nowMs: Long): CardState {
        val prev = card ?: CardState(itemId = itemId)
        val ease = nextEase(prev.ease, grade)
        val introduced = if (card == null || prev.introducedMs <= 0L) nowMs else prev.introducedMs

        if (grade == Grade.AGAIN) {
            return prev.copy(
                itemId = itemId,
                reps = 0,
                lapses = prev.lapses + 1,
                ease = ease,
                intervalDays = AGAIN_INTERVAL_DAYS,
                dueAtMs = nowMs + AGAIN_DELAY_MS,
                lastReviewedMs = nowMs,
                lastGrade = grade,
                introducedMs = introduced,
            )
        }

        val reps = prev.reps + 1
        val interval = when (reps) {
            1 -> when (grade) { Grade.HARD -> 0.5; Grade.EASY -> 2.0; else -> 1.0 }
            2 -> when (grade) { Grade.HARD -> 2.0; Grade.EASY -> 4.0; else -> 3.0 }
            else -> {
                val factor = when (grade) { Grade.HARD -> 0.6; Grade.EASY -> 1.3; else -> 1.0 }
                (prev.intervalDays * ease * factor).coerceIn(MIN_MATURE_INTERVAL_DAYS, MAX_INTERVAL_DAYS)
            }
        }
        return prev.copy(
            itemId = itemId,
            reps = reps,
            ease = ease,
            intervalDays = interval,
            dueAtMs = nowMs + (interval * DAY_MS).roundToLong(),
            lastReviewedMs = nowMs,
            lastGrade = grade,
            introducedMs = introduced,
        )
    }

    override fun due(cards: Collection<CardState>, nowMs: Long): List<CardState> =
        cards.filter { it.dueAtMs <= nowMs }.sortedWith(compareBy<CardState>({ it.dueAtMs }, { it.itemId }))

    override fun gradeFor(verdict: Verdict, latencyMs: Long?): Grade = when (verdict) {
        Verdict.CORRECT -> if (latencyMs != null && latencyMs >= 0 && latencyMs < SpeechThresholds.EASY_LATENCY_MS) Grade.EASY else Grade.GOOD
        Verdict.PARTIAL -> Grade.HARD
        Verdict.WRONG, Verdict.NO_ANSWER -> Grade.AGAIN
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        /** AGAIN → 10분 뒤 다시 */
        const val AGAIN_DELAY_MS = 10L * 60 * 1000
        const val AGAIN_INTERVAL_DAYS = 10.0 / (24 * 60)
        const val MIN_EASE = 1.3
        const val MAX_EASE = 3.0
        const val MAX_INTERVAL_DAYS = 180.0
        /** 3회차 이상 카드는 최소 하루 간격 */
        const val MIN_MATURE_INTERVAL_DAYS = 1.0

        /** SM-2 ease 갱신 */
        fun nextEase(ease: Double, grade: Grade): Double {
            val miss = (5 - grade.quality).toDouble()
            return (ease + 0.1 - miss * (0.08 + miss * 0.02)).coerceIn(MIN_EASE, MAX_EASE)
        }
    }
}
