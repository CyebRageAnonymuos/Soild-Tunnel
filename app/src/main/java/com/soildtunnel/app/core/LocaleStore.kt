package com.soildtunnel.app.core

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/** Persists the in-app language choice: "system", "en" or "fa". */
object LocaleStore {

    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val PERSIAN = "fa"

    private const val PREFS_NAME = "app_locale"
    private const val KEY_LOCALE = "locale"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(context: Context): String =
        prefs(context).getString(KEY_LOCALE, SYSTEM) ?: SYSTEM

    fun set(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LOCALE, value).apply()
    }

    /** Wraps [context] with the selected locale so stringResource() resolves accordingly. */
    fun wrap(context: Context): Context {
        val tag = get(context)
        if (tag == SYSTEM) return context
        val locale = try {
            Locale.forLanguageTag(tag)
        } catch (_: Exception) {
            return context
        }
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
