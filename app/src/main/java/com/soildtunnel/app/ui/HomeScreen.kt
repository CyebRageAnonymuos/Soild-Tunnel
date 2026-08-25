package com.soildtunnel.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.soildtunnel.app.R
import com.soildtunnel.app.core.IpEndpoint
import com.soildtunnel.app.core.NetProbe
import com.soildtunnel.app.core.ServerCatalog
import com.soildtunnel.app.core.ServerPinger
import com.soildtunnel.app.core.UpdateChecker
import com.soildtunnel.app.model.ConnectionProfile
import com.soildtunnel.app.model.ConnectionState
import com.soildtunnel.app.model.isBusy
import com.soildtunnel.app.model.isConnected
import com.soildtunnel.app.ui.components.AmbientBackground
import com.soildtunnel.app.ui.components.ButtonMode
import com.soildtunnel.app.ui.components.ConnectButton
import com.soildtunnel.app.ui.components.ConnectionCard
import com.soildtunnel.app.ui.components.DiagnosticsPanel
import com.soildtunnel.app.ui.components.ServerPickerSheet
import com.soildtunnel.app.ui.components.glassChip
import com.soildtunnel.app.ui.theme.CardSubSurface
import com.soildtunnel.app.ui.theme.CardTextDim
import com.soildtunnel.app.ui.theme.CardTextMuted
import com.soildtunnel.app.ui.theme.CardTextPrimary
import com.soildtunnel.app.ui.theme.EdgeNeon
import com.soildtunnel.app.ui.theme.EdgeNeonBright
import com.soildtunnel.app.ui.theme.NeonAmber
import com.soildtunnel.app.ui.theme.NeonCyan
import com.soildtunnel.app.ui.theme.NeonMint
import com.soildtunnel.app.ui.theme.DrawerGlass
import com.soildtunnel.app.ui.theme.SheetGlass
import com.soildtunnel.app.ui.theme.latencyColor

@OptIn(ExperimentalMaterial3Api::class)
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

    val context = LocalContext.current

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val drawerVisible = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open

    // Advanced settings, reachable directly from the home screen (top-right).
    var showAdvancedSheet by remember { mutableStateOf(false) }
    val advancedSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // The server console.
    var showServerSheet by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf(UpdateChecker.getCachedResult()) }
    val settingsEnabled = state is ConnectionState.Idle || state is ConnectionState.Error

    // Kick off a background sweep when the screen first appears; TTL keeps
    // it from hammering the network if data is still fresh.
    LaunchedEffect(Unit) { ServerPinger.maybeAutoRefresh() }
    LaunchedEffect(Unit) { updateResult = UpdateChecker.checkIfNeeded() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // CONTROL ROOM: the drawer is a near-black console pane with a
            // neon hairline, floating over the blueprint backdrop.
            ModalDrawerSheet(
                drawerContainerColor = DrawerGlass,
                modifier = Modifier.fillMaxWidth(0.9f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                ) {
                    // Brand header: logo + name + console tagline.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.ic_logo),
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                ),
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                text = stringResource(R.string.tagline),
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

                    if (drawerVisible) {
                        DiagnosticsPanel()

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

                        AboutPanel()
                    }
                }
            }
        },
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            AmbientBackground(accent = accent, active = state.isConnected)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                // Console brand block.
                Spacer(Modifier.height(26.dp))
                Image(
                    painter = painterResource(R.drawable.ic_logo),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                // Update available banner
                if (updateResult.hasUpdate) {
                    Spacer(Modifier.height(14.dp))
                    UpdateBanner(
                        version = updateResult.latestVersion,
                        onClick = {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(updateResult.downloadUrl),
                                )
                            )
                        },
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
                onClick = { drawerScope.launch { drawerState.open() } },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
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
                        contentDescription = stringResource(R.string.menu_open),
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
                    .statusBarsPadding()
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
                        contentDescription = stringResource(R.string.advanced_open),
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(21.dp),
                    )
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
        ModalBottomSheet(
            onDismissRequest = { showAdvancedSheet = false },
            sheetState = advancedSheetState,
            containerColor = SheetGlass,
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
                    .navigationBarsPadding()
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
        selected == null -> stringResource(R.string.endpoint_range_custom_short)
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
            text = stringResource(R.string.server_title).uppercase(),
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
        if (selected != null && !isAuto) {
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
        result.measuring -> stringResource(R.string.ping_measuring_short)
        result.ms >= 0 -> stringResource(R.string.server_ping_ms, result.ms)
        result.at > 0L -> stringResource(R.string.ping_unavailable)
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
    is ConnectionState.Idle -> stringResource(R.string.state_idle)
    is ConnectionState.Launching -> stringResource(R.string.state_launching)
    is ConnectionState.Connecting -> stringResource(R.string.state_connecting)
    is ConnectionState.Verifying -> stringResource(R.string.state_verifying)
    is ConnectionState.Connected -> stringResource(R.string.state_connected)
    is ConnectionState.Reconnecting -> stringResource(R.string.state_reconnecting)
    is ConnectionState.Disconnecting -> stringResource(R.string.state_disconnecting)
    is ConnectionState.Error -> stringResource(R.string.state_error)
}

@Composable
private fun stateSubtitle(state: ConnectionState): String = when (state) {
    is ConnectionState.Idle -> stringResource(R.string.tap_to_connect)
    // The exit IP + flag is shown inside the card, so keep the subtitle generic
    // instead of leaking the internal 127.0.0.1:port address.
    is ConnectionState.Connected -> stringResource(R.string.tap_to_disconnect)
    is ConnectionState.Reconnecting ->
        stringResource(R.string.reconnect_attempt, state.attempt, state.maxAttempts)
    is ConnectionState.Error -> state.message
    else -> stringResource(R.string.tap_to_disconnect)
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
            imageVector = Icons.Rounded.SystemUpdate,
            contentDescription = null,
            tint = NeonAmber,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.update_available),
                style = MaterialTheme.typography.labelLarge,
                color = CardTextPrimary,
            )
            Text(
                text = stringResource(R.string.update_version, version),
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
