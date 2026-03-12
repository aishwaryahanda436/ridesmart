package com.ridesmart.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * RideSmart Production Palette v2.0
 * Modern high-contrast theme designed for outdoor visibility and OLED efficiency.
 */

// ── BRAND & ACTION ──────────────────────────────────────────────────
val RideGreen = Color(0xFF00FF88)       // Vivid Neon Green (Primary CTA)
val RideGreenDark = Color(0xFF00C868)   // Deep Forest (Press states)
val RideBlue = Color(0xFF00D1FF)        // Electric Blue (Secondary/Info)

// ── SIGNAL VERDICTS ─────────────────────────────────────────────────
val SignalGreen = Color(0xFF00FF88)     // Profit/Accept
val SignalYellow = Color(0xFFFFB800)    // Borderline/Caution
val SignalRed = Color(0xFFFF3B30)       // Risk/Skip

// ── DARK SYSTEM SURFACES ───────────────────────────────────────────
val DarkBackground = Color(0xFF08080A)  // True Black (OLED efficient)
val DarkSurface = Color(0xFF121216)     // Deep Slate
val DarkCard = Color(0xFF121216)        // Standard card surface
val DarkSurfaceVariant = Color(0xFF1C1C24) // Lighter Slate for depth
val DarkBorder = Color(0xFF2A2A32)      // Subtle separators

// ── TEXT SYSTEM ─────────────────────────────────────────────────────
val TextPrimary = Color(0xFFF2F2F7)     // High-intensity white
val TextSecondary = Color(0xFFA1A1B2)   // Soft silver (Metadata)
val TextMuted = Color(0xFF636370)        // Dimmed slate (Hints)
val TextOnGreen = Color(0xFF000000)     // Black on primary for contrast
