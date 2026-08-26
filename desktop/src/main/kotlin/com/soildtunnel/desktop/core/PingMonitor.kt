package com.soildtunnel.desktop.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

data class PingResult(
    val ms: Long = -1L,
    val running: Boolean = false,
    val error: Boolean = false,
)

/** Live latency probe for the telemetry card, serialised by a mutex. */
object PingMonitor {
    private val _state = MutableStateFlow(PingResult())
    val state: StateFlow<PingResult> = _state.asStateFlow()

    private val mutex = Mutex()

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
        val start = System.nanoTime()
        return try {
            val socket = if (viaTunnel) {
                Socket(
                    Proxy(Proxy.Type.SOCKS, InetSocketAddress(TunnelConfig.SOCKS_HOST, TunnelConfig.SOCKS_PORT)),
                )
            } else {
                Socket()
            }
            socket.use { s ->
                s.connect(InetSocketAddress("1.1.1.1", 53), 5000)
            }
            (System.nanoTime() - start) / 1_000_000
        } catch (e: Exception) {
            DiagnosticsLog.w("ping", "Latency probe failed (viaTunnel=$viaTunnel): ${e.message}")
            -1L
        }
    }
}
