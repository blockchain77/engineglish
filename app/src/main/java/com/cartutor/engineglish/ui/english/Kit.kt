package com.cartutor.engineglish.ui.english

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cartutor.engineglish.english.core.ExpressionUi
import com.cartutor.engineglish.ui.theme.Y
import com.cartutor.engineglish.ui.theme.YType

/*
 * 엔진글리시 UI 공용 부품 — 차량용 기준: 터치 대상 76dp 이상, 본문 19sp 이상, 강조색 하나.
 */

/** 터치 대상 최소 높이 (차량 UX) */
internal val TouchMin = 76.dp

// ───────────────────────── 표면 ─────────────────────────

/** 카드 표면. accent 를 주면 그 색으로 테두리와 위쪽 은은한 빛 */
@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    padding: Dp = 24.dp,
    shape: Shape = RoundedCornerShape(28.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(shape)
            .background(Y.Bg2)
            .then(
                if (accent != null) Modifier.background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.12f), Color.Transparent)))
                else Modifier
            )
            .border(1.dp, accent?.copy(alpha = 0.45f) ?: Y.StrokeSoft, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** 작은 대문자 꼬리표 ("오늘의 출근길") */
@Composable
internal fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Y.Text3) {
    Text(text, modifier = modifier, style = YType.Eyebrow, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Y.Text1, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Y.Text2)
            }
        }
        trailing?.invoke(this)
    }
}

/** 화면 머리글 — 큰 제목 + 한 줄 설명 */
@Composable
internal fun ScreenHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null, trailing: @Composable (RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.displaySmall, color = Y.Text1, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Spacer(Modifier.height(6.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Y.Text2)
            }
        }
        trailing?.invoke(this)
    }
}

// ───────────────────────── 버튼 ─────────────────────────

internal enum class Tone { PRIMARY, SECONDARY, GHOST, DANGER }

/** 차량용 큰 버튼 — 최소 높이 76dp */
@Composable
internal fun BigButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: Tone = Tone.PRIMARY,
    enabled: Boolean = true,
    minHeight: Dp = TouchMin,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium,
) {
    val shape = RoundedCornerShape(22.dp)
    val (bg, fg, stroke) = when {
        !enabled -> Triple(Y.Bg3, Y.Text3, Y.Bg4)
        tone == Tone.PRIMARY -> Triple(Y.Accent, Y.OnAccent, Y.Accent)
        tone == Tone.SECONDARY -> Triple(Y.Bg3, Y.Text1, Y.Stroke)
        tone == Tone.GHOST -> Triple(Color.Transparent, Y.Text2, Y.Stroke)
        else -> Triple(Y.Red.copy(alpha = 0.14f), Y.Red, Y.Red.copy(alpha = 0.55f))
    }
    Row(
        modifier
            .heightIn(min = minHeight)
            .clip(shape)
            .background(bg)
            .border(1.dp, stroke, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(label, style = textStyle, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
    }
}

/** 주행 화면 버튼 — 아이콘 위 · 글자 아래, 장갑 낀 손으로도 누르기 쉽게 크게 */
@Composable
internal fun DriveButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    highlight: Boolean = false,
    height: Dp = 104.dp,
) {
    val shape = RoundedCornerShape(26.dp)
    val fg = when {
        !enabled -> Y.Text3
        highlight -> Y.OnAccent
        else -> Y.Text1
    }
    Column(
        modifier
            .heightIn(min = height)
            .clip(shape)
            .background(if (highlight && enabled) Y.Accent else Y.Bg3)
            .border(1.dp, if (highlight && enabled) Y.Accent else Y.Stroke, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(38.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = fg, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ───────────────────────── 칩 · 배지 ─────────────────────────

@Composable
internal fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Y.Text2,
    bg: Color = color.copy(alpha = 0.13f),
    icon: ImageVector? = null,
    large: Boolean = false,
) {
    Row(
        modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = if (large) 18.dp else 14.dp, vertical = if (large) 9.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(if (large) 24.dp else 18.dp))
            Spacer(Modifier.width(if (large) 8.dp else 6.dp))
        }
        Text(
            text,
            style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 아이콘 배지 (둥근 사각) */
@Composable
internal fun IconBadge(icon: ImageVector, color: Color, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(color.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size * 0.52f))
    }
}

/** 칩 묶음 — 좁은 창에서는 다음 줄로 넘긴다 (잘리지 않게) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipFlow(
    modifier: Modifier = Modifier,
    center: Boolean = false,
    spacing: Dp = 10.dp,
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier,
        horizontalArrangement = if (center) Arrangement.spacedBy(spacing, Alignment.CenterHorizontally) else Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) { content() }
}

@Composable
internal fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

@Composable
internal fun HDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Y.StrokeSoft))
}

// ───────────────────────── 스위치 · 선택 ─────────────────────────

/** 보이는 스위치 (터치는 줄 전체가 받는다) */
@Composable
internal fun SwitchVisual(checked: Boolean, enabled: Boolean = true) {
    val x by animateFloatAsState(if (checked) 1f else 0f, tween(180), label = "switch")
    val track = when {
        !enabled -> Y.Bg4
        checked -> Y.Accent
        else -> Y.Bg4
    }
    Box(
        Modifier.width(84.dp).height(48.dp).clip(CircleShape).background(track).padding(5.dp),
    ) {
        Box(
            Modifier
                .padding(start = (36 * x).dp)
                .size(38.dp)
                .clip(CircleShape)
                .background(if (checked && enabled) Y.OnAccent else Y.Text2),
        )
    }
}

/** 켜기/끄기 한 줄 — 줄 전체가 76dp 이상 터치 대상 */
@Composable
internal fun ToggleRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .clip(RoundedCornerShape(20.dp))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (checked) Y.Accent else Y.Text3, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) Y.Text1 else Y.Text3)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Y.Text2)
            }
        }
        Spacer(Modifier.width(16.dp))
        SwitchVisual(checked, enabled)
    }
}

/** 여러 개 중 하나 고르기 — 칸마다 76dp 이상 */
@Composable
internal fun <T> Segmented(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Y.Bg1)
            .border(1.dp, Y.StrokeSoft, RoundedCornerShape(24.dp))
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        options.forEach { o ->
            val sel = o == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = TouchMin)
                    .clip(RoundedCornerShape(19.dp))
                    .background(if (sel) Y.AccentSoft else Color.Transparent)
                    .border(1.5.dp, if (sel) Y.Accent else Color.Transparent, RoundedCornerShape(19.dp))
                    .selectable(selected = sel, enabled = enabled, role = Role.RadioButton) { onSelect(o) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(o),
                    style = MaterialTheme.typography.titleSmall,
                    color = when {
                        !enabled -> Y.Text3
                        sel -> Y.Accent
                        else -> Y.Text2
                    },
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 큰 선택 카드 (온보딩 트랙·목표) */
@Composable
internal fun ChoiceCard(
    title: String,
    body: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) Y.AccentSoft else Y.Bg2)
            .border(if (selected) 2.dp else 1.dp, if (selected) Y.Accent else Y.StrokeSoft, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(26.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, if (selected) Y.Accent else Y.Text2, size = 60.dp)
            Spacer(Modifier.weight(1f))
            if (badge != null) Chip(badge, color = Y.Accent)
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Y.Text1, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Y.Text2, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

// ───────────────────────── 진행 표시 ─────────────────────────

/** 원형 진행 고리 */
@Composable
internal fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = Y.Accent,
    track: Color = Y.Bg4,
    stroke: Dp = 10.dp,
    animate: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val target = progress.coerceIn(0f, 1f)
    val anim by animateFloatAsState(target, tween(if (animate) 500 else 0), label = "ring")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val d = size.minDimension - s
            val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
            drawArc(track, -90f, 360f, false, tl, Size(d, d), style = Stroke(s))
            if (anim > 0f) drawArc(color, -90f, 360f * anim, false, tl, Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
        }
        content()
    }
}

/** 가로 진행 막대 */
@Composable
internal fun Meter(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = Y.Accent,
    track: Color = Y.Bg4,
    height: Dp = 10.dp,
) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(400), label = "meter")
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)) {
        if (p > 0f) Box(Modifier.fillMaxWidth(p).height(height).clip(CircleShape).background(color))
    }
}

/** 숫자 타일 */
@Composable
internal fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    icon: ImageVector? = null,
    color: Color = Y.Text1,
    caption: String? = null,
) {
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(Y.Bg3).padding(horizontal = 22.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = Y.Text3, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = Y.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = YType.MetricSmall, color = color, maxLines = 1)
            if (unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(unit, style = MaterialTheme.typography.bodyMedium, color = Y.Text3, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        if (caption != null) {
            Spacer(Modifier.height(4.dp))
            Text(caption, style = MaterialTheme.typography.bodySmall, color = Y.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ───────────────────────── 목록 · 빈 상태 ─────────────────────────

/** 표현 한 줄 (복습 · 리포트) */
@Composable
internal fun ExpressionRow(
    e: ExpressionUi,
    modifier: Modifier = Modifier,
    tagColor: Color = Y.Text2,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 84.dp).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(e.textEn, style = MaterialTheme.typography.titleLarge, color = Y.Text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (e.meaningKo.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(e.meaningKo, style = MaterialTheme.typography.bodyMedium, color = Y.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (e.tagKo.isNotBlank()) {
            Spacer(Modifier.width(14.dp))
            Chip(e.tagKo, color = tagColor)
        }
        trailing?.invoke(this)
    }
}

@Composable
internal fun EmptyNote(icon: ImageVector, title: String, modifier: Modifier = Modifier, body: String? = null) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = Y.Text3, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Y.Text2, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Y.Text3, textAlign = TextAlign.Center)
        }
    }
}

/** 주행 중 잠긴 화면 */
@Composable
internal fun LockedPanel(
    modifier: Modifier = Modifier,
    title: String = LOCKED_KO,
    body: String = "안전을 위해 주행 중에는 주행 학습 화면만 쓸 수 있어요.",
) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 760.dp)) {
            IconBadge(Icons.Rounded.Lock, Y.LoadFocus, size = 96.dp)
            Spacer(Modifier.height(24.dp))
            Text(title, style = MaterialTheme.typography.headlineLarge, color = Y.Text1, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = Y.Text2, textAlign = TextAlign.Center)
        }
    }
}

internal const val LOCKED_KO = "정차 후 이용할 수 있어요"

// ───────────────────────── 확인 시트 ─────────────────────────

/** 화면 위에 뜨는 확인 창 (시스템 대화상자 대신 — 차량 화면에서 크기·대비를 맞추려고) */
@Composable
internal fun ConfirmSheet(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    dismissLabel: String = "취소",
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Y.Dim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Panel(
            Modifier
                .padding(24.dp)
                .widthIn(max = 760.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            accent = if (danger) Y.Red else Y.Accent,
            padding = 32.dp,
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Y.Text1, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = Y.Text2)
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BigButton(dismissLabel, onDismiss, Modifier.weight(1f), tone = Tone.SECONDARY)
                BigButton(confirmLabel, onConfirm, Modifier.weight(1f), tone = if (danger) Tone.DANGER else Tone.PRIMARY)
            }
        }
    }
}
