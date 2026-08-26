package com.soildtunnel.desktop.core

import com.soildtunnel.desktop.Strings

/** Persists the in-app language choice: "system", "en" or "fa". */
object LocaleStore {

    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val PERSIAN = "fa"

    fun get(): String = Strings.language

    fun set(value: String) {
        Strings.setLang(value)
    }
}
