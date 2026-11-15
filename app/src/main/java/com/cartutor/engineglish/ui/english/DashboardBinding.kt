package com.cartutor.engineglish.ui.english

import com.cartutor.engineglish.english.core.EnglishUiState
import com.cartutor.engineglish.english.core.ParkedDashboardState

/*
 * 코치 상태(ParkedDashboardState) → 대시보드 화면 값(DashboardUi).
 * 주차 대시보드 화면이 코치 상태를 직접 알지 않도록, 둘을 잇는 곳은 이 파일 하나뿐이다.
 */

internal fun dashboardUiOf(d: ParkedDashboardState, state: EnglishUiState): DashboardUi {
    val weak = d.pronWeakPoint
    return DashboardUi(
        minutes = d.minutes,
        answered = d.answered,
        accuracyPct = d.accuracyPercent,
        avgLatencyMs = d.avgResponseMs,
        newCount = d.newExpressions,
        reviewCount = d.reviewed,
        xpGained = d.xpGained,
        xpTotal = d.xpAfter,
        streakDays = d.streakDays,
        goalDone = d.goalDone,
        goalTarget = d.goalTarget,
        levelLabel = listOfNotNull(state.profile?.track?.ko, d.level?.ko).joinToString(" · "),
        levelProgressBefore = d.levelProgressBefore,
        levelProgressAfter = d.levelProgressAfter,
        unitLabel = d.unitTitleKo.orEmpty(),
        weakPointTitle = weak?.type?.ko,
        weakPointExample = weak?.exampleKo,
        mistakes = d.mistakes.map { it.textEn to it.meaningKo },
        key = d.sessionId,
        headline = d.headlineKo.ifBlank { "오늘도 수고했어요!" },
        subtitle = d.subtitleKo.ifBlank { null },
        goalBefore = d.goalBefore,
        leveledUp = d.leveledUp,
        levelGoalLabel = d.levelGoalKo,
        unitProgressBefore = d.unitProgressBefore,
        unitProgressAfter = d.unitProgressAfter,
        unitPassed = d.unitPassed,
        nextUnitLabel = d.nextUnitTitleKo,
        weakPointTip = weak?.tipKo,
        badges = d.events.map { eventKo(it) },
    )
}
