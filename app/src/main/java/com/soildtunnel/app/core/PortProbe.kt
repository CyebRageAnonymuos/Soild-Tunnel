package com.soildtunnel.app.core

import kotlinx.coroutines.delay
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The ground-truth "are we connected?" check — identical in spirit to the
 * desktop app: a successful TCP connect to the local SOCKS5 port means the
 * engine is up and tunnelling.
 */
object PortProbe {
    fun isOpen(host: String, port: Int, timeoutMs: Int = 800): Boolean =
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            true
        } catch (e: Exception) {
            false
        }

    /**
     * Polls until the port opens or [totalTimeoutMs] elapses. Aborts early if
     * [isEngineAlive] returns false, so a dead engine fails fast with a clear
     * error instead of the caller hanging for the entire (possibly 5-minute)
     * timeout window.
     */
    suspend fun awaitOpen(
        host: String,
        port: Int,
        totalTimeoutMs: Long,
        // SPEED FIX: 300 ms polling detects the engine's port up to ~700 ms
        // sooner than the old 1 s poll; a localhost TCP connect is ~free.
        intervalMs: Long = 300,
        isEngineAlive: () -> Boolean = { true },
    ): Boolean {
        val deadline = System.currentTimeMillis() + totalTimeoutMs
        //  CPU FIX: the poll is now adaptive instead of a flat 300 ms for
        // the entire window. The engine either opens its port within the first
        // few seconds (fast path — keep the tight interval so we notice
        // immediately) or it is doing a long endpoint scan that can run for
        // MINUTES. In THOROUGH/IRONCLAD mode the old fixed interval meant up to
        // ~1,200 wake-ups + socket syscalls per connect attempt, all of it pure
        // overhead on the exact code path where the CPU is already busy
        // scanning. Backing off to [MAX_INTERVAL_MS] cuts that by roughly an
        // order of magnitude while costing at most one extra second of
        // detection latency on a slow connect.
        var interval = intervalMs
        val fastPhaseEnd = System.currentTimeMillis() + FAST_PHASE_MS
        while (System.currentTimeMillis() < deadline) {
            if (isOpen(host, port)) return true
            if (!isEngineAlive()) return false
            delay(interval)
            if (System.currentTimeMillis() > fastPhaseEnd && interval < MAX_INTERVAL_MS) {
                interval = (interval * 3 / 2).coerceAtMost(MAX_INTERVAL_MS)
            }
        }
        return false
    }

    /**
     * Waits until nothing is listening on [port] any more.
     *
     *  PROTOCOL-SWITCH FIX: a new engine must never be started while the
     * previous one still owns the local SOCKS5 port, otherwise the connect
     * either races a dying listener or verifies against it. Polls at a very
     * cheap 100 ms because a released localhost port is what the user is
     * waiting for.
     */
    suspend fun awaitClosed(
        host: String,
        port: Int,
        totalTimeoutMs: Long,
        intervalMs: Long = 100,
    ): Boolean {
        val deadline = System.currentTimeMillis() + totalTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isOpen(host, port, timeoutMs = 250)) return true
            delay(intervalMs)
        }
        return !isOpen(host, port, timeoutMs = 250)
    }

    /** Keep polling tightly for this long, then back off. */
    private const val FAST_PHASE_MS = 5_000L

    /** Upper bound for the adaptive poll interval. */
    private const val MAX_INTERVAL_MS = 1_500L
}
