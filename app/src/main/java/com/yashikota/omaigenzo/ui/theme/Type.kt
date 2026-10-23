package com.yashikota.omaigenzo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Japanese glyphs are full-width, so the Latin-tuned negative tracking of the Material defaults is
// removed, and line heights are a little looser so kana and kanji do not feel cramped.
private val Base = Typography()

val OmaiTypography = Typography(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp, lineHeight = 34.sp),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp, lineHeight = 24.sp),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    bodyLarge = Base.bodyLarge.copy(letterSpacing = 0.sp, lineHeight = 26.sp),
    bodyMedium = Base.bodyMedium.copy(letterSpacing = 0.sp, lineHeight = 22.sp),
    bodySmall = Base.bodySmall.copy(letterSpacing = 0.sp, lineHeight = 18.sp),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    labelSmall = Base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
)

/** Tabular figures so counters do not jitter sideways as digits change. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")
