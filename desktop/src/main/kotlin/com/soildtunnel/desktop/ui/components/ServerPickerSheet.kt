package com.soildtunnel.desktop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import com.soildtunnel.desktop.core.NetProbe
import com.soildtunnel.desktop.core.ServerCatalog
import com.soildtunnel.desktop.core.ServerNode
import com.soildtunnel.desktop.core.ServerPinger
import com.soildtunnel.desktop.model.ConnectionProfile
import com.soildtunnel.desktop.ui.common.tr
import com.soildtunnel.desktop.ui.common.trF
import com.soildtunnel.desktop.ui.theme.CardSubSurface
import com.soildtunnel.desktop.ui.theme.CardTextDim
import com.soildtunnel.desktop.ui.theme.CardTextMuted
import com.soildtunnel.desktop.ui.theme.CardTextPrimary
import com.soildtunnel.desktop.ui.theme.EdgeNeon
import com.soildtunnel.desktop.ui.theme.NeonCyan
import com.soildtunnel.desktop.ui.theme.NeonMint
import com.soildtunnel.desktop.ui.theme.SheetGlass
import com.soildtunnel.desktop.ui.theme.latencyColor

/** Server list bottom sheet with live ping badges. */
@Composable
fun ServerPickerSheet(
    profile: ConnectionProfile,
    onSelect: (ServerNode) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = ServerCatalog.selectedIn(profile)
    val scope = rememberCoroutineScope()
    val sweeping by ServerPinger.sweeping.collectAsState()

    // Measure as soon as the console opens — one parallel sweep, ~2.5s max.
    LaunchedEffect(Unit) { ServerPinger.maybeAutoRefresh() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                color = SheetGlass,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                modifier = modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 26.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        NeonSectionLabel(tr("server_pick_title"), accent = NeonCyan)
                        Spacer(Modifier.weight(1f))
                        RefreshButton(sweeping = sweeping) {
                            scope.launch { ServerPinger.refreshAll() }
                        }
                    }
                    Text(
                        text = tr("server_pick_subtitle"),
                        fontSize = 12.sp,
                        color = CardTextMuted,
                        modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                    )

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.height(430.dp),
                    ) {
                        itemsIndexed(ServerCatalog.all, key = { _, node -> node.id }) { _, node ->
                            ServerRow(
                                node = node,
                                selected = selected?.id == node.id,
                                onSelect = {
                                    onSelect(node)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// rows

@Composable
private fun ServerRow(
    node: ServerNode,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val border by animateColorAsState(
        targetValue = if (selected) NeonMint.copy(alpha = 0.65f) else EdgeNeon,
        animationSpec = tween(250),
        label = "rowBorder",
    )
    val isAuto = node.id == ServerCatalog.AUTO_ID

    // Live detection: whatever datacenter the last probe ACTUALLY landed on,
    // straight from Cloudflare's own trace. Static label is the fallback.
    val results by ServerPinger.state.collectAsState()
    val result = results[node.id] ?: ServerPinger.Result()
    val liveName = result.countryName
    val flag = if (liveName != null) NetProbe.flagEmoji(result.countryCode) else ""
    val title = if (!isAuto && liveName != null) liveName else node.name
    // The measured colo code is more truthful than the static catalog code.
    val codeLabel = when {
        isAuto -> node.code
        result.colo != null -> result.colo
        else -> node.code
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(color = CardSubSurface, shape = shape)
            .border(1.dp, border, shape)
            .clickable(onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        NodeGlyph(isAuto = isAuto, selected = selected)

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (flag.isNotEmpty()) {
                    Text(
                        text = flag,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = title,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    letterSpacing = 1.2.sp,
                    color = if (selected) NeonMint else CardTextPrimary,
                )
                if (!isAuto) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = codeLabel,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = CardTextDim,
                        style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr),
                    )
                }
            }
            Text(
                text = if (isAuto) tr("server_auto_desc") else node.rangeSpec,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = CardTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
            )
        }

        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = tr("server_selected"),
                tint = NeonMint,
                modifier = Modifier.size(18.dp),
            )
        }
        PingBadge(nodeId = node.id)
    }
}

/** Round glyph: bolt for Auto, LED for regular nodes. */
@Composable
private fun NodeGlyph(isAuto: Boolean, selected: Boolean) {
    val tint = if (selected || isAuto) NeonMint else NeonCyan
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .background(tint.copy(alpha = 0.10f), CircleShape)
            .border(1.dp, tint.copy(alpha = 0.35f), CircleShape),
    ) {
        if (isAuto) {
            Icon(
                imageVector = Icons.Rounded.Bolt,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(17.dp),
            )
        } else {
            LedDot(color = tint, size = 22.dp, glowing = true)
        }
    }
}

/** Live latency badge: measuring dots, N/A, or color-coded ms. */
@Composable
private fun PingBadge(nodeId: String) {
    val results by ServerPinger.state.collectAsState()
    val result = results[nodeId] ?: ServerPinger.Result()

    val breath = if (result.measuring) {
        val transition = rememberInfiniteTransition(label = "pingBadge")
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(700, easing = LinearEasing),
                RepeatMode.Reverse,
            ),
            label = "pingBreath",
        ).value
    } else 1f

    val text = when {
        result.measuring -> tr("ping_measuring_short")
        result.ms >= 0 -> trF("server_ping_ms", result.ms)
        result.at > 0L -> tr("ping_unavailable")
        else -> "\u2014"
    }
    val color = when {
        result.measuring -> NeonCyan
        result.ms >= 0 -> latencyColor(result.ms)
        result.at > 0L -> CardTextDim
        else -> CardTextDim
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .border(1.dp, color.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
            .alpha(breath)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = color,
            style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr),
        )
    }
}

@Composable
private fun RefreshButton(sweeping: Boolean, onClick: () -> Unit) {
    val rotation = if (sweeping) {
        val transition = rememberInfiniteTransition(label = "refreshSpin")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
            label = "refreshRotation",
        ).value
    } else 0f

    Icon(
        imageVector = Icons.Rounded.Refresh,
        contentDescription = tr("server_ping_refresh"),
        tint = if (sweeping) NeonCyan else CardTextMuted,
        modifier = Modifier
            .size(40.dp)
            .padding(8.dp)
            .rotate(rotation)
            .clickable(onClick = onClick),
    )
}
