package com.cartutor.engineglish.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val scheme = darkColorScheme(
    primary = Y.Accent,
    onPrimary = Y.OnAccent,
    primaryContainer = Y.AccentSoft,
    onPrimaryContainer = Y.Accent,
    secondary = Y.Periwinkle,
    onSecondary = Y.Bg0,
    tertiary = Y.Violet,
    background = Y.Bg0,
    onBackground = Y.Text1,
    surface = Y.Bg2,
    onSurface = Y.Text1,
    surfaceVariant = Y.Bg3,
    onSurfaceVariant = Y.Text2,
    surfaceContainerLowest = Y.Bg0,
    surfaceContainerLow = Y.Bg1,
    surfaceContainer = Y.Bg2,
    surfaceContainerHigh = Y.Bg3,
    surfaceContainerHighest = Y.Bg4,
    outline = Y.Stroke,
    outlineVariant = Y.Bg4,
    error = Y.Red,
)

val YShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun EngineglishTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = YTypography, shapes = YShapes, content = content)
}
