package com.soildtunnel.desktop.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object DesktopToast {
    var message by mutableStateOf<String?>(null)

    fun show(m: String) {
        message = m
    }

    fun consume() {
        message = null
    }
}
