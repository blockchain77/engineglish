package com.cartutor.engineglish.ui.english

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TipsAndUpdates
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cartutor.engineglish.english.core.EnglishCoach
import com.cartutor.engineglish.english.core.EnglishRoute
import com.cartutor.engineglish.english.core.EnglishUiState
import com.cartutor.engineglish.english.core.EveningStudy
import com.cartutor.engineglish.english.core.ExpressionUi
import com.cartutor.engineglish.english.core.Kst
import com.cartutor.engineglish.english.core.SessionOrigin
import com.cartutor.engineglish.english.core.SessionRequest
import com.cartutor.engineglish.english.core.SuggestionKind
import com.cartutor.engineglish.english.core.SuggestionUi
import com.cartutor.engineglish.ui.theme.Y
import com.cartutor.engineglish.ui.theme.YType

/*
 * 홈 (정차 중) — 다음 출퇴근 세션 계획 · 오늘 진행 · 이어서 학습 · 복습 · 오늘의 한 문장 · 지난 학습 결과 · 저장한 표현.
 */

@Composable
internal fun HomeScreen(state: EnglishUiState, coach: EnglishCoach, a: AppActions) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cols = when {
            maxWidth >= 1500.dp -> 3
            maxWidth >= 980.dp -> 2
            else -> 1
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp)
                .padding(top = 8.dp, bottom = 40.dp),
        ) {
            Greeting(state)
            state.suggestion?.let {
                SuggestionCard(it, coach)
                Spacer(Modifier.height(20.dp))
            }
            CardGrid(
                cols = cols,
                cards = listOf(
                    { m -> CommutePlanCard(state, coach, a, m) },
                    { m -> TodayCard(state, m) },
                ),
                weights = if (cols >= 2) listOf(if (cols == 3) 2f else 1.2f, 1f) else null,
            )
            Spacer(Modifier.height(20.dp))
            CardGrid(
                cols = cols,
                cards = listOf(
                    { m -> ContinueCard(state, coach, a, m) },
                    { m -> ReviewCard(state, coach, a, m) },
                    { m -> SentenceCard(state, coach, a, m) },
                ),
            )
            Spacer(Modifier.height(20.dp))
            CardGrid(
                cols = cols,
                cards = listOf(
                    { m -> LastResultCard(state, coach, a, m) },
                    { m -> SavedCard(state, a, m) },
                ),
            )
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = Y.Text3, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "학습 기록은 이 차 안에만 암호화해 저장돼요. 답은 앱 안에서 채점하고 서버로 보내지 않아요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Y.Text3,
                )
            }
        }
    }
}

/** 카드 격자 — cols 개씩 한 줄, 같은 높이 */
@Composable
internal fun CardGrid(
    cols: Int,
    cards: List<@Composable (Modifier) -> Unit>,
    weights: List<Float>? = null,
    spacing: Dp = 20.dp,
) {
    if (cols <= 1) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            cards.forEach { it(Modifier.fillMaxWidth()) }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
        cards.withIndex().toList().chunked(cols).forEach { row ->
            // 같은 줄 카드는 가장 큰 카드 높이에 맞춘다
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                row.forEach { (i, card) ->
                    val w = weights?.getOrNull(i) ?: 1f
                    card(Modifier.weight(w).fillMaxHeight())
                }
                if (weights == null) repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Greeting(state: EnglishUiState) {
    val m = Kst.minuteOfDay(System.currentTimeMillis())
    val hello = when (m / 60) {
        in 5..10 -> "좋은 아침이에요"
        in 11..16 -> "좋은 오후예요"
        in 17..21 -> "오늘도 수고 많았어요"
        else -> "늦게까지 수고 많아요"
    }
    val p = state.profile
    val sub = if (p != null) "${p.track.ko} · ${p.level.ko} · 하루 ${p.goal.items}문장" else null
    ScreenHeader(
        title = hello,
        subtitle = sub,
        trailing = {
            Chip("${state.progress.xp} XP", color = Y.Accent, icon = Icons.Rounded.Star, large = true)
            Spacer(Modifier.width(12.dp))
            Chip("연속 ${state.progress.streakDays}일", color = Y.Retry, icon = Icons.Rounded.LocalFireDepartment, large = true)
        },
    )
}

@Composable
private fun SuggestionCard(s: SuggestionUi, coach: EnglishCoach) {
    val icon = when (s.kind) {
        SuggestionKind.START_COMMUTE_SESSION -> Icons.Rounded.DirectionsCar
        SuggestionKind.SWITCH_TRACK_TRAVEL -> Icons.Rounded.Flight
        SuggestionKind.CONTEXT_HOOK -> Icons.Rounded.TipsAndUpdates
        SuggestionKind.PRON_DRILL -> Icons.Rounded.GraphicEq
        SuggestionKind.PLACEMENT_NEEDED -> Icons.Rounded.Quiz
        SuggestionKind.REVIEW_DUE -> Icons.Rounded.History
    }
    Panel(Modifier.fillMaxWidth(), accent = Y.Accent, padding = 24.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, Y.Accent, size = 64.dp)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(s.titleKo, style = MaterialTheme.typography.titleLarge, color = Y.Text1, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(s.bodyKo, style = MaterialTheme.typography.bodyLarge, color = Y.Text2)
            }
            Spacer(Modifier.width(16.dp))
            BigButton(s.dismissLabelKo, { coach.dismissSuggestion(s.id) }, tone = Tone.GHOST)
            Spacer(Modifier.width(12.dp))
            BigButton(s.acceptLabelKo, { coach.acceptSuggestion(s.id) })
        }
    }
}

@Composable
private fun CommutePlanCard(state: EnglishUiState, coach: EnglishCoach, a: AppActions, modifier: Modifier) {
    val preview = state.nextPlanPreviewKo
    val (head, parts) = splitPlanTitle(preview ?: "오늘의 세션")
    val st = state.settings
    Panel(modifier, accent = Y.Accent, padding = 30.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("다음 세션", color = Y.Accent)
            Spacer(Modifier.weight(1f))
            Chip(
                if (st.autoStartOnCommute) "출근길 자동 시작" else "출근길 직접 시작",
                color = if (st.autoStartOnCommute) Y.LoadRelaxed else Y.Text2,
            )
            Spacer(Modifier.width(8.dp))
            Chip(
                "퇴근길 ${st.eveningStudy.ko}",
                color = if (st.eveningStudy == EveningStudy.NEVER) Y.Text2 else Y.Periwinkle,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(head, style = YType.Hero, color = Y.Text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(14.dp))
        if (parts.isNotEmpty()) {
            ChipFlow {
                parts.forEach { Chip(it, color = Y.Text1, bg = Y.Bg3, large = true) }
            }
        } else {
            Text("출발하면 남은 운전 시간에 맞춰 오늘 할 양을 정해요.", style = MaterialTheme.typography.bodyLarge, color = Y.Text2)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "출근길은 ${st.morningMode.ko}, 퇴근길은 ${st.eveningMode.ko} · 도착 1분 전에 학습을 마치고, 주차하면 결과를 보여 드려요",
            style = MaterialTheme.typography.bodyMedium,
            color = Y.Text3,
        )
        Spacer(Modifier.weight(1f, fill = false))
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BigButton(
                "지금 시작",
                { a.result(coach.startSession(SessionRequest(origin = SessionOrigin.USER))) },
                icon = Icons.Rounded.PlayArrow,
                modifier = Modifier.weight(1f),
            )
            BigButton(
                "주행 화면",
                { a.go(EnglishRoute.DRIVE) },
                icon = Icons.Rounded.DirectionsCar,
                tone = Tone.SECONDARY,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TodayCard(state: EnglishUiState, modifier: Modifier) {
    val pr = state.progress
    val goal = pr.todayGoal.takeIf { it > 0 } ?: state.profile?.goal?.items ?: 0
    Panel(modifier, padding = 30.dp) {
        Eyebrow("오늘")
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(
                progress = if (goal > 0) pr.todayAnswered.toFloat() / goal else 0f,
                modifier = Modifier.size(156.dp),
                stroke = 14.dp,
                color = if (goal > 0 && pr.todayAnswered >= goal) Y.Correct else Y.Accent,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${pr.todayAnswered}", style = YType.Metric, color = Y.Text1)
                    Text("/ ${goal}문장", style = MaterialTheme.typography.labelLarge, color = Y.Text3)
                }
            }
            Spacer(Modifier.width(26.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                MiniStat(Icons.Rounded.LocalFireDepartment, "연속 학습", "${pr.streakDays}일", Y.Retry)
                MiniStat(Icons.Rounded.EmojiEvents, "정확도", pr.todayAccuracy?.let { percentKo(it) } ?: "-", Y.Correct)
                MiniStat(Icons.Rounded.Star, "누적 XP", "${pr.xp}", Y.Accent)
            }
        }
        if (goal > 0 && pr.todayAnswered >= goal) {
            Spacer(Modifier.height(16.dp))
            Chip("오늘 목표를 채웠어요", color = Y.Correct, large = true)
        }
    }
}

@Composable
private fun MiniStat(icon: ImageVector, label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelLarge, color = Y.Text3)
            Text(value, style = MaterialTheme.typography.titleLarge, color = Y.Text1, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ContinueCard(state: EnglishUiState, coach: EnglishCoach, a: AppActions, modifier: Modifier) {
    val pr = state.progress
    val p = state.profile
    val unit = pr.currentUnit
    Panel(modifier, onClick = { a.go(EnglishRoute.LESSON) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(p?.track?.let { trackIcon(it) } ?: Icons.Rounded.Quiz, Y.Accent, size = 56.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Eyebrow("이어서 학습")
                Text(
                    listOfNotNull(p?.track?.ko, p?.level?.ko).joinToString(" · ").ifEmpty { "학습" },
                    style = MaterialTheme.typography.titleLarge,
                    color = Y.Text1,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        if (unit != null) {
            Text("유닛 ${unit.key.unit} · ${unit.titleKo}", style = MaterialTheme.typography.titleMedium, color = Y.Text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Meter(if (unit.total > 0) unit.answered.toFloat() / unit.total else 0f)
            Spacer(Modifier.height(6.dp))
            Text("${unit.answered} / ${unit.total}문장 · 80% 넘게 맞히면 다음 유닛", style = MaterialTheme.typography.bodySmall, color = Y.Text3)
        } else {
            Text("첫 유닛부터 시작해요", style = MaterialTheme.typography.titleMedium, color = Y.Text2)
        }
        Spacer(Modifier.height(14.dp))
        Text("레벨 업까지 ${percentKo(pr.levelProgress)}", style = MaterialTheme.typography.labelLarge, color = Y.Text2)
        Spacer(Modifier.height(6.dp))
        Meter(pr.levelProgress, color = Y.Periwinkle, height = 8.dp)
        Spacer(Modifier.height(20.dp))
        BigButton(
            "정차 중에 학습하기",
            { a.result(coach.startSession(SessionRequest(track = p?.track, origin = SessionOrigin.USER))) },
            tone = Tone.SECONDARY,
            icon = Icons.Rounded.PlayArrow,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ReviewCard(state: EnglishUiState, coach: EnglishCoach, a: AppActions, modifier: Modifier) {
    val due = state.progress.dueReviews
    val mistakes = state.library.mistakesToday.size
    Panel(modifier, onClick = { a.go(EnglishRoute.REVIEW) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.History, Y.LoadParked, size = 56.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Eyebrow("복습")
                Text("지금 복습할 표현", style = MaterialTheme.typography.titleLarge, color = Y.Text1, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$due", style = YType.Metric, color = if (due > 0) Y.Text1 else Y.Text3)
            Spacer(Modifier.width(6.dp))
            Text("개", style = MaterialTheme.typography.titleMedium, color = Y.Text3, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(8.dp))
        if (mistakes > 0) Chip("오늘 틀린 표현 ${mistakes}개", color = Y.Retry)
        else Text("간격 반복으로 잊기 직전에 다시 물어봐요", style = MaterialTheme.typography.bodyMedium, color = Y.Text3)
        Spacer(Modifier.height(20.dp))
        BigButton(
            "복습 시작",
            { a.result(coach.startReview()) },
            enabled = due > 0 || mistakes > 0,
            tone = Tone.SECONDARY,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SentenceCard(state: EnglishUiState, coach: EnglishCoach, a: AppActions, modifier: Modifier) {
    val lib = state.library
    val best = state.summary?.bestSentence?.let { it to "방금 세션" }
        ?: state.dashboard?.bestSentence?.let { it to "지난 학습" }
    val e: ExpressionUi? = best?.let { ExpressionUi(null, it.first, "", it.second) }
        ?: lib.mistakesToday.firstOrNull()
        ?: lib.dueSoon.firstOrNull()
        ?: lib.saved.firstOrNull()
    Panel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.FormatQuote, Y.Periwinkle, size = 56.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Eyebrow("오늘의 한 문장")
                Text("소리 내어 한 번 읽어 보세요", style = MaterialTheme.typography.titleMedium, color = Y.Text2)
            }
        }
        Spacer(Modifier.height(18.dp))
        if (e != null) {
            Text(e.textEn, style = YType.English.copy(fontSize = YType.English.fontSize * 0.85f), color = Y.Accent, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (e.meaningKo.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(e.meaningKo, style = MaterialTheme.typography.bodyLarge, color = Y.Text2)
            }
            Spacer(Modifier.height(10.dp))
            if (e.tagKo.isNotBlank()) Chip(e.tagKo)
            Spacer(Modifier.height(18.dp))
            BigButton(
                "표현 저장",
                { a.result(coach.saveExpression(e.textEn)) },
                icon = Icons.Rounded.BookmarkAdd,
                tone = Tone.GHOST,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text("첫 세션을 마치면 오늘 가장 아쉬웠던 문장을 여기에 띄워 드려요.", style = MaterialTheme.typography.bodyLarge, color = Y.Text3)
        }
    }
}

/** 지난 학습 결과 — 마지막 주행 학습의 주차 대시보드를 다시 연다 */
@Composable
private fun LastResultCard(state: EnglishUiState, coach: EnglishCoach, a: AppActions, modifier: Modifier) {
    val d = state.dashboard
    Panel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.EmojiEvents, Y.Correct, size = 56.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Eyebrow("지난 학습 결과")
                Text(
                    if (d != null) "${d.slot.ko} · ${dateTimeKo(d.endedAtMs)}" else "아직 결과가 없어요",
                    style = MaterialTheme.typography.titleLarge,
                    color = Y.Text1,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        if (d != null) {
            ChipFlow {
                Chip("${d.answered}문장", color = Y.Text1, bg = Y.Bg3)
                Chip("정확도 ${d.accuracyPercent}%", color = Y.Correct)
                Chip("+${d.xpGained} XP", color = Y.Accent)
            }
            Spacer(Modifier.weight(1f, fill = false))
            Spacer(Modifier.height(18.dp))
            BigButton(
                "결과 다시 보기",
                { a.result(coach.showDashboard()) },
                icon = Icons.Rounded.EmojiEvents,
                tone = Tone.SECONDARY,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                "주행 학습을 마치고 주차하면 오늘의 학습 결과와 진행률을 한눈에 보여 드려요.",
                style = MaterialTheme.typography.bodyMedium,
                color = Y.Text2,
            )
        }
    }
}

@Composable
private fun SavedCard(state: EnglishUiState, a: AppActions, modifier: Modifier) {
    val n = state.progress.savedExpressions
    Panel(modifier, onClick = { a.go(EnglishRoute.REVIEW) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Bookmark, Y.Accent, size = 56.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Eyebrow("저장한 표현")
                Text("${n}개", style = MaterialTheme.typography.titleLarge, color = Y.Text1, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("운전 중엔 Gleo에게 ‘이 표현 저장해 줘’라고 말해 보세요", style = MaterialTheme.typography.bodyMedium, color = Y.Text2)
    }
}
