package com.ridesmart.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val RideSmartColorScheme = darkColorScheme(
    primary          = Amber400,
    onPrimary        = BgDeep,
    primaryContainer = AmberLight,
    onPrimaryContainer = Amber800,

    secondary        = Green400,
    onSecondary      = BgDeep,
    secondaryContainer = GreenLight,
    onSecondaryContainer = GreenDark,

    tertiary         = Red400,
    onTertiary       = BgDeep,

    background       = BgDeep,
    onBackground     = TextPrimary,

    surface          = BgSurface,
    onSurface        = TextPrimary,
    surfaceVariant   = BgElevated,
    onSurfaceVariant = TextSecondary,

    outline          = BorderDefault,
    outlineVariant   = BorderSubtle,

    error            = Red400,
    onError          = BgDeep
)

@Composable
fun RidesmartTheme(content: @Composable () -> Unit) {
    // dynamicColor is intentionally OFF — wallpaper colors must never
    // override the green/amber/red signal colours captains rely on
    MaterialTheme(
        colorScheme = RideSmartColorScheme,
        typography  = Typography,
        content     = content
    )
}
