package com.pixtane.holos.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object HolosColors {
    val Bg = Color(0xFF0A0A10)
    val Surface = Color(0xFF15151F)
    val SurfaceHigh = Color(0xFF1E1E2B)
    val Outline = Color(0xFF2C2C3D)
    val Violet = Color(0xFFA78BFA)
    val VioletDeep = Color(0xFF6D28D9)
    val Pink = Color(0xFFF472B6)
    val Text = Color(0xFFF5F5F8)
    val Muted = Color(0xFF9A9AAE)
    val Faint = Color(0xFF5E5E73)
    val Success = Color(0xFF34D399)
    val Danger = Color(0xFFF87171)

    val accentBrush = Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFD946EF), Color(0xFFF472B6)))
}

private val scheme = darkColorScheme(
    primary = HolosColors.Violet,
    onPrimary = Color(0xFF1A0B3D),
    primaryContainer = HolosColors.VioletDeep,
    onPrimaryContainer = Color.White,
    secondary = HolosColors.Pink,
    onSecondary = Color(0xFF3B0A24),
    background = HolosColors.Bg,
    onBackground = HolosColors.Text,
    surface = HolosColors.Surface,
    onSurface = HolosColors.Text,
    surfaceVariant = HolosColors.SurfaceHigh,
    onSurfaceVariant = HolosColors.Muted,
    surfaceContainer = HolosColors.Surface,
    surfaceContainerHigh = HolosColors.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF26263A),
    surfaceContainerLow = Color(0xFF111119),
    outline = HolosColors.Outline,
    outlineVariant = Color(0xFF22222F),
    error = HolosColors.Danger,
)

private val base = Typography()
private val typography = base.copy(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

val LyricsStyle = TextStyle(fontSize = 23.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)

@Composable
fun HolosTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
