package com.soildtunnel.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The app backdrop: a deep navy sky with two soft colour pools.
 *
 * 1.3 LIQUID GLASS: glass only reads as glass when there is something behind
 * it, so the flat fill from the early perf fix grew two large radial gradients
 * back - a cool blue pool top-left and a violet one bottom-right; while the
 * tunnel is up the blue shifts to brand mint so the whole screen warms with
 * the connection.
 *
 * The same lesson stands: NOTHING animates here. The gradients are drawn once
 * per size/accent change inside drawBehind (no Canvas node, no recomposition,
 * no frame callbacks). A static draw like this costs effectively nothing after
 * the first frame, unlike the old infinite-transition aurora.
 */
@Composable
fun AmbientBackground(
    accent: Color,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val primaryGlow = if (active) ACTIVE_GLOW else accent
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(BACKDROP)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(primaryGlow.copy(alpha = 0.13f), Color.Transparent),
                        center = Offset(-size.width * 0.10f, -size.height * 0.05f),
                        radius = size.maxDimension * 0.95f,
                    ),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(VIOLET_GLOW.copy(alpha = 0.11f), Color.Transparent),
                        center = Offset(size.width * 1.05f, size.height * 0.95f),
                        radius = size.maxDimension * 0.85f,
                    ),
                )
            },
    )
}

/** The flat app background colour: pure black, so glass reads as glass. */
private val BACKDROP = Color(0xFF010103)

/** Bottom-right pool; a fixed violet keeps both corners distinct. */
private val VIOLET_GLOW = Color(0xFF7C4DFF)

/** Mint pool used instead of the idle blue while the tunnel is connected. */
private val ACTIVE_GLOW = Color(0xFF00E5A8)
