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
import androidx.compose.material.icons.rounded.Bolt
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

enum class ButtonMode { IDLE, BUSY, CONNECTED, ERROR }

/**
 * The centrepiece action, rebuilt for the liquid-glass system (1.3).
 *
 * Three stacked layers, back to front:
 *
 * 1. HALO       - a soft radial bloom of the mode colour behind everything;
 * 2. GLASS RING - a floating translucent ring (glassPane recipe: fill + sheen
 * + bright rim), which is what makes the orb read as glass;
 * 3. DISC       - the tappable core: an accent-tinted pool holding the power
 * glyph, plus a travelling arc while connecting.
 *
 * PERFORMANCE CONTRACT (this app has history - see AmbientBackground): infinite
 * animations live inside [OrbHalo], [BusyArc] and [SpinningGlyph], each of which
 * is only COMPOSED while its state actually runs them. An idle or errored orb
 * subscribes to zero frame callbacks; a connected one breathes the halo only.
 */
@Composable
fun ConnectButton(
    mode: ButtonMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when (mode) {
        ButtonMode.IDLE -> Color(0xFF5B9BFF)
        ButtonMode.BUSY -> Color(0xFF5B9BFF)
        ButtonMode.CONNECTED -> Color(0xFF32E0C4)
        ButtonMode.ERROR -> Color(0xFFFF5C7A)
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
        OrbHalo(accent = animatedAccent, pulsing = mode == ButtonMode.CONNECTED)

        // The floating glass ring.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(RING)
                .glassChip(),
        ) {
            if (mode == ButtonMode.BUSY) {
                BusyArc(accent = animatedAccent)
            }
        }

        // The tappable disc.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(DISC)
                .background(
                    brush = Brush.verticalGradient(
                        listOf(animatedAccent.copy(alpha = 0.32f), DISC_BASE),
                    ),
                    shape = CircleShape,
                )
                .border(1.dp, animatedAccent.copy(alpha = 0.35f), CircleShape)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                ),
        ) {
            val tint = if (mode == ButtonMode.CONNECTED) DEEP_TEXT else animatedAccent
            val glyph = when (mode) {
                ButtonMode.CONNECTED -> Icons.Rounded.Bolt
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

// ------------------------------------------------------------------ layers

@Composable
private fun OrbHalo(accent: Color, pulsing: Boolean) {
    if (!pulsing) {
        Canvas(modifier = Modifier.size(HALO)) { drawHalo(accent, 1f) }
        return
    }
    val breath = rememberInfiniteTransition(label = "orbHalo").animateFloat(
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
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.40f), Color.Transparent),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = radius,
        ),
        radius = radius,
    )
}

/** Travelling progress arc hugging the inside of the glass ring. */
@Composable
private fun BusyArc(accent: Color) {
    val rotation = rememberInfiniteTransition(label = "orbBusy").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing)),
        label = "rotation",
    )
    Canvas(modifier = Modifier.size(RING - 10.dp).rotate(rotation.value)) {
        drawArc(
            color = accent,
            startAngle = -90f,
            sweepAngle = 90f,
            useCenter = false,
            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun SpinningGlyph(glyph: ImageVector, tint: Color) {
    val rotation = rememberInfiniteTransition(label = "orbSpin").animateFloat(
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

// ------------------------------------------------------------------ tokens

private val HALO = 240.dp
private val RING = 184.dp
private val DISC = 148.dp
private val GLYPH = 56.dp
private val DISC_BASE = Color(0xFF0A0A10)
private val DEEP_TEXT = Color(0xFF062B24)
