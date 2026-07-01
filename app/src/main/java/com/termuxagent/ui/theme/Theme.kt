package com.termuxagent.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode { System, Black, White }

val LocalThemeMode = staticCompositionLocalOf { ThemeMode.System }

private val InkColors = darkColorScheme(
    primary = InkPrimary,
    onPrimary = InkOnPrimary,
    primaryContainer = InkSurfaceMax,
    onPrimaryContainer = InkOnSurface,
    secondary = InkSecondary,
    onSecondary = InkOnPrimary,
    secondaryContainer = InkSurfaceHi,
    onSecondaryContainer = InkOnSurface,
    tertiary = InkTertiary,
    onTertiary = InkOnPrimary,
    tertiaryContainer = InkSurfaceHi,
    onTertiaryContainer = InkOnSurface,
    background = InkBlack,
    onBackground = InkOnBg,
    surface = InkSurface,
    onSurface = InkOnSurface,
    surfaceVariant = InkSurfaceHi,
    onSurfaceVariant = InkOnSurfaceV,
    surfaceTint = InkOnSurface,
    inverseSurface = PaperWhite,
    inverseOnSurface = InkBlack,
    error = StatusError,
    onError = InkBlack,
    errorContainer = Color(0xFF3D1010),
    onErrorContainer = StatusError,
    outline = InkBorder,
    outlineVariant = InkBorderSoft,
    scrim = InkBlack,
)

private val PaperColors = lightColorScheme(
    primary = PaperPrimary,
    onPrimary = PaperOnPrimary,
    primaryContainer = PaperSurfaceMax,
    onPrimaryContainer = PaperOnSurface,
    secondary = PaperSecondary,
    onSecondary = PaperOnPrimary,
    secondaryContainer = PaperSurfaceHi,
    onSecondaryContainer = PaperOnSurface,
    tertiary = PaperTertiary,
    onTertiary = PaperOnPrimary,
    tertiaryContainer = PaperSurfaceHi,
    onTertiaryContainer = PaperOnSurface,
    background = PaperWhite,
    onBackground = PaperOnBg,
    surface = PaperSurface,
    onSurface = PaperOnSurface,
    surfaceVariant = PaperSurfaceHi,
    onSurfaceVariant = PaperOnSurfaceV,
    surfaceTint = PaperOnSurface,
    inverseSurface = InkBlack,
    inverseOnSurface = PaperWhite,
    error = StatusError,
    onError = PaperWhite,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF991B1B),
    outline = PaperBorder,
    outlineVariant = PaperBorderSoft,
    scrim = InkBlack,
)

@Composable
fun TermuXagentTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Black -> true
        ThemeMode.White -> false
    }
    val context = LocalContext.current
    val colorScheme = when {
        // Dynamic color takes precedence when explicitly enabled AND on Android 12+.
        // Falls back to the e-ink palette otherwise.
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        isDark -> InkColors
        else -> PaperColors
    }

    CompositionLocalProvider(LocalThemeMode provides themeMode) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = TermuXagentTypography,
            content = content
        )
    }
}

