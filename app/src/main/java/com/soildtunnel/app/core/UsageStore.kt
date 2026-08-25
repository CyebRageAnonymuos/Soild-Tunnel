package com.soildtunnel.app.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * Persists per-session traffic history. A sampler runs inside the VPN service
 * (not the UI) so bytes are counted even when the activity is closed, and the
 * partial session is written to disk on every tick so a force-kill loses at
 * most one sampling interval.
 */
object UsageStore {

    data class Session(
        val startedAt: Long,
        val endedAt: Long,
        val rxBytes: Long,
        val txBytes: Long,
    )

    private const val PREFS_NAME = "usage_history"
    private const val KEY_HISTORY = "history"
    private const val KEY_CURRENT = "current"
    private const val MAX_SESSIONS = 60
    private const val SAMPLE_MS = 30_000L

    private var scope: CoroutineScope? = null
    private val current = AtomicReference<Session?>(null)

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        current.set(loadCurrent())
    }

    /** Starts sampling. Call once per established session. */
    fun startSession() {
        stopSampler()
        // A leftover open session means the previous run was force-killed
        // before endSession(); keep its bytes, then open a fresh session.
        current.get()?.takeIf { it.endedAt == 0L }?.let { stale ->
            if (stale.rxBytes > 0L || stale.txBytes > 0L) {
                current.set(stale.copy(endedAt = System.currentTimeMillis()))
                persistFinalized()
            }
        }
        current.set(Session(System.currentTimeMillis(), 0L, 0L, 0L))
        persistCurrent()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            while (true) {
                delay(SAMPLE_MS)
                accumulate()
            }
        }
    }

    /** Finalizes the open session and stops sampling. Safe to call twice. */
    fun endSession() {
        stopSampler()
        accumulate()
        val session = current.get() ?: return
        if (session.rxBytes > 0L || session.txBytes > 0L) {
            current.set(session.copy(endedAt = System.currentTimeMillis()))
            persistFinalized()
        } else {
            current.set(null)
            prefs.edit().remove(KEY_CURRENT).apply()
        }
    }

    fun history(): List<Session> {
        val raw = runCatching { prefs.getString(KEY_HISTORY, null) }.getOrNull() ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Session(
                    startedAt = o.getLong("s"),
                    endedAt = o.getLong("e"),
                    rxBytes = o.getLong("rx"),
                    txBytes = o.getLong("tx"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** The open session (may be null between connections). */
    fun liveSession(): Session? = current.get()?.takeIf { it.endedAt == 0L }

    private fun accumulate() {
        val session = current.get() ?: return
        val hev = HevTunnel.traffic()
        val share = ShareBridge.traffic()
        // Counters are cumulative per engine process; clamp guards against
        // resets after an in-place engine restart mid-session.
        val down = ((hev?.downloadBytes ?: 0L) + share.downloadBytes).coerceAtLeast(0L)
        val up = ((hev?.uploadBytes ?: 0L) + share.uploadBytes).coerceAtLeast(0L)
        val lastRx = session.rxBytes
        val lastTx = session.txBytes
        current.set(
            session.copy(
                rxBytes = maxOf(lastRx, down),
                txBytes = maxOf(lastTx, up),
            ),
        )
        persistCurrent()
    }

    private fun persistCurrent() {
        val session = current.get() ?: return
        prefs.edit().putString(KEY_CURRENT, toJson(session).toString()).apply()
    }

    private fun persistFinalized() {
        val session = current.get() ?: return
        val list = history().toMutableList()
        list.add(0, session)
        while (list.size > MAX_SESSIONS) list.removeAt(list.size - 1)
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit()
            .putString(KEY_HISTORY, arr.toString())
            .remove(KEY_CURRENT)
            .apply()
        current.set(null)
    }

    private fun loadCurrent(): Session? {
        val raw = runCatching { prefs.getString(KEY_CURRENT, null) }.getOrNull() ?: return null
        return runCatching { fromJson(JSONObject(raw)) }.getOrNull()
    }

    private fun toJson(s: Session): JSONObject = JSONObject().apply {
        put("s", s.startedAt)
        put("e", s.endedAt)
        put("rx", s.rxBytes)
        put("tx", s.txBytes)
    }

    private fun fromJson(o: JSONObject): Session = Session(
        startedAt = o.getLong("s"),
        endedAt = o.getLong("e"),
        rxBytes = o.getLong("rx"),
        txBytes = o.getLong("tx"),
    )

    private fun stopSampler() {
        scope?.cancel()
        scope = null
    }
}
