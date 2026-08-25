package com.soildtunnel.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live latency measurements for the built-in server list.
 *
 * One shared engine for the whole app: the picker sheet, the home-screen
 * server pill and any future surface read the same snapshot instead of each
 * hammering the network. A measurement is a plain TCP connect to the node's
 * representative edge address on 443 — cheap, permission-free, and identical
 * to what Smart Auto uses when it ranks ranges.
 *
 * Results are cached with a timestamp so reopening the picker shows the last
 * numbers instantly; [refreshAll] re-measures everything in parallel.
 */
object ServerPinger {

    data class Result(
        /** Round-trip in ms, or -1 when unreachable / not yet measured. */
        val ms: Long = -1L,
        val measuring: Boolean = false,
        /** Wall clock of the completed measurement, 0 while pending. */
        val at: Long = 0L,
    )

    private val _state = MutableStateFlow<Map<String, Result>>(emptyMap())
    val state: StateFlow<Map<String, Result>> = _state.asStateFlow()

    /** Guards against stacked sweeps; also observable so the UI can show it. */
    private val _sweeping = MutableStateFlow(false)
    val sweeping: StateFlow<Boolean> = _sweeping.asStateFlow()
    private val sweepGuard = AtomicBoolean(false)

    /**
     * Re-measures every node in [ServerCatalog.all] concurrently. Each probe
     * costs one connect timeout at worst, and they all run in parallel, so a
     * full sweep never takes longer than a single timeout.
     */
    suspend fun refreshAll(timeoutMs: Int = DEFAULT_TIMEOUT_MS): Unit = withContext(Dispatchers.IO) {
        if (!sweepGuard.compareAndSet(false, true)) {
            return@withContext // a sweep is already running; do not stack probes
        }
        _sweeping.value = true
        try {
            val targets = ServerCatalog.all
            // Flip every entry into its measuring state, keeping stale values
            // visible (dimmed by the UI) so the list does not flash empty.
            _state.update { current ->
                targets.associate { it.id to (current[it.id] ?: Result()).copy(measuring = true) }
            }
            coroutineScope {
                targets.map { node ->
                    async {
                        val started = System.currentTimeMillis()
                        val ms = tcpPing(node.probeHost, ServerCatalog.probePort(), timeoutMs)
                        // Enforce a minimum visible duration so rapid sweeps do not flicker.
                        val elapsed = System.currentTimeMillis() - started
                        if (elapsed < MIN_VISIBLE_MS) delay(MIN_VISIBLE_MS - elapsed)
                        _state.update { current ->
                            current + (node.id to Result(ms = ms, measuring = false, at = System.currentTimeMillis()))
                        }
                    }
                }.forEach { it.await() }
            }
        } finally {
            sweepGuard.set(false)
            _sweeping.value = false
        }
    }

    /** Current known result for [nodeId], measuring flag included. */
    fun resultFor(nodeId: String): Result = _state.value[nodeId] ?: Result()

    /**
     * TCP connect latency to [host]:[port] in ms, or -1 when unreachable.
     * Safe to call from any thread; blocks the caller for at most [timeoutMs].
     */
    fun tcpPing(host: String, port: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Long = runCatching {
        val start = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        (System.nanoTime() - start) / 1_000_000
    }.getOrDefault(-1L)

    private const val DEFAULT_TIMEOUT_MS = 2_500
    private const val MIN_VISIBLE_MS = 350L
}
