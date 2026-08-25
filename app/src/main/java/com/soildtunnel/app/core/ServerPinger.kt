package com.soildtunnel.app.core

import android.content.Context
import android.content.SharedPreferences
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
import org.json.JSONArray
import org.json.JSONObject
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

    private var prefs: SharedPreferences? = null
    private var lastSweepAt: Long = 0L

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        lastSweepAt = p.getLong(KEY_LAST_SWEEP, 0L)
        loadFromDisk(p)
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
            lastSweepAt = System.currentTimeMillis()
            prefs?.edit()?.putLong(KEY_LAST_SWEEP, lastSweepAt)?.apply()
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

    private fun loadFromDisk(p: SharedPreferences) {
        val raw = p.getString(KEY_RESULTS, null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            val map = mutableMapOf<String, Result>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                map[obj.getString("id")] = Result(
                    ms = obj.optLong("ms", -1L),
                    at = obj.optLong("at", 0L),
                    colo = obj.optString("colo", null),
                    countryCode = obj.optString("cc", null),
                    countryName = obj.optString("cn", null),
                )
            }
            if (map.isNotEmpty()) _state.value = map
        }
    }

    private fun saveToDisk() {
        val p = prefs ?: return
        val arr = JSONArray()
        _state.value.forEach { (id, r) ->
            if (r.at > 0L) {
                arr.put(JSONObject().apply {
                    put("id", id)
                    put("ms", r.ms)
                    put("at", r.at)
                    put("colo", r.colo ?: JSONObject.NULL)
                    put("cc", r.countryCode ?: JSONObject.NULL)
                    put("cn", r.countryName ?: JSONObject.NULL)
                })
            }
        }
        p.edit().putString(KEY_RESULTS, arr.toString()).apply()
    }

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
    private const val KEY_RESULTS = "results"
    private const val KEY_LAST_SWEEP = "last_sweep"
    private const val DEFAULT_TIMEOUT_MS = 2_500
    private const val MIN_VISIBLE_MS = 350L
    private const val TRACE_SNI = "speed.cloudflare.com"
    private const val MAX_TRACE_BYTES = 32_000
    private const val STALE_MS = 6 * 60 * 60 * 1000L // 6 hours
    private val COLO_REGEX = Regex("(?:^|[\r\n])colo=([A-Za-z0-9]+)")
}
