package com.termuxagent.ui.theme

import androidx.compose.ui.graphics.Color

// ──────────────────────────────────────────────────────────────────────────────
// E-ink monochrome palette.
// Pure black + pure white + grays only. Color is reserved exclusively for
// status semantics (success green, error red, warning amber) and even there
// it's used sparingly — on icons, never on large surfaces.
// ──────────────────────────────────────────────────────────────────────────────

// Dark theme — "Ink" (pure black, OLED-friendly)
val InkBlack       = Color(0xFF000000)
val InkSurface     = Color(0xFF0A0A0A)
val InkSurfaceHi   = Color(0xFF141414)
val InkSurfaceMax  = Color(0xFF1C1C1C)
val InkBorder      = Color(0xFF262626)
val InkBorderSoft  = Color(0xFF1A1A1A)
val InkOnBg        = Color(0xFFFFFFFF)
val InkOnSurface   = Color(0xFFEDEDED)
val InkOnSurfaceV  = Color(0xFF888888)
val InkOnSurfaceD  = Color(0xFF555555)
val InkPrimary     = Color(0xFFFFFFFF)   // white as primary action color
val InkOnPrimary   = Color(0xFF000000)
val InkSecondary   = Color(0xFFB8B8B8)
val InkTertiary    = Color(0xFF707070)

// Light theme — "Paper" (pure white)
val PaperWhite     = Color(0xFFFFFFFF)
val PaperSurface   = Color(0xFFFAFAFA)
val PaperSurfaceHi = Color(0xFFF2F2F2)
val PaperSurfaceMax= Color(0xFFEAEAEA)
val PaperBorder    = Color(0xFFE0E0E0)
val PaperBorderSoft= Color(0xFFEEEEEE)
val PaperOnBg      = Color(0xFF000000)
val PaperOnSurface = Color(0xFF111111)
val PaperOnSurfaceV= Color(0xFF555555)
val PaperOnSurfaceD= Color(0xFF999999)
val PaperPrimary   = Color(0xFF000000)   // black as primary action color
val PaperOnPrimary = Color(0xFFFFFFFF)
val PaperSecondary = Color(0xFF444444)
val PaperTertiary  = Color(0xFF888888)

// Status colors — used only on icons & small accents
val StatusSuccess  = Color(0xFF22C55E)
val StatusError    = Color(0xFFEF4444)
val StatusWarn     = Color(0xFFF59E0B)
val StatusInfo     = Color(0xFF3B82F6)
