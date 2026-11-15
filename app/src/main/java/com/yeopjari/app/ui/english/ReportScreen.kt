package com.yeopjari.app.ui.english

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingFlat
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yeopjari.app.english.core.EnglishCoach
import com.yeopjari.app.english.core.EnglishUiState
import com.yeopjari.app.english.core.FluencySummary
import com.yeopjari.app.english.core.Kst
import com.yeopjari.app.english.core.ScreenMode
import com.yeopjari.app.english.core.SessionRecord
import com.yeopjari.app.ui.theme.Y
import com.yeopjari.app.ui.theme.YType

/*
 * 리포트 — 이번 주 학습 시간 · 푼 문장 · 정확도 · 응답 속도 추세 · 발음 프로필 · 최근 세션.
 */

@Composable
internal fun ReportScreen(state: EnglishUiState, coach: EnglishCoach, a: AppActions) {
    if (state.screenMode == ScreenMode.DRIVE) {
        LockedPanel()
        return
    }
    val pr = state.progress
    val lib = state.library
    val week = pr.week
    val minutes = week.sumOf { it.driveMinutes }
    val answered = week.sumOf { it.answered }
    val correct = week.sumOf { it.correct }
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
            ScreenHeader("리포트", subtitle = "최근 7일 동안 출퇴근길에서 연습한 기록이에요")
            // 핵심 숫자
            val tiles: List<@Composable (Modifier) -> Unit> = listOf(
                { m -> StatTile("학습 시간", minutesKo(minutes), m, icon = Icons.Rounded.Timer, caption = "주행하며 공부한 시간") },
                { m -> StatTile("푼 문장", "$answered", m, unit = "개", icon = Icons.Rounded.CheckCircle) },
                { m -> StatTile("정확도", if (answered > 0) ratioKo(correct, answered) else "-", m, icon = Icons.Rounded.EmojiEvents, color = Y.Correct) },
                { m -> StatTile("연속 학습", "${pr.streakDays}", m, unit = "일", icon = Icons.Rounded.LocalFireDepartment, color = Y.Retry) },
                { m -> StatTile("익힌 표현", "${pr.learnedItems}", m, unit = if (pr.totalItems > 0) "/ ${pr.totalItems}" else "개", icon = Icons.AutoMirrored.Rounded.MenuBook) },
                { m -> StatTile("누적 XP", "${pr.xp}", m, icon = Icons.Rounded.Star, color = Y.Accent) },
            )
            CardGrid(cols = if (cols >= 3) 6 else if (cols == 2) 3 else 2, cards = tiles, spacing = 16.dp)
            Spacer(Modifier.height(24.dp))
            CardGrid(
                cols = if (cols >= 2) 2 else 1,
                weights = if (cols >= 2) listOf(1.35f, 1f) else null,
                cards = listOf(
                    { m -> WeekCard(state, m) },
                    { m -> FluencyCard(pr.fluency, m) },
                ),
            )
            Spacer(Modifier.height(24.dp))
            CardGrid(
                cols = if (cols >= 2) 2 else 1,
                cards = listOf(
                    { m -> AccuracyTrendCard(lib.recentSessions, m) },
                    { m -> PronProfileCard(state, coach, a, m) },
                ),
            )
            Spacer(Modifier.height(24.dp))
            RecentSessions(lib.recentSessions)
        }
    }
}

@Composable
private fun WeekCard(state: EnglishUiState, modifier: Modifier) {
    val week = state.progress.week
    val today = Kst.epochDay(System.currentTimeMillis())
    val max = (week.maxOfOrNull { it.answered } ?: 0).coerceAtLeast(1)
    Panel(modifier, padding = 28.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("이번 주", style = MaterialTheme.typography.titleLarge, color = Y.Text1, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Dot(Y.Bg4, size = 12.dp)
            Spacer(Modifier.width(6.dp))
            Text("푼 문장", style = MaterialTheme.typography.labelLarge, color = Y.Text3)
            Spacer(Modifier.width(14.dp))
            Dot(Y.Accent, size = 12.dp)
            Spacer(Modifier.width(6.dp))
            Text("정답", style = MaterialTheme.typography.labelLarge, color = Y.Text3)
        }
        Spacer(Modifier.height(20.dp))
        if (week.isEmpty()) {
            EmptyNote(Icons.Rounded.Schedule, "아직 기록이 없어요", body = "첫 출퇴근 세션을 마치면 여기에 쌓여요.")
        } else {
            Row(Modifier.fillMaxWidth().height(240.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                week.forEach { d ->
                    val isToday = d.day == today
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${d.answered}", style = MaterialTheme.typography.labelLarge, color = if (d.answered > 0) Y.Text1 else Y.Text3)
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                            val h = d.answered.toFloat() / max
                            if (h > 0f) {
                                Box(
                                    Modifier.fillMaxWidth(0.56f).fillMaxHeight(h).clip(RoundedCornerShape(10.dp)).background(Y.Bg4),
                                    contentAlignment = Alignment.BottomCenter,
                                ) {
                                    val c = if (d.answered > 0) d.correct.toFloat() / d.answered else 0f
                                    if (c > 0f) Box(Modifier.fillMaxWidth().fillMaxHeight(c).clip(RoundedCornerShape(10.dp)).background(Y.Accent))
                                }
                            } else {
                                Box(Modifier.fillMaxWidth(0.56f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Y.Bg3))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            dayOfWeekKo(d.day),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isToday) Y.Accent else Y.Text2,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                        )
                        Text(if (d.driveMinutes > 0) "${d.driveMinutes}분" else " ", style = MaterialTheme.typography.labelSmall, color = Y.Text3)
                    }
                }
            }
        }
    }
}

@Composable
private fun FluencyCard(f: FluencySummary?, modifier: Modifier) {
    Panel(modifier, padding = 28.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Speed, Y.Periwinkle, size = 52.dp)
            Spacer(Modifier.width(14.dp))
            Text("말하기 속도", style = MaterialTheme.typography.titleLarge, color = Y.Text1, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        if (f == null || f.samples == 0) {
            EmptyNote(Icons.Rounded.Timer, "아직 잰 답이 없어요", body = "말로 답하면 얼마나 빨리 답을 시작했는지, 1분에 몇 단어를 말했는지 재요.")
            return@Panel
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(secondsKo(f.avgLatencyMs), style = YType.Metric, color = Y.Text1)
            Spacer(Modifier.width(10.dp))
            Text("만에 답을 시작해요", style = MaterialTheme.typography.titleMedium, color = Y.Text2, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        val trend = f.latencyTrendMs
        val (icon, color, text) = when {
            trend < -100 -> Triple(Icons.AutoMirrored.Rounded.TrendingDown, Y.Correct, "지난주보다 ${secondsKo(-trend)} 빨라졌어요")
            trend > 100 -> Triple(Icons.AutoMirrored.Rounded.TrendingUp, Y.Retry, "지난주보다 ${secondsKo(trend)} 느려졌어요")
            else -> Triple(Icons.AutoMirrored.Rounded.TrendingFlat, Y.Text2, "지난주와 비슷해요")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.titleMedium, color = color)
        }
        Spacer(Modifier.height(18.dp))
        // 지난주 → 이번 주 응답 지연 (낮을수록 좋음)
        val before = (f.avgLatencyMs - trend).coerceAtLeast(0L)
        Sparkline(
            values = listOf(before.toFloat(), f.avgLatencyMs.toFloat()),
            modifier = Modifier.fillMaxWidth().height(70.dp),
            color = color,
            invert = true,
        )
        Row(Modifier.fillMaxWidth()) {
            Text("지난주 ${secondsKo(before)}", style = MaterialTheme.typography.labelMedium, color = Y.Text3)
            Spacer(Modifier.weight(1f))
            Text("이번 주 ${secondsKo(f.avgLatencyMs)}", style = MaterialTheme.typography.labelMedium, color = Y.Text2)
        }
        Spacer(Modifier.height(14.dp))
        Text("1분에 ${f.avgWpm}단어 · 답변 ${f.samples}개 기준", style = MaterialTheme.typography.bodyMedium, color = Y.Text3)
    }
}

/** 작은 꺾은선 — invert = 값이 낮을수록 위 (응답 지연처럼 낮을수록 좋은 값) */
@Composable
private fun Sparkline(values: List<Float>, modifier: Modifier, color: Color, invert: Boolean = false, min: Float? = null, max: Float? = null) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = min ?: values.min()
        val hi = max ?: values.max()
        val span = (hi - lo).takeIf { it > 0f } ?: 1f
        val pad = 8.dp.toPx()
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        fun pt(i: Int): Offset {
            val x = pad + w * i / (values.size - 1)
            var t = (values[i] - lo) / span
            if (invert) t = 1f - t
            val y = pad + h * (1f - t)
            return Offset(x, y)
        }
        val path = Path().apply {
            moveTo(pt(0).x, pt(0).y)
            for (i in 1 until values.size) lineTo(pt(i).x, pt(i).y)
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(pt(values.size - 1).x, size.height)
            lineTo(pt(0).x, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.25f), Color.Transparent)))
        drawPath(path, color, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        for (i in values.indices) drawCircle(color, 5.dp.toPx(), pt(i))
    }
}

@Composable
private fun AccuracyTrendCard(sessions: List<SessionRecord>, modifier: Modifier) {
    val recent = sessions.filter { it.answered > 0 }.sortedBy { it.startedMs }.takeLast(10)
    Panel(modifier, padding = 28.dp) {
        Text("세션별 정확도", style = MaterialTheme.typography.titleLarge, color = Y.Text1, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("최근 ${recent.size}번 세션 · 거의 맞힌 답은 절반으로 셌어요", style = MaterialTheme.typography.bodyMedium, color = Y.Text3)
        Spacer(Modifier.height(18.dp))
        if (recent.size < 2) {
            EmptyNote(Icons.Rounded.EmojiEvents, "세션을 두 번 이상 마치면 추세를 보여 드려요")
        } else {
            val values = recent.map { (it.correct + it.partial * 0.5f) / it.answered * 100f }
            Sparkline(values, Modifier.fillMaxWidth().height(160.dp), Y.Correct, min = 0f, max = 100f)
            Row(Modifier.fillMaxWidth()) {
                Text(dateTimeKo(recent.first().startedMs), style = MaterialTheme.typography.labelMedium, color = Y.Text3)
                Spacer(Modifier.weight(1f))
                Text("최근 ${values.last().toInt()}%", style = MaterialTheme.typography.labelLarge, color = Y.Correct)
            }
        }
    }
}

@Composable
private fun PronProfileCard(state: EnglishUiState, coach: EnglishCoach, a: AppActions, modifier: Modifier) {
    val tips = state.library.pronTips
    Panel(modifier, padding = 28.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.GraphicEq, Y.Retry, size = 52.dp)
            Spacer(Modifier.width(14.dp))
            Text("발음 프로필", style = MaterialTheme.typography.titleLarge, color = Y.Text1, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        if (tips.isEmpty()) {
            EmptyNote(Icons.Rounded.GraphicEq, "아직 짚을 발음이 없어요", body = "말로 답하면 R/L, F/P처럼 헷갈리기 쉬운 소리를 찾아 드려요.")
            return@Panel
        }
        val max = (tips.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)
        tips.take(5).forEach { t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.type.ko, style = MaterialTheme.typography.titleMedium, color = Y.Text1, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(10.dp))
                        Text("${t.count}번", style = MaterialTheme.typography.labelLarge, color = Y.Retry)
                    }
                    Spacer(Modifier.height(6.dp))
                    Meter(t.count.toFloat() / max, color = Y.Retry, height = 8.dp)
                    if (t.tipKo.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(t.tipKo, style = MaterialTheme.typography.bodySmall, color = Y.Text3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(14.dp))
                BigButton("연습", { a.result(coach.startPronDrill(t.type)) }, tone = Tone.SECONDARY)
            }
        }
    }
}

@Composable
private fun RecentSessions(sessions: List<SessionRecord>) {
    val list = sessions.sortedByDescending { it.startedMs }.take(8)
    Panel(Modifier.fillMaxWidth(), padding = 28.dp) {
        Text("최근 세션", style = MaterialTheme.typography.titleLarge, color = Y.Text1, fontWeight = FontWeight.Bold)
        if (list.isEmpty()) {
            EmptyNote(Icons.Rounded.Schedule, "아직 마친 세션이 없어요")
            return@Panel
        }
        Spacer(Modifier.height(10.dp))
        list.forEach { s ->
            val min = ((s.endedMs - s.startedMs) / 60_000L).toInt().coerceAtLeast(0)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(slotIcon(s.slot), Y.Text2, size = 52.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("${s.slot.ko} · ${s.track.ko} · ${s.mode.ko}", style = MaterialTheme.typography.titleMedium, color = Y.Text1, fontWeight = FontWeight.SemiBold)
                    Text("${dateTimeKo(s.startedMs)} · ${minutesKo(min)}", style = MaterialTheme.typography.bodySmall, color = Y.Text3)
                }
                if (s.interrupted > 0) {
                    Chip("운전 집중 ${s.interrupted}번", color = Y.LoadFocus)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    "${s.correct} / ${s.answered}",
                    style = MaterialTheme.typography.titleLarge,
                    color = Y.Text1,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(96.dp),
                )
                Spacer(Modifier.width(10.dp))
                Chip("+${s.xp} XP", color = Y.Accent)
            }
            HDivider()
        }
    }
}
