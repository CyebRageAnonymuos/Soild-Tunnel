package com.soildtunnel.desktop.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.soildtunnel.desktop.core.ShareBridge
import com.soildtunnel.desktop.model.ConnectionProfile
import com.soildtunnel.desktop.model.CoreLogLevel
import com.soildtunnel.desktop.model.DnsMode
import com.soildtunnel.desktop.model.EndpointMode
import com.soildtunnel.desktop.model.IpVersion
import com.soildtunnel.desktop.model.Noize
import com.soildtunnel.desktop.model.Protocol
import com.soildtunnel.desktop.model.ScanMode
import com.soildtunnel.desktop.model.SplitMode
import com.soildtunnel.desktop.model.TeamAuth
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.soildtunnel.desktop.ui.common.DesktopToast
import com.soildtunnel.desktop.ui.common.tr
import com.soildtunnel.desktop.ui.components.DropdownSelector
import com.soildtunnel.desktop.ui.components.LtrOutlinedTextField
import com.soildtunnel.desktop.ui.components.SegmentedSelector

/**
 * Collapsible "Advanced" card exposing the full engine feature set:
 * protocol, scan mode, IP version, Amnezia-style obfuscation, endpoint
 * selection (auto / manual IP / custom range), keepalive, MTU, TLS
 * fragmentation, ECH, MASQUE-over-HTTP/2, quick reconnect, proxy mode and
 * per-app split tunneling.
 */
@Composable
fun AdvancedPanel(
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    // True when hosted in the home-screen bottom sheet, where the card should
    // open already expanded instead of requiring an extra tap.
    startExpanded: Boolean = false,
) {
    var expanded by remember { mutableStateOf(startExpanded) }
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f, tween(300), label = "arrow")

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
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "  " + tr("advanced"),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
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
                    Spacer(Modifier.height(16.dp))

                    // ---------- Core ----------
                    SettingLabel(tr("protocol"))
                    SegmentedSelector(
                        options = Protocol.entries,
                        selected = profile.protocol,
                        onSelect = { onProfileChange(profile.copy(protocol = it)) },
                        label = { protocolLabel(it) },
                        enabled = enabled,
                    )
                    Spacer(Modifier.height(16.dp))

                    SettingLabel(tr("scan_mode"))
                    DropdownSelector(
                        options = ScanMode.entries,
                        selected = profile.scanMode,
                        onSelect = { onProfileChange(profile.copy(scanMode = it)) },
                        label = { scanLabel(it) },
                        enabled = enabled,
                    )
                    Spacer(Modifier.height(16.dp))

                    SettingLabel(tr("ip_version"))
                    SegmentedSelector(
                        options = IpVersion.entries,
                        selected = profile.ipVersion,
                        onSelect = { onProfileChange(profile.copy(ipVersion = it)) },
                        label = { ipLabel(it) },
                        enabled = enabled,
                    )

                    // ---------- Transport & anti-DPI ----------
                    SectionHeader(tr("section_transport"))

                    SettingLabel(tr("noize_title"))
                    DropdownSelector(
                        options = Noize.entries,
                        selected = profile.noize,
                        onSelect = { onProfileChange(profile.copy(noize = it)) },
                        label = { noizeLabel(it) },
                        enabled = enabled,
                    )
                    HelperText(tr("noize_desc"))
                    Spacer(Modifier.height(16.dp))

                    SettingLabel(tr("endpoint_mode"))
                    SegmentedSelector(
                        options = EndpointMode.entries,
                        selected = profile.endpointMode,
                        onSelect = { onProfileChange(profile.copy(endpointMode = it)) },
                        label = { endpointLabel(it) },
                        enabled = enabled,
                    )
                    if (profile.endpointMode == EndpointMode.MANUAL_PEER) {
                        Spacer(Modifier.height(12.dp))
                        // BiDi fix: ip:port is LTR technical text — a plain
                        // OutlinedTextField scrambles typed digits in the RTL
                        // (Persian) locale. LtrOutlinedTextField pins LTR.
                        LtrOutlinedTextField(
                            value = profile.manualPeer,
                            onValueChange = { onProfileChange(profile.copy(manualPeer = it)) },
                            enabled = enabled,
                            singleLine = true,
                            label = { Text(tr("manual_peer_label")) },
                            placeholder = { Text(tr("manual_peer_hint")) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (profile.endpointMode == EndpointMode.MANUAL_RANGE) {
                        Spacer(Modifier.height(12.dp))
                        // BiDi fix: CIDR ranges are LTR technical text — this is
                        // the exact field where typed digits appeared shuffled.
                        LtrOutlinedTextField(
                            value = profile.manualRange,
                            onValueChange = { onProfileChange(profile.copy(manualRange = it)) },
                            enabled = enabled,
                            singleLine = false,
                            label = { Text(tr("manual_range_label")) },
                            placeholder = { Text(tr("manual_range_hint")) },
                            supportingText = { Text(tr("manual_range_help")) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(16.dp))

                    SettingLabel(tr("keepalive_label"))
                    DropdownSelector(
                        options = ConnectionProfile.KEEPALIVE_PRESETS,
                        selected = profile.keepalive,
                        onSelect = { onProfileChange(profile.copy(keepalive = it)) },
                        label = { if (it == 0) tr("keepalive_default") else "$it" },
                        enabled = enabled,
                    )
                    Spacer(Modifier.height(16.dp))

                    SettingLabel(tr("mtu_label"))
                    DropdownSelector(
                        options = ConnectionProfile.MTU_PRESETS,
                        selected = profile.mtu,
                        onSelect = { onProfileChange(profile.copy(mtu = it)) },
                        label = { "$it" },
                        enabled = enabled,
                    )
                    HelperText(tr("mtu_desc"))
                    Spacer(Modifier.height(8.dp))

                    Divider()

                    ToggleRow(
                        title = tr("fragment_title"),
                        description = tr("fragment_desc"),
                        checked = profile.fragment,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(fragment = it)) },
                    )
                    ToggleRow(
                        title = tr("ech_title"),
                        description = tr("ech_desc"),
                        checked = profile.ech,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(ech = it)) },
                    )
                    ToggleRow(
                        title = tr("masque_http2"),
                        description = tr("masque_http2_desc"),
                        checked = profile.masqueHttp2,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(masqueHttp2 = it)) },
                    )
                    ToggleRow(
                        title = tr("quick_reconnect"),
                        description = tr("quick_reconnect_desc"),
                        checked = profile.quickReconnect,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(quickReconnect = it)) },
                    )

                    // ---------- DNS inside the tunnel ----------
                    SettingLabel(tr("dns_label"))
                    SegmentedSelector(
                        options = DnsMode.entries,
                        selected = profile.dnsMode,
                        onSelect = { onProfileChange(profile.copy(dnsMode = it)) },
                        label = { dnsModeLabel(it) },
                        enabled = enabled,
                    )
                    if (profile.dnsMode != DnsMode.PLAIN) {
                        Spacer(Modifier.height(8.dp))
                        LtrOutlinedTextField(
                            value = profile.encryptedDnsEndpoint,
                            onValueChange = { onProfileChange(profile.copy(encryptedDnsEndpoint = it)) },
                            enabled = enabled,
                            singleLine = true,
                            label = { Text(tr("enc_dns_endpoint_label")) },
                            placeholder = { Text(tr("enc_dns_endpoint_hint")) },
                            supportingText = { Text(tr("enc_dns_endpoint_help")) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (profile.dnsMode == DnsMode.PLAIN) {
                        Spacer(Modifier.height(8.dp))
                        LtrOutlinedTextField(
                            value = profile.dnsServers,
                            onValueChange = { onProfileChange(profile.copy(dnsServers = it)) },
                            enabled = enabled,
                            singleLine = true,
                            label = { Text(tr("dns_label")) },
                            placeholder = { Text(tr("dns_hint")) },
                            supportingText = { Text(tr("dns_help")) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        HelperText(tr("dns_help"))
                    }
                    Spacer(Modifier.height(16.dp))

                    // ---------- Routing rules ----------
                    SectionHeader(tr("section_routes"))

                    LtrOutlinedTextField(
                        value = profile.routeBlock,
                        onValueChange = { onProfileChange(profile.copy(routeBlock = it)) },
                        enabled = enabled,
                        singleLine = false,
                        label = { Text(tr("route_block_label")) },
                        placeholder = { Text(tr("route_block_hint")) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    LtrOutlinedTextField(
                        value = profile.routeDirect,
                        onValueChange = { onProfileChange(profile.copy(routeDirect = it)) },
                        enabled = enabled,
                        singleLine = false,
                        label = { Text(tr("route_direct_label")) },
                        placeholder = { Text(tr("route_direct_hint")) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HelperText(tr("routes_help"))
                    Spacer(Modifier.height(8.dp))

                    // Match the domain rules above on the name read
                    // from the first bytes. Android is always a tun front end,
                    // so without this the engine only ever sees an address and
                    // every domain rule above would quietly do nothing.
                    ToggleRow(
                        title = tr("route_sniff_title"),
                        description = tr("route_sniff_desc"),
                        checked = profile.routeSniff,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(routeSniff = it)) },
                    )
                    if (profile.routeSniff) {
                        LtrOutlinedTextField(
                            value = if (profile.routeSniffMs == 0) "" else profile.routeSniffMs.toString(),
                            onValueChange = {
                                onProfileChange(
                                    profile.copy(
                                        routeSniffMs = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0,
                                    ),
                                )
                            },
                            enabled = enabled,
                            singleLine = true,
                            label = { Text(tr("route_sniff_ms_label")) },
                            placeholder = { Text(tr("route_sniff_ms_hint")) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    // ---------- Upstream proxy / chaining ----------
                    SectionHeader(tr("section_upstream"))

                    LtrOutlinedTextField(
                        value = profile.upstreamProxy,
                        onValueChange = { onProfileChange(profile.copy(upstreamProxy = it)) },
                        enabled = enabled,
                        singleLine = true,
                        label = { Text(tr("upstream_label")) },
                        placeholder = { Text(tr("upstream_hint")) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HelperText(tr("upstream_help"))
                    Spacer(Modifier.height(8.dp))

                    // ---------- Zero Trust / organization ----------
                    SectionHeader(tr("section_zerotrust"))

                    SettingLabel(tr("team_auth_label"))
                    DropdownSelector(
                        options = TeamAuth.entries,
                        selected = profile.teamAuth,
                        onSelect = { onProfileChange(profile.copy(teamAuth = it)) },
                        label = { teamAuthLabel(it) },
                        enabled = enabled,
                    )
                    HelperText(tr("team_auth_desc"))

                    if (profile.teamAuth != TeamAuth.OFF) {
                        Spacer(Modifier.height(12.dp))
                        LtrOutlinedTextField(
                            value = profile.team,
                            onValueChange = { onProfileChange(profile.copy(team = it)) },
                            enabled = enabled,
                            singleLine = true,
                            label = { Text(tr("team_label")) },
                            placeholder = { Text(tr("team_hint")) },
                            supportingText = { Text(tr("team_help")) },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        when (profile.teamAuth) {
                            TeamAuth.SERVICE_TOKEN -> {
                                Spacer(Modifier.height(12.dp))
                                LtrOutlinedTextField(
                                    value = profile.accessClientId,
                                    onValueChange = {
                                        onProfileChange(profile.copy(accessClientId = it))
                                    },
                                    enabled = enabled,
                                    singleLine = true,
                                    label = { Text(tr("access_id_label")) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(12.dp))
                                // Masked: a service-token secret is an
                                // organization credential, so it must not be
                                // readable over someone's shoulder or land in a
                                // screenshot.
                                LtrOutlinedTextField(
                                    value = profile.accessClientSecret,
                                    onValueChange = {
                                        onProfileChange(profile.copy(accessClientSecret = it))
                                    },
                                    enabled = enabled,
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    label = { Text(tr("access_secret_label")) },
                                    supportingText = {
                                        Text(tr("access_secret_help"))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            TeamAuth.EMAIL -> {
                                Spacer(Modifier.height(12.dp))
                                LtrOutlinedTextField(
                                    value = profile.accessEmail,
                                    onValueChange = {
                                        onProfileChange(profile.copy(accessEmail = it))
                                    },
                                    enabled = enabled,
                                    singleLine = true,
                                    label = { Text(tr("access_email_label")) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            TeamAuth.TOKEN -> {
                                Spacer(Modifier.height(12.dp))
                                LtrOutlinedTextField(
                                    value = profile.accessToken,
                                    onValueChange = {
                                        onProfileChange(profile.copy(accessToken = it))
                                    },
                                    enabled = enabled,
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    label = { Text(tr("access_token_label")) },
                                    supportingText = {
                                        Text(tr("access_secret_help"))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            TeamAuth.OFF -> Unit
                        }

                        Spacer(Modifier.height(4.dp))
                        ToggleRow(
                            title = tr("gateway_title"),
                            description = tr("gateway_desc"),
                            checked = profile.gateway,
                            enabled = enabled,
                            onChange = { onProfileChange(profile.copy(gateway = it)) },
                        )
                    }

                    // ---------- Routing ----------
                    SectionHeader(tr("section_routing"))

                    ToggleRow(
                        title = tr("proxy_mode_title"),
                        description = tr("proxy_mode_desc"),
                        checked = profile.proxyMode,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(proxyMode = it)) },
                    )
                    // Fixed local proxy endpoints (standard ports,
                    // they never change) shown right under the toggle with a
                    // one-tap copy button, so nobody has to dig through logs.
                    AnimatedVisibility(visible = profile.proxyMode) {
                        Column {
                            Spacer(Modifier.height(4.dp))
                            HelperText(tr("proxy_endpoints_hint"))
                            ProxyEndpointRow(
                                label = tr("proxy_socks_label"),
                                value = "127.0.0.1:${ShareBridge.SOCKS_SHARE_PORT}",
                            )
                            ProxyEndpointRow(
                                label = tr("proxy_http_label"),
                                value = "127.0.0.1:${ShareBridge.HTTP_SHARE_PORT}",
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    SettingLabel(tr("split_mode"))
                    SegmentedSelector(
                        options = SplitMode.entries,
                        selected = profile.splitMode,
                        onSelect = { onProfileChange(profile.copy(splitMode = it)) },
                        label = { splitLabel(it) },
                        enabled = enabled,
                    )

                    // ---------- Security & stability ----------
                    SectionHeader(tr("section_security"))

                    ToggleRow(
                        title = tr("kill_switch_title"),
                        description = tr("kill_switch_desc"),
                        checked = profile.killSwitch,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(killSwitch = it)) },
                    )
                    if (profile.killSwitch) {
                        ToggleRow(
                            title = tr("strict_kill_switch_title"),
                            description = tr("strict_kill_switch_desc"),
                            checked = profile.strictKillSwitch,
                            enabled = enabled,
                            onChange = { onProfileChange(profile.copy(strictKillSwitch = it)) },
                        )
                    }
                    ToggleRow(
                        title = tr("ipv6_leak_title"),
                        description = tr("ipv6_leak_desc"),
                        checked = profile.ipv6LeakProtection,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(ipv6LeakProtection = it)) },
                    )
                    ToggleRow(
                        title = tr("reprovision_title"),
                        description = tr("reprovision_desc"),
                        checked = profile.autoReprovision,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(autoReprovision = it)) },
                    )
                    ToggleRow(
                        title = tr("smart_reconnect_title"),
                        description = tr("smart_reconnect_desc"),
                        checked = profile.smartReconnect,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(smartReconnect = it)) },
                    )
                    if (profile.smartReconnect) {
                        SettingLabel(tr("reconnect_limit_label"))
                        DropdownSelector(
                            options = listOf(3, 5, 10, 15, 20),
                            selected = profile.reconnectRetryLimit,
                            onSelect = { onProfileChange(profile.copy(reconnectRetryLimit = it)) },
                            label = { "$it" },
                            enabled = enabled,
                        )
                    }

                    // ---------- Engine tuning ----------
                    SectionHeader(tr("section_engine_tuning"))

                    if (profile.fragment) {
                        LtrOutlinedTextField(
                            value = profile.fragmentSize,
                            onValueChange = { onProfileChange(profile.copy(fragmentSize = it)) },
                            enabled = enabled,
                            singleLine = true,
                            label = { Text(tr("fragment_size_label")) },
                            placeholder = { Text(tr("fragment_size_hint")) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        LtrOutlinedTextField(
                            value = profile.fragmentDelay,
                            onValueChange = { onProfileChange(profile.copy(fragmentDelay = it)) },
                            enabled = enabled,
                            singleLine = true,
                            label = { Text(tr("fragment_delay_label")) },
                            placeholder = { Text(tr("fragment_delay_hint")) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    ToggleRow(
                        title = tr("no_data_check_title"),
                        description = tr("no_data_check_desc"),
                        checked = profile.noDataCheck,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(noDataCheck = it)) },
                    )
                    LtrOutlinedTextField(
                        value = profile.tlsGroups,
                        onValueChange = { onProfileChange(profile.copy(tlsGroups = it)) },
                        enabled = enabled,
                        singleLine = true,
                        label = { Text(tr("tls_groups_label")) },
                        placeholder = { Text(tr("tls_groups_hint")) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    LtrOutlinedTextField(
                        value = profile.customSni,
                        onValueChange = { onProfileChange(profile.copy(customSni = it)) },
                        enabled = enabled,
                        singleLine = true,
                        label = { Text(tr("custom_sni_label")) },
                        placeholder = { Text(tr("custom_sni_hint")) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HelperText(tr("custom_sni_desc"))
                    Spacer(Modifier.height(12.dp))
                    LtrOutlinedTextField(
                        value = if (profile.validateSecs == 0) "" else profile.validateSecs.toString(),
                        onValueChange = { onProfileChange(profile.copy(validateSecs = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0)) },
                        enabled = enabled,
                        singleLine = true,
                        label = { Text(tr("validate_secs_label")) },
                        placeholder = { Text(tr("secs_hint")) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    LtrOutlinedTextField(
                        value = if (profile.reconnectSecs == 0) "" else profile.reconnectSecs.toString(),
                        onValueChange = { onProfileChange(profile.copy(reconnectSecs = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0)) },
                        enabled = enabled,
                        singleLine = true,
                        label = { Text(tr("reconnect_secs_label")) },
                        placeholder = { Text(tr("secs_hint")) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    ToggleRow(
                        title = tr("no_profile_retry_title"),
                        description = tr("no_profile_retry_desc"),
                        checked = profile.noProfileRetry,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(noProfileRetry = it)) },
                    )
                    SettingLabel(tr("core_log_level_label"))
                    DropdownSelector(
                        options = CoreLogLevel.entries,
                        selected = profile.coreLogLevel,
                        onSelect = { onProfileChange(profile.copy(coreLogLevel = it)) },
                        label = { it.name },
                        enabled = enabled,
                    )

                    // ---------- Reset ----------
                    SectionHeader(tr("section_reset"))
                    OutlinedButton(
                        onClick = {
                            // Restore every setting to factory defaults. Persisted
                            // immediately through the normal onProfileChange path
                            // (DataStore), exactly like any other settings change.
                            onProfileChange(ConnectionProfile())
                            DesktopToast.show(tr("reset_done"))
                        },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                        Text(text = "  " + tr("reset_settings"))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(8.dp))
    Divider()
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
    )
}

@Composable
private fun Divider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
    )
}

@Composable
private fun SettingLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun HelperText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun protocolLabel(protocol: Protocol): String = when (protocol) {
    Protocol.AUTO -> tr("protocol_auto")
    Protocol.MASQUE -> tr("protocol_masque")
    Protocol.WIREGUARD -> tr("protocol_wireguard")
    Protocol.GOOL -> tr("protocol_gool")
}

@Composable
private fun scanLabel(mode: ScanMode): String = when (mode) {
    ScanMode.TURBO -> tr("scan_turbo")
    ScanMode.BALANCED -> tr("scan_balanced")
    ScanMode.THOROUGH -> tr("scan_thorough")
    ScanMode.STEALTH -> tr("scan_stealth")
    ScanMode.IRONCLAD -> tr("scan_ironclad")
}

@Composable
private fun ipLabel(ip: IpVersion): String = when (ip) {
    IpVersion.V4 -> tr("ip_v4")
    IpVersion.V6 -> tr("ip_v6")
    IpVersion.BOTH -> tr("ip_both")
}

@Composable
private fun noizeLabel(n: Noize): String = when (n) {
    Noize.OFF -> tr("noize_off")
    Noize.LIGHT -> tr("noize_light")
    Noize.FIREWALL -> tr("noize_firewall")
    Noize.BALANCED -> tr("noize_balanced")
    Noize.GFW -> tr("noize_gfw")
    Noize.AGGRESSIVE -> tr("noize_aggressive")
}

@Composable
private fun endpointLabel(m: EndpointMode): String = when (m) {
    EndpointMode.AUTO -> tr("endpoint_auto")
    EndpointMode.MANUAL_PEER -> tr("endpoint_peer")
    EndpointMode.MANUAL_RANGE -> tr("endpoint_range")
}

@Composable
private fun dnsModeLabel(m: DnsMode): String = when (m) {
    DnsMode.PLAIN -> tr("enc_dns_plain")
    DnsMode.DOH -> tr("enc_dns_doh")
    DnsMode.DOT -> tr("enc_dns_dot")
}

@Composable
private fun teamAuthLabel(a: TeamAuth): String = when (a) {
    TeamAuth.OFF -> tr("team_auth_off")
    TeamAuth.SERVICE_TOKEN -> tr("team_auth_service")
    TeamAuth.EMAIL -> tr("team_auth_email")
    TeamAuth.TOKEN -> tr("team_auth_token")
}

@Composable
private fun splitLabel(m: SplitMode): String = when (m) {
    SplitMode.OFF -> tr("split_off")
    SplitMode.INCLUDE -> tr("split_include")
    SplitMode.EXCLUDE -> tr("split_exclude")
}

/**
 * One fixed proxy endpoint (e.g. "127.0.0.1:10808") with a copy button.
 * The value is a compile-time constant address: it is the SAME every session,
 * so what the user copies into Psiphon/Telegram/etc. keeps working forever.
 */
@Composable
private fun ProxyEndpointRow(label: String, value: String) {
    val clipboard = LocalClipboardManager.current
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
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        IconButton(onClick = {
            clipboard.setText(AnnotatedString(value))
            DesktopToast.show(tr("share_copied"))
        }) {
            Icon(
                Icons.Rounded.ContentCopy,
                contentDescription = tr("share_copy"),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
