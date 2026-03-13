package com.ridesmart.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * RideSmart HUD Theme v2.0
 * Ultra-modern neon-dark interface optimized for high-speed decision making.
 */
private val RideSmartColorScheme = darkColorScheme(
    primary = RideGreen,
    onPrimary = TextOnGreen,
    primaryContainer = RideGreenDark,
    onPrimaryContainer = Color.White,
    secondary = RideBlue,
    onSecondary = Color.Black,
    tertiary = SignalYellow,
    onTertiary = Color.Black,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = DarkBorder,
    error = SignalRed,
    onError = Color.White
)

@Composable
fun RidesmartTheme(
    darkTheme: Boolean = true, // Always dark for drivers
    content: @Composable () -> Unit
) {
    val colorScheme = RideSmartColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = DarkBackground.toArgb()
            window.navigationBarColor = DarkBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
