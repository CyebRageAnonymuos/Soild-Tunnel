package com.soildtunnel.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Void behind everything: near-black with slight blue tint. */
val Void = Color(0xFF030408)

// Always-dark pinned scheme. Dynamic color is disabled.
private val ControlRoomScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Color(0xFF00232B),
    secondary = NeonMint,
    onSecondary = Color(0xFF00291E),
    tertiary = NeonViolet,
    onTertiary = Color(0xFF1D1240),
    background = Void,
    onBackground = OnDark,
    surface = PanelBottom,
    onSurface = OnDark,
    surfaceVariant = CardSubSurface,
    onSurfaceVariant = OnDarkMuted,
    surfaceContainer = Color(0xFF0B0E14),
    surfaceContainerHigh = Color(0xFF11151C),
    surfaceContainerHighest = Color(0xFF161B24),
    error = NeonRed,
    onError = Color(0xFF2B040C),
    outline = EdgeNeon,
    outlineVariant = Color(0x1A35E0FF),
)

@Composable
fun SoildTunnelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ControlRoomScheme,
        typography = SoildTunnelTypography,
        content = content,
    )
}
