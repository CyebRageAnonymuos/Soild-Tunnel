package com.soildtunnel.desktop.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.soildtunnel.desktop.ui.theme.GlowPoolViolet
import com.soildtunnel.desktop.ui.theme.GridLine

/** Static backdrop: void base + grid lines + two glow pools. */
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
