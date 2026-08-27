package com.soildtunnel.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

enum class ButtonMode { IDLE, BUSY, CONNECTED, ERROR }

/**
 * Main action button.
 *
 * Layers back to front: halo, tick ring, telemetry ring (dashed + busy arc),
 * corner brackets, tappable disc with icon. Animations are conditionally
 * composed: idle = static, busy = spinning glyph + arc, connected = breathing
 * halo, error = static red.
 */
@Composable
fun ConnectButton(
    mode: ButtonMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when (mode) {
        ButtonMode.IDLE -> NeonTokens.idle
        ButtonMode.BUSY -> NeonTokens.busy
        ButtonMode.CONNECTED -> NeonTokens.connected
        ButtonMode.ERROR -> NeonTokens.error
    }
    val animatedAccent by animateColorAsState(accent, tween(600), label = "accent")

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(150),
        label = "pressScale",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(HALO)
            .scale(pressScale),
    ) {
        CoreHalo(accent = animatedAccent, pulsing = mode == ButtonMode.CONNECTED)

        // Static radar tick ring — drawn once, never invalidates.
        Canvas(modifier = Modifier.size(TICK_RING)) { drawTickRing(animatedAccent) }

        // Telemetry ring: dashed base + travelling arc while busy.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(RING),
        ) {
            Canvas(modifier = Modifier.size(RING)) { drawDashedRing() }
            if (mode == ButtonMode.BUSY) {
                BusyArc(accent = animatedAccent)
            }
        }

        // Corner targeting brackets around the whole stage.
        Box(
            modifier = Modifier
                .size(BRACKETS)
                .neonBrackets(
                    color = animatedAccent.copy(alpha = if (mode == ButtonMode.IDLE) 0.45f else 0.8f),
                    length = 18.dp,
                    inset = 2.dp,
                    strokeWidth = 2.dp,
                ),
        )

        // The tappable disc — liquid glass effect.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(DISC)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            animatedAccent.copy(alpha = 0.30f),
                            DISC_BASE,
                        ),
                    ),
                    shape = CircleShape,
                )
                .border(1.5.dp, animatedAccent.copy(alpha = 0.55f), CircleShape)
                .border(6.dp, animatedAccent.copy(alpha = 0.08f), CircleShape)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                ),
        ) {
            // Glass sheen overlay — top highlight for depth.
            Box(
                modifier = Modifier
                    .size(DISC)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.12f),
                                Color.Transparent,
                            ),
                            startY = 0f,
                            endY = DISC.value * 0.45f,
                        ),
                        shape = CircleShape,
                    ),
            )
            val tint = if (mode == ButtonMode.CONNECTED) DEEP_TEXT else animatedAccent
            val glyph: ImageVector = when (mode) {
                ButtonMode.BUSY -> Icons.Rounded.Autorenew
                else -> Icons.Rounded.PowerSettingsNew
            }
            if (mode == ButtonMode.BUSY) {
                SpinningGlyph(glyph, tint)
            } else {
                Icon(
                    imageVector = glyph,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(GLYPH),
                )
            }
        }
    }
}

// layers

@Composable
private fun CoreHalo(accent: Color, pulsing: Boolean) {
    if (!pulsing) {
        Canvas(modifier = Modifier.size(HALO)) { drawHalo(accent, 1f) }
        return
    }
    val breath = rememberInfiniteTransition(label = "coreHalo").animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )
    Canvas(modifier = Modifier.size(HALO)) { drawHalo(accent, breath.value) }
}

private fun DrawScope.drawHalo(accent: Color, scale: Float) {
    val radius = size.minDimension / 2f * scale
    // Primary glow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.40f), Color.Transparent),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = radius,
        ),
        radius = radius,
    )
    // Secondary inner glow for glass depth
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.06f), Color.Transparent),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = radius * 0.55f,
        ),
        radius = radius * 0.55f,
    )
}

/** 60 ticks; every 5th is a long major tick. */
private fun DrawScope.drawTickRing(accent: Color) {
    val stroke = 1.5.dp.toPx()
    val outer = size.minDimension / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    val minorInner = outer - 7.dp.toPx()
    val majorInner = outer - 13.dp.toPx()
    for (i in 0 until TICK_COUNT) {
        val angle = Math.PI * 2.0 * i / TICK_COUNT - Math.PI / 2.0
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()
        val major = i % MAJOR_EVERY == 0
        val inner = if (major) majorInner else minorInner
        drawLine(
            color = if (major) accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.14f),
            start = Offset(center.x + dx * inner, center.y + dy * inner),
            end = Offset(center.x + dx * outer, center.y + dy * outer),
            strokeWidth = if (major) stroke * 1.4f else stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** Thin dashed circle hugging the inside of the tick ring. Static draw. */
private fun DrawScope.drawDashedRing() {
    val sweep = 360f / DASH_COUNT
    val style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
    val dash = sweep * 0.45f
    for (i in 0 until DASH_COUNT) {
        drawArc(
            color = Color.White.copy(alpha = 0.16f),
            startAngle = i * sweep,
            sweepAngle = dash,
            useCenter = false,
            style = style,
        )
    }
}

/** Travelling progress arc hugging the telemetry ring. */
@Composable
private fun BusyArc(accent: Color) {
    val rotation = rememberInfiniteTransition(label = "coreBusy").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing)),
        label = "rotation",
    )
    Canvas(
        modifier = Modifier
            .size(RING)
            .rotate(rotation.value),
    ) {
        drawArc(
            color = accent,
            startAngle = -90f,
            sweepAngle = 80f,
            useCenter = false,
            style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun SpinningGlyph(glyph: ImageVector, tint: Color) {
    val rotation = rememberInfiniteTransition(label = "coreSpin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing)),
        label = "spin",
    )
    Icon(
        imageVector = glyph,
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .size(GLYPH)
            .rotate(rotation.value),
    )
}

// tokens

/** Mode colours, centralised so HomeScreen and this file stay in sync. */
private object NeonTokens {
    val idle = Color(0xFF35E0FF)
    val busy = Color(0xFF35E0FF)
    val connected = Color(0xFF3DFFC8)
    val error = Color(0xFFFF4D6F)
}

private val HALO = 250.dp
private val TICK_RING = 206.dp
private val RING = 178.dp
private val BRACKETS = 236.dp
private val DISC = 142.dp
private val GLYPH = 54.dp
private const val TICK_COUNT = 60
private const val MAJOR_EVERY = 5
private const val DASH_COUNT = 48
private val DISC_BASE = Color(0xFF05070B)
private val DEEP_TEXT = Color(0xFF062B24)
