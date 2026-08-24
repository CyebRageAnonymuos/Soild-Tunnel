package com.soildtunnel.app.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.soildtunnel.app.R
import com.soildtunnel.app.core.ShareBridge
import com.soildtunnel.app.model.ConnectionProfile
import com.soildtunnel.app.model.ConnectionState
import com.soildtunnel.app.model.isConnected

/**
 * Collapsible "Share VPN" card in the drawer.
 *
 * Turns the phone into a proxy gateway for the laptop / another phone on the
 * same Wi-Fi or hotspot: shows the exact `ip:port` values to type into the
 * other device's proxy settings, each with a one-tap copy button.
 */
@Composable
fun SharePanel(
    state: ConnectionState,
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f, tween(300), label = "shareArrow")
    val shareActive by ShareBridge.active.collectAsState()
    // Show the ACTUAL bound ports (fixed standard ports; null while a listener is
    // busy), so the values on screen always match what the bridge listens on.
    val socksPort by ShareBridge.socksPort.collectAsState()
    val httpPort by ShareBridge.httpPort.collectAsState()

    // Re-resolve the LAN IP whenever the panel opens or connectivity flips.
    val lanIp = remember(expanded, shareActive, state.isConnected) { ShareBridge.lanAddress() }

    // Self-healing: whenever the panel is composed while connected with the
    // toggle on but the bridge not running yet, (re)start it. start is
    // asynchronous and thread-safe, so this can never block the UI.
    LaunchedEffect(state.isConnected, profile.lanShare, shareActive) {
        if (state.isConnected && profile.lanShare && !shareActive) {
            withContext(Dispatchers.IO) { ShareBridge.start() }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.WifiTethering,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.share_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.share_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(arrowRotation),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                    Spacer(Modifier.height(4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.share_toggle),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.share_toggle_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = profile.lanShare,
                            onCheckedChange = { on ->
                                onProfileChange(profile.copy(lanShare = on))
                                // Take effect immediately for the current session
                                // (the service also honours the flag on connect).
                                if (state.isConnected) {
                                    if (on) ShareBridge.start() else ShareBridge.stop()
                                }
                            },
                        )
                    }

                    when {
                        !state.isConnected -> InfoText(stringResource(R.string.share_need_connect))
                        !profile.lanShare -> Unit
                        lanIp == null -> InfoText(stringResource(R.string.share_need_wifi))
                        shareActive -> {
                            InfoText(stringResource(R.string.share_howto))
                            Spacer(Modifier.height(8.dp))
                            EndpointRow(
                                label = stringResource(R.string.share_http_label),
                                value = "$lanIp:${httpPort ?: ShareBridge.HTTP_SHARE_PORT}",
                            )
                            EndpointRow(
                                label = stringResource(R.string.share_socks_label),
                                value = "$lanIp:${socksPort ?: ShareBridge.SOCKS_SHARE_PORT}",
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.share_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                            )
                        }
                        // Bridge is starting (or failed to bind): never leave
                        // the panel blank.
                        else -> InfoText(stringResource(R.string.share_starting))
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun EndpointRow(label: String, value: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                // BiDi fix: ip:port must always render LTR, even in RTL locale.
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(value))
                Toast.makeText(context, R.string.share_copied, Toast.LENGTH_SHORT).show()
            },
        ) {
            Icon(
                Icons.Rounded.ContentCopy,
                contentDescription = stringResource(R.string.share_copy),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
