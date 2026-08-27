package com.soildtunnel.app.ui.components

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.soildtunnel.app.R
import com.soildtunnel.app.core.NetProbe
import com.soildtunnel.app.core.ServerCatalog
import com.soildtunnel.app.core.ServerNode
import com.soildtunnel.app.core.ServerPinger
import com.soildtunnel.app.model.ConnectionProfile
import com.soildtunnel.app.ui.theme.CardSubSurface
import com.soildtunnel.app.ui.theme.CardTextDim
import com.soildtunnel.app.ui.theme.CardTextMuted
import com.soildtunnel.app.ui.theme.CardTextPrimary
import com.soildtunnel.app.ui.theme.EdgeNeon
import com.soildtunnel.app.ui.theme.NeonCyan
import com.soildtunnel.app.ui.theme.NeonMint
import com.soildtunnel.app.ui.theme.SheetGlass
import com.soildtunnel.app.ui.theme.latencyColor

/** Server list bottom sheet with live ping badges. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerPickerSheet(
    profile: ConnectionProfile,
    onSelect: (ServerNode) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selected = ServerCatalog.selectedIn(profile)
    val scope = rememberCoroutineScope()
    val sweeping by ServerPinger.sweeping.collectAsState()

    // Measure as soon as the console opens — one parallel sweep, ~2.5s max.
    LaunchedEffect(Unit) { ServerPinger.maybeAutoRefresh() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetGlass,
        modifier = modifier,
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
                NeonSectionLabel(stringResource(R.string.server_pick_title), accent = NeonCyan)
                Spacer(Modifier.weight(1f))
                RefreshButton(sweeping = sweeping) {
                    scope.launch { ServerPinger.refreshAll() }
                }
            }
            Text(
                text = stringResource(R.string.server_pick_subtitle),
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

    // Always show the static catalog name and flag. The live Cloudflare colo
    // can differ from the catalog label due to anycast routing, which confused
    // users (e.g. "Italy" showing as "Azerbaijan"). The actual exit country
    // is shown in ConnectionCard after the tunnel is established.
    val results by ServerPinger.state.collectAsState()
    val result = results[node.id] ?: ServerPinger.Result()
    val flag = if (!isAuto) NetProbe.flagEmoji(node.countryCode) else ""
    val title = node.name
    val codeLabel = node.code

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
                text = if (isAuto) stringResource(R.string.server_auto_desc) else node.rangeSpec,
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
                contentDescription = stringResource(R.string.server_selected),
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
        result.measuring -> stringResource(R.string.ping_measuring_short)
        result.ms >= 0 -> stringResource(R.string.server_ping_ms, result.ms)
        result.at > 0L -> stringResource(R.string.ping_unavailable)
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
            .background(color = color.copy(alpha = 0.10f), shape = RoundedCornerShape(8.dp))
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
        contentDescription = stringResource(R.string.server_ping_refresh),
        tint = if (sweeping) NeonCyan else CardTextMuted,
        modifier = Modifier
            .size(40.dp)
            .padding(8.dp)
            .rotate(rotation)
            .clickable(onClick = onClick),
    )
}
