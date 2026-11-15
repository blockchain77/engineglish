package com.cartutor.engineglish.ui.english

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cartutor.engineglish.english.core.HudIcon
import com.cartutor.engineglish.english.core.HudUi
import com.cartutor.engineglish.ui.theme.Y

/*
 * 앞유리 HUD 시뮬레이터 — Pleos 에는 HUD 공개 API 가 없어서 앱 안에 앞유리 장면을 그리고,
 * 투사되는 내용은 속도와 '힌트 한 단어'뿐이다 (주행 중 영어 문장은 읽히지 않고 귀로만).
 * 장면은 늘 정지 그림 — 주변 시야에 움직임을 만들지 않는다.
 */

private val SkyTop = Color(0xFF03060E)
private val SkyHorizon = Color(0xFF1C2747)
private val GroundFar = Color(0xFF0C1120)
private val GroundNear = Color(0xFF04060B)
private val RoadFar = Color(0xFF161C29)
private val RoadNear = Color(0xFF202735)
private val CityGlow = Color(0xFFFF9D5C)
private val Dash = Color(0xFF05070C)

/** HUD 에 지금 띄울 학습 층 */
private sealed interface HudLayer {
    data class Hint(val word: String) : HudLayer
    data object Listening : HudLayer
    data object Correct : HudLayer
    data object Retry : HudLayer
    data object Hold : HudLayer
    data object Paused : HudLayer
    data object Idle : HudLayer
}

private fun layerOf(hud: HudUi): HudLayer = when (hud.icon) {
    HudIcon.LISTENING -> hud.hint?.takeIf { it.isNotBlank() }?.let { HudLayer.Hint(it.trim()) } ?: HudLayer.Listening
    HudIcon.CORRECT -> HudLayer.Correct
    HudIcon.RETRY -> HudLayer.Retry
    HudIcon.HOLD -> HudLayer.Hold
    HudIcon.PAUSED -> HudLayer.Paused
    HudIcon.NONE -> hud.hint?.takeIf { it.isNotBlank() }?.let { HudLayer.Hint(it.trim()) } ?: HudLayer.Idle
}

/**
 * 앞유리 HUD.
 * @param speedKph 왼쪽에 투사되는 속도 (실제 HUD 처럼)
 * @param showLabel '앞유리 HUD 시뮬레이터' 표시 (실차 HUD API 가 없다는 정직한 표기)
 */
@Composable
internal fun WindshieldHud(
    hud: HudUi,
    speedKph: Int,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(26.dp)).background(SkyTop)) {
        val k = (maxHeight / 190.dp).coerceIn(0.72f, 1.5f)
        // 장면 (크기가 바뀔 때만 다시 만든다)
        Spacer(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val hy = h * 0.46f
                    val cx = w * 0.5f
                    val road = Path().apply {
                        moveTo(cx - w * 0.015f, hy)
                        lineTo(cx + w * 0.015f, hy)
                        lineTo(cx + w * 0.62f, h)
                        lineTo(cx - w * 0.62f, h)
                        close()
                    }
                    val lanes = Path().apply {
                        // 차로 점선 (정지 그림)
                        for (u in floatArrayOf(-0.33f, 0.33f)) {
                            var z = 1.0f
                            while (z < 9f) {
                                val y0 = hy + (h - hy) / z
                                val y1 = hy + (h - hy) / (z + 0.55f)
                                if (y0 - y1 < 1.2f) break
                                val x0 = cx + u * w * 0.62f / z
                                val x1 = cx + u * w * 0.62f / (z + 0.55f)
                                val lw0 = w * 0.0035f / z * 2f
                                val lw1 = w * 0.0035f / (z + 0.55f) * 2f
                                moveTo(x0 - lw0, y0)
                                lineTo(x0 + lw0, y0)
                                lineTo(x1 + lw1, y1)
                                lineTo(x1 - lw1, y1)
                                close()
                                z += 1.2f
                            }
                        }
                    }
                    val dash = Path().apply {
                        moveTo(0f, h)
                        lineTo(0f, h * 0.9f)
                        quadraticTo(w * 0.5f, h * 0.84f, w, h * 0.9f)
                        lineTo(w, h)
                        close()
                    }
                    val sky = Brush.verticalGradient(0f to SkyTop, 1f to SkyHorizon, startY = 0f, endY = hy)
                    val ground = Brush.verticalGradient(listOf(GroundFar, GroundNear), startY = hy, endY = h)
                    val roadBrush = Brush.verticalGradient(listOf(RoadFar, RoadNear), startY = hy, endY = h)
                    val glow = Brush.radialGradient(
                        0f to CityGlow.copy(alpha = 0.22f), 1f to Color.Transparent,
                        center = Offset(cx, hy), radius = w * 0.35f,
                    )
                    val shade = Brush.radialGradient(
                        0f to Color.Black.copy(alpha = 0.55f), 1f to Color.Transparent,
                        center = Offset(cx, h * 0.55f), radius = w * 0.32f,
                    )
                    onDrawBehind {
                        drawRect(sky, size = Size(w, hy + 1f))
                        drawRect(ground, topLeft = Offset(0f, hy), size = Size(w, h - hy))
                        drawRect(glow, topLeft = Offset(0f, hy - w * 0.35f), size = Size(w, w * 0.35f))
                        drawPath(road, roadBrush)
                        drawPath(lanes, Color.White.copy(alpha = 0.55f))
                        drawRect(shade)
                        drawPath(dash, Dash)
                    }
                },
        )

        // 투사 영역: 왼쪽 속도 · 가운데 학습 층
        Row(
            Modifier.fillMaxSize().padding(horizontal = (28 * k).dp, vertical = (10 * k).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width((150 * k).dp)) {
                HudText("$speedKph", (46 * k), weight = FontWeight.Bold)
                HudText("km/h", (16 * k), color = Y.Hud.copy(alpha = 0.65f))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = layerOf(hud),
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                    label = "hud-layer",
                ) { layer -> HudLayerView(layer, k) }
            }
            Spacer(Modifier.width((150 * k).dp))
        }

        if (showLabel) {
            Chip(
                "앞유리 HUD 시뮬레이터",
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                color = Y.Text2,
                bg = Color(0x99000000),
            )
        }
    }
}

@Composable
private fun HudLayerView(layer: HudLayer, k: Float) {
    when (layer) {
        is HudLayer.Hint -> Row(verticalAlignment = Alignment.CenterVertically) {
            HudGlyph(Icons.Rounded.Mic, 36 * k)
            Spacer(Modifier.width((16 * k).dp))
            // 힌트는 한 단어 — 길면 줄임표
            HudText(layer.word, 54 * k, weight = FontWeight.Bold)
        }
        HudLayer.Listening -> Row(verticalAlignment = Alignment.CenterVertically) {
            HudGlyph(Icons.Rounded.Mic, 36 * k)
            Spacer(Modifier.width((12 * k).dp))
            HudText("듣는 중", 34 * k, weight = FontWeight.Bold)
        }
        HudLayer.Correct -> Row(verticalAlignment = Alignment.CenterVertically) {
            HudGlyph(Icons.Rounded.Check, 44 * k, Y.Correct)
            Spacer(Modifier.width((10 * k).dp))
            HudText("정답", 40 * k, color = Y.Correct, weight = FontWeight.Bold)
        }
        HudLayer.Retry -> Row(verticalAlignment = Alignment.CenterVertically) {
            HudGlyph(Icons.Rounded.Replay, 40 * k, Y.Retry)
            Spacer(Modifier.width((10 * k).dp))
            HudText("한 번 더", 38 * k, color = Y.Retry, weight = FontWeight.Bold)
        }
        HudLayer.Hold -> Row(verticalAlignment = Alignment.CenterVertically) {
            HudGlyph(Icons.Rounded.Visibility, 30 * k, Y.Hud.copy(alpha = 0.6f))
            Spacer(Modifier.width((10 * k).dp))
            HudText("운전 집중", 28 * k, color = Y.Hud.copy(alpha = 0.6f))
        }
        HudLayer.Paused -> Row(verticalAlignment = Alignment.CenterVertically) {
            HudGlyph(Icons.Rounded.Pause, 30 * k, Y.Hud.copy(alpha = 0.6f))
            Spacer(Modifier.width((10 * k).dp))
            HudText("일시정지", 28 * k, color = Y.Hud.copy(alpha = 0.6f))
        }
        HudLayer.Idle -> HudText("엔진글리시", 20 * k, color = Y.Hud.copy(alpha = 0.35f), spacing = 4f)
    }
}

/** HUD 글자 — 단색 + 같은 색 번짐으로 투사된 빛처럼 */
@Composable
private fun HudText(
    text: String,
    sizeSp: Float,
    color: Color = Y.Hud,
    weight: FontWeight = FontWeight.SemiBold,
    spacing: Float = 0f,
) {
    Text(
        text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            color = color,
            fontSize = sizeSp.sp,
            lineHeight = (sizeSp * 1.15f).sp,
            fontWeight = weight,
            letterSpacing = spacing.sp,
            shadow = Shadow(color = color.copy(alpha = color.alpha * 0.85f), offset = Offset.Zero, blurRadius = 18f),
        ),
    )
}

@Composable
private fun HudGlyph(icon: ImageVector, sizeDp: Float, tint: Color = Y.Hud) {
    Icon(
        icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .size(sizeDp.dp)
            .drawWithCache {
                val r = size.minDimension * 0.85f
                val c = Offset(size.width / 2f, size.height / 2f)
                val glow = Brush.radialGradient(listOf(tint.copy(alpha = 0.30f), Color.Transparent), center = c, radius = r)
                onDrawBehind { drawCircle(glow, radius = r, center = c) }
            },
    )
}
