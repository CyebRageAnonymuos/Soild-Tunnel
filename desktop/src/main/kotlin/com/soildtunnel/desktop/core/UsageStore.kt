package com.soildtunnel.desktop.core

import com.soildtunnel.desktop.DesktopPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Persists per-session traffic history. A sampler runs in the connection
 * owner (not the UI) so bytes are counted even when the window is closed, and
 * the partial session is written to disk on every tick so a force-kill loses
 * at most one sampling interval.
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

    private val prefs = DesktopPrefs(PREFS_NAME)

    fun init() {
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
            prefs.edit { it.remove(KEY_CURRENT) }
        }
    }

    fun history(): List<Session> {
        val raw = runCatching { prefs.getString(KEY_HISTORY, null) }.getOrNull() ?: return emptyList()
        return raw.split('\n').mapNotNull { line -> runCatching { decode(line) }.getOrNull() }
    }

    /** The open session (may be null between connections). */
    fun liveSession(): Session? = current.get()?.takeIf { it.endedAt == 0L }

    private fun accumulate() {
        val session = current.get() ?: return
        // Counters are cumulative per engine process; clamp guards against
        // resets after an in-place engine restart mid-session.
        val t = TrafficCounters.traffic()
        val down = t.downloadBytes.coerceAtLeast(0L)
        val up = t.uploadBytes.coerceAtLeast(0L)
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
        prefs.edit { it.putString(KEY_CURRENT, encode(session)) }
    }

    private fun persistFinalized() {
        val session = current.get() ?: return
        val list = history().toMutableList()
        list.add(0, session)
        while (list.size > MAX_SESSIONS) list.removeAt(list.size - 1)
        prefs.edit {
            it.putString(KEY_HISTORY, list.joinToString("\n") { s -> encode(s) })
            it.remove(KEY_CURRENT)
        }
        current.set(null)
    }

    private fun loadCurrent(): Session? {
        val raw = runCatching { prefs.getString(KEY_CURRENT, null) }.getOrNull() ?: return null
        return runCatching { decode(raw) }.getOrNull()
    }

    // Sessions are stored as comma-separated "s,e,rx,tx" longs; the history is
    // one session per line.

    private fun encode(s: Session): String =
        "${s.startedAt},${s.endedAt},${s.rxBytes},${s.txBytes}"

    private fun decode(line: String): Session? {
        val f = line.split(',')
        if (f.size != 4) return null
        return Session(
            startedAt = f[0].toLongOrNull() ?: return null,
            endedAt = f[1].toLongOrNull() ?: return null,
            rxBytes = f[2].toLongOrNull() ?: return null,
            txBytes = f[3].toLongOrNull() ?: return null,
        )
    }

    private fun stopSampler() {
        scope?.cancel()
        scope = null
    }
}
