package com.soildtunnel.app.core

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/** Result of a single latency measurement. */
data class PingResult(
    val ms: Long = -1L,
    val running: Boolean = false,
    val error: Boolean = false,
)

/**
 * On-demand TCP latency check, ported from the merged PingRepository and
 * adapted to SoildTunnel Mobile's tunnel plumbing ([TunnelConfig]).
 *
 * BATTERY DESIGN: there is deliberately NO periodic polling loop here. A
 * measurement only runs when the user taps the ping badge, or exactly once
 * after a new connection comes up. Each run is a single TCP handshake to
 * Cloudflare's anycast resolver (1.1.1.1:53) with a hard 5 s timeout, so one
 * measurement costs one packet round-trip and never keeps the CPU awake.
 */
object PingMonitor {
    private val _state = MutableStateFlow(PingResult())
    val state: StateFlow<PingResult> = _state.asStateFlow()

    /** Serialises concurrent taps so two probes can never overlap. */
    private val mutex = Mutex()

    /**
     * Measures TCP handshake latency to 1.1.1.1:53.
     *
     * @param viaTunnel when true the probe socket is opened THROUGH the local
     * SOCKS5 listener of the running engine, so the number reflects the
     * tunnel's real end-to-end latency; when false it connects directly and
     * shows the operator's latency instead.
     */
    suspend fun pingOnce(viaTunnel: Boolean) {
        if (!mutex.tryLock()) return
        try {
            _state.value = PingResult(running = true)
            val ms = withContext(Dispatchers.IO) { measure(viaTunnel) }
            _state.value = if (ms >= 0) PingResult(ms = ms) else PingResult(error = true)
        } finally {
            mutex.unlock()
        }
    }

    private fun measure(viaTunnel: Boolean): Long {
        val start = SystemClock.elapsedRealtime()
        return try {
            val socket = if (viaTunnel) {
                Socket(
                    Proxy(
                        Proxy.Type.SOCKS,
                        InetSocketAddress(TunnelConfig.SOCKS_HOST, TunnelConfig.SOCKS_PORT),
                    ),
                )
            } else {
                Socket()
            }
            socket.use { s ->
                s.connect(InetSocketAddress("1.1.1.1", 53), 5000)
            }
            SystemClock.elapsedRealtime() - start
        } catch (e: Exception) {
            DiagnosticsLog.w("ping", "Latency probe failed (viaTunnel=$viaTunnel): ${e.message}")
            -1L
        }
    }
}
