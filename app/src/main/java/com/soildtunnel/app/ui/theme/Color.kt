package com.soildtunnel.app.ui.theme

import androidx.compose.ui.graphics.Color

// Pure-black dark palette used when dynamic color is unavailable.
val Black950 = Color(0xFF000000)
val Black900 = Color(0xFF050507)
val Black800 = Color(0xFF0B0B10)
val Black700 = Color(0xFF121218)

val SoildTunnelBlue = Color(0xFF00E5FF)
val SoildTunnelCyan = Color(0xFF7C4DFF)
val SoildTunnelError = Color(0xFFFF5C7A)

val OnDark = Color(0xFFEDEFF4)
val OnDarkMuted = Color(0xFF98A0AE)

// ---- Brand tokens for the unified connection card ----
//
// The card is pinned to these instead of MaterialTheme, because Material You
// repaints every themed surface from the user's wallpaper on Android 12+ and
// that turned the connection card into a colour that was no longer SoildTunnel.

/** Mint/teal accent of the connected state. */
val SoildTunnelMint = Color(0xFF00FFC6)
/** Second light of the animated card edge, cooler than the mint. */
val SoildTunnelGlowCyan = Color(0xFF9D7BFF)

/** Glass card surface: translucent black floating over the aurora backdrop. */
val CardSurfaceTop = Color(0xB3121216)
val CardSurfaceBottom = Color(0xE6050508)
/** Every sub-container inside the card (IP pill, speed strip, protocol strip). */
val CardSubSurface = Color(0xFF101014)

val CardTextPrimary = Color(0xFFF2F3F7)
val CardTextMuted = Color(0xFF9AA1AF)
val CardTextDim = Color(0xFF5F6570)

// ---- Liquid glass system ----
//
// One shared recipe for every glass surface in the app: a translucent white
// gradient (the "pane"), a stop-based specular sheen across the top edge, and
// a hairline white rim. All values are alpha-first so the same tokens work on
// any backdrop.

/** Base pane fill, top of the vertical gradient. */
val GlassFillTop = Color(0x26FFFFFF)
/** Base pane fill, bottom of the vertical gradient. */
val GlassFillBottom = Color(0x0DFFFFFF)
/** Hairline rim around every glass surface. */
val GlassEdge = Color(0x30FFFFFF)
/** Stronger inner rim for elevated surfaces (power orb ring, drawer). */
val GlassEdgeBright = Color(0x47FFFFFF)
/** Specular sheen: bright at y=0, gone by ~30% height. */
val GlassSheenTop = Color(0x17FFFFFF)

/** Translucent drawer surface floating over the aurora backdrop. */
val DrawerGlass = Color(0xF2000000)
/** Bottom-sheet surface, slightly more opaque for legibility. */
val SheetGlass = Color(0xFA08080A)
/** Small circular chips behind the top-bar icons. */
val ChipFillTop = Color(0x21FFFFFF)
val ChipFillBottom = Color(0x0FFFFFFF)
