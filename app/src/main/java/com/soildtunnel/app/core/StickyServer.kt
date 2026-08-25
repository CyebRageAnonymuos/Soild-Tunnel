package com.soildtunnel.app.core

import android.content.Context
import android.content.SharedPreferences

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

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): Pin? {
        val p = prefs(context)
        val range = p.getString(KEY_RANGE, null)?.trim().orEmpty()
        if (range.isEmpty()) return null
        return Pin(
            range = range,
            savedAt = p.getLong(KEY_SAVED_AT, 0L),
            until = p.getLong(KEY_UNTIL, 0L),
        )
    }

    /** Pin is only usable while unexpired. Expired pins are dropped on read. */
    fun usable(context: Context): Pin? {
        val pin = load(context) ?: return null
        if (System.currentTimeMillis() >= pin.until) {
            clear(context)
            return null
        }
        return pin
    }

    fun save(context: Context, range: String) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putString(KEY_RANGE, range)
            .putLong(KEY_SAVED_AT, now)
            .putLong(KEY_UNTIL, now + TTL_MS)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private const val TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
}
