package com.soildtunnel.app.core

/**
 * Single source of truth for the tunnel plumbing constants shared between the
 * VpnService (which builds the TUN + hev config) and the UI/diagnostics layer
 * (which talks to the local SOCKS5 proxy to probe connectivity and geolocation).
 *
 * IMPORTANT: [TUN_IPV4] MUST be written into BOTH the VpnService interface
 * address AND the hev-socks5-tunnel `tunnel.ipv4` field. Battle-tested clients always set
 * `tunnel.ipv4` in its hev config; omitting it leaves hev's internal lwIP netif
 * without an address, so packets are read from TUN but never routed to the
 * SOCKS5 proxy -> the classic "connected but no site loads" symptom.
 */
object TunnelConfig {
    /** Local SOCKS5 proxy the SoildTunnel engine exposes. */
    const val SOCKS_HOST = "127.0.0.1"
    const val SOCKS_PORT = 1819

    /** Point-to-point TUN addressing (matches hev tunnel.ipv4 / tunnel.ipv6). */
    const val TUN_IPV4 = "10.10.14.1"
    const val TUN_IPV4_PREFIX = 30
    const val TUN_IPV6 = "fc00::10:10:14:1"
    const val TUN_IPV6_PREFIX = 126

    /**
     * Fallback TUN MTU. The live value comes from the user's
     * [com.soildtunnel.app.model.ConnectionProfile.mtu]; this constant is only
     * used when no profile MTU is available. 1500 = standard Ethernet frame
     * size: throughput-optimal, and unlike the old 8500 experiment it carries
     * no path-MTU/fragmentation risk on Iranian mobile networks.
     */
    const val MTU = 1500

    /** DNS resolvers advertised on the TUN interface. */
    val DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8")
}
