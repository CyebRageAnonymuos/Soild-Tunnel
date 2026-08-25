package com.soildtunnel.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.soildtunnel.app.ui.theme.GlowPoolViolet
import com.soildtunnel.app.ui.theme.GridLine

/**
 * The control-room backdrop.
 *
 * Three static layers, back to front:
 *
 * 1. VOID      — near-black base with a whisper of blue;
 * 2. GRID      — a faint phosphor-blueprint grid (the console feel);
 * 3. GLOW POOLS— one cyan pool top-left, one violet pool bottom-right; while
 *                the tunnel is up the pools warm into mint/cyan so the whole
 *                room lights up with the connection.
 *
 * Same performance contract as every backdrop before it: NOTHING animates.
 * Everything is drawn once per size/accent change inside drawBehind — no
 * Canvas node, no recomposition, no frame callbacks.
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
                drawRect(VOID)
                drawGrid()
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(primaryGlow.copy(alpha = 0.14f), Color.Transparent),
                        center = Offset(-size.width * 0.10f, -size.height * 0.05f),
                        radius = size.maxDimension * 0.95f,
                    ),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(VIOLET_GLOW.copy(alpha = 0.12f), Color.Transparent),
                        center = Offset(size.width * 1.05f, size.height * 0.95f),
                        radius = size.maxDimension * 0.85f,
                    ),
                )
            },
    )
}

/** Blueprint grid: hairline verticals + horizontals on a fixed pitch. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid() {
    val step = GRID_STEP.toPx()
    if (step <= 0f) return
    val color = GridLine
    var x = 0f
    while (x <= size.width) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), 1f)
        x += step
    }
    var y = 0f
    while (y <= size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), 1f)
        y += step
    }
}

private val VOID = Color(0xFF030408)
private val ACTIVE_GLOW = Color(0xFF2BE8C0)
private val VIOLET_GLOW = GlowPoolViolet
private val GRID_STEP = 44.dp
