package com.toxicbakery.app.bison.common.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val LocalBisonPalette = staticCompositionLocalOf { BisonThemes.GruvboxDark }

val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
)

fun BisonThemePalette.toMaterialColorScheme(): ColorScheme = if (isDark) {
    darkColorScheme(
        primary = accent,
        onPrimary = terminalBg,
        primaryContainer = accent.copy(alpha = 0.22f),
        onPrimaryContainer = text,
        secondary = mauve,
        onSecondary = terminalBg,
        secondaryContainer = mauve.copy(alpha = 0.22f),
        onSecondaryContainer = text,
        tertiary = green,
        onTertiary = terminalBg,
        background = terminalBg,
        onBackground = text,
        surface = panelBg,
        onSurface = text,
        surfaceVariant = panelBg.copy(alpha = 0.85f),
        onSurfaceVariant = text.copy(alpha = 0.78f),
        error = red,
        onError = Color.White,
        outline = text.copy(alpha = 0.25f),
    )
} else {
    lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = accent.copy(alpha = 0.16f),
        onPrimaryContainer = text,
        secondary = mauve,
        onSecondary = Color.White,
        secondaryContainer = mauve.copy(alpha = 0.16f),
        onSecondaryContainer = text,
        tertiary = green,
        onTertiary = Color.White,
        background = terminalBg,
        onBackground = text,
        surface = panelBg,
        onSurface = text,
        surfaceVariant = panelBg.copy(alpha = 0.90f),
        onSurfaceVariant = text.copy(alpha = 0.74f),
        error = red,
        onError = Color.White,
        outline = text.copy(alpha = 0.25f),
    )
}

@Composable
fun BisonTheme(
    palette: BisonThemePalette = BisonThemes.GruvboxDark,
    content: @Composable () -> Unit,
) {
    val colorScheme = remember(palette) { palette.toMaterialColorScheme() }
    CompositionLocalProvider(LocalBisonPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content,
        )
    }
}
