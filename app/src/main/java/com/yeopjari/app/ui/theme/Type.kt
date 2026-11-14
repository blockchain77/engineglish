package com.yeopjari.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 차량용 타이포그래피 스케일.
 * 주행 중 1.5초 이내 글랜스 인지를 위해 본문 최소 20sp, 핵심 수치는 40sp 이상을 사용한다.
 */
val YTypography = Typography(
    displayLarge = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, lineHeight = 70.sp),
    displayMedium = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, lineHeight = 56.sp),
    displaySmall = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.SemiBold, lineHeight = 48.sp),
    headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp),
    headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, lineHeight = 38.sp),
    headlineSmall = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 34.sp),
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
    titleMedium = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleSmall = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Normal, lineHeight = 31.sp),
    bodyMedium = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Normal, lineHeight = 28.sp),
    bodySmall = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    labelLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    labelMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, letterSpacing = 0.4.sp),
)
