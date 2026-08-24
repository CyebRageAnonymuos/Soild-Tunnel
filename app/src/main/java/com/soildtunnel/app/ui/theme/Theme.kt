package com.soildtunnel.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Fallback scheme: a pure-black theme for devices below Android 12.
private val SoildTunnelDarkColorScheme = darkColorScheme(
    primary = SoildTunnelBlue,
    onPrimary = Color.Black,
    secondary = SoildTunnelCyan,
    onSecondary = Color(0xFF04211C),
    tertiary = SoildTunnelCyan,
    background = Black950,
    onBackground = OnDark,
    surface = Black900,
    onSurface = OnDark,
    surfaceVariant = Black800,
    onSurfaceVariant = OnDarkMuted,
    error = SoildTunnelError,
    onError = Color.Black,
    outline = Black700,
)

/**
 * Material You: uses the wallpaper-derived dynamic dark palette on Android 12+,
 * and falls back to the navy scheme otherwise. Always dark by design.
 */
@Composable
fun SoildTunnelTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        SoildTunnelDarkColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SoildTunnelTypography,
        content = content,
    )
}
