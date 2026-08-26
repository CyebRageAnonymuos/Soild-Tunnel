package com.soildtunnel.desktop.core

import com.soildtunnel.desktop.DesktopPrefs
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

object ServerPinger {

    data class Result(
        val ms: Long = -1L,
        val measuring: Boolean = false,
        val at: Long = 0L,
        val colo: String? = null,
        val countryCode: String? = null,
        val countryName: String? = null,
    )

    private val _state = MutableStateFlow<Map<String, Result>>(emptyMap())
    val state: StateFlow<Map<String, Result>> = _state.asStateFlow()

    private val _sweeping = MutableStateFlow(false)
    val sweeping: StateFlow<Boolean> = _sweeping.asStateFlow()
    private val sweepGuard = AtomicBoolean(false)

    private val prefs = DesktopPrefs(PREFS_NAME)
    private var lastSweepAt: Long = 0L

    fun init() {
        lastSweepAt = prefs.getLong(KEY_LAST_SWEEP, 0L)
        loadFromDisk()
    }

    suspend fun refreshAll(timeoutMs: Int = DEFAULT_TIMEOUT_MS): Unit = withContext(Dispatchers.IO) {
        if (!sweepGuard.compareAndSet(false, true)) return@withContext
        _sweeping.value = true
        try {
            val targets = ServerCatalog.all
            _state.update { cur ->
                targets.associate { it.id to (cur[it.id] ?: Result()).copy(measuring = true) }
            }
            coroutineScope {
                targets.map { node ->
                    async {
                        val started = System.currentTimeMillis()
                        val result = probeNode(node.probeHost, ServerCatalog.probePort(), timeoutMs)
                        val elapsed = System.currentTimeMillis() - started
                        if (elapsed < MIN_VISIBLE_MS) delay(MIN_VISIBLE_MS - elapsed)
                        _state.update { cur -> cur + (node.id to result) }
                    }
                }.forEach { it.await() }
            }
            // Stamp the sweep timestamp only when at least one probe answered.
            // A total blackout (offline, blocked network) must not block retries.
            val anySuccess = _state.value.values.any { it.ms >= 0 }
            if (anySuccess) {
                lastSweepAt = System.currentTimeMillis()
                prefs.edit { it.putLong(KEY_LAST_SWEEP, lastSweepAt) }
            }
            saveToDisk()
        } finally {
            sweepGuard.set(false)
            _sweeping.value = false
        }
    }

    /** Only sweeps if data is older than [STALE_MS]. UI call sites should use this. */
    suspend fun maybeAutoRefresh() {
        if (System.currentTimeMillis() - lastSweepAt < STALE_MS) return
        refreshAll()
    }

    fun resultFor(nodeId: String): Result = _state.value[nodeId] ?: Result()

    // disk persistence
    //
    // One properties key per catalog node holding "ms|at|colo|cc|cn"; the
    // fields come from [GeoTable] and probe results, so none of them can
    // contain the '|' separator.

    private fun loadFromDisk() {
        val map = mutableMapOf<String, Result>()
        for (node in ServerCatalog.all) {
            val raw = prefs.getString(KEY_RESULT_PREFIX + node.id, null) ?: continue
            decodeResult(raw)?.let { map[node.id] = it }
        }
        if (map.isNotEmpty()) _state.value = map
    }

    private fun saveToDisk() {
        prefs.edit { e ->
            _state.value.forEach { (id, r) ->
                if (r.at > 0L) e.putString(KEY_RESULT_PREFIX + id, encodeResult(r))
            }
        }
    }

    private fun encodeResult(r: Result): String =
        listOf(
            r.ms.toString(),
            r.at.toString(),
            r.colo.orEmpty(),
            r.countryCode.orEmpty(),
            r.countryName.orEmpty(),
        ).joinToString("|")

    private fun decodeResult(raw: String): Result? {
        val f = raw.split('|')
        if (f.size < 2) return null
        return Result(
            ms = f[0].toLongOrNull() ?: -1L,
            at = f[1].toLongOrNull() ?: 0L,
            colo = f.getOrElse(2) { "" }.ifEmpty { null },
            countryCode = f.getOrElse(3) { "" }.ifEmpty { null },
            countryName = f.getOrElse(4) { "" }.ifEmpty { null },
        )
    }

    private fun probeNode(host: String, port: Int, timeoutMs: Int): Result {
        val at = System.currentTimeMillis()
        return try {
            Socket().use { raw ->
                raw.soTimeout = timeoutMs
                val start = System.nanoTime() / 1_000_000
                raw.connect(InetSocketAddress(host, port), timeoutMs)
                val ms = System.nanoTime() / 1_000_000 - start
                val colo = runCatching { traceColo(raw, timeoutMs) }.getOrNull()
                val geo = GeoTable.byColo(colo)
                Result(ms = ms, at = at, colo = colo, countryCode = geo?.iso, countryName = geo?.country)
            }
        } catch (_: Exception) {
            Result(ms = -1L, at = at)
        }
    }

    private fun traceColo(socket: Socket, timeoutMs: Int): String? {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(socket, TRACE_SNI, 443, true) as SSLSocket
        try {
            ssl.sslParameters = (ssl.sslParameters ?: SSLParameters()).apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
            ssl.startHandshake()
            val request = "GET /cdn-cgi/trace HTTP/1.1\r\nHost: $TRACE_SNI\r\nUser-Agent: SoildTunnel/1.0\r\nAccept: */*\r\nConnection: close\r\n\r\n"
            ssl.outputStream.write(request.toByteArray(Charsets.US_ASCII))
            ssl.outputStream.flush()
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
            runCatching { ssl.close() }
        }
    }

    fun tcpPing(host: String, port: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Long = runCatching {
        val start = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        (System.nanoTime() - start) / 1_000_000
    }.getOrDefault(-1L)

    private const val PREFS_NAME = "server_pinger"
    private const val KEY_LAST_SWEEP = "last_sweep"
    private const val KEY_RESULT_PREFIX = "result."
    private const val DEFAULT_TIMEOUT_MS = 2_500
    private const val MIN_VISIBLE_MS = 350L
    private const val TRACE_SNI = "speed.cloudflare.com"
    private const val MAX_TRACE_BYTES = 32_000
    private const val STALE_MS = 24 * 60 * 60 * 1000L // 24 hours
    private val COLO_REGEX = Regex("(?:^|[\r\n])colo=([A-Za-z0-9]+)")
}
