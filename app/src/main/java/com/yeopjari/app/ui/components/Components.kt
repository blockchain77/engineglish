package com.yeopjari.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yeopjari.app.core.model.AgentPhase
import com.yeopjari.app.core.model.AiTier
import com.yeopjari.app.ui.theme.Y
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ───────────────────────── Surfaces ─────────────────────────

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    padding: Dp = 24.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier
            .clip(shape)
            .background(Y.Bg2)
            .background(Y.CardGlow)
            .then(
                if (accent != null) Modifier.border(1.5.dp, Brush.linearGradient(listOf(accent.copy(alpha = 0.75f), accent.copy(alpha = 0.08f))), shape)
                else Modifier.border(1.dp, Y.StrokeSoft, shape)
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier, trailing: @Composable (RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Y.Text1, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

@Composable
fun Eyebrow(text: String, color: Color = Y.Text3) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = color, letterSpacing = 1.2.sp)
}

// ───────────────────────── Buttons & chips ─────────────────────────

/** 차량용 대형 버튼 — 최소 높이 76dp (장갑·진동 환경 고려) */
@Composable
fun BigButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = true,
    color: Color = Y.Cyan,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier
            .heightIn(min = 76.dp)
            .clip(shape)
            .then(
                if (primary) Modifier.background(if (enabled) Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.78f))) else Brush.horizontalGradient(listOf(Y.Bg4, Y.Bg4)))
                else Modifier.background(Y.Bg3).border(1.dp, Y.Stroke, shape)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val fg = if (primary && enabled) Y.Bg0 else if (enabled) Y.Text1 else Y.Text3
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(label, style = MaterialTheme.typography.titleMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Pill(text: String, color: Color = Y.Text2, bg: Color = Y.Bg3, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).background(bg).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

@Composable
fun SuggestionChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Y.Bg3)
            .border(1.dp, Y.Stroke, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, style = MaterialTheme.typography.bodyMedium, color = Y.Text1, maxLines = 2) }
}

/** AI 처리 계층 표시 — 온디바이스 / 클라우드 / 안전 규칙 */
@Composable
fun TierBadge(tier: AiTier, latencyMs: Int? = null, modifier: Modifier = Modifier) {
    val (icon, color) = when (tier) {
        AiTier.ON_DEVICE -> Icons.Rounded.Bolt to Y.TierEdge
        AiTier.CLOUD -> Icons.Rounded.Cloud to Y.TierCloud
        AiTier.RULE -> Icons.Rounded.Shield to Y.Amber
    }
    Pill(
        text = tier.short + (latencyMs?.takeIf { it > 0 }?.let { " · ${"%.2f".format(it / 1000f)}s" } ?: ""),
        color = color, bg = color.copy(alpha = 0.12f), icon = icon, modifier = modifier,
    )
}

// ───────────────────────── AI Orb ─────────────────────────

/**
 * 옆자리 AI 오브. 상태(듣기·생각·말하기)를 색과 움직임으로 표현한다.
 * 주행 중(still = true)에는 어떤 상태에서도 움직이지 않는다 — 상태별 색과(듣는 중이면) 고정된 고리 하나만 그리고,
 * 무한 애니메이션은 만들지도 않는다 (주의분산 가이드: 중앙 화면에 장식용 움직임 금지).
 */
@Composable
fun AiOrb(phase: AgentPhase, size: Dp, still: Boolean = false, modifier: Modifier = Modifier) {
    if (still) StillOrb(phase, size, modifier) else LiveOrb(phase, size, modifier)
}

private fun orbColors(phase: AgentPhase): List<Color> = when (phase) {
    AgentPhase.LISTENING -> listOf(Y.Cyan, Color(0xFF3EE6C8), Y.Periwinkle)
    AgentPhase.THINKING, AgentPhase.ROUTING -> listOf(Y.Periwinkle, Y.Violet, Color(0xFFFF7CC8))
    AgentPhase.SPEAKING -> listOf(Y.Cyan, Y.Periwinkle, Y.Violet)
    AgentPhase.IDLE -> listOf(Color(0xFF4FC8E8), Y.Periwinkle, Color(0xFF8E6BE0))
}

/** 오브 몸통 (후광 고리 제외) — [r0]은 회전 각도 */
private fun DrawScope.drawOrbBody(colors: List<Color>, base: Float, r0: Float) {
    val c = center
    drawCircle(Brush.radialGradient(listOf(colors[1].copy(alpha = 0.35f), Color.Transparent), c, base * 1.6f), radius = base * 1.6f, center = c)
    rotate(r0, c) {
        drawCircle(Brush.sweepGradient(colors + colors.first(), c), radius = base, center = c)
    }
    // inner glow blobs
    for (i in 0..2) {
        val a = Math.toRadians((r0 * (1 + i * 0.4f) + i * 120).toDouble())
        val off = Offset((cos(a) * base * 0.32f).toFloat(), (sin(a) * base * 0.32f).toFloat())
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), c + off, base * 0.62f), radius = base * 0.62f, center = c + off)
    }
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.5f), Color.Transparent), c - Offset(base * 0.28f, base * 0.34f), base * 0.45f), radius = base * 0.45f, center = c - Offset(base * 0.28f, base * 0.34f))
    drawCircle(Color.White.copy(alpha = 0.18f), radius = base, center = c, style = Stroke(1.5.dp.toPx()))
}

/** 주행 중 오브 — 정지 그림. 상태는 색으로, 듣는 중이면 고정 고리 하나 */
@Composable
private fun StillOrb(phase: AgentPhase, size: Dp, modifier: Modifier) {
    val colors = orbColors(phase)
    Canvas(modifier.size(size)) {
        val base = this.size.minDimension / 2f * 0.62f
        if (phase == AgentPhase.LISTENING) {
            drawCircle(colors[0].copy(alpha = 0.6f), radius = base * 1.25f, center = center, style = Stroke(width = 3.dp.toPx()))
        }
        drawOrbBody(colors, base, r0 = 30f)
    }
}

/** 정차 중 오브 — 회전·맥박·후광 */
@Composable
private fun LiveOrb(phase: AgentPhase, size: Dp, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "orb")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (phase == AgentPhase.THINKING) 2200 else 9000, easing = LinearEasing)), label = "rot")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (phase == AgentPhase.LISTENING) 900 else 1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val scale by animateFloatAsState(
        when (phase) { AgentPhase.LISTENING -> 1.08f; AgentPhase.SPEAKING -> 1.04f; AgentPhase.THINKING, AgentPhase.ROUTING -> 0.96f; else -> 1f },
        label = "scale",
    )
    val colors = orbColors(phase)
    Canvas(modifier.size(size)) {
        val c = center
        val base = this.size.minDimension / 2f * 0.62f * scale
        // halo rings
        if (phase == AgentPhase.LISTENING || phase == AgentPhase.SPEAKING) {
            for (i in 0..2) {
                val k = ((pulse + i / 3f) % 1f)
                drawCircle(colors[0].copy(alpha = (1f - k) * 0.35f), radius = base * (1.05f + k * 0.55f), center = c, style = Stroke(width = 3.dp.toPx()))
            }
        }
        drawOrbBody(colors, base, rot)
    }
}

// ───────────────────────── Data viz ─────────────────────────

/** SOC 링 게이지 */
@Composable
fun SocRing(soc: Float, size: Dp, stroke: Dp = 14.dp, charging: Boolean = false, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val anim by animateFloatAsState(soc.coerceIn(0f, 100f) / 100f, tween(800), label = "soc")
    val color = when { soc < 15 -> Y.Red; soc < 30 -> Y.Amber; else -> Y.Ev }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val arcSize = Size(this.size.width - s, this.size.height - s)
            val tl = Offset(s / 2, s / 2)
            drawArc(Y.Bg4, 135f, 270f, false, tl, arcSize, style = Stroke(s, cap = StrokeCap.Round))
            drawArc(Brush.sweepGradient(listOf(color.copy(alpha = 0.6f), color, color.copy(alpha = 0.6f))), 135f, 270f * anim, false, tl, arcSize, style = Stroke(s, cap = StrokeCap.Round))
            if (charging) {
                val ang = Math.toRadians((135 + 270 * anim).toDouble())
                val rr = (this.size.minDimension - s) / 2
                drawCircle(Color.White, s * 0.45f, center + Offset((cos(ang) * rr).toFloat(), (sin(ang) * rr).toFloat()))
            }
        }
        content()
    }
}

/** 단순 라인 차트 (SOC 곡선 등). points: x(0..1), y(0..100) */
@Composable
fun MiniLineChart(
    points: List<Pair<Float, Float>>,
    modifier: Modifier = Modifier,
    color: Color = Y.Ev,
    threshold: Float? = 10f,
    markers: List<Pair<Float, String>> = emptyList(),
) {
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        fun px(x: Float) = x * w
        fun py(y: Float) = h - (y.coerceIn(0f, 100f) / 100f) * h
        // grid
        for (g in listOf(25f, 50f, 75f)) drawLine(Y.StrokeSoft, Offset(0f, py(g)), Offset(w, py(g)), 1.dp.toPx())
        threshold?.let {
            drawRect(Y.Red.copy(alpha = 0.10f), Offset(0f, py(it)), Size(w, h - py(it)))
            drawLine(Y.Red.copy(alpha = 0.6f), Offset(0f, py(it)), Offset(w, py(it)), 1.5.dp.toPx())
        }
        val path = Path().apply {
            moveTo(px(points[0].first), py(points[0].second))
            points.drop(1).forEach { lineTo(px(it.first), py(it.second)) }
        }
        val fill = Path().apply { addPath(path); lineTo(px(points.last().first), h); lineTo(px(points.first().first), h); close() }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), Color.Transparent)))
        drawPath(path, color, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        markers.forEach { (x, _) ->
            drawLine(Y.Text2.copy(alpha = 0.5f), Offset(px(x), 0f), Offset(px(x), h), 1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
        }
        drawCircle(color, 7.dp.toPx(), Offset(px(points.last().first), py(points.last().second)))
    }
}

/** 수평 막대 (요인 기여도 등) */
@Composable
fun FactorBar(label: String, value: Float, max: Float, color: Color, trailing: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Y.Text2, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(trailing, style = MaterialTheme.typography.labelLarge, color = color)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(Y.Bg4)) {
            Box(Modifier.fillMaxWidth((value / max).coerceIn(0.02f, 1f)).height(10.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.6f), color))))
        }
    }
}

@Composable
fun StatTile(label: String, value: String, unit: String = "", color: Color = Y.Text1, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(Y.Bg3).padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, tint = Y.Text3, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            Text(label, style = MaterialTheme.typography.labelLarge, color = Y.Text3, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = color, fontWeight = FontWeight.Bold, maxLines = 1)
            if (unit.isNotEmpty()) { Spacer(Modifier.width(4.dp)); Text(unit, style = MaterialTheme.typography.bodyMedium, color = Y.Text3, modifier = Modifier.padding(bottom = 4.dp)) }
        }
    }
}

/** 상단 아이콘 원형 배지 */
@Composable
fun IconBadge(icon: ImageVector, color: Color, size: Dp = 56.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(18.dp)).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.52f))
    }
}

/** 원형 진행 링 (타이머 등) */
@Composable
fun ProgressRing(progress: Float, size: Dp, color: Color, stroke: Dp = 12.dp, content: @Composable () -> Unit) {
    val anim by animateFloatAsState(progress.coerceIn(0f, 1f), tween(600), label = "ring")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            drawCircle(Y.Bg4, (this.size.minDimension - s) / 2, style = Stroke(s))
            drawArc(color, -90f, 360f * anim, false, Offset(s / 2, s / 2), Size(this.size.width - s, this.size.height - s), style = Stroke(s, cap = StrokeCap.Round))
        }
        content()
    }
}

/** 미니 차량 탑뷰 (도어·탑승 상태) */
@Composable
fun CarTopView(
    doorsOpen: List<Boolean>, // FL, FR, RL, RR, trunk
    occupied: List<Boolean>,  // FL, FR, RL, RR
    modifier: Modifier = Modifier,
    tpms: Boolean = false,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val bw = w * 0.46f
        val bh = h * 0.9f
        val left = (w - bw) / 2
        val top = (h - bh) / 2
        drawRoundRect(Y.Bg4, Offset(left, top), Size(bw, bh), CornerRadius(bw * 0.32f))
        drawRoundRect(Y.Text3.copy(alpha = 0.6f), Offset(left, top), Size(bw, bh), CornerRadius(bw * 0.32f), style = Stroke(2.dp.toPx()))
        // windshield
        drawRoundRect(Color(0xFF2B3A4E), Offset(left + bw * 0.12f, top + bh * 0.2f), Size(bw * 0.76f, bh * 0.12f), CornerRadius(12f))
        drawRoundRect(Color(0xFF2B3A4E), Offset(left + bw * 0.14f, top + bh * 0.78f), Size(bw * 0.72f, bh * 0.08f), CornerRadius(12f))
        // seats
        val seatW = bw * 0.28f
        val seatH = bh * 0.14f
        val seatPos = listOf(
            Offset(left + bw * 0.15f, top + bh * 0.38f), Offset(left + bw * 0.57f, top + bh * 0.38f),
            Offset(left + bw * 0.15f, top + bh * 0.58f), Offset(left + bw * 0.57f, top + bh * 0.58f),
        )
        seatPos.forEachIndexed { i, p ->
            drawRoundRect(if (occupied.getOrElse(i) { false }) Y.Cyan.copy(alpha = 0.85f) else Y.Bg3, p, Size(seatW, seatH), CornerRadius(14f))
        }
        // doors
        val doorLen = bh * 0.17f
        val doorY = listOf(top + bh * 0.36f, top + bh * 0.36f, top + bh * 0.56f, top + bh * 0.56f)
        for (i in 0..3) {
            val isLeft = i % 2 == 0
            val x = if (isLeft) left else left + bw
            val open = doorsOpen.getOrElse(i) { false }
            val col = if (open) Y.Amber else Y.Text3.copy(alpha = 0.5f)
            if (open) {
                val dir = if (isLeft) -1f else 1f
                drawLine(col, Offset(x, doorY[i]), Offset(x + dir * doorLen * 0.75f, doorY[i] + doorLen * 0.65f), 6.dp.toPx(), StrokeCap.Round)
            } else drawLine(col, Offset(x, doorY[i]), Offset(x, doorY[i] + doorLen), 4.dp.toPx(), StrokeCap.Round)
        }
        if (doorsOpen.getOrElse(4) { false }) drawLine(Y.Amber, Offset(left + bw * 0.2f, top + bh), Offset(left + bw * 0.8f, top + bh + 18f), 6.dp.toPx(), StrokeCap.Round)
        // wheels
        val wheelCol = if (tpms) Y.Amber else Y.Text3
        listOf(Offset(left - 6f, top + bh * 0.15f), Offset(left + bw - 10f, top + bh * 0.15f), Offset(left - 6f, top + bh * 0.72f), Offset(left + bw - 10f, top + bh * 0.72f)).forEach {
            drawRoundRect(wheelCol, it, Size(16f, bh * 0.13f), CornerRadius(8f))
        }
    }
}

/** 둥근 숫자 다이얼 (공조 온도 등) */
@Composable
fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StepBtn("−", onMinus)
        Text(value, style = MaterialTheme.typography.displaySmall, color = Y.Text1, modifier = Modifier.padding(horizontal = 22.dp))
        StepBtn("+", onPlus)
    }
}

@Composable
private fun StepBtn(t: String, onClick: () -> Unit) {
    Box(Modifier.size(72.dp).clip(CircleShape).background(Y.Bg3).border(1.dp, Y.Stroke, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(t, style = MaterialTheme.typography.headlineLarge, color = Y.Text1)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 10.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Y.StrokeSoft))

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val x by animateFloatAsState(if (on) 1f else 0f, label = "tog")
    Box(
        Modifier.width(76.dp).height(44.dp).clip(CircleShape).background(if (on) Y.Cyan else Y.Bg4).clickable { onChange(!on) }.padding(5.dp),
    ) {
        Box(Modifier.padding(start = (32 * x).dp).size(34.dp).clip(CircleShape).background(if (on) Y.Bg0 else Y.Text2))
    }
}

/** 원 둘레 점 배치용 */
fun polar(center: Offset, r: Float, deg: Float) = Offset(center.x + r * cos(deg * PI / 180).toFloat(), center.y + r * sin(deg * PI / 180).toFloat())

@Composable
fun rememberPhaseLabel(phase: AgentPhase): String = remember(phase) {
    when (phase) {
        AgentPhase.IDLE -> "말씀하세요"
        AgentPhase.LISTENING -> "듣고 있어요"
        AgentPhase.ROUTING -> "이해하는 중"
        AgentPhase.THINKING -> "생각하는 중"
        AgentPhase.SPEAKING -> "말하는 중"
    }
}

@Composable
fun GradientText(text: String, style: androidx.compose.ui.text.TextStyle) {
    Text(text, style = style.copy(brush = Y.AiGradientH))
}

@Composable
fun Glow(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.drawBehind { drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.25f), Color.Transparent)), radius = size.minDimension) })
}

@Composable
fun MaxWidth(max: Dp, content: @Composable () -> Unit) = Box(Modifier.widthIn(max = max)) { content() }
