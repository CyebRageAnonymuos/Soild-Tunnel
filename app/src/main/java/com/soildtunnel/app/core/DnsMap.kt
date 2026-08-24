package com.soildtunnel.app.core

import android.os.SystemClock
import java.util.Collections
import java.util.Locale

/**
 * Short-lived IP -> domain cache filled from DNS answers observed on the TUN.
 * Merged into SoildTunnel; used by the userspace tunnel bridge so a flow to an
 * IP can be attributed to the domain that resolved to it.
 */
object DnsMap {
    private data class Entry(
        val domain: String,
        val expiresAt: Long
    )

    private const val MAX_ENTRIES = 4096
    private const val DEFAULT_TTL_MILLIS = 300_000L
    private val ipToDomains = Collections.synchronizedMap(
        object : LinkedHashMap<String, MutableList<Entry>>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableList<Entry>>?): Boolean {
                return size > MAX_ENTRIES
            }
        }
    )

    fun put(ip: String, domain: String, ttlMillis: Long = DEFAULT_TTL_MILLIS) {
        val normalizedIp = ip.trim()
        val normalizedDomain = domain.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (normalizedIp.isEmpty() || normalizedDomain.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val expiry = now + ttlMillis.coerceIn(1_000L, 86_400_000L)
        synchronized(ipToDomains) {
            val entries = ipToDomains.getOrPut(normalizedIp) { mutableListOf() }
            entries.removeAll { it.expiresAt <= now || it.domain == normalizedDomain }
            entries.add(Entry(normalizedDomain, expiry))
            while (entries.size > 8) entries.removeAt(0)
        }
    }

    fun get(ip: String): String? {
        val now = SystemClock.elapsedRealtime()
        synchronized(ipToDomains) {
            val entries = ipToDomains[ip] ?: return null
            entries.removeAll { it.expiresAt <= now }
            if (entries.isEmpty()) {
                ipToDomains.remove(ip)
                return null
            }
            return entries.last().domain
        }
    }

    fun clear() {
        ipToDomains.clear()
    }
}
