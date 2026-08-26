package com.soildtunnel.desktop.ui.theme

import androidx.compose.ui.graphics.Color

// Control Room Neon palette. Near-black base, neon edges, phosphor accents.

// ---- Neon accents ----------------------------------------------------------

/** Idle/standby accent, borders, focus states. */
val NeonCyan = Color(0xFF35E0FF)
/** "GO" state: tunnel up. Kept in the mint family for brand continuity. */
val NeonMint = Color(0xFF3DFFC8)
/** Secondary light: used for upload, secondary glow bands, gradients. */
val NeonViolet = Color(0xFF9D7BFF)
/** Caution: slow latency, degraded links. */
val NeonAmber = Color(0xFFFFC24B)
/** Fault: errors, unreachable nodes, kill-switch. */
val NeonRed = Color(0xFFFF4D6F)

// Back-compat aliases (older panels still import these).
val SoildTunnelBlue = NeonCyan
val SoildTunnelCyan = NeonViolet
val SoildTunnelMint = NeonMint
val SoildTunnelGlowCyan = NeonViolet
val SoildTunnelError = NeonRed

// ---- Text ------------------------------------------------------------------

val OnDark = Color(0xFFF2F8FC)
val OnDarkMuted = Color(0xFF93A4B4)

val CardTextPrimary = Color(0xFFF2F8FC)
val CardTextMuted = Color(0xFF93A4B4)
val CardTextDim = Color(0xFF5A6875)

// ---- Console surfaces ------------------------------------------------------

/** Translucent console panel, top of the vertical gradient. */
val PanelTop = Color(0xB310141B)
/** Same panel, bottom: slightly more opaque so text always sits on enough ink. */
val PanelBottom = Color(0xE607090C)
/** Sub-containers inside a panel (IP pill, speed strip, meta strip). */
val CardSubSurface = Color(0xFF0B0E14)

// Back-compat aliases.
val CardSurfaceTop = PanelTop
val CardSurfaceBottom = PanelBottom

// ---- Hairline neon edges ---------------------------------------------------

/** Standard 1px edge on panels: faint cyan. */
val EdgeNeon = Color(0x2935E0FF)
/** Brighter edge for elevated surfaces (power orb ring, drawer). */
val EdgeNeonBright = Color(0x4035E0FF)
/** Pure-white hairline used inside glass chips. */
val GlassEdge = Color(0x30FFFFFF)
val GlassEdgeBright = Color(0x47FFFFFF)

// ---- Blueprint grid backdrop ----------------------------------------------

/** Grid lines over the void. Alpha-first so it stays whisper-quiet. */
val GridLine = Color(0x1135E0FF)
/** Corner glow pools behind everything. */
val GlowPoolCyan = Color(0xFF1E6E85)
val GlowPoolViolet = Color(0xFF4A3B8C)

// ---- Liquid-glass fills -----------

val GlassFillTop = Color(0x26FFFFFF)
val GlassFillBottom = Color(0x0DFFFFFF)
val GlassSheenTop = Color(0x17FFFFFF)

/** Drawer surface: nearly opaque black so logs stay readable. */
val DrawerGlass = Color(0xF5030508)
/** Bottom-sheet surface, slightly more opaque for legibility. */
val SheetGlass = Color(0xFA070A0F)
/** Small circular chips behind the top-bar icons. */
val ChipFillTop = Color(0x21FFFFFF)
val ChipFillBottom = Color(0x0FFFFFFF)

// ---- Latency badges --------------------------------------------------------

fun latencyColor(ms: Long): Color = when {
    ms < 0 -> CardTextDim
    ms < 90 -> NeonMint
    ms < 220 -> NeonAmber
    else -> NeonRed
}
