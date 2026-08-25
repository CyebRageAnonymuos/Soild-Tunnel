package com.soildtunnel.app.core

import android.os.SystemClock
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
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Live latency + REAL EXIT-COUNTRY measurements for the built-in server list.
 *
 * One shared engine for the whole app: the picker sheet, the home-screen
 * server pill and any future surface read the same snapshot instead of each
 * hammering the network.
 *
 * HOW THE COUNTRY DETECTION WORKS: every listed range is Cloudflare anycast,
 * so the physical datacenter behind an IP changes with routing. During each
 * measurement the SAME TCP connection used for the latency check is upgraded
 * to TLS (SNI: speed.cloudflare.com) and asks the edge itself via
 * /cdn-cgi/trace which colo served the request ("colo=FRA"). The colo code is
 * mapped through [GeoTable] into a real country. Whatever the routing does,
 * the label always reflects where THIS device is ACTUALLY landing right now;
 * when the trace fails the UI falls back to the static label.
 *
 * Results are cached with a timestamp so reopening the picker shows the last
 * numbers instantly; [refreshAll] re-measures everything in parallel.
 */
object ServerPinger {

    /** Result of one live measurement against a catalog node. */
    data class Result(
        /** Round-trip in ms, or -1 when unreachable / not yet measured. */
        val ms: Long = -1L,
        val measuring: Boolean = false,
        /** Wall clock of the completed measurement, 0 while pending. */
        val at: Long = 0L,
        /**
         * Cloudflare datacenter code the probe actually landed on (e.g. "FRA"),
         * straight from the edge itself. Null when unknown.
         */
        val colo: String? = null,
        /** ISO code + display name of that datacenter's real country. */
        val countryCode: String? = null,
        val countryName: String? = null,
    )

    private val _state = MutableStateFlow<Map<String, Result>>(emptyMap())
    val state: StateFlow<Map<String, Result>> = _state.asStateFlow()

    /** Guards against stacked sweeps; also observable so the UI can show it. */
    private val _sweeping = MutableStateFlow(false)
    val sweeping: StateFlow<Boolean> = _sweeping.asStateFlow()
    private val sweepGuard = AtomicBoolean(false)

    /**
     * Re-measures every node in [ServerCatalog.all] concurrently. Each probe
     * costs one connect (+one tiny HTTPS trace) at worst, and they all run in
     * parallel, so a full sweep never takes longer than a single timeout.
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
                        val result = probeNode(node.probeHost, ServerCatalog.probePort(), timeoutMs)
                        // Enforce a minimum visible duration so rapid sweeps do not flicker.
                        val elapsed = System.currentTimeMillis() - started
                        if (elapsed < MIN_VISIBLE_MS) delay(MIN_VISIBLE_MS - elapsed)
                        _state.update { current ->
                            current + (node.id to result)
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
     * Full probe of one edge address: TCP connect latency first, then — over
     * the very same connection, so both numbers describe the same path — the
     * colo trace. Never throws; unreachable nodes yield ms = -1.
     */
    private fun probeNode(host: String, port: Int, timeoutMs: Int): Result {
        val at = System.currentTimeMillis()
        return try {
            Socket().use { raw ->
                raw.soTimeout = timeoutMs
                val start = SystemClock.elapsedRealtime()
                raw.connect(InetSocketAddress(host, port), timeoutMs)
                val ms = SystemClock.elapsedRealtime() - start

                val colo = runCatching { traceColo(raw, timeoutMs) }.getOrNull()
                val geo = GeoTable.byColo(colo)
                Result(
                    ms = ms,
                    at = at,
                    colo = colo,
                    countryCode = geo?.iso,
                    countryName = geo?.country,
                )
            }
        } catch (_: Exception) {
            Result(ms = -1L, at = at)
        }
    }

    /**
     * Asks the edge connected to [socket] which datacenter it is, by upgrading
     * the raw TCP connection to TLS with the SNI of a Cloudflare-hosted zone
     * and reading /cdn-cgi/trace. Returns the colo code (e.g. "FRA") or null.
     */
    private fun traceColo(socket: Socket, timeoutMs: Int): String? {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(socket, TRACE_SNI, 443, true) as SSLSocket
        try {
            ssl.sslParameters = (ssl.sslParameters ?: SSLParameters()).apply {
                // Validate we are really talking to Cloudflare, not a spoofer.
                endpointIdentificationAlgorithm = "HTTPS"
            }
            ssl.startHandshake()

            val request = buildString {
                append("GET /cdn-cgi/trace HTTP/1.1\r\n")
                append("Host: ").append(TRACE_SNI).append("\r\n")
                append("User-Agent: SoildTunnel/1.0\r\n")
                append("Accept: */*\r\n")
                append("Connection: close\r\n\r\n")
            }
            ssl.outputStream.write(request.toByteArray(Charsets.US_ASCII))
            ssl.outputStream.flush()

            // Read to EOF ("Connection: close"); soTimeout bounds every read.
            val body = StringBuilder()
            val buffer = ByteArray(4096)
            while (true) {
                val n = ssl.inputStream.read(buffer)
                if (n <= 0) break
                body.append(String(buffer, 0, n, Charsets.US_ASCII))
                if (body.length > MAX_TRACE_BYTES) break
            }

            return COLO_REGEX.find(body.toString())?.groupValues?.get(1)?.uppercase()
        } finally {
            runCatching { ssl.close() } // autoClose=true also drops the raw socket
        }
    }

    /**
     * Plain TCP connect latency to [host]:[port] in ms, or -1 when unreachable.
     * Safe to call from any thread; blocks the caller for at most [timeoutMs].
     */
    fun tcpPing(host: String, port: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Long = runCatching {
        val start = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        (System.nanoTime() - start) / 1_000_000
    }.getOrDefault(-1L)

    private const val DEFAULT_TIMEOUT_MS = 2_500
    private const val MIN_VISIBLE_MS = 350L
    private const val TRACE_SNI = "speed.cloudflare.com"
    private const val MAX_TRACE_BYTES = 32_000
    private val COLO_REGEX = Regex("(?:^|[\r\n])colo=([A-Za-z0-9]+)")
}
