package com.soildtunnel.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Loads the same strings.xml files the Android app ships (values + values-fa)
 * from the classpath, so translations stay a single source of truth.
 */
object Strings {

    var language: String by mutableStateOf(DesktopPrefs("app_locale").getString("locale", "system") ?: "system")
        private set

    private val en: Map<String, String> by lazy { load("strings/values.xml") }
    private val fa: Map<String, String> by lazy { load("strings/values-fa.xml") }

    fun setLang(tag: String) {
        language = tag
        DesktopPrefs("app_locale").edit { it.putString("locale", tag) }
    }

    operator fun get(key: String): String =
        when (language) {
            "fa" -> fa[key] ?: en[key] ?: key
            else -> en[key] ?: key
        }

    fun format(key: String, vararg args: Any?): String = get(key).let { s ->
        var out = s
        args.forEachIndexed { i, arg ->
            out = out.replace("%${i + 1}\$s", arg.toString())
            out = out.replace("%${i + 1}\$d", arg.toString())
        }
        out
    }

    private fun load(path: String): Map<String, String> {
        val stream = try {
            Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
                ?: Strings::class.java.classLoader.getResourceAsStream(path)
        } catch (_: Exception) {
            null
        }
            ?: runCatching { File(path).inputStream() }.getOrNull()
            ?: return emptyMap()
        return try {
            val text = stream.bufferedReader().readText()
            Regex("<string name=\"([^\"]+)\">([^<]*)</string>").findAll(text)
                .associate { m ->
                    m.groupValues[1] to unescape(m.groupValues[2])
                }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun unescape(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("\\'", "'")
        .replace("\\u2026", "\u2026")
}
