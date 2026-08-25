package com.soildtunnel.app.core

import android.content.Context
import android.content.SharedPreferences
import com.soildtunnel.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    data class Result(
        val hasUpdate: Boolean = false,
        val latestVersion: String = "",
        val downloadUrl: String = "",
    )

    private const val PREFS_NAME = "update_checker"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_LAST_RESULT = "last_result"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    private var prefs: SharedPreferences? = null
    private var cached: Result? = null

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        val raw = p.getString(KEY_LAST_RESULT, null)
        if (raw != null) {
            cached = try {
                val obj = JSONObject(raw)
                Result(
                    hasUpdate = obj.optBoolean("has_update", false),
                    latestVersion = obj.optString("version", ""),
                    downloadUrl = obj.optString("url", ""),
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    fun getCachedResult(): Result = cached ?: Result()

    suspend fun checkIfNeeded(): Result {
        val lastCheck = prefs?.getLong(KEY_LAST_CHECK, 0L) ?: 0L
        if (System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS && cached != null) {
            return cached!!
        }
        return checkNow()
    }

    suspend fun checkNow(): Result = withContext(Dispatchers.IO) {
        val releasesUrl = BuildConfig.RELEASES_URL.ifBlank { return@withContext Result() }
        val apiBase = releasesUrl.replace("/releases/latest", "")
        val apiUrl = "https://api.github.com/repos/${apiBase.removePrefix("https://github.com/")}/releases/latest"

        val result = try {
            val conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "SoildTunnel/${BuildConfig.VERSION_NAME}")

            if (conn.responseCode != 200) {
                Result()
            } else {
                val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val json = JSONObject(body)
                val tag = json.optString("tag_name", "")
                val version = tag.removePrefix("v").let { v ->
                    val idx = v.indexOf("-")
                    if (idx > 0) v.substring(0, idx) else v
                }
                val current = BuildConfig.VERSION_NAME
                val hasUpdate = isNewer(version, current)
                val downloadUrl = if (hasUpdate) releasesUrl else ""
                Result(hasUpdate = hasUpdate, latestVersion = version, downloadUrl = downloadUrl)
            }
        } catch (_: Exception) {
            Result()
        }

        cached = result
        prefs?.edit()
            ?.putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            ?.putString(KEY_LAST_RESULT, JSONObject().apply {
                put("has_update", result.hasUpdate)
                put("version", result.latestVersion)
                put("url", result.downloadUrl)
            }.toString())
            ?.apply()

        result
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split(".").mapNotNull { it.toIntOrNull() }
        val c = current.split(".").mapNotNull { it.toIntOrNull() }
        val max = maxOf(l.size, c.size)
        for (i in 0 until max) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }
}
