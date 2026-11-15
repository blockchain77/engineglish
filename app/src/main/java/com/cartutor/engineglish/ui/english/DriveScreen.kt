package com.cartutor.engineglish.ui.english

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.cartutor.engineglish.english.core.CoachPhase
import com.cartutor.engineglish.english.core.CommuteSlot
import com.cartutor.engineglish.english.core.EnglishCoach
import com.cartutor.engineglish.english.core.EnglishUiState
import com.cartutor.engineglish.english.core.EveningStudy
import com.cartutor.engineglish.english.core.FeedbackUi
import com.cartutor.engineglish.english.core.LearnerCommand
import com.cartutor.engineglish.english.core.ListeningUi
import com.cartutor.engineglish.english.core.LoadAssessment
import com.cartutor.engineglish.english.core.LoadLevel
import com.cartutor.engineglish.english.core.MicState
import com.cartutor.engineglish.english.core.PromptUi
import com.cartutor.engineglish.english.core.ScreenMode
import com.cartutor.engineglish.english.core.SessionOrigin
import com.cartutor.engineglish.english.core.SessionRequest
import com.cartutor.engineglish.english.core.SessionSummaryUi
import com.cartutor.engineglish.english.core.SessionUi
import com.cartutor.engineglish.english.core.SignalOrigin
import com.cartutor.engineglish.english.core.Verdict
import com.cartutor.engineglish.ui.theme.Y
import com.cartutor.engineglish.ui.theme.YType
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/*
 * 주행 학습 화면 — 주행 중 유일하게 조작할 수 있는 화면.
 * 규칙: 문제 글자 34sp 이상 · 버튼 4개 이하(76dp 이상) · 스크롤·목록 없음 · 주행 중 영어 문장은 화면에 쓰지 않음
 * (귀로 듣고 HUD 에 힌트 한 단어) · 부분 인식 글자는 정차 중에만 · 주행 중 장식 애니메이션 없음.
 */

/** 가운데 영역 종류 */
private enum class CenterKind { HOLD, PAUSED, SUMMARY, CONFIRM, BRIEFING, QUESTION, WAITING, IDLE }

private data class CenterKey(val kind: CenterKind, val itemId: String? = null)

private val QUESTION_PHASES = setOf(
    CoachPhase.PROMPTING, CoachPhase.LISTENING, CoachPhase.JUDGING, CoachPhase.FEEDBACK,
)

private fun centerKeyOf(s: EnglishUiState): CenterKey = when {
    s.phase == CoachPhase.HOLD -> CenterKey(CenterKind.HOLD)
    s.phase == CoachPhase.PAUSED -> CenterKey(CenterKind.PAUSED)
    s.phase == CoachPhase.SUMMARY && s.summary != null -> CenterKey(CenterKind.SUMMARY)
    s.phase == CoachPhase.CONFIRM -> CenterKey(CenterKind.CONFIRM)
    s.phase == CoachPhase.BRIEFING -> CenterKey(CenterKind.BRIEFING)
    s.phase in QUESTION_PHASES && s.prompt != null -> CenterKey(CenterKind.QUESTION, s.prompt.itemId)
    s.session != null -> CenterKey(CenterKind.WAITING)
    else -> CenterKey(CenterKind.IDLE)
}

/**
 * @param onNotice 짧은 안내 (세션을 시작할 수 없을 때 등)
 */
@Composable
internal fun DriveScreen(
    state: EnglishUiState,
    coach: EnglishCoach,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val driving = state.screenMode == ScreenMode.DRIVE
    BoxWithConstraints(modifier.fillMaxSize().background(Y.Bg0)) {
        val compact = maxWidth < 900.dp || maxHeight < 620.dp
        val k = when {
            maxWidth >= 1800.dp && maxHeight >= 900.dp -> 1.25f
            maxWidth >= 1100.dp -> 1f
            else -> 0.85f
        }
        val hudHeight = (maxHeight * 0.2f).coerceIn(110.dp, 230.dp)
        val pad = if (compact) 18.dp else 30.dp
        Column(
            Modifier.fillMaxSize().padding(horizontal = pad, vertical = if (compact) 14.dp else 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DriveTopRow(state, compact)
            Spacer(Modifier.height(14.dp))
            WindshieldHud(
                hud = state.hud,
                speedKph = state.drive.speedKph,
                modifier = Modifier.widthIn(max = 1700.dp).fillMaxWidth().height(hudHeight),
                showLabel = !compact,
            )
            Spacer(Modifier.height(16.dp))
            Box(Modifier.weight(1f).widthIn(max = 1600.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                DriveCenter(state, driving, k)
            }
            Spacer(Modifier.height(14.dp))
            DriveControls(
                state, coach, driving, onNotice,
                Modifier.widthIn(max = 1500.dp).fillMaxWidth(),
                buttonHeight = if (compact) 92.dp else (104 * k).coerceAtMost(124f).dp,
            )
            if (state.drive.routeStrip.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                RouteStrip(state.drive.routeStrip, Modifier.widthIn(max = 1600.dp).fillMaxWidth(), showAllNames = !driving && !compact)
            }
        }
    }
}

// ───────────────────────── 위 줄 ─────────────────────────

@Composable
private fun DriveTopRow(state: EnglishUiState, compact: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // 부하 알약은 남는 폭 안에서만 (이유가 길어도 오른쪽 정보가 밀려나지 않게)
        Box(Modifier.weight(1f)) {
            LoadChip(state.load, maxReasons = if (compact) 1 else 2, large = !compact)
        }
        Spacer(Modifier.width(12.dp))
        val d = state.drive
        if (d.origin == SignalOrigin.SIMULATOR && !compact) {
            Chip("시뮬레이터 주행", color = Y.Violet)
            Spacer(Modifier.width(12.dp))
        }
        val slotText = buildString {
            d.slot?.let { append(it.ko) }
            d.routeRemainingMin?.let {
                if (isNotEmpty()) append(" · ")
                append("도착까지 ${minutesKo(it)}")
            }
        }
        if (slotText.isNotEmpty()) {
            Chip(slotText, color = Y.Text1, bg = Y.Bg3, icon = slotIcon(d.slot), large = !compact)
            Spacer(Modifier.width(12.dp))
        }
        state.session?.let { SessionProgress(it, compact) }
    }
}

/** 부하 단계 알약 — 색 + 단계 이름 + 이유 (최대 2개) */
@Composable
internal fun LoadChip(load: LoadAssessment, modifier: Modifier = Modifier, maxReasons: Int = 2, large: Boolean = false) {
    val c = loadColor(load.level)
    Row(
        modifier
            .clip(CircleShape)
            .background(c.copy(alpha = 0.14f))
            .border(1.5.dp, c.copy(alpha = 0.6f), CircleShape)
            .padding(horizontal = if (large) 22.dp else 16.dp, vertical = if (large) 11.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(loadIcon(load.level), contentDescription = null, tint = c, modifier = Modifier.size(if (large) 30.dp else 24.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            load.level.ko,
            style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            color = c,
            fontWeight = FontWeight.Bold,
        )
        val reasons = load.reasons.take(maxReasons)
        if (reasons.isNotEmpty()) {
            Text(
                "  ·  " + reasons.joinToString("  ·  ") { it.textKo },
                style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                color = Y.Text1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SessionProgress(s: SessionUi, compact: Boolean) {
    val total = s.total.coerceAtLeast(1)
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProgressRing(
            progress = s.answered.toFloat() / total,
            modifier = Modifier.size(if (compact) 48.dp else 60.dp),
            stroke = 7.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "${s.index.coerceAtMost(total)} / $total",
                style = YType.MetricSmall.copy(fontSize = if (compact) 22.sp else 26.sp),
                color = Y.Text1,
            )
            if (!compact) Text("정답 ${s.correct}", style = MaterialTheme.typography.labelLarge, color = Y.Text3)
        }
    }
}

// ───────────────────────── 가운데 ─────────────────────────

@Composable
private fun DriveCenter(state: EnglishUiState, driving: Boolean, k: Float) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 가운데 영역이 낮으면 아이콘·제목도 함께 줄인다 (글자는 34sp 아래로 줄이지 않는다)
        val kk = minOf(k, (maxHeight / 440.dp).coerceIn(0.6f, 1.3f))
        val key = centerKeyOf(state)
        AnimatedContent(
            targetState = key,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "drive-center",
            modifier = Modifier.fillMaxSize(),
        ) { shown ->
            // 사라지는 화면은 마지막으로 보던 상태로 그린다 (다음 문제 글자가 겹쳐 보이지 않게)
            val s = rememberLastWhileCurrent(shown == key, state)
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (shown.kind) {
                    CenterKind.HOLD -> HoldView(s.load, kk)
                    CenterKind.PAUSED -> PausedView(kk)
                    CenterKind.SUMMARY -> s.summary?.let { DriveSummaryView(it, driving, kk) }
                    CenterKind.CONFIRM -> ConfirmView(s.prompt, kk)
                    CenterKind.BRIEFING -> BriefingView(s.session, kk)
                    CenterKind.QUESTION -> s.prompt?.let { QuestionView(s, it, driving, kk) }
                    CenterKind.WAITING -> WaitingView(s, kk)
                    CenterKind.IDLE -> IdleView(s, driving, kk)
                }
            }
        }
    }
}

/** 주행 화면 제목 크기 — 34sp 아래로는 줄이지 않는다 */
private fun bigSp(base: Float, k: Float): Float = maxOf(YType.DRIVE_PROMPT_MIN_SP.toFloat(), base * k)

/** AnimatedContent 안에서: 지금 보이는 키는 최신 값, 사라지는 키는 마지막으로 본 값 */
@Composable
internal fun <T> rememberLastWhileCurrent(isCurrent: Boolean, value: T): T {
    val holder = remember { arrayOfNulls<Any?>(1).also { it[0] = value } }
    if (isCurrent) holder[0] = value
    @Suppress("UNCHECKED_CAST")
    return holder[0] as T
}

/** 34sp 아래로는 줄이지 않는 자동 맞춤 글자 */
@Composable
internal fun FitText(
    text: String,
    maxSp: Float,
    modifier: Modifier = Modifier,
    minSp: Float = YType.DRIVE_PROMPT_MIN_SP.toFloat(),
    color: Color = Y.Text1,
    weight: FontWeight = FontWeight.Bold,
    maxLines: Int = 3,
    align: TextAlign = TextAlign.Center,
) {
    val hi = maxOf(maxSp, minSp)
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontWeight = weight, textAlign = align, lineHeight = 1.2.em, fontSize = hi.sp),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = minSp.sp, maxFontSize = hi.sp, stepSize = 2.sp),
    )
}

@Composable
private fun ColumnScope.QuestionView(s: EnglishUiState, p: PromptUi, driving: Boolean, k: Float) {
    ChipFlow(center = true) {
        val kc = kindColor(p.kind)
        Chip(p.titleKo, color = kc, icon = skillIcon(p.skill), large = true)
        if (p.attempt >= 2) Chip("한 번 더", color = Y.Retry, large = true)
    }
    Spacer(Modifier.height((18 * k).dp))
    // 문제 글자는 남는 높이 안에서 가장 크게 (34sp 아래로는 줄이지 않음)
    FitText(p.displayKo, maxSp = 54f * k, modifier = Modifier.fillMaxWidth().weight(1f, fill = false))
    val en = p.displayEn
    if (en != null && !driving) {
        Spacer(Modifier.height(12.dp))
        FitText(
            en, maxSp = 40f * k, minSp = 28f, color = Y.Accent, weight = FontWeight.SemiBold, maxLines = 2,
            modifier = Modifier.fillMaxWidth().weight(0.6f, fill = false),
        )
    }
    Spacer(Modifier.height((22 * k).dp))
    val fb = s.feedback
    when {
        s.phase == CoachPhase.FEEDBACK && fb != null -> FeedbackBadge(fb, showModel = !driving, k = k)
        s.phase == CoachPhase.LISTENING -> ListeningIndicator(s.listening, animate = !driving, showPartial = !driving, k = k)
        s.phase == CoachPhase.JUDGING -> StatusLine(Icons.Rounded.Check, "확인하고 있어요", Y.Text2)
        else -> StatusLine(Icons.AutoMirrored.Rounded.VolumeUp, "잘 듣고 영어로 답해 보세요", Y.Text2)
    }
}

@Composable
private fun StatusLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.headlineSmall, color = color)
    }
}

/** 듣는 중 — 마이크 고리 (정차 중에만 맥박·남은 시간 · 부분 인식 글자) */
@Composable
internal fun ListeningIndicator(l: ListeningUi, animate: Boolean, showPartial: Boolean, k: Float) {
    val ringSize = (112 * k).dp
    val active = l.mic == MicState.LISTENING
    val color = if (active) Y.Accent else Y.Text2
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(ringSize), contentAlignment = Alignment.Center) {
            if (animate && active) {
                val t = rememberInfiniteTransition(label = "mic")
                val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "mic-pulse")
                Canvas(Modifier.fillMaxSize()) {
                    val base = size.minDimension / 2f * 0.62f
                    for (i in 0..1) {
                        val q = (p + i * 0.5f) % 1f
                        drawCircle(color.copy(alpha = (1f - q) * 0.4f), radius = base * (1f + q * 0.6f), style = Stroke(3.dp.toPx()))
                    }
                }
            }
            DeadlineRing(l.deadlineMs, animate, color, Modifier.fillMaxSize().padding(ringSize * 0.12f))
            Box(
                Modifier.size(ringSize * 0.56f).clip(CircleShape).background(color.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Mic, contentDescription = "듣는 중", tint = color, modifier = Modifier.size(ringSize * 0.32f))
            }
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.widthIn(max = 900.dp)) {
            Text(
                when (l.mic) {
                    MicState.PREPARING -> "마이크를 준비하고 있어요"
                    MicState.PROCESSING -> "확인하고 있어요"
                    else -> "말해 보세요"
                },
                style = MaterialTheme.typography.headlineMedium,
                color = Y.Text1,
                fontWeight = FontWeight.Bold,
            )
            if (showPartial && l.partial.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("“${l.partial}”", style = MaterialTheme.typography.titleLarge, color = Y.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 듣기 남은 시간 고리 — 주행 중에는 움직이지 않는 고리만 */
@Composable
private fun DeadlineRing(deadlineMs: Long?, animate: Boolean, color: Color, modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val start = remember(deadlineMs) { System.currentTimeMillis() }
    LaunchedEffect(deadlineMs, animate) {
        if (deadlineMs != null && animate) {
            while (true) {
                now = System.currentTimeMillis()
                if (now >= deadlineMs) break
                delay(100)
            }
        }
    }
    val frac = if (animate && deadlineMs != null && deadlineMs > start) {
        ((deadlineMs - now).toFloat() / (deadlineMs - start)).coerceIn(0f, 1f)
    } else {
        1f
    }
    Canvas(modifier) {
        val s = 5.dp.toPx()
        val d = size.minDimension - s
        val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
        drawArc(color.copy(alpha = 0.18f), -90f, 360f, false, tl, Size(d, d), style = Stroke(s))
        drawArc(color, -90f, 360f * frac, false, tl, Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
    }
}

/** 채점 결과 — ✓ / ↻ 글리프 + 한 줄 (정차 중에만 모범 답안 글자) */
@Composable
private fun FeedbackBadge(f: FeedbackUi, showModel: Boolean, k: Float) {
    val c = verdictColor(f.verdict)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size((96 * k).dp).clip(CircleShape).background(c.copy(alpha = 0.16f)).border(2.dp, c, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(verdictIcon(f.verdict), contentDescription = f.verdict.ko, tint = c, modifier = Modifier.size((56 * k).dp))
        }
        Spacer(Modifier.width(22.dp))
        Column(Modifier.widthIn(max = 1000.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.headlineKo, fontSize = bigSp(40f, k).sp, color = c, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (f.xp > 0) {
                    Spacer(Modifier.width(14.dp))
                    Chip("+${f.xp} XP", color = Y.Accent, large = true)
                }
            }
            if (showModel && f.verdict != Verdict.CORRECT && f.modelAnswer.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(f.modelAnswer, style = YType.English.copy(fontSize = 30.sp), color = Y.Text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 운전 집중 — 문제 글자 없이 이유만, 차분하게 */
@Composable
private fun ColumnScope.HoldView(load: LoadAssessment, k: Float) {
    val c = loadColor(load.level)
    IconBadge(loadIcon(load.level), c, size = (104 * k).dp)
    Spacer(Modifier.height((22 * k).dp))
    Text("운전에 집중하세요", fontSize = bigSp(50f, k).sp, fontWeight = FontWeight.Bold, color = Y.Text1, textAlign = TextAlign.Center)
    Spacer(Modifier.height((10 * k).dp))
    Text("여유가 생기면 이어서 할게요", style = MaterialTheme.typography.titleLarge, color = Y.Text2, textAlign = TextAlign.Center)
    val reasons = load.reasons.take(3)
    if (reasons.isNotEmpty()) {
        Spacer(Modifier.height((22 * k).dp))
        ChipFlow(center = true, spacing = 12.dp) {
            reasons.forEach { r -> Chip(r.textKo, color = loadColor(r.code.level), large = true) }
        }
    }
}

@Composable
private fun ColumnScope.PausedView(k: Float) {
    IconBadge(Icons.Rounded.Pause, Y.Text2, size = (104 * k).dp)
    Spacer(Modifier.height((22 * k).dp))
    Text("잠깐 멈췄어요", fontSize = bigSp(48f, k).sp, fontWeight = FontWeight.Bold, color = Y.Text1)
    Spacer(Modifier.height((10 * k).dp))
    Text("‘계속’이라고 말하거나 계속 버튼을 누르세요", style = MaterialTheme.typography.titleLarge, color = Y.Text2, textAlign = TextAlign.Center)
}

@Composable
private fun ColumnScope.ConfirmView(p: PromptUi?, k: Float) {
    IconBadge(Icons.Rounded.QuestionAnswer, Y.Accent, size = (96 * k).dp)
    Spacer(Modifier.height((20 * k).dp))
    FitText(p?.displayKo ?: "계속할까요?", maxSp = 50f * k, modifier = Modifier.fillMaxWidth().weight(1f, fill = false))
    Spacer(Modifier.height((12 * k).dp))
    Text("‘네’ 또는 ‘아니요’로 답해 주세요", style = MaterialTheme.typography.titleLarge, color = Y.Text2)
}

@Composable
private fun ColumnScope.BriefingView(session: SessionUi?, k: Float) {
    val title = session?.titleKo ?: "세션을 준비하고 있어요"
    val (head, parts) = splitPlanTitle(title)
    Eyebrow("오늘의 세션", color = Y.Accent)
    Spacer(Modifier.height((14 * k).dp))
    FitText(head, maxSp = 60f * k, maxLines = 2, modifier = Modifier.fillMaxWidth().weight(1f, fill = false))
    if (parts.isNotEmpty()) {
        Spacer(Modifier.height((18 * k).dp))
        ChipFlow(center = true, spacing = 12.dp) {
            parts.forEach { Chip(it, color = Y.Text1, bg = Y.Bg3, large = true) }
        }
    }
    Spacer(Modifier.height((18 * k).dp))
    Text("운전이 바빠지면 알아서 쉬었다가 이어서 해요", style = MaterialTheme.typography.titleMedium, color = Y.Text2)
}

/** 세션 중이지만 지금 부하에서 낼 문제가 없을 때 — 조용히 기다림 */
@Composable
private fun ColumnScope.WaitingView(s: EnglishUiState, k: Float) {
    IconBadge(loadIcon(s.load.level), loadColor(s.load.level), size = (88 * k).dp)
    Spacer(Modifier.height((20 * k).dp))
    Text("잠시 쉬어 갈게요", fontSize = bigSp(42f, k).sp, fontWeight = FontWeight.Bold, color = Y.Text1)
    Spacer(Modifier.height((10 * k).dp))
    Text("지금 길에 맞는 문제가 나오면 바로 이어서 해요", style = MaterialTheme.typography.titleLarge, color = Y.Text2, textAlign = TextAlign.Center)
}

/** 학습을 마친 뒤 이만큼은 '다음 세션' 대신 '주차하면 결과를 보여 드려요'를 보여 준다 */
private const val FINISHED_NOTE_MS = 15 * 60_000L

@Composable
private fun ColumnScope.IdleView(s: EnglishUiState, driving: Boolean, k: Float) {
    // 도착 1분 전에 학습을 마친 뒤 (아직 달리는 중) — 다음 세션 안내 대신 차분한 마무리
    val endedAt = s.dashboard?.endedAtMs
    if (driving && endedAt != null && System.currentTimeMillis() - endedAt in 0L..FINISHED_NOTE_MS) {
        IconBadge(Icons.Rounded.Flag, Y.Correct, size = (88 * k).dp)
        Spacer(Modifier.height((20 * k).dp))
        Text("오늘 학습은 여기까지예요", fontSize = bigSp(44f, k).sp, fontWeight = FontWeight.Bold, color = Y.Text1, textAlign = TextAlign.Center)
        Spacer(Modifier.height((10 * k).dp))
        Text("주차하면 오늘의 결과를 보여 드릴게요", style = MaterialTheme.typography.titleLarge, color = Y.Text2, textAlign = TextAlign.Center)
        return
    }
    val preview = s.nextPlanPreviewKo
    val (head, parts) = splitPlanTitle(preview ?: "오늘의 세션")
    Eyebrow(if (driving) "주행 학습 준비 완료" else "출발 전", color = Y.Accent)
    Spacer(Modifier.height((14 * k).dp))
    FitText(head, maxSp = 58f * k, maxLines = 2, modifier = Modifier.fillMaxWidth().weight(1f, fill = false))
    if (parts.isNotEmpty()) {
        Spacer(Modifier.height((16 * k).dp))
        ChipFlow(center = true, spacing = 12.dp) {
            parts.forEach { Chip(it, color = Y.Text1, bg = Y.Bg3, large = true) }
        }
    }
    Spacer(Modifier.height((18 * k).dp))
    Text(
        when {
            // 퇴근길은 설정 '퇴근길 학습'을 따른다 (기본: 먼저 물어보기)
            s.drive.slot == CommuteSlot.EVENING -> when (s.settings.eveningStudy) {
                EveningStudy.ASK -> "퇴근길에는 먼저 물어보고 시작해요"
                EveningStudy.ALWAYS -> "퇴근길이면 곧 알아서 시작해요"
                EveningStudy.NEVER -> "‘지금 시작’을 누르면 바로 시작해요"
            }
            s.settings.autoStartOnCommute && driving -> "출근길이면 곧 알아서 시작해요"
            s.settings.autoStartOnCommute -> "출근길에 출발하면 알아서 시작해요. 지금 바로 시작해도 돼요"
            else -> "‘지금 시작’을 누르면 바로 시작해요"
        },
        style = MaterialTheme.typography.titleLarge,
        color = Y.Text2,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ColumnScope.DriveSummaryView(sum: SessionSummaryUi, driving: Boolean, k: Float) {
    IconBadge(Icons.Rounded.Flag, Y.Correct, size = (96 * k).dp)
    Spacer(Modifier.height((20 * k).dp))
    Text(sum.titleKo, fontSize = bigSp(46f, k).sp, fontWeight = FontWeight.Bold, color = Y.Text1, textAlign = TextAlign.Center)
    Spacer(Modifier.height((20 * k).dp))
    ChipFlow(center = true, spacing = 16.dp) {
        SummaryNumber("푼 문장", "${sum.answered}")
        SummaryNumber("정답", "${sum.correct}", Y.Correct)
        SummaryNumber("XP", "+${sum.xpGained}", Y.Accent)
        if (sum.interrupted > 0) SummaryNumber("운전 집중", "${sum.interrupted}번", Y.LoadFocus)
    }
    val best = sum.bestSentence
    if (!driving && best != null) {
        Spacer(Modifier.height((18 * k).dp))
        Text("오늘의 한 문장", style = YType.Eyebrow, color = Y.Text3)
        Spacer(Modifier.height(6.dp))
        Text(best, style = YType.English, color = Y.Accent, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SummaryNumber(label: String, value: String, color: Color = Y.Text1) {
    Column(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(22.dp)).background(Y.Bg2).padding(horizontal = 26.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = YType.Metric, color = color)
        Text(label, style = MaterialTheme.typography.labelLarge, color = Y.Text3)
    }
}

// ───────────────────────── 버튼 (최대 4개) ─────────────────────────

@Composable
private fun DriveControls(
    state: EnglishUiState,
    coach: EnglishCoach,
    driving: Boolean,
    onNotice: (String) -> Unit,
    modifier: Modifier,
    buttonHeight: Dp,
) {
    val phase = state.phase
    val session = state.session
    val prompt = state.prompt
    Box(modifier.heightIn(min = buttonHeight), contentAlignment = Alignment.Center) {
        when {
            // 운전 집중 중에는 버튼도 숨겨 차분하게 (핸들 버튼은 그대로 동작). 자리는 그대로 두어 화면이 출렁이지 않게
            phase == CoachPhase.HOLD -> Spacer(Modifier.height(buttonHeight))

            phase == CoachPhase.CONFIRM -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DriveButton("네", Icons.Rounded.Check, { coach.onCommand(LearnerCommand.YES) }, Modifier.weight(1f), highlight = true, height = buttonHeight)
                DriveButton("아니요", Icons.Rounded.Close, { coach.onCommand(LearnerCommand.NO) }, Modifier.weight(1f), height = buttonHeight)
            }

            phase == CoachPhase.SUMMARY -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                DriveButton("확인", Icons.Rounded.Check, { coach.dismissSummary() }, Modifier.widthIn(min = 320.dp), highlight = true, height = buttonHeight)
            }

            session == null || !phase.inSession -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                DriveButton(
                    "지금 시작",
                    Icons.Rounded.PlayArrow,
                    {
                        val r = coach.startSession(SessionRequest(origin = SessionOrigin.USER))
                        if (!r.ok) onNotice(r.messageKo)
                    },
                    Modifier.widthIn(min = 360.dp),
                    highlight = true,
                    height = buttonHeight,
                )
            }

            else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val hasItem = prompt != null
                val paused = phase == CoachPhase.PAUSED
                DriveButton("다시", Icons.Rounded.Replay, { coach.onCommand(LearnerCommand.REPEAT) }, Modifier.weight(1f), enabled = hasItem && !paused, height = buttonHeight)
                DriveButton(
                    "힌트", Icons.Rounded.Lightbulb, { coach.onCommand(LearnerCommand.HINT) }, Modifier.weight(1f),
                    enabled = prompt != null && !paused && !prompt.hintShown, height = buttonHeight,
                )
                DriveButton("넘기기", Icons.Rounded.SkipNext, { coach.onCommand(LearnerCommand.SKIP) }, Modifier.weight(1f), enabled = hasItem && !paused, height = buttonHeight)
                if (paused) {
                    DriveButton("계속", Icons.Rounded.PlayArrow, { coach.resume() }, Modifier.weight(1f), highlight = true, height = buttonHeight)
                } else {
                    DriveButton("일시정지", Icons.Rounded.Pause, { coach.pause() }, Modifier.weight(1f), height = buttonHeight)
                }
                // 정차 중에만 '끝내기' (주행 중 버튼은 4개까지)
                if (!driving) {
                    DriveButton("끝내기", Icons.Rounded.Flag, { coach.stopSession() }, Modifier.weight(1f), height = buttonHeight)
                }
            }
        }
    }
}

/** 부하 단계 색 점 + 이름 (작은 표시용) */
@Composable
internal fun LoadDot(level: LoadLevel, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Dot(loadColor(level), size = 12.dp)
        Spacer(Modifier.width(8.dp))
        Text(level.ko, style = MaterialTheme.typography.labelLarge, color = loadColor(level))
    }
}

/** 소수 % (0..1 → "84%") — 진행률 반올림 */
internal fun ratioKo(num: Int, den: Int): String = if (den <= 0) "-" else "${(num * 100f / den).roundToInt()}%"
