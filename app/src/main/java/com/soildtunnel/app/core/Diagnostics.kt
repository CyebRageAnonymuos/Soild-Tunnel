package com.soildtunnel.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Runs the ordered connectivity self-test against the local SOCKS5 proxy and
 * records every step in [DiagnosticsLog]. The order is deliberate so a reader
 * can pinpoint WHERE the pipeline breaks:
 *
 * port      -> is the engine even listening?
 * handshake -> does it speak SOCKS5?
 * tcp       -> can it open an outbound TCP connection (to an IP, no DNS)?
 * dns_http  -> can it resolve a domain AND fetch over HTTP end-to-end?
 *
 * Example: port+handshake+tcp PASS but dns_http FAIL => the tunnel works but
 * DNS (SOCKS5 UDP ASSOCIATE / remote resolution) is broken — the usual reason a
 * WARP-style tunnel "connects but no site loads".
 *
 * SPEED (root-cause rework): this self-test is now the GATE for the
 * Connected state, so every second it wastes is a second the user stares at
 * "connecting". Three structural fixes cut the readiness time dramatically:
 *
 * 1. The TCP and DNS+HTTP checks run CONCURRENTLY. They are independent
 * probes of the same proxy; running them back-to-back doubled the
 * cold-start wait for no benefit.
 * 2. Retries fire every 750 ms instead of every 3 s. The engine's inner
 * tunnel becomes ready at an unpredictable instant inside the warm-up
 * window; a 3 s poll added up to ~3 s of pure detection latency (per
 * check!) after the tunnel was already usable.
 * 3. The DNS+HTTP probe races ALL geolocation providers in parallel
 * ([NetProbe.fetchIpInfoViaSocksRaced]) instead of trying them one by
 * one. On Iranian networks individual providers are often filtered or
 * slow in ways that differ per operator/region (DPI variance), so the
 * serial fallback chain could burn 20-30 s of timeouts before reaching
 * the provider that actually answers. The race always finishes as fast
 * as the FASTEST provider for that user's network.
 */
object Diagnostics {
    const val C_PORT = "socks_port"
    const val C_HANDSHAKE = "socks_handshake"
    const val C_TCP = "tcp_via_proxy"
    const val C_DNS = "dns_http_via_tunnel"

    private const val TAG = "diag"

    // How long we keep retrying the outbound checks after connect. Warp-in-warp
    // (GOOL) keeps building its INNER tunnel for a while after the SOCKS5 port
    // is already open; during that window every CONNECT is rejected with rep=1.
    // That is a COLD START, not a failure, so give the engine a grace window
    // instead of failing on the very first attempt.
    private const val OUTBOUND_GRACE_MS = 90_000L
    private const val OUTBOUND_RETRY_DELAY_MS = 750L
    private const val TCP_PROBE_TIMEOUT_MS = 4_000
    private const val GEO_PROBE_TIMEOUT_MS = 6_000

    fun resetChecks(
        host: String = TunnelConfig.SOCKS_HOST,
        port: Int = TunnelConfig.SOCKS_PORT,
    ) {
        DiagnosticsLog.setChecks(
            listOf(
                ComponentCheck(C_PORT, "SOCKS5 port $host:$port"),
                ComponentCheck(C_HANDSHAKE, "SOCKS5 handshake"),
                ComponentCheck(C_TCP, "TCP via proxy (1.1.1.1:80)"),
                ComponentCheck(C_DNS, "DNS + HTTP via tunnel"),
            )
        )
    }

    /** Runs all checks (steps 3+4 concurrently). Safe to call from any coroutine. */
    suspend fun run(
        host: String = TunnelConfig.SOCKS_HOST,
        port: Int = TunnelConfig.SOCKS_PORT,
    ): Boolean = withContext(Dispatchers.IO) {
        resetChecks(host, port)
        DiagnosticsLog.i(TAG, "Starting connectivity self-test…")

        // 1. Port open
        DiagnosticsLog.updateCheck(C_PORT, CheckState.RUNNING)
        val portOpen = PortProbe.isOpen(host, port, 1500)
        DiagnosticsLog.updateCheck(
            C_PORT,
            if (portOpen) CheckState.PASS else CheckState.FAIL,
            if (portOpen) "listening" else "no listener",
        )
        DiagnosticsLog.log(TAG, if (portOpen) LogLevel.INFO else LogLevel.ERROR, "port open = $portOpen")
        if (!portOpen) {
            failRemaining(C_HANDSHAKE, C_TCP, C_DNS)
            return@withContext false
        }

        // 2. SOCKS5 handshake
        DiagnosticsLog.updateCheck(C_HANDSHAKE, CheckState.RUNNING)
        val handshake = NetProbe.checkSocksHandshake(host, port)
        DiagnosticsLog.updateCheck(C_HANDSHAKE, if (handshake) CheckState.PASS else CheckState.FAIL)
        DiagnosticsLog.log(TAG, if (handshake) LogLevel.INFO else LogLevel.ERROR, "socks5 handshake = $handshake")
        if (!handshake) {
            failRemaining(C_TCP, C_DNS)
            return@withContext false
        }

        // 3 + 4. TCP-via-proxy and DNS+HTTP end-to-end — CONCURRENT, each with
        // its own fast retry loop over the shared cold-start grace window.
        val deadline = System.currentTimeMillis() + OUTBOUND_GRACE_MS
        val (tcp, info) = coroutineScope {
            val tcpJob = async {
                DiagnosticsLog.updateCheck(C_TCP, CheckState.RUNNING)
                var ok = NetProbe.checkTcpViaProxy(host, port, "1.1.1.1", 80, TCP_PROBE_TIMEOUT_MS)
                while (!ok && System.currentTimeMillis() < deadline) {
                    delay(OUTBOUND_RETRY_DELAY_MS)
                    ok = NetProbe.checkTcpViaProxy(host, port, "1.1.1.1", 80, TCP_PROBE_TIMEOUT_MS)
                }
                DiagnosticsLog.updateCheck(C_TCP, if (ok) CheckState.PASS else CheckState.FAIL)
                DiagnosticsLog.log(TAG, if (ok) LogLevel.INFO else LogLevel.ERROR, "tcp via proxy = $ok")
                ok
            }
            val dnsJob = async {
                DiagnosticsLog.updateCheck(C_DNS, CheckState.RUNNING)
                var result = NetProbe.fetchIpInfoViaSocksRaced(host, port, GEO_PROBE_TIMEOUT_MS)
                while (result == null && System.currentTimeMillis() < deadline) {
                    delay(OUTBOUND_RETRY_DELAY_MS)
                    result = NetProbe.fetchIpInfoViaSocksRaced(host, port, GEO_PROBE_TIMEOUT_MS)
                }
                result
            }
            Pair(tcpJob.await(), dnsJob.await())
        }

        val dnsOk = info != null
        DiagnosticsLog.updateCheck(
            C_DNS,
            if (dnsOk) CheckState.PASS else CheckState.FAIL,
            if (dnsOk) "exit ${info!!.ip} ${info.countryCode ?: "?"}" else "no response",
        )
        DiagnosticsLog.log(
            TAG,
            if (dnsOk) LogLevel.INFO else LogLevel.ERROR,
            if (dnsOk) "dns+http OK, exit ip=${info!!.ip} cc=${info.countryCode}" else "dns+http FAILED",
        )

        // The self-test already discovered the real exit IP through the tunnel.
        // Feed it straight into the badge so the UI never has to race a second,
        // independent lookup right after connect — the IP + flag is visible the
        // INSTANT the app reports Connected.
        if (dnsOk) {
            SoildTunnelController.offerTunnelIpInfo(IpEndpoint(info!!.ip, info.countryCode, true))
            SoildTunnelController.setIpLoading(false)
        }

        if (!dnsOk) {
            DiagnosticsLog.w(
                TAG,
                if (tcp) "TCP works but DNS/HTTP fails → likely broken remote DNS (SOCKS5 UDP ASSOCIATE)."
                else "Proxy cannot open outbound connections → engine has no upstream route.",
            )
        }
        dnsOk
    }

    private fun failRemaining(vararg ids: String) {
        ids.forEach { DiagnosticsLog.updateCheck(it, CheckState.FAIL, "skipped") }
    }
}
