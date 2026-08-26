package com.soildtunnel.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.soildtunnel.desktop.browseUrl
import com.soildtunnel.desktop.core.IpEndpoint
import com.soildtunnel.desktop.core.NetProbe
import com.soildtunnel.desktop.core.ServerCatalog
import com.soildtunnel.desktop.core.ServerPinger
import com.soildtunnel.desktop.core.UpdateChecker
import com.soildtunnel.desktop.model.ConnectionProfile
import com.soildtunnel.desktop.model.ConnectionState
import com.soildtunnel.desktop.model.isBusy
import com.soildtunnel.desktop.model.isConnected
import com.soildtunnel.desktop.ui.common.rememberLogoPainter
import com.soildtunnel.desktop.ui.common.tr
import com.soildtunnel.desktop.ui.common.trF
import com.soildtunnel.desktop.ui.components.AmbientBackground
import com.soildtunnel.desktop.ui.components.ButtonMode
import com.soildtunnel.desktop.ui.components.ConnectButton
import com.soildtunnel.desktop.ui.components.ConnectionCard
import com.soildtunnel.desktop.ui.components.DiagnosticsPanel
import com.soildtunnel.desktop.ui.components.LanguagePanel
import com.soildtunnel.desktop.ui.components.UsagePanel
import com.soildtunnel.desktop.ui.components.ServerPickerSheet
import com.soildtunnel.desktop.ui.components.glassChip
import com.soildtunnel.desktop.ui.theme.CardSubSurface
import com.soildtunnel.desktop.ui.theme.CardTextDim
import com.soildtunnel.desktop.ui.theme.CardTextMuted
import com.soildtunnel.desktop.ui.theme.CardTextPrimary
import com.soildtunnel.desktop.ui.theme.EdgeNeon
import com.soildtunnel.desktop.ui.theme.NeonAmber
import com.soildtunnel.desktop.ui.theme.NeonCyan
import com.soildtunnel.desktop.ui.theme.NeonMint
import com.soildtunnel.desktop.ui.theme.DrawerGlass
import com.soildtunnel.desktop.ui.theme.SheetGlass
import com.soildtunnel.desktop.ui.theme.latencyColor

@Composable
fun HomeScreen(
    state: ConnectionState,
    profile: ConnectionProfile,
    connectedSince: Long?,
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onToggleConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode = when {
        state.isConnected -> ButtonMode.CONNECTED
        state.isBusy -> ButtonMode.BUSY
        state is ConnectionState.Error -> ButtonMode.ERROR
        else -> ButtonMode.IDLE
    }

    val accent = when (mode) {
        ButtonMode.CONNECTED -> NeonMint
        ButtonMode.ERROR -> Color(0xFFFF4D6F)
        else -> NeonCyan
    }

    var drawerOpen by remember { mutableStateOf(false) }

    // Advanced settings, reachable directly from the home screen (top-right).
    var showAdvancedSheet by remember { mutableStateOf(false) }

    // The server console.
    var showServerSheet by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf(UpdateChecker.getCachedResult()) }
    val settingsEnabled = state is ConnectionState.Idle || state is ConnectionState.Error

    // Kick off a background sweep when the screen first appears; TTL keeps
    // it from hammering the network if data is still fresh.
    LaunchedEffect(Unit) { ServerPinger.maybeAutoRefresh() }
    LaunchedEffect(Unit) { updateResult = UpdateChecker.checkIfNeeded() }

    Box(modifier = modifier.fillMaxSize()) {
        AmbientBackground(accent = accent, active = state.isConnected)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            // Console brand block.
            Spacer(Modifier.height(26.dp))
            Image(
                painter = rememberLogoPainter(),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = tr("app_name"),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = tr("tagline"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            // Update available banner
            if (updateResult.hasUpdate) {
                Spacer(Modifier.height(14.dp))
                UpdateBanner(
                    version = updateResult.latestVersion,
                    onClick = { browseUrl(updateResult.downloadUrl) },
                    onDismiss = { updateResult = UpdateChecker.Result() },
                )
            }

            Spacer(Modifier.height(22.dp))

            // Server selector pill — the entry point to the node console.
            ServerSelectorPill(
                profile = profile,
                enabled = settingsEnabled,
                onClick = { if (settingsEnabled) showServerSheet = true },
            )

            Spacer(Modifier.height(24.dp))

            ConnectButton(mode = mode, onClick = onToggleConnection)

            Spacer(Modifier.height(26.dp))

            // Status, timer, IP, speeds and the protocol row: one unified
            // telemetry console - see ConnectionCard.
            ConnectionCard(
                connected = state.isConnected,
                statusTitle = stateTitle(state),
                statusCaption = stateSubtitle(state),
                connectedSince = connectedSince,
                ipInfo = ipInfo,
                ipLoading = ipLoading,
                error = state is ConnectionState.Error,
            )

            Spacer(Modifier.height(16.dp))
        }

        IconButton(
            onClick = { drawerOpen = true },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(42.dp)
                    .glassChip(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = tr("menu_open"),
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(21.dp),
                )
            }
        }

        // Advanced settings straight from the home screen.
        IconButton(
            onClick = { showAdvancedSheet = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(42.dp)
                    .glassChip(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = tr("advanced_open"),
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(21.dp),
                )
            }
        }

        // Backdrop scrim: tap anywhere outside the drawer to dismiss it.
        if (drawerOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f))
                    .clickable { drawerOpen = false },
            )
        }

        AnimatedVisibility(
            visible = drawerOpen,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
        ) {
            // CONTROL ROOM: the drawer is a near-black console pane with a
            // neon hairline, floating over the blueprint backdrop.
            Surface(
                color = DrawerGlass,
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.86f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {},
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                ) {
                    // Brand header: logo + name + console tagline.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = rememberLogoPainter(),
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = tr("app_name"),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                ),
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                text = tr("tagline"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(NeonCyan.copy(alpha = 0.18f)),
                    )
                    Spacer(Modifier.height(18.dp))

                    DiagnosticsPanel()

                    Spacer(Modifier.height(16.dp))

                    UsagePanel()

                    Spacer(Modifier.height(16.dp))

                    SharePanel(
                        state = state,
                        profile = profile,
                        onProfileChange = onProfileChange,
                    )

                    Spacer(Modifier.height(16.dp))

                    AdvancedPanel(
                        profile = profile,
                        onProfileChange = onProfileChange,
                        enabled = settingsEnabled,
                    )

                    Spacer(Modifier.height(16.dp))

                    LanguagePanel()

                    Spacer(Modifier.height(16.dp))

                    AboutPanel()
                }
            }
        }
    }

    if (showServerSheet) {
        ServerPickerSheet(
            profile = profile,
            onSelect = { node ->
                onProfileChange(ServerCatalog.applyTo(profile, node))
            },
            onDismiss = { showServerSheet = false },
        )
    }

    if (showAdvancedSheet) {
        Dialog(
            onDismissRequest = { showAdvancedSheet = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Surface(
                    color = SheetGlass,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            // The advanced card is much taller than a phone screen.
                            // Give the sheet a bounded viewport and scroll that viewport;
                            // otherwise Compose measures the whole card and Material's
                            // bottom sheet clips its lower controls behind the nav bar.
                            .fillMaxHeight(0.92f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 32.dp),
                    ) {
                        var sheetReady by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { sheetReady = true }
                        if (sheetReady) {
                            AdvancedPanel(
                                profile = profile,
                                onProfileChange = onProfileChange,
                                enabled = settingsEnabled,
                                startExpanded = true,
                            )
                        } else {
                            Spacer(Modifier.height(320.dp))
                        }
                    }
                }
            }
        }
    }
}

// server selector

/**
 * The compact home-screen pill: SERVER label + current node codename + its
 * live ping badge + chevron. Tapping opens the full [ServerPickerSheet] flow.
 */
@Composable
private fun ServerSelectorPill(
    profile: ConnectionProfile,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val selected = ServerCatalog.selectedIn(profile)
    val isAuto = selected?.id == ServerCatalog.AUTO_ID

    // Prefer the country the last live probe ACTUALLY landed on (Cloudflare
    // anycast reroutes all the time); fall back to the static catalog label.
    val results by ServerPinger.state.collectAsState()
    val result = selected?.let { results[it.id] } ?: ServerPinger.Result()
    val name = when {
        selected == null -> tr("endpoint_range_custom_short")
        result.countryName != null -> result.countryName
        else -> selected.name
    }
    val flag =
        if (selected != null && result.countryName != null) NetProbe.flagEmoji(result.countryCode)
        else ""

    val alpha = if (enabled) 1f else 0.55f
    val shape = RoundedCornerShape(16.dp)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .background(color = CardSubSurface, shape = shape)
            .border(1.dp, EdgeNeon, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Text(
            text = tr("server_title").uppercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp,
            color = CardTextDim,
        )
        Spacer(Modifier.width(2.dp))
        if (flag.isNotEmpty()) {
            Text(text = flag, fontSize = 13.sp)
        }
        Text(
            text = name,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            letterSpacing = 1.4.sp,
            color = if (isAuto) CardTextPrimary else NeonCyan,
        )
        if (selected != null) {
            HomePingBadge(nodeId = selected.id)
        }
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = null,
            tint = CardTextMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Lightweight copy of the picker badge for the home-screen pill. */
@Composable
private fun HomePingBadge(nodeId: String) {
    val results by ServerPinger.state.collectAsState()
    val result = results[nodeId] ?: ServerPinger.Result()

    val text = when {
        result.measuring -> tr("ping_measuring_short")
        result.ms >= 0 -> trF("server_ping_ms", result.ms)
        result.at > 0L -> tr("ping_unavailable")
        else -> "\u2014"
    }
    val color = when {
        result.measuring -> NeonCyan
        result.ms >= 0 -> latencyColor(result.ms)
        else -> CardTextDim
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .background(color = color.copy(alpha = 0.10f), shape = RoundedCornerShape(8.dp))
            .border(1.dp, color.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = color,
            style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr),
        )
    }
}

@Composable
private fun stateTitle(state: ConnectionState): String = when (state) {
    is ConnectionState.Idle -> tr("state_idle")
    is ConnectionState.Launching -> tr("state_launching")
    is ConnectionState.Connecting -> tr("state_connecting")
    is ConnectionState.Verifying -> tr("state_verifying")
    is ConnectionState.Connected -> tr("state_connected")
    is ConnectionState.Reconnecting -> tr("state_reconnecting")
    is ConnectionState.Disconnecting -> tr("state_disconnecting")
    is ConnectionState.Error -> tr("state_error")
}

@Composable
private fun stateSubtitle(state: ConnectionState): String = when (state) {
    is ConnectionState.Idle -> tr("tap_to_connect")
    // The exit IP + flag is shown inside the card, so keep the subtitle generic
    // instead of leaking the internal 127.0.0.1:port address.
    is ConnectionState.Connected -> tr("tap_to_disconnect")
    is ConnectionState.Reconnecting ->
        trF("reconnect_attempt", state.attempt, state.maxAttempts)
    is ConnectionState.Error -> state.message
    else -> tr("tap_to_disconnect")
}

@Composable
private fun UpdateBanner(
    version: String,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NeonAmber.copy(alpha = 0.10f))
            .border(1.dp, NeonAmber.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Update,
            contentDescription = null,
            tint = NeonAmber,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = tr("update_available"),
                style = MaterialTheme.typography.labelLarge,
                color = CardTextPrimary,
            )
            Text(
                text = trF("update_version", version),
                style = MaterialTheme.typography.labelSmall,
                color = NeonAmber,
            )
        }
        Text(
            text = "\u00D7",
            color = CardTextMuted,
            modifier = Modifier
                .clickable { onDismiss() }
                .padding(4.dp),
        )
    }
}
