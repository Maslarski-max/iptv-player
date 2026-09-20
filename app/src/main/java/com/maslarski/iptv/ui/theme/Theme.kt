package com.maslarski.iptv.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val Background = Color(0xFF0B0B10)
    val Surface = Color(0xFF14141C)
    val SurfaceElevated = Color(0xFF1C1C27)
    val SurfaceHighest = Color(0xFF262633)
    val Slate = Color(0xFF334155)
    val OnSurface = Color(0xFFF1F1F6)
    val Muted = Color(0xFF9CA3AF)
    val NeonPurple = Color(0xFFA855F7)
    val ElectricBlue = Color(0xFF38BDF8)
    val Gold = Color(0xFFFBBF24)
    val Danger = Color(0xFFF87171)
    val Success = Color(0xFF34D399)
    val Live = Color(0xFFEF4444)

    val FocusGradient = Brush.linearGradient(listOf(NeonPurple, ElectricBlue))
    val HeroScrim = Brush.verticalGradient(listOf(Color.Transparent, Background.copy(alpha = 0.85f), Background))
    val HeroSideScrim = Brush.horizontalGradient(listOf(Background, Background.copy(alpha = 0.7f), Color.Transparent))
    val HeroTopScrim = Brush.verticalGradient(listOf(Background.copy(alpha = 0.9f), Background.copy(alpha = 0.5f), Color.Transparent))
}

private val DarkScheme = darkColorScheme(
    primary = Palette.NeonPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3B0764),
    onPrimaryContainer = Color(0xFFF3E8FF),
    secondary = Palette.ElectricBlue,
    onSecondary = Color(0xFF082F49),
    tertiary = Palette.Gold,
    onTertiary = Color(0xFF422006),
    background = Palette.Background,
    onBackground = Palette.OnSurface,
    surface = Palette.Surface,
    onSurface = Palette.OnSurface,
    surfaceVariant = Palette.SurfaceElevated,
    onSurfaceVariant = Palette.Muted,
    surfaceContainer = Palette.SurfaceElevated,
    surfaceContainerHigh = Palette.SurfaceHighest,
    surfaceContainerHighest = Palette.SurfaceHighest,
    outline = Palette.Slate,
    error = Palette.Danger,
    onError = Color.White,
)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 54.sp, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 46.sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.5.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)

@Composable
fun IptvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, typography = AppTypography) {
        CompositionLocalProvider(LocalContentColor provides DarkScheme.onBackground, content = content)
    }
}
