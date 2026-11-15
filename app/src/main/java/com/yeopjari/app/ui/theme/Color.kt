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

    // ───────── 옆자리 영어 (차량용 다크 테마 · 강조색 하나) ─────────

    /** 단 하나의 강조색 — 버튼·선택·진행 표시 */
    val Accent = Color(0xFF5EE6FF)
    val AccentSoft = Color(0x295EE6FF)
    val OnAccent = Color(0xFF03131A)

    /** 운전 부하 단계 색 — 정차 파랑 · 여유 초록 · 보통 청록 · 집중 주황 · 위험 빨강 */
    val LoadParked = Color(0xFF4DA3FF)
    val LoadRelaxed = Color(0xFF3EE6A0)
    val LoadNormal = Color(0xFF2FD3C4)
    val LoadFocus = Color(0xFFFFB547)
    val LoadCritical = Color(0xFFFF5C6C)

    /** 채점 색 (앰비언트 펄스와 같은 색) */
    val Correct = Color(0xFF2EE66B)
    val Retry = Color(0xFFFFB020)
    val Miss = Color(0xFFFF7A7A)

    /** 앞유리 HUD 투사 색 */
    val Hud = Color(0xFF8CF5E6)

    /** 화면을 덮는 시트 뒤 */
    val Dim = Color(0xB3000000)
}
