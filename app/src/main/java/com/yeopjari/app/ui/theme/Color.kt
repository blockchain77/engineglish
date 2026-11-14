package com.yeopjari.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 옆자리 디자인 토큰 — "Night Drive" 팔레트.
 * 차량 디스플레이(야간 주행·고휘도 주간)를 모두 고려해 대비 4.5:1 이상을 유지한다.
 */
object Y {
    // Surfaces
    val Bg0 = Color(0xFF06080C)
    val Bg1 = Color(0xFF0B0F15)
    val Bg2 = Color(0xFF121821)
    val Bg3 = Color(0xFF1A222D)
    val Bg4 = Color(0xFF232D3A)
    val Stroke = Color(0xFF263142)
    val StrokeSoft = Color(0x1FFFFFFF)

    // Text
    val Text1 = Color(0xFFF3F6FA)
    val Text2 = Color(0xFFADB8C8)
    val Text3 = Color(0xFF6E7A8C)

    // Brand / AI
    val Cyan = Color(0xFF5EE6FF)
    val Periwinkle = Color(0xFF7C8CFF)
    val Violet = Color(0xFFB57CFF)

    // Semantic
    val Ev = Color(0xFF3EE6A0)
    val Amber = Color(0xFFFFB547)
    val Red = Color(0xFFFF5C6C)
    val Blue = Color(0xFF4DA3FF)
    val Pet = Color(0xFFFF9F6B)
    val Kids = Color(0xFFFFD45E)
    val Rest = Color(0xFF9C8CFF)

    // Tiers (AI 처리 위치 표시)
    val TierEdge = Color(0xFF3EE6A0)
    val TierCloud = Color(0xFF8FA2FF)

    val AiGradient = Brush.linearGradient(listOf(Cyan, Periwinkle, Violet))
    val AiGradientH = Brush.horizontalGradient(listOf(Cyan, Periwinkle, Violet))
    val CardGlow = Brush.verticalGradient(listOf(Color(0x14FFFFFF), Color(0x05FFFFFF)))
    val Scrim = Brush.verticalGradient(listOf(Color(0x00000000), Color(0xCC000000)))
}
