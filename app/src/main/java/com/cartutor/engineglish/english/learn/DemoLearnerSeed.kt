package com.cartutor.engineglish.english.learn

import com.cartutor.engineglish.english.core.CardState
import com.cartutor.engineglish.english.core.CommutePattern
import com.cartutor.engineglish.english.core.CommuteSlot
import com.cartutor.engineglish.english.core.ContentSource
import com.cartutor.engineglish.english.core.DailyGoal
import com.cartutor.engineglish.english.core.DayStat
import com.cartutor.engineglish.english.core.FluencyDay
import com.cartutor.engineglish.english.core.Grade
import com.cartutor.engineglish.english.core.Kst
import com.cartutor.engineglish.english.core.LearnerProfile
import com.cartutor.engineglish.english.core.LearnerState
import com.cartutor.engineglish.english.core.Level
import com.cartutor.engineglish.english.core.Mistake
import com.cartutor.engineglish.english.core.PronIssueRecord
import com.cartutor.engineglish.english.core.PronIssueType
import com.cartutor.engineglish.english.core.PronProfile
import com.cartutor.engineglish.english.core.ProgressState
import com.cartutor.engineglish.english.core.SavedExpression
import com.cartutor.engineglish.english.core.SessionRecord
import com.cartutor.engineglish.english.core.Skill
import com.cartutor.engineglish.english.core.SlotMode
import com.cartutor.engineglish.english.core.Track
import com.cartutor.engineglish.english.core.UnitRecord
import com.cartutor.engineglish.english.core.Verdict
import kotlin.math.roundToLong

/**
 * PT·데모 전용 가상 학습 기록 — 가상 인물의 '지난 2주 출퇴근 학습'(개인정보 없음).
 * 데모가 허용된 빌드(디버그 또는 ADB 켜짐)에서 명시적으로 부를 때만 쓴다. 자동으로 실행되지 않는다.
 *
 * 프로필·설정은 그대로 두고(프로필이 없으면 비즈니스 · 중급으로 만든다) 학습 기록을 가상 기록으로 바꾼다:
 * 아래 레벨 유닛 통과 + 지금 레벨 1유닛 일부, 카드 ⅓쯤 오늘 복습 예정, 어제까지 5일 스트릭(오늘 답하면 6일),
 * 12일 통계 · 발음 프로필 · 빨라지는 응답 지연 · 지난 세션 · 저장 표현 · 어제 틀린 표현.
 */
object DemoLearnerSeed {
    private const val DAY = 24L * 60 * 60 * 1000
    private const val HOUR = 60L * 60 * 1000
    private const val MIN = 60L * 1000

    fun seed(state: LearnerState, content: ContentSource, nowMs: Long): LearnerState {
        val today = Kst.epochDay(nowMs)
        val profile = (state.profile ?: LearnerProfile(Track.BUSINESS, DailyGoal.STEADY, Level.INTERMEDIATE, createdDay = today - 14))
            .copy(placementDone = true, placementCorrect = state.profile?.placementCorrect ?: 8)

        // 배운 문항: 아래 레벨 전부 + 지금 레벨 앞부분 (초급이면 1유닛 전체 + 2유닛 앞 4개)
        val lowerUnits = Level.entries.filter { it < profile.level }.flatMap { content.units(profile.track, it) }
        val levelUnits = content.units(profile.track, profile.level)
        val lowerItems = lowerUnits.flatMap { content.unitItems(it.key) }
        val levelItems = levelUnits.flatMap { content.unitItems(it.key) }
        val learned = lowerItems + levelItems.take(if (lowerItems.isEmpty()) 14 else 4)

        val cards = LinkedHashMap<String, CardState>()
        learned.forEachIndexed { i, item -> cards[item.id] = card(item.id, i, nowMs) }

        // 유닛 기록: 아래 레벨은 통과, 지금 레벨은 푼 만큼 점수
        val units = LinkedHashMap<String, UnitRecord>()
        lowerUnits.forEachIndexed { u, info ->
            val scores = info.itemIds.withIndex().associate { (i, id) -> id to if ((i + u) % 9 == 4) 0.5f else 1f }
            units[info.key.id] = UnitRecord(info.key.id, scores, passed = true, passedAtMs = nowMs - (10 - u % 5) * DAY)
        }
        val seenAtLevel = learned.filter { it.level == profile.level }.map { it.id }.toSet()
        levelUnits.forEachIndexed { u, info ->
            val scores = info.itemIds.filter { it in seenAtLevel }.withIndex().associate { (i, id) -> id to if (i % 5 == 3) 0.5f else 1f }
            if (scores.isEmpty()) return@forEachIndexed
            val passed = scores.size == info.itemIds.size && scores.values.average() >= 0.8
            units[info.key.id] = UnitRecord(info.key.id, scores, passed = passed, passedAtMs = if (passed) nowMs - (3 + u) * DAY else null)
        }

        // 지난 12일: 어제까지 5일 연속, 그 전엔 띄엄띄엄
        val activeBack = listOf(1, 2, 3, 4, 5, 7, 8, 10, 12)
        val days = activeBack.sortedDescending().mapIndexed { k, back ->
            val answered = 12 + (back * 7) % 13
            val correct = (answered * (0.68 + 0.02 * k)).toInt().coerceAtMost(answered)
            val partial = ((answered - correct) / 2)
            DayStat(
                day = today - back,
                answered = answered,
                correct = correct,
                partial = partial,
                newLearned = 3 + back % 4,
                reviewed = answered - (3 + back % 4),
                xp = correct * 10 + partial * 5,
                driveMinutes = if (back % 3 == 0) 32 else 64,
            )
        }
        val progress = ProgressState(
            xp = days.sumOf { it.xp } + units.values.count { it.passed } * 50,
            streakDays = 5,
            bestStreak = 7,
            lastActiveDay = today - 1,
            days = days,
            units = units,
            totalAnswered = days.sumOf { it.answered },
            totalCorrect = days.sumOf { it.correct },
        )

        // 발음 프로필: R/L 이 가장 잦음
        val pronSamples = listOf(
            Triple(PronIssueType.R_L, "right", "light"), Triple(PronIssueType.TH_S, "think", "sink"),
            Triple(PronIssueType.R_L, "road", "load"), Triple(PronIssueType.F_P, "coffee", "copy"),
            Triple(PronIssueType.R_L, "correct", "collect"), Triple(PronIssueType.TH_S, "thank", "sank"),
            Triple(PronIssueType.V_B, "very", "berry"), Triple(PronIssueType.R_L, "pray", "play"),
        )
        val pron = PronProfile(
            counts = linkedMapOf(PronIssueType.R_L to 5, PronIssueType.TH_S to 3, PronIssueType.F_P to 2, PronIssueType.V_B to 1),
            recent = pronSamples.mapIndexed { i, (t, e, h) -> PronIssueRecord(t, e, h, nowMs - (8 - i) * DAY + 8 * HOUR) },
        )

        // 유창성: 2주 전 2.4초 → 어제 1.5초
        val fluency = activeBack.sortedDescending().map { back ->
            FluencyDay(
                day = today - back,
                samples = 6 + back % 4,
                avgLatencyMs = 1_450L + back * 80L,
                avgWpm = 112 - back * 2,
                avgPauses = 0.8f + back * 0.08f,
            )
        }

        val sessions = activeBack.take(6).sortedDescending().mapIndexed { k, back ->
            val morning = k % 2 == 0
            val start = nowMs - back * DAY + if (morning) 0L else 10 * HOUR + 20 * MIN
            val stat = days.first { it.day == today - back }
            SessionRecord(
                id = "s-demo-$back-${if (morning) "am" else "pm"}",
                startedMs = start,
                endedMs = start + 32 * MIN,
                slot = if (morning) CommuteSlot.MORNING else CommuteSlot.EVENING,
                mode = if (morning) SlotMode.NEW_EXPRESSIONS else SlotMode.REVIEW,
                track = profile.track,
                answered = stat.answered / 2 + 4,
                correct = stat.correct / 2 + 3,
                partial = stat.partial / 2,
                interrupted = 1 + back % 3,
                xp = stat.xp / 2 + 30,
            )
        }

        val sentenceLike = learned.filter { it.skill in setOf(Skill.EXPRESSION, Skill.SENTENCE_BUILD, Skill.LISTEN_REPEAT) && it.answerWords <= 12 }
        val saved = sentenceLike.take(3).mapIndexed { i, item -> SavedExpression(item.modelAnswer, item.id, nowMs - (6 - i * 2) * DAY) }
        val mistakes = learned.filterIndexed { i, _ -> i % 11 == 5 }.take(3).mapIndexed { i, item ->
            Mistake(item.id, today - 1 - i, if (i % 2 == 0) Verdict.WRONG else Verdict.PARTIAL, nowMs - (1 + i) * DAY + 9 * HOUR)
        }

        return state.copy(
            profile = profile,
            progress = progress,
            cards = cards,
            pron = pron,
            fluency = fluency,
            commute = CommutePattern(
                morningDepartures = listOf(455, 461, 452, 466, 458),
                eveningDepartures = listOf(1086, 1101, 1093),
                trips = 8,
            ),
            saved = saved,
            mistakes = mistakes,
            sessions = sessions,
        )
    }

    /** 결정적인 가상 카드 — 대략 ⅓ 은 이미 복습 때가 지남 */
    private fun card(itemId: String, i: Int, nowMs: Long): CardState {
        val reps = 1 + i % 4
        val interval = when (reps) { 1 -> 1.0; 2 -> 3.0; 3 -> 7.0; else -> 15.0 }
        val overdue = i % 3 == 0
        val last = if (overdue) nowMs - ((interval + 0.4) * DAY).roundToLong() else nowMs - ((interval * 0.4) * DAY).roundToLong()
        return CardState(
            itemId = itemId,
            reps = reps,
            lapses = if (i % 7 == 3) 1 else 0,
            ease = 2.5 - 0.15 * (i % 4),
            intervalDays = interval,
            dueAtMs = last + (interval * DAY).roundToLong(),
            lastReviewedMs = last,
            lastGrade = if (i % 5 == 2) Grade.HARD else Grade.GOOD,
            introducedMs = last - ((5 + i % 9) * DAY),
        )
    }

    /** 데모 기록이 들어 있나 (설정 화면 표시용) */
    fun isSeeded(state: LearnerState): Boolean =
        state.sessions.any { it.id.startsWith("s-demo-") }
}
