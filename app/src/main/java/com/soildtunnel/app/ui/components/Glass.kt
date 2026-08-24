package com.soildtunnel.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.soildtunnel.app.ui.theme.GlassEdge
import com.soildtunnel.app.ui.theme.GlassEdgeBright
import com.soildtunnel.app.ui.theme.GlassFillBottom
import com.soildtunnel.app.ui.theme.GlassFillTop
import com.soildtunnel.app.ui.theme.GlassSheenTop

/**
 * The liquid glass system (1.3).
 *
 * Every "pane" in the app is built from the same three layers, so the UI reads
 * as one material instead of a pile of surfaces:
 *
 *   1. FILL   — a translucent white vertical gradient (frosted pane body);
 *   2. SHEEN  — a stop-based specular streak across the top edge, which is what
 *               sells the "glass" illusion (bright rim of light at y=0, gone by
 *               ~30% height; stop-based, so it scales to any surface size);
 *   3. RIM    — a hairline white border catching the light around the edge.
 *
 * Performance contract: pure static drawing. No blur passes, no RenderEffect,
 * no animation inside the material itself — the previous animated aurora
 * background taught us that full-screen continuous redraws eat the frame budget
 * on mid-range devices. Glass here costs two gradient fills + one border per
 * surface and never invalidates on its own.
 */

/** Glass fill for a surface sitting directly on the aurora backdrop. */
fun Modifier.glassPane(
    shape: Shape,
    brightRim: Boolean = false,
): Modifier = this
    .background(
        brush = Brush.verticalGradient(listOf(GlassFillTop, GlassFillBottom)),
        shape = shape,
    )
    .background(
        // Stop-based sheen: 100% at the top edge, fully transparent by 32%.
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0.00f to GlassSheenTop,
                0.32f to Color.Transparent,
            ),
        ),
        shape = shape,
    )
    .border(1.dp, if (brightRim) GlassEdgeBright else GlassEdge, shape)

/** A finished glass panel with rounded corners and standard inner padding. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .glassPane(shape)
            .padding(contentPadding),
        content = content,
    )
}

/** Small circular glass chip used behind standalone icons (top-bar buttons). */
fun Modifier.glassChip(): Modifier = this
    .glassPane(RoundedCornerShape(50), brightRim = true)
