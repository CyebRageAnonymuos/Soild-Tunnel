package com.soildtunnel.desktop.core

import com.soildtunnel.desktop.DesktopPrefs

/**
 * Pins the winning edge range for 24 hours so reconnects land on the same
 * exit country instead of re-running edge selection on every connect.
 */
object StickyServer {

    data class Pin(val range: String, val savedAt: Long, val until: Long)

    private const val PREFS_NAME = "sticky_server"
    private const val KEY_RANGE = "range"
    private const val KEY_SAVED_AT = "saved_at"
    private const val KEY_UNTIL = "until"

    private val prefs = DesktopPrefs(PREFS_NAME)

    fun load(): Pin? {
        val range = prefs.getString(KEY_RANGE, null)?.trim().orEmpty()
        if (range.isEmpty()) return null
        return Pin(
            range = range,
            savedAt = prefs.getLong(KEY_SAVED_AT, 0L),
            until = prefs.getLong(KEY_UNTIL, 0L),
        )
    }

    /** Pin is only usable while unexpired. Expired pins are dropped on read. */
    fun usable(): Pin? {
        val pin = load() ?: return null
        if (System.currentTimeMillis() >= pin.until) {
            clear()
            return null
        }
        return pin
    }

    fun save(range: String) {
        val now = System.currentTimeMillis()
        prefs.edit {
            it.putString(KEY_RANGE, range)
            it.putLong(KEY_SAVED_AT, now)
            it.putLong(KEY_UNTIL, now + TTL_MS)
        }
    }

    fun clear() {
        prefs.edit { it.clear() }
    }

    private const val TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
}
