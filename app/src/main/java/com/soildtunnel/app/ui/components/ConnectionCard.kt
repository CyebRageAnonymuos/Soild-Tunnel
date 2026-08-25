package com.soildtunnel.app.ui.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.sin
import kotlinx.coroutines.delay
import com.soildtunnel.app.R
import com.soildtunnel.app.core.EngineMeta
import com.soildtunnel.app.core.HevTunnel
import com.soildtunnel.app.core.IpEndpoint
import com.soildtunnel.app.core.NetProbe
import com.soildtunnel.app.core.PingMonitor
import com.soildtunnel.app.core.ShareBridge
import com.soildtunnel.app.ui.theme.CardSubSurface
import com.soildtunnel.app.ui.theme.CardTextDim
import com.soildtunnel.app.ui.theme.CardTextMuted
import com.soildtunnel.app.ui.theme.CardTextPrimary
import com.soildtunnel.app.ui.theme.NeonCyan
import com.soildtunnel.app.ui.theme.NeonMint
import com.soildtunnel.app.ui.theme.NeonRed
import com.soildtunnel.app.ui.theme.NeonViolet

/**
 * THE telemetry console — bottom block of the home screen.
 *
 * One cohesive control-room console instead of a pile of floating surfaces,
 * with a fixed vertical hierarchy:
 *
 *  0. header        ("TELEMETRY" label + status LED)
 *  1. status        (large state word in the mode accent)
 *  2. session timer ("CONNECTED FOR" + HH:MM:SS in a mono face)
 *  3. server IP pill(label + country flag + address)
 *  4. speed strip   (live down/up rate and session totals)
 *  5. meta strip    (Protocol | Endpoint | Latency, three equal columns)
 *
 * While connected, the console edge carries the travelling equaliser light
 * show (cyan → mint → violet bands). Implementation notes, because this app
 * has a history of animations eating the frame budget (see AmbientBackground):
 *  - ONE closed path, measured once per size change and cached;
 *  - animation state is read inside the draw lambda, so a frame costs a border
 *    redraw only — never a recomposition of the card;
 *  - the infinite transition is composed ONLY while connected;
 *  - each band is three strokes (halo, mid, core) in additive blend.
 */
@Composable
fun ConnectionCard(
    connected: Boolean,
    statusTitle: String,
    statusCaption: String,
    connectedSince: Long?,
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
    error: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = when {
        error -> ERROR_ACCENT
        connected -> NeonMint
        else -> IDLE_ACCENT
    }

    // Only alive while connected: no frame subscription when there is nothing
    // to show off.
    val pulse = if (connected) rememberGlowPulse() else null

    Box(
        modifier = modifier
            .fillMaxWidth()
            .neonPanel(CARD_SHAPE, edge = accent.copy(alpha = 0.28f))
            .glassEdge(accent = accent, pulse = pulse)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ConsoleHeader(connected = connected, error = error)
            StatusBlock(title = statusTitle, caption = statusCaption, accent = accent)
            TimerBlock(connectedSince = connectedSince, connected = connected)
            ServerIpPill(connected = connected, ipInfo = ipInfo, ipLoading = ipLoading)
            SpeedStrip(connectedSince = connectedSince, connected = connected)
            ProtocolStrip(connected = connected)
        }
    }
}

// --------------------------------------------------------------- 0. header

@Composable
private fun ConsoleHeader(connected: Boolean, error: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LedDot(
            color = when {
                error -> NeonRed
                connected -> NeonMint
                else -> NeonCyan
            },
            glowing = true,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.console_label),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            ),
            color = CardTextDim,
        )
    }
}

// --------------------------------------------------------------- 1. status

@Composable
private fun StatusBlock(title: String, caption: String, accent: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            targetState = title,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "cardStatus",
        ) { value ->
            Text(
                text = value,
                fontSize = 30.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                color = accent,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(4.dp))
        AnimatedContent(
            targetState = caption,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "cardCaption",
        ) { value ->
            Text(
                text = value,
                fontSize = 13.sp,
                color = CardTextMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------------------------------------------------------------- 2. timer

@Composable
private fun TimerBlock(connectedSince: Long?, connected: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedSince) {
        if (connectedSince == null) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val elapsed = if (connectedSince == null) 0L else (now - connectedSince).coerceAtLeast(0L) / 1000L
    val text = String.format(
        Locale.US,
        "%02d:%02d:%02d",
        elapsed / 3600,
        (elapsed % 3600) / 60,
        elapsed % 60,
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .background(color = CardSubSurface, shape = SUB_SHAPE)
            .subEdge(SUB_SHAPE)
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = stringResource(R.string.connected_for),
            fontSize = 10.sp,
            letterSpacing = 1.6.sp,
            fontFamily = FontFamily.Monospace,
            color = CardTextDim,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 34.sp,
            letterSpacing = 1.5.sp,
            color = if (connected) CardTextPrimary else CardTextDim,
        )
    }
}

// ------------------------------------------------------------ 3. server IP

@Composable
private fun ServerIpPill(connected: Boolean, ipInfo: IpEndpoint?, ipLoading: Boolean) {
    val label = stringResource(
        if (connected) R.string.ip_server_label else R.string.ip_your_label,
    )
    val flag = NetProbe.flagEmoji(ipInfo?.countryCode)
    val value = when {
        ipLoading && ipInfo == null -> stringResource(R.string.ip_checking)
        ipInfo != null -> ipInfo.ip
        else -> stringResource(R.string.ip_unavailable)
    }

    Row(
        modifier = Modifier
            .background(color = CardSubSurface, shape = SUB_SHAPE)
            .subEdge(SUB_SHAPE)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = label, fontSize = 12.sp, color = CardTextMuted)
        if (ipInfo != null) {
            Text(text = flag, fontSize = 15.sp)
        }
        AnimatedContent(
            targetState = value,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "cardIp",
        ) { shown ->
            Text(
                text = shown,
                // BiDi: an address is LTR technical text even in the Persian UI.
                style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Ltr),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = CardTextPrimary,
            )
        }
    }
}

// --------------------------------------------------------------- 4. speeds

@Composable
private fun SpeedStrip(connectedSince: Long?, connected: Boolean) {
    val stats = rememberTrafficStats(connectedSince = connectedSince, connected = connected)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = CardSubSurface, shape = SUB_SHAPE)
            .subEdge(SUB_SHAPE)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeedCell(
            icon = Icons.Rounded.ArrowDownward,
            tint = NeonMint,
            label = stringResource(R.string.traffic_download),
            rate = stats.downRate,
            total = stats.downTotal,
            modifier = Modifier.weight(1f),
        )
        CellDivider()
        SpeedCell(
            icon = Icons.Rounded.ArrowUpward,
            tint = NeonCyan,
            label = stringResource(R.string.traffic_upload),
            rate = stats.upRate,
            total = stats.upTotal,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SpeedCell(
    icon: ImageVector,
    tint: Color,
    label: String,
    rate: Long,
    total: Long,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.background(color = tint.copy(alpha = 0.12f), shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier
                    .padding(5.dp)
                    .size(15.dp),
            )
        }
        Column(modifier = Modifier.padding(start = 9.dp)) {
            Text(
                text = formatRate(rate),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = CardTextPrimary,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.traffic_total, formatBytes(total)),
                fontSize = 10.sp,
                color = CardTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ------------------------------------------------------------- 5. protocol

@Composable
private fun ProtocolStrip(connected: Boolean) {
    val meta by EngineMeta.state.collectAsState()
    val ping by PingMonitor.state.collectAsState()

    // Live latency, exactly like the desktop edition: one cheap TCP handshake
    // through the tunnel every few seconds, serialised by PingMonitor.
    LaunchedEffect(connected) {
        while (connected) {
            PingMonitor.pingOnce(viaTunnel = true)
            delay(LATENCY_REFRESH_MS)
        }
    }

    val dash = "\u2014"
    val protocol = if (connected) meta.protocol ?: dash else dash
    val endpoint = if (connected) meta.endpoint ?: "\u2026" else dash
    val latency = when {
        !connected -> dash
        ping.ms >= 0 -> "${ping.ms} ms"
        ping.running -> "\u2026"
        else -> dash
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = CardSubSurface, shape = SUB_SHAPE)
            .subEdge(SUB_SHAPE)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetaCell(stringResource(R.string.meta_protocol), protocol, Modifier.weight(1f))
        CellDivider()
        MetaCell(stringResource(R.string.meta_endpoint), endpoint, Modifier.weight(1f))
        CellDivider()
        MetaCell(stringResource(R.string.meta_latency), latency, Modifier.weight(1f))
    }
}

@Composable
private fun MetaCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label.uppercase(),
            fontSize = 9.sp,
            letterSpacing = 1.2.sp,
            fontFamily = FontFamily.Monospace,
            color = CardTextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = CardTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
        )
    }
}

@Composable
private fun CellDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(28.dp)
            .background(DIVIDER),
    )
}

// ------------------------------------------------------------ traffic feed

/** Instantaneous rates + session totals, polled once per second. */
private data class TrafficStats(
    val downRate: Long = 0L,
    val upRate: Long = 0L,
    val downTotal: Long = 0L,
    val upTotal: Long = 0L,
)

/**
 * Sums BOTH possible traffic paths so the meter works in every mode:
 *  - hev-socks5-tunnel's direction-corrected counters (system-VPN mode; null in
 * proxy mode, where there is no TUN),
 *  - ShareBridge: bytes relayed through the local SOCKS5/HTTP listeners (the
 * only source in proxy mode, plus LAN clients in system-VPN mode).
 *
 * Rates come from deltas against a monotonic clock, so a wall-clock jump cannot
 * invent a spike. A negative delta (core restart during auto-reconnect, or a
 * fresh sharing session resetting the bridge counters) is clamped to zero and
 * the baseline rebases itself.
 */
@Composable
private fun rememberTrafficStats(connectedSince: Long?, connected: Boolean): TrafficStats {
    var stats by remember(connectedSince) { mutableStateOf(TrafficStats()) }

    LaunchedEffect(connectedSince, connected) {
        if (!connected) return@LaunchedEffect
        var lastDown = -1L
        var lastUp = -1L
        var lastAt = 0L
        while (true) {
            val hev = HevTunnel.traffic()
            val share = ShareBridge.traffic()
            if (hev != null || ShareBridge.active.value) {
                val down = (hev?.downloadBytes ?: 0L) + share.downloadBytes
                val up = (hev?.uploadBytes ?: 0L) + share.uploadBytes
                val at = SystemClock.elapsedRealtime()
                var downRate = stats.downRate
                var upRate = stats.upRate
                if (lastAt > 0L && at > lastAt) {
                    val dt = at - lastAt
                    downRate = ((down - lastDown).coerceAtLeast(0L) * 1000L) / dt
                    upRate = ((up - lastUp).coerceAtLeast(0L) * 1000L) / dt
                }
                stats = TrafficStats(downRate, upRate, down, up)
                lastDown = down
                lastUp = up
                lastAt = at
            }
            delay(1_000L)
        }
    }

    return stats
}

// ------------------------------------------------------- the animated edge

/** The two animated states of the border light show. */
private class GlowPulse(val phase: State<Float>, val breath: State<Float>)

@Composable
private fun rememberGlowPulse(): GlowPulse {
    val transition = rememberInfiniteTransition(label = "cardGlow")
    // Travels the perimeter linearly, so the loop is seamless.
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(GLOW_TRAVEL_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    // Overall intensity, so the whole edge breathes instead of only flickering.
    val breath = transition.animateFloat(
        initialValue = 0.68f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )
    return remember(phase, breath) { GlowPulse(phase, breath) }
}

/**
 * One equaliser band: where it sits on the perimeter, how long it is, and which
 * harmonic of the travel phase drives its intensity. The harmonics are WHOLE
 * numbers on purpose - a fractional one would jump when the phase wraps from 1
 * back to 0 and the whole edge would visibly stutter once per cycle.
 */
private class GlowBand(
    val offset: Float,
    val span: Float,
    val harmonic: Int,
    val skew: Float,
    /** 0f = cyan … 1f = violet; the core mint sits between. */
    val tint: Float,
)

private val GLOW_BANDS = listOf(
    GlowBand(offset = 0.00f, span = 0.15f, harmonic = 2, skew = 0.00f, tint = 0.35f),
    GlowBand(offset = 0.13f, span = 0.08f, harmonic = 3, skew = 0.34f, tint = 0.70f),
    GlowBand(offset = 0.28f, span = 0.13f, harmonic = 5, skew = 0.11f, tint = 0.20f),
    GlowBand(offset = 0.43f, span = 0.06f, harmonic = 7, skew = 0.61f, tint = 0.95f),
    GlowBand(offset = 0.56f, span = 0.14f, harmonic = 3, skew = 0.79f, tint = 0.45f),
    GlowBand(offset = 0.70f, span = 0.09f, harmonic = 5, skew = 0.24f, tint = 0.80f),
    GlowBand(offset = 0.85f, span = 0.12f, harmonic = 2, skew = 0.50f, tint = 0.05f),
)

/**
 * The console edge: a soft inner glow, a hairline neon border, and - while
 * connected - the travelling equaliser light.
 */
private fun Modifier.glassEdge(accent: Color, pulse: GlowPulse?): Modifier = drawWithCache {
    val hairline = 1.dp.toPx()
    val inset = hairline / 2f
    val radius = CARD_RADIUS.toPx()
    val outline = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(inset, inset, size.width - inset, size.height - inset),
                cornerRadius = CornerRadius(radius),
            ),
        )
    }
    val measure = PathMeasure().apply { setPath(outline, true) }
    val perimeter = measure.length
    val band = Path()
    val innerGlow = Brush.radialGradient(
        colors = listOf(accent.copy(alpha = 0.10f), Color.Transparent),
        center = Offset(size.width / 2f, 0f),
        radius = size.width * 0.95f,
    )

    onDrawBehind {
        drawPath(outline, brush = innerGlow)
        drawPath(
            outline,
            color = accent.copy(alpha = if (pulse == null) 0.22f else 0.30f),
            style = Stroke(hairline),
        )
        if (pulse == null) return@onDrawBehind

        val phase = pulse.phase.value
        val breath = pulse.breath.value
        for (spec in GLOW_BANDS) {
            val amp = 0.5f + 0.5f * sin(TWO_PI * (spec.harmonic * phase + spec.skew))
            val length = perimeter * spec.span * (0.30f + 0.95f * amp)
            val start = ((phase + spec.offset) % 1f) * perimeter
            // Cyan → mint → violet sweep across the bands.
            val colour = when {
                spec.tint < 0.5f ->
                    lerp(NeonCyan, NeonMint, (spec.tint / 0.5f).coerceIn(0f, 1f))
                else ->
                    lerp(NeonMint, NeonViolet, ((spec.tint - 0.5f) / 0.5f).coerceIn(0f, 1f))
            }
            val width = hairline * (1.1f + 2.3f * amp)
            val alpha = (0.20f + 0.80f * amp) * breath

            band.reset()
            measure.appendSegment(band, start, length, perimeter)

            // halo -> mid -> core, additively blended: a soft bloom without a
            // blur pass or an extra layer.
            drawGlowStroke(band, colour, alpha * 0.09f, width * 4.4f)
            drawGlowStroke(band, colour, alpha * 0.26f, width * 2.1f)
            drawGlowStroke(band, colour, alpha, width)
        }
    }
}

private fun DrawScope.drawGlowStroke(path: Path, colour: Color, alpha: Float, width: Float) {
    drawPath(
        path = path,
        color = colour.copy(alpha = alpha.coerceIn(0f, 1f)),
        style = Stroke(width = width, cap = StrokeCap.Round),
        blendMode = BlendMode.Plus,
    )
}

/** Copies a piece of the perimeter, wrapping around the corner if it overruns. */
private fun PathMeasure.appendSegment(dst: Path, start: Float, length: Float, perimeter: Float) {
    val end = start + length
    if (end <= perimeter) {
        getSegment(start, end, dst, true)
    } else {
        getSegment(start, perimeter, dst, true)
        getSegment(0f, end - perimeter, dst, true)
    }
}

/** The 1px low-opacity neon rim shared by every sub-container in the console. */
private fun Modifier.subEdge(shape: CornerBasedShape): Modifier = drawWithCache {
    val hairline = 1.dp.toPx()
    val inset = hairline / 2f
    val radius = shape.topStart.toPx(size, this)
    val outline = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(inset, inset, size.width - inset, size.height - inset),
                cornerRadius = CornerRadius(radius),
            ),
        )
    }
    onDrawBehind {
        drawPath(outline, color = SUB_BORDER, style = Stroke(hairline))
    }
}

// ---------------------------------------------------------------- helpers

private fun formatBytes(v: Long): String {
    if (v < 1024L) return "$v B"
    val kb = v / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

private fun formatRate(v: Long): String = formatBytes(v) + "/s"

private val CARD_RADIUS = 24.dp
private val CARD_SHAPE = RoundedCornerShape(CARD_RADIUS)
private val SUB_SHAPE = RoundedCornerShape(14.dp)
private val SUB_BORDER = Color(0x2435E0FF)
private val DIVIDER = Color(0x1FFFFFFF)
private val IDLE_ACCENT = NeonCyan
private val ERROR_ACCENT = NeonRed
private const val GLOW_TRAVEL_MS = 5_200
private const val LATENCY_REFRESH_MS = 4_000L
private const val TWO_PI = 6.2831855f
