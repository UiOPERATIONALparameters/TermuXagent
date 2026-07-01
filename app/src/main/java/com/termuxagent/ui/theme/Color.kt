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
val InkSurfaceHi   = Color(0xFF161616)
val InkSurfaceMax  = Color(0xFF202020)
val InkBorder      = Color(0xFF2E2E2E)
val InkBorderSoft  = Color(0xFF1F1F1F)
val InkOnBg        = Color(0xFFFFFFFF)
val InkOnSurface   = Color(0xFFF2F2F2)
val InkOnSurfaceV  = Color(0xFFA8A8A8)   // brighter — was 0x88, too dim
val InkOnSurfaceD  = Color(0xFF707070)
val InkPrimary     = Color(0xFFFFFFFF)
val InkOnPrimary   = Color(0xFF000000)
val InkSecondary   = Color(0xFFCCCCCC)
val InkTertiary    = Color(0xFF888888)

// Light theme — "Paper" (pure white)
val PaperWhite     = Color(0xFFFFFFFF)
val PaperSurface   = Color(0xFFFAFAFA)
val PaperSurfaceHi = Color(0xFFF0F0F0)
val PaperSurfaceMax= Color(0xFFE6E6E6)
val PaperBorder    = Color(0xFFD8D8D8)
val PaperBorderSoft= Color(0xFFE8E8E8)
val PaperOnBg      = Color(0xFF000000)
val PaperOnSurface = Color(0xFF111111)
val PaperOnSurfaceV= Color(0xFF505050)   // darker — was 0x55, more readable
val PaperOnSurfaceD= Color(0xFF888888)
val PaperPrimary   = Color(0xFF000000)
val PaperOnPrimary = Color(0xFFFFFFFF)
val PaperSecondary = Color(0xFF333333)
val PaperTertiary  = Color(0xFF666666)

// Status colors — used only on icons & small accents
val StatusSuccess  = Color(0xFF22C55E)
val StatusError    = Color(0xFFEF4444)
val StatusWarn     = Color(0xFFF59E0B)
val StatusInfo     = Color(0xFF3B82F6)
