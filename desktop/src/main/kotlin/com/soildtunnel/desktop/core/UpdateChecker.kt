package com.soildtunnel.desktop.core

import com.soildtunnel.desktop.DesktopBuild
import com.soildtunnel.desktop.DesktopPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer app version.
 *
 * Two endpoints are tried in order, because api.github.com is unreachable on
 * some networks while github.com still works:
 *  1. REST API (JSON tag_name)
 *  2. the releases/latest page itself, read from its 302 Location header
 *
 * A failed network round-trip is NOT cached as "no update": only successful
 * checks arm the 24h interval, otherwise one offline launch would silence the
 * banner for a whole day.
 */
object UpdateChecker {

    data class Result(
        val hasUpdate: Boolean = false,
        val latestVersion: String = "",
        val downloadUrl: String = "",
    )

    private const val PREFS_NAME = "update_checker"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_LAST_FAIL = "last_fail"
    private const val KEY_LAST_RESULT = "last_result"

    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // success
    private const val FAIL_RETRY_MS = 30 * 60 * 1000L          // after a failed check

    private const val FALLBACK_RELEASES_URL = "https://github.com/CyebRageAnonymuos/Soild-Tunnel/releases/latest"

    private val prefs = DesktopPrefs(PREFS_NAME)
    private var cached: Result? = null

    fun init() {
        cached = prefs.getString(KEY_LAST_RESULT, null)?.let(::decode)
    }

    fun getCachedResult(): Result = cached ?: Result()

    suspend fun checkIfNeeded(): Result {
        val now = System.currentTimeMillis()
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L)
        val lastFail = prefs.getLong(KEY_LAST_FAIL, 0L)
        if (now - lastCheck < CHECK_INTERVAL_MS && cached != null) return cached!!
        if (now - lastFail < FAIL_RETRY_MS && cached != null) return cached!!
        return checkNow()
    }

    suspend fun checkNow(): Result = withContext(Dispatchers.IO) {
        val releasesUrl = DesktopBuild.RELEASES_URL.ifBlank { FALLBACK_RELEASES_URL }
        val latest = fetchViaApi(releasesUrl) ?: fetchViaRedirect(releasesUrl)

        val result = if (latest == null) {
            // Network failed: keep any previous banner state untouched and let
            // the next launch retry after FAIL_RETRY_MS.
            prefs.edit { it.putLong(KEY_LAST_FAIL, System.currentTimeMillis()) }
            cached ?: Result()
        } else {
            val version = normalize(latest)
            val hasUpdate = isNewer(version, DesktopBuild.VERSION_NAME)
            Result(
                hasUpdate = hasUpdate,
                latestVersion = if (hasUpdate) version else "",
                downloadUrl = if (hasUpdate) releasesUrl else "",
            ).also { save(it) }
        }

        cached = result
        result
    }

    private fun save(result: Result) {
        prefs.edit {
            it.putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            it.remove(KEY_LAST_FAIL)
            it.putString(KEY_LAST_RESULT, encode(result))
        }
    }

    /** Endpoint 1: api.github.com JSON. Returns the raw tag, e.g. v1.0.2-build.14 */
    private fun fetchViaApi(releasesUrl: String): String? = try {
        val base = releasesUrl.removeSuffix("/releases/latest").removePrefix("https://github.com/")
        val conn = URL("https://api.github.com/repos/$base/releases/latest")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "SoildTunnel/${DesktopBuild.VERSION_NAME}")
        try {
            if (conn.responseCode != 200) null
            else Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")
                .find(conn.inputStream.bufferedReader().use { it.readText() })
                ?.groupValues?.get(1)
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Endpoint 2: github.com/.../releases/latest answers 302 with the tag in
     * the redirect target. Reading only the header keeps this cheap.
     */
    private fun fetchViaRedirect(releasesUrl: String): String? = try {
        val conn = URL(releasesUrl).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", "SoildTunnel/${DesktopBuild.VERSION_NAME}")
        try {
            val loc = conn.getHeaderField("Location")
            if (conn.responseCode in 300..399 && loc != null) {
                loc.substringAfterLast("/tag/", "").ifEmpty { null }
            } else null
        } finally {
            conn.disconnect()
        }
    } catch (_: IOException) {
        null
    } catch (_: Exception) {
        null
    }

    /** v1.0.2-build.14 -> 1.0.2 */
    private fun normalize(tag: String): String {
        val v = tag.removePrefix("v").trim()
        val idx = v.indexOf('-')
        return if (idx > 0) v.substring(0, idx) else v
    }

    /**
     * The cached Result is stored pipe-encoded ("has_update|version|url");
     * neither the tag-derived version nor the releases URL can contain '|',
     * and the limit-split keeps any stray tail inside [Result.downloadUrl].
     */
    private fun encode(result: Result): String =
        "${if (result.hasUpdate) "1" else "0"}|${result.latestVersion}|${result.downloadUrl}"

    private fun decode(raw: String): Result? = runCatching {
        val f = raw.split('|', limit = 3)
        Result(
            hasUpdate = f[0] == "1",
            latestVersion = f.getOrElse(1) { "" },
            downloadUrl = f.getOrElse(2) { "" },
        )
    }.getOrNull()

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
