package com.soildtunnel.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView

/** The void behind everything: pure near-black with a whisper of blue. */
val Void = Color(0xFF030408)

// ---------------------------------------------------------------------------
// CONTROL ROOM NEON scheme — always dark, always the same palette.
//
// Dynamic Material You colour is deliberately DISABLED: a wallpaper-derived
// scheme repainted the console into whatever happened to be on the user's
// lock screen (see the history note in ConnectionCard). The control-room
// identity only works if every surface speaks the same neon language, so the
// scheme below is pinned. Panels that read MaterialTheme tokens (segmented
// selectors, dropdowns, inputs, cards) inherit it automatically.
// ---------------------------------------------------------------------------
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
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = ControlRoomScheme,
        typography = SoildTunnelTypography,
        content = content,
    )
}
