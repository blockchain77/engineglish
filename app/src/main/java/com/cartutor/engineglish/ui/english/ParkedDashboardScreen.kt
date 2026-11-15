package com.cartutor.engineglish.ui.english

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cartutor.engineglish.ui.theme.Y
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/*
 * 주차 대시보드 — 주행 학습을 마치고 P 단에 넣으면 전체 화면으로 뜨는 '오늘의 학습 결과'.
 *
 *   위: 부릉이(캐릭터) 축하 애니메이션 + 큰 인사말 + 얻은 XP · 연속 학습
 *   가운데: 학습 결과 타일 6개 (학습 시간 · 푼 문장 · 정확도 · 평균 응답 · 새 표현 · 복습)
 *   아래: 진행률 (오늘 목표 링 · 레벨 막대 · 유닛 막대 — 세션 전 → 후로 차오른다) + 발음 한 가지 + 틀린 표현
 *   버튼: 틀린 표현 복습 · 홈으로
 *
 * 정차(P단) 전용 화면이라 숫자 올라가기 · 색종이 같은 움직임을 쓴다. 주행 중에는 띄우지 않는다.
 * 화면 상태(코치)와 떨어져 있도록 입력은 [DashboardUi] 하나뿐이다 (코치 상태 → DashboardUi 는 DashboardBinding.kt).
 * 기준 크기 1130 × 800dp 에 맞춰 그리고, 창 크기에 따라 통째로 늘리거나 줄인다.
 */

/**
 * 대시보드에 그릴 값.
 * @param avgLatencyMs 평균 응답 시간 (말로 답한 문항이 없으면 null → "-")
 * @param levelLabel "비즈니스 · 중급"
 * @param levelProgressBefore 레벨 업까지 진행률 0..1 (세션 전) → [levelProgressAfter] 까지 막대가 차오른다
 * @param unitLabel 공부하던 유닛 이름 (없으면 빈 문자열)
 * @param weakPointTitle 발음 약점 ("R / L 구분"), 없으면 null
 * @param weakPointExample "‘right’가 ‘light’로 들렸어요"
 * @param mistakes 틀린 표현 (영어, 한국어 뜻)
 * @param key 세션 id — 바뀌면 애니메이션을 처음부터 다시 재생
 * @param leveledUp 이번 세션에서 레벨 업 (막대를 끝까지 채운 뒤 새 레벨 진행률로)
 * @param badges 이번 세션에 생긴 일 ("유닛 3 통과", "5일 연속 학습" …) 최대 2개만 보인다
 */
internal data class DashboardUi(
    val minutes: Int,
    val answered: Int,
    val accuracyPct: Int,
    val avgLatencyMs: Long?,
    val newCount: Int,
    val reviewCount: Int,
    val xpGained: Int,
    val xpTotal: Int,
    val streakDays: Int,
    val goalDone: Int,
    val goalTarget: Int,
    val levelLabel: String,
    val levelProgressBefore: Float,
    val levelProgressAfter: Float,
    val unitLabel: String,
    val weakPointTitle: String?,
    val weakPointExample: String?,
    val mistakes: List<Pair<String, String>>,
    val key: String = "",
    val headline: String = "오늘도 수고했어요!",
    val subtitle: String? = null,
    val goalBefore: Int = 0,
    val leveledUp: Boolean = false,
    val levelGoalLabel: String = "",
    val unitProgressBefore: Float = 0f,
    val unitProgressAfter: Float = 0f,
    val unitPassed: Boolean = false,
    val nextUnitLabel: String? = null,
    val weakPointTip: String? = null,
    val badges: List<String> = emptyList(),
)

/**
 * @param celebrate true = 방금 끝난 세션 (부릉이가 뛰고 색종이가 내린다), false = '지난 학습 결과' 다시 보기 (차분하게)
 */
@Composable
internal fun ParkedDashboardScreen(
    ui: DashboardUi,
    onReviewMistakes: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
    celebrate: Boolean = true,
) {
    // 0 → 1: 숫자가 올라가고 막대·링이 차오르는 진행 (한 번만)
    val reveal = remember(ui.key) { Animatable(0f) }
    LaunchedEffect(ui.key) {
        reveal.snapTo(0f)
        delay(if (celebrate) 700L else 200L)
        reveal.animateTo(1f, tween(1500, easing = FastOutSlowInEasing))
    }
    val progress: () -> Float = { reveal.value }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Y.Bg0)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(Y.Accent.copy(alpha = 0.14f), Color.Transparent),
                        center = Offset(size.width * 0.13f, size.height * 0.17f),
                        radius = size.height * 0.75f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(Y.Violet.copy(alpha = 0.08f), Color.Transparent),
                        center = Offset(size.width * 0.96f, size.height),
                        radius = size.height * 0.65f,
                    ),
                )
            }
            // 아래 화면으로 터치가 새지 않게
            .clickable(interactionSource = null, indication = null) {},
    ) {
        val k = minOf(maxWidth / 1130.dp, maxHeight / 800.dp).coerceIn(0.62f, 1.7f)
        val d = remember(k) { DashScale(k) }
        // 기준보다 세로가 넉넉하면 한 줄씩 더 보여 준다
        val roomy = maxHeight >= 880.dp * k
        Column(Modifier.fillMaxSize().padding(horizontal = d.dp(36), vertical = d.dp(22))) {
            Hero(ui, d, celebrate, progress)
            Spacer(Modifier.height(d.dp(12)))
            ResultTiles(ui, d, progress)
            Spacer(Modifier.height(d.dp(12)))
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.dp(14))) {
                ProgressPanel(ui, d, progress, Modifier.weight(1.25f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(d.dp(12))) {
                    WeakPointPanel(ui, d, roomy, Modifier.weight(0.8f).fillMaxWidth())
                    MistakesPanel(ui, d, if (roomy) 3 else 2, Modifier.weight(1f).fillMaxWidth())
                }
            }
            Spacer(Modifier.height(d.dp(14)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.dp(14))) {
                DashButton(
                    d, "틀린 표현 복습", Icons.Rounded.Replay, onReviewMistakes, Modifier.weight(1f),
                    primary = false, enabled = ui.mistakes.isNotEmpty(),
                )
                DashButton(d, "홈으로", Icons.Rounded.Home, onHome, Modifier.weight(1f), primary = true)
            }
        }
        // 화면 전체에 내리는 색종이 (약 3.6초, 터치는 받지 않는다)
        ConfettiOverlay(Modifier.fillMaxSize(), playKey = ui.key, enabled = celebrate)
    }
}

/** 기준 크기(1130 × 800dp) 대비 배율 */
private class DashScale(val k: Float) {
    fun dp(v: Int): Dp = (v * k).dp
    fun sp(v: Int): TextUnit = (v * k).sp
}

private val Tnum = TextStyle(fontFeatureSettings = "tnum")

/** 대시보드 버튼 — 화면 배율에 맞춰 글자·아이콘도 함께 커진다 (높이는 늘 76dp 이상) */
@Composable
private fun DashButton(
    d: DashScale,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(d.dp(24))
    val bg = when {
        !enabled -> Y.Bg2
        primary -> Y.Accent
        else -> Y.Bg3
    }
    val fg = when {
        !enabled -> Y.Text3
        primary -> Y.OnAccent
        else -> Y.Text1
    }
    Row(
        modifier
            .height(d.dp(80).coerceAtLeast(TouchMin))
            .clip(shape)
            .background(bg)
            .border(1.dp, if (primary && enabled) Y.Accent else Y.Stroke, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = d.dp(24)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(d.dp(32)))
        Spacer(Modifier.width(d.dp(12)))
        Text(label, fontSize = d.sp(26), fontWeight = FontWeight.Bold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ───────────────────────── 위: 캐릭터 + 인사말 ─────────────────────────

@Composable
private fun Hero(ui: DashboardUi, d: DashScale, celebrate: Boolean, progress: () -> Float) {
    Row(Modifier.fillMaxWidth().height(d.dp(214)), verticalAlignment = Alignment.CenterVertically) {
        Mascot(
            Modifier.size(d.dp(214)),
            mood = if (celebrate) MascotMood.CELEBRATE else MascotMood.IDLE,
            playKey = ui.key,
        )
        Spacer(Modifier.width(d.dp(22)))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.LocalParking, contentDescription = null, tint = Y.LoadParked, modifier = Modifier.size(d.dp(22)))
                Spacer(Modifier.width(d.dp(8)))
                Text(
                    "주차 완료 · 오늘의 학습 결과",
                    fontSize = d.sp(17), fontWeight = FontWeight.Bold, color = Y.LoadParked, letterSpacing = 1.sp, maxLines = 1,
                )
            }
            Spacer(Modifier.height(d.dp(6)))
            Text(
                ui.headline,
                fontSize = d.sp(54), lineHeight = d.sp(64), fontWeight = FontWeight.Bold, color = Y.Text1,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(d.dp(4)))
            Text(
                ui.subtitle ?: "${minutesKo(ui.minutes)} 동안 ${ui.answered}문장을 연습했어요",
                fontSize = d.sp(24), lineHeight = d.sp(32), color = Y.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(d.dp(14)))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.dp(12))) {
                XpPill(ui, d, progress)
                Pill(d, Icons.Rounded.LocalFireDepartment, "연속 ${ui.streakDays}일", Y.Retry)
                ui.badges.take(2).forEach { badge ->
                    Pill(d, Icons.Rounded.EmojiEvents, badge, Y.Correct, Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}

/** 얻은 XP — 숫자가 0 에서 올라간다 */
@Composable
private fun XpPill(ui: DashboardUi, d: DashScale, progress: () -> Float) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Y.AccentSoft)
            .border(1.5.dp, Y.Accent.copy(alpha = 0.7f), CircleShape)
            .padding(horizontal = d.dp(20), vertical = d.dp(9)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Star, contentDescription = null, tint = Y.Accent, modifier = Modifier.size(d.dp(30)))
        Spacer(Modifier.width(d.dp(8)))
        CountText(0, ui.xpGained, progress, d.sp(30), Y.Accent) { "+$it XP" }
        Spacer(Modifier.width(d.dp(12)))
        CountText(ui.xpTotal - ui.xpGained, ui.xpTotal, progress, d.sp(18), Y.Text2, FontWeight.Medium) { "누적 $it" }
    }
}

@Composable
private fun Pill(d: DashScale, icon: ImageVector, text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = d.dp(18), vertical = d.dp(11)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(d.dp(26)))
        Spacer(Modifier.width(d.dp(8)))
        Text(text, fontSize = d.sp(22), fontWeight = FontWeight.Bold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** from → to 로 올라가는 숫자. progress 를 여기서만 읽어 이 글자만 다시 그린다 */
@Composable
private fun CountText(
    from: Int,
    to: Int,
    progress: () -> Float,
    fontSize: TextUnit,
    color: Color,
    weight: FontWeight = FontWeight.Bold,
    format: (Int) -> String = { "$it" },
) {
    val v = from + ((to - from) * progress()).roundToInt()
    Text(format(v), fontSize = fontSize, fontWeight = weight, color = color, maxLines = 1, style = Tnum)
}

// ───────────────────────── 가운데: 학습 결과 타일 ─────────────────────────

@Composable
private fun ResultTiles(ui: DashboardUi, d: DashScale, progress: () -> Float) {
    Row(Modifier.fillMaxWidth().height(d.dp(104)), horizontalArrangement = Arrangement.spacedBy(d.dp(12))) {
        Tile(d, 0, progress, Icons.Rounded.Timer, "학습 시간", "분") {
            CountText(0, ui.minutes, progress, d.sp(38), Y.Text1)
        }
        Tile(d, 1, progress, Icons.Rounded.CheckCircle, "푼 문장", "개") {
            CountText(0, ui.answered, progress, d.sp(38), Y.Text1)
        }
        Tile(d, 2, progress, Icons.Rounded.EmojiEvents, "정확도", "%") {
            CountText(0, ui.accuracyPct, progress, d.sp(38), Y.Correct)
        }
        Tile(d, 3, progress, Icons.Rounded.Speed, "평균 응답", if (ui.avgLatencyMs != null) "초" else null) {
            val ms = ui.avgLatencyMs
            if (ms != null) {
                CountText(0, ms.toInt(), progress, d.sp(38), Y.Text1) { "%.1f".format(it / 1000f) }
            } else {
                Text("-", fontSize = d.sp(38), fontWeight = FontWeight.Bold, color = Y.Text3)
            }
        }
        Tile(d, 4, progress, Icons.Rounded.AutoAwesome, "새 표현", "개") {
            CountText(0, ui.newCount, progress, d.sp(38), Y.Accent)
        }
        Tile(d, 5, progress, Icons.Rounded.History, "복습", "개") {
            CountText(0, ui.reviewCount, progress, d.sp(38), Y.Text1)
        }
    }
}

/** 결과 타일 — 왼쪽부터 차례로 떠오른다 */
@Composable
private fun RowScope.Tile(
    d: DashScale,
    index: Int,
    progress: () -> Float,
    icon: ImageVector,
    label: String,
    unit: String?,
    value: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(d.dp(22))
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .graphicsLayer {
                val p = (progress() * 5f - index * 0.35f).coerceIn(0f, 1f)
                alpha = 0.25f + 0.75f * p
                translationY = (1f - p) * 14.dp.toPx()
            }
            .clip(shape)
            .background(Y.Bg2)
            .border(1.dp, Y.StrokeSoft, shape)
            .padding(horizontal = d.dp(16), vertical = d.dp(8)),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Y.Text3, modifier = Modifier.size(d.dp(20)))
            Spacer(Modifier.width(d.dp(6)))
            Text(label, fontSize = d.sp(17), fontWeight = FontWeight.SemiBold, color = Y.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(d.dp(4)))
        Row(verticalAlignment = Alignment.Bottom) {
            value()
            if (unit != null) {
                Spacer(Modifier.width(d.dp(4)))
                Text(unit, fontSize = d.sp(18), color = Y.Text3, modifier = Modifier.padding(bottom = d.dp(6)))
            }
        }
    }
}

// ───────────────────────── 아래: 진행률 ─────────────────────────

@Composable
private fun ProgressPanel(ui: DashboardUi, d: DashScale, progress: () -> Float, modifier: Modifier) {
    val reached = ui.goalTarget > 0 && ui.goalDone >= ui.goalTarget
    Panel(modifier, accent = Y.Accent, padding = d.dp(22)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("진행률", fontSize = d.sp(17), fontWeight = FontWeight.Bold, color = Y.Accent, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            if (reached) {
                Text("오늘 목표 달성!", fontSize = d.sp(18), fontWeight = FontWeight.Bold, color = Y.Correct)
            } else if (ui.goalTarget > 0) {
                Text(
                    "오늘 목표까지 ${ui.goalTarget - ui.goalDone}문장",
                    fontSize = d.sp(18), fontWeight = FontWeight.SemiBold, color = Y.Text2,
                )
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GoalRing(ui, d, progress, reached)
            Spacer(Modifier.width(d.dp(26)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(d.dp(18))) {
                val levelPct = percentKo(ui.levelProgressAfter)
                GainRow(
                    d = d,
                    title = ui.levelLabel.ifBlank { "레벨" },
                    trailing = when {
                        ui.leveledUp -> "레벨 업!"
                        ui.levelGoalLabel.isNotBlank() -> "${ui.levelGoalLabel} $levelPct"
                        else -> "레벨 업까지 $levelPct"
                    },
                    before = ui.levelProgressBefore,
                    after = ui.levelProgressAfter,
                    wrapped = ui.leveledUp,
                    color = Y.Periwinkle,
                    trailingColor = if (ui.leveledUp) Y.Accent else Y.Text2,
                    progress = progress,
                )
                if (ui.unitLabel.isNotBlank()) {
                    GainRow(
                        d = d,
                        title = ui.unitLabel,
                        trailing = if (ui.unitPassed) "유닛 통과!" else percentKo(ui.unitProgressAfter),
                        before = ui.unitProgressBefore,
                        after = ui.unitProgressAfter,
                        wrapped = false,
                        color = if (ui.unitPassed) Y.Correct else Y.Accent,
                        trailingColor = if (ui.unitPassed) Y.Correct else Y.Text2,
                        progress = progress,
                        caption = when {
                            ui.unitPassed && ui.nextUnitLabel != null -> "다음 유닛 · ${ui.nextUnitLabel}"
                            ui.unitPassed -> null
                            else -> "80% 넘게 맞히면 다음 유닛이 열려요"
                        },
                    )
                }
            }
        }
    }
}

/** 오늘 목표 링 — 세션 전 분량은 어둡게, 이번 세션에 채운 만큼이 밝게 차오른다 */
@Composable
private fun GoalRing(ui: DashboardUi, d: DashScale, progress: () -> Float, reached: Boolean) {
    val target = ui.goalTarget.coerceAtLeast(1)
    val before = (ui.goalBefore.toFloat() / target).coerceIn(0f, 1f)
    val after = (ui.goalDone.toFloat() / target).coerceIn(0f, 1f)
    val color = if (reached) Y.Correct else Y.Accent
    val stroke = d.dp(15)
    Box(Modifier.size(d.dp(172)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val dia = size.minDimension - s
            val tl = Offset((size.width - dia) / 2f, (size.height - dia) / 2f)
            val arc = Size(dia, dia)
            val head = before + (after - before) * progress()
            drawArc(Y.Bg4, -90f, 360f, false, tl, arc, style = Stroke(s))
            if (head > 0f) drawArc(color, -90f, 360f * head, false, tl, arc, style = Stroke(s, cap = StrokeCap.Round))
            if (before > 0f && before < 1f) {
                drawArc(Y.Bg0.copy(alpha = 0.5f), -90f, 360f * before, false, tl, arc, style = Stroke(s, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CountText(ui.goalBefore, ui.goalDone, progress, d.sp(46), Y.Text1)
            Text(
                if (ui.goalTarget > 0) "/ ${ui.goalTarget}문장" else "문장",
                fontSize = d.sp(17), fontWeight = FontWeight.SemiBold, color = Y.Text3, maxLines = 1,
            )
        }
    }
}

/** 제목 + 오른쪽 설명 + 차오르는 막대 */
@Composable
private fun GainRow(
    d: DashScale,
    title: String,
    trailing: String,
    before: Float,
    after: Float,
    wrapped: Boolean,
    color: Color,
    trailingColor: Color,
    progress: () -> Float,
    caption: String? = null,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                fontSize = d.sp(21), fontWeight = FontWeight.Bold, color = Y.Text1, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(d.dp(12)))
            // 오른쪽 설명은 짧게 — 제목이 밀려 잘리지 않도록 폭을 제한한다
            Text(
                trailing,
                fontSize = d.sp(18), fontWeight = FontWeight.SemiBold, color = trailingColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = d.dp(170)),
            )
        }
        Spacer(Modifier.height(d.dp(9)))
        GainMeter(before, after, wrapped, color, d.dp(16), progress)
        if (caption != null) {
            Spacer(Modifier.height(d.dp(6)))
            Text(caption, fontSize = d.sp(16), color = Y.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 세션 전(before) → 후(after) 로 차오르는 막대. 세션 전 분량은 어둡게 남긴다.
 * wrapped = 레벨 업: 먼저 끝까지 채운 뒤 0 에서 after 까지 다시 채운다.
 */
@Composable
private fun GainMeter(before: Float, after: Float, wrapped: Boolean, color: Color, height: Dp, progress: () -> Float) {
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val p = progress()
        val b = before.coerceIn(0f, 1f)
        val a = after.coerceIn(0f, 1f)
        val base: Float
        val head: Float
        if (wrapped) {
            if (p < 0.5f) {
                base = b
                head = b + (1f - b) * (p * 2f)
            } else {
                base = 0f
                head = a * ((p - 0.5f) * 2f)
            }
        } else {
            base = minOf(a, b)
            head = b + (a - b) * p
        }
        val r = CornerRadius(size.height / 2f)
        val track = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, r)) }
        clipPath(track) {
            drawRect(Y.Bg4)
            if (head > 0f) drawRoundRect(color, size = Size(size.width * head, size.height), cornerRadius = r)
            if (base > 0f && base < head) drawRect(Y.Bg0.copy(alpha = 0.5f), size = Size(size.width * base, size.height))
        }
    }
}

// ───────────────────────── 아래: 발음 한 가지 · 틀린 표현 ─────────────────────────

@Composable
private fun WeakPointPanel(ui: DashboardUi, d: DashScale, roomy: Boolean, modifier: Modifier) {
    Panel(modifier, padding = d.dp(16)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Y.Retry, modifier = Modifier.size(d.dp(24)))
            Spacer(Modifier.width(d.dp(8)))
            Text("발음 한 가지", fontSize = d.sp(17), fontWeight = FontWeight.Bold, color = Y.Retry, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(d.dp(8)))
        val title = ui.weakPointTitle
        if (title == null) {
            Text("짚을 발음이 없었어요", fontSize = d.sp(23), fontWeight = FontWeight.Bold, color = Y.Text1, maxLines = 1)
            Spacer(Modifier.height(d.dp(2)))
            Text("오늘은 또렷하게 잘 말했어요", fontSize = d.sp(18), color = Y.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Text(title, fontSize = d.sp(23), fontWeight = FontWeight.Bold, color = Y.Text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val example = ui.weakPointExample
            if (!example.isNullOrBlank()) {
                Spacer(Modifier.height(d.dp(2)))
                Text(example, fontSize = d.sp(20), fontWeight = FontWeight.SemiBold, color = Y.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val tip = ui.weakPointTip
            if (roomy && !tip.isNullOrBlank()) {
                Spacer(Modifier.height(d.dp(4)))
                Text(tip, fontSize = d.sp(17), color = Y.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MistakesPanel(ui: DashboardUi, d: DashScale, maxRows: Int, modifier: Modifier) {
    val n = ui.mistakes.size
    Panel(modifier, padding = d.dp(16)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Replay, contentDescription = null, tint = Y.Miss, modifier = Modifier.size(d.dp(24)))
            Spacer(Modifier.width(d.dp(8)))
            Text("틀린 표현", fontSize = d.sp(17), fontWeight = FontWeight.Bold, color = Y.Miss, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            if (n > 0) Text("${n}개", fontSize = d.sp(18), fontWeight = FontWeight.SemiBold, color = Y.Text2)
        }
        Spacer(Modifier.height(d.dp(6)))
        if (n == 0) {
            Text("틀린 표현이 없어요", fontSize = d.sp(23), fontWeight = FontWeight.Bold, color = Y.Text1, maxLines = 1)
            Spacer(Modifier.height(d.dp(2)))
            Text("오늘은 완벽했어요!", fontSize = d.sp(18), color = Y.Text2, maxLines = 1)
            return@Panel
        }
        ui.mistakes.take(maxRows).forEachIndexed { i, (en, ko) ->
            if (i > 0) Spacer(Modifier.height(d.dp(6)))
            Text(
                en,
                fontSize = d.sp(20), lineHeight = d.sp(25), fontWeight = FontWeight.SemiBold, color = Y.Text1,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (ko.isNotBlank()) {
                Text(ko, fontSize = d.sp(15), lineHeight = d.sp(20), color = Y.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
