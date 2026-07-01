package com.termuxagent.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Use the system default fonts to avoid bundling extra font files. The system
// default on modern Android (Roboto + Roboto Flex) is already excellent, and
// Compose's default Material 3 typography is well-tuned.
private val Default = Typography()

val TermuXagentTypography = Typography(
    displayLarge = Default.displayLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    displayMedium = Default.displayMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    displaySmall = Default.displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineLarge = Default.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    headlineMedium = Default.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = Default.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Default.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    titleSmall = Default.titleSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    bodyLarge = Default.bodyLarge.copy(lineHeight = 24.sp, letterSpacing = 0.15.sp),
    bodyMedium = Default.bodyMedium.copy(lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall = Default.bodySmall.copy(lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelLarge = Default.labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    labelMedium = Default.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = Default.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

val MonoTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
    letterSpacing = 0.sp,
)
