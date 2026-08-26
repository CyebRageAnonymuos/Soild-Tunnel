package com.soildtunnel.desktop.data

import com.soildtunnel.desktop.Paths
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Properties

/**
 * Persists the last-used [ConnectionProfile] as a plain properties file
 * (one key per field, all values strings; enums by name, lists comma-joined)
 * in the app data dir — the desktop replacement for the Android DataStore
 * ProfileStore. Unlike the Android port there is no Keystore-sealed secret
 * store, so the Zero Trust secrets live here too.
 */
class DesktopProfileStore {

    private val file = File(Paths.dataDir, FILE_NAME)

    private val _profile = MutableStateFlow(load())
    val profileFlow: StateFlow<ConnectionProfile> = _profile.asStateFlow()

    fun save(profile: ConnectionProfile) {
        val props = Properties()
        props.setProperty("protocol", profile.protocol.name)
        props.setProperty("scan", profile.scanMode.name)
        props.setProperty("ip", profile.ipVersion.name)
        props.setProperty("quick", profile.quickReconnect.toString())
        props.setProperty("h2", profile.masqueHttp2.toString())
        props.setProperty("share", profile.lanShare.toString())
        // Added later
        props.setProperty("noize", profile.noize.name)
        props.setProperty("endpoint", profile.endpointMode.name)
        props.setProperty("peer", profile.manualPeer)
        props.setProperty("range", profile.manualRange)
        props.setProperty("keepalive", profile.keepalive.toString())
        props.setProperty("fragment", profile.fragment.toString())
        props.setProperty("ech", profile.ech.toString())
        props.setProperty("mtu", profile.mtu.toString())
        props.setProperty("proxy", profile.proxyMode.toString())
        props.setProperty("split", profile.splitMode.name)
        props.setProperty("splitApps", profile.splitApps.joinToString(","))
        props.setProperty("dns", profile.dnsServers)
        props.setProperty("dnsMode", profile.dnsMode.name)
        props.setProperty("encryptedDnsEndpoint", profile.encryptedDnsEndpoint)
        props.setProperty("team", profile.team)
        props.setProperty("teamAuth", profile.teamAuth.name)
        props.setProperty("accessId", profile.accessClientId)
        props.setProperty("accessSecret", profile.accessClientSecret)
        props.setProperty("accessEmail", profile.accessEmail)
        props.setProperty("accessToken", profile.accessToken)
        props.setProperty("gateway", profile.gateway.toString())
        props.setProperty("routeBlock", profile.routeBlock)
        props.setProperty("routeDirect", profile.routeDirect)
        // Added later (feature parity)
        props.setProperty("killSwitch", profile.killSwitch.toString())
        props.setProperty("strictKillSwitch", profile.strictKillSwitch.toString())
        props.setProperty("ipv6Leak", profile.ipv6LeakProtection.toString())
        props.setProperty("smartReconnect", profile.smartReconnect.toString())
        props.setProperty("reconnectRetryLimit", profile.reconnectRetryLimit.toString())
        props.setProperty("fragmentSize", profile.fragmentSize)
        props.setProperty("fragmentDelay", profile.fragmentDelay)
        props.setProperty("noDataCheck", profile.noDataCheck.toString())
        props.setProperty("tlsGroups", profile.tlsGroups)
        props.setProperty("customSni", profile.customSni)
        props.setProperty("validateSecs", profile.validateSecs.toString())
        props.setProperty("reconnectSecs", profile.reconnectSecs.toString())
        props.setProperty("noProfileRetry", profile.noProfileRetry.toString())
        props.setProperty("coreLogLevel", profile.coreLogLevel.name)
        props.setProperty("blockedApps", profile.blockedApps.joinToString(","))
        props.setProperty("upstreamProxy", profile.upstreamProxy)
        props.setProperty("routeSniff", profile.routeSniff.toString())
        props.setProperty("routeSniffMs", profile.routeSniffMs.toString())
        props.setProperty("autoReprovision", profile.autoReprovision.toString())
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { props.store(it, null) }
        }
        _profile.value = profile
    }

    private fun load(): ConnectionProfile {
        val props = Properties()
        runCatching {
            if (file.exists()) file.inputStream().use { props.load(it) }
        }
        fun str(key: String, def: String): String = props.getProperty(key) ?: def
        fun bool(key: String, def: Boolean): Boolean =
            props.getProperty(key)?.toBooleanStrictOrNull() ?: def
        fun int(key: String, def: Int): Int = props.getProperty(key)?.toIntOrNull() ?: def
        fun <T : Enum<T>> en(key: String, def: T): T =
            props.getProperty(key)?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: def
        fun list(key: String): List<String> =
            str(key, "").split(',').map { it.trim() }.filter { it.isNotEmpty() }

        return ConnectionProfile(
            protocol = en("protocol", Protocol.AUTO),
            scanMode = en("scan", ScanMode.BALANCED),
            ipVersion = en("ip", IpVersion.V4),
            quickReconnect = bool("quick", true),
            masqueHttp2 = bool("h2", false),
            lanShare = bool("share", false),
            noize = en("noize", Noize.OFF),
            endpointMode = en("endpoint", EndpointMode.AUTO),
            manualPeer = str("peer", ""),
            manualRange = str("range", ""),
            keepalive = int("keepalive", 0),
            fragment = bool("fragment", false),
            ech = bool("ech", false),
            mtu = int("mtu", ConnectionProfile.DEFAULT_MTU),
            proxyMode = bool("proxy", false),
            splitMode = en("split", SplitMode.OFF),
            splitApps = list("splitApps"),
            dnsServers = str("dns", ""),
            dnsMode = en("dnsMode", DnsMode.PLAIN),
            encryptedDnsEndpoint = str("encryptedDnsEndpoint", ""),
            team = str("team", ""),
            teamAuth = en("teamAuth", TeamAuth.OFF),
            accessClientId = str("accessId", ""),
            accessClientSecret = str("accessSecret", ""),
            accessEmail = str("accessEmail", ""),
            accessToken = str("accessToken", ""),
            gateway = bool("gateway", false),
            routeBlock = str("routeBlock", ""),
            routeDirect = str("routeDirect", ""),
            killSwitch = bool("killSwitch", false),
            strictKillSwitch = bool("strictKillSwitch", false),
            ipv6LeakProtection = bool("ipv6Leak", true),
            smartReconnect = bool("smartReconnect", true),
            reconnectRetryLimit = int("reconnectRetryLimit", 5),
            fragmentSize = str("fragmentSize", ""),
            fragmentDelay = str("fragmentDelay", ""),
            noDataCheck = bool("noDataCheck", false),
            tlsGroups = str("tlsGroups", ""),
            customSni = str("customSni", ""),
            validateSecs = int("validateSecs", 0),
            reconnectSecs = int("reconnectSecs", 0),
            noProfileRetry = bool("noProfileRetry", false),
            coreLogLevel = en("coreLogLevel", CoreLogLevel.WARN),
            blockedApps = list("blockedApps"),
            upstreamProxy = str("upstreamProxy", ""),
            routeSniff = bool("routeSniff", true),
            routeSniffMs = int("routeSniffMs", 0),
            autoReprovision = bool("autoReprovision", true),
        )
    }

    private companion object {
        const val FILE_NAME = "profile.properties"
    }
}
