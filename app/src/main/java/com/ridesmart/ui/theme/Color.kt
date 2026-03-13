package com.ridesmart.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * RideSmart Production Palette v3.0
 * Clean modern theme with blue accent, designed for outdoor visibility and OLED efficiency.
 */

// ── BRAND & ACTION ──────────────────────────────────────────────────
val RideGreen = Color(0xFF3A86FF)       // Primary Blue Accent (CTA)
val RideGreenDark = Color(0xFF2A6AD8)   // Pressed state
val RideBlue = Color(0xFF3A86FF)        // Primary accent (same as brand)

// ── SIGNAL VERDICTS (used ONLY for profit indicators) ───────────────
val SignalGreen = Color(0xFF4CAF50)     // High Profit / Accept
val SignalYellow = Color(0xFFFF9800)    // Medium Profit / Borderline
val SignalRed = Color(0xFFF44336)       // Low Profit / Skip

// ── DARK SYSTEM SURFACES ───────────────────────────────────────────
val DarkBackground = Color(0xFF121212)  // Material Dark background
val DarkSurface = Color(0xFF1E1E1E)     // Card / Surface
val DarkCard = Color(0xFF1E1E1E)        // Standard card surface
val DarkSurfaceVariant = Color(0xFF2C2C2C) // Lighter surface for depth
val DarkBorder = Color(0xFF333333)      // Subtle separators

// ── TEXT SYSTEM ─────────────────────────────────────────────────────
val TextPrimary = Color(0xFFFFFFFF)     // Primary text
val TextSecondary = Color(0xFFB0B0B0)   // Secondary text
val TextMuted = Color(0xFF757575)       // Dimmed hints
val TextOnGreen = Color(0xFFFFFFFF)     // White on primary for contrast
