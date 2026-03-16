package com.ridesmart.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ridesmart.R

// Barlow Condensed — verdict labels, section headers, button text
val BarlowCondensed = FontFamily(
    Font(R.font.barlow_condensed_regular, FontWeight.Normal),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
    Font(R.font.barlow_condensed_extrabold, FontWeight.ExtraBold)
)

// IBM Plex Mono — all numbers: fare, profit score, ₹/km
val IbmPlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium)
)

val Typography = Typography(
    // Large verdict text — "ACCEPT" / "SKIP"
    displayLarge = TextStyle(
        fontFamily = BarlowCondensed,
        fontWeight = FontWeight.ExtraBold,
        fontSize   = 36.sp,
        letterSpacing = 0.1.sp
    ),
    // Section headers
    titleLarge = TextStyle(
        fontFamily = BarlowCondensed,
        fontWeight = FontWeight.Bold,
        fontSize   = 18.sp,
        letterSpacing = 0.08.sp
    ),
    titleMedium = TextStyle(
        fontFamily = BarlowCondensed,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 14.sp,
        letterSpacing = 0.1.sp
    ),
    // Body labels
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize   = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize   = 13.sp,
        lineHeight = 19.sp
    ),
    // Muted hints
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize   = 11.sp,
        letterSpacing = 0.06.sp
    )
)

// Use these directly for number displays (fare, score, ₹/km)
val MonoNumberStyle = TextStyle(
    fontFamily = IbmPlexMono,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.sp
)
