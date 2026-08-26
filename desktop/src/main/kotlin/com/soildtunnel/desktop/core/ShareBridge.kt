package com.soildtunnel.desktop.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop stub of the Android LAN-share bridge. The engine binds its proxy
 * listeners to localhost only, so sharing on the desktop edition is a
 * no-op for now; the panel stays fully functional and simply reports the
 * standard ports as unavailable while inactive.
 */
object ShareBridge {

    const val SOCKS_SHARE_PORT = 7878
    const val HTTP_SHARE_PORT = 7879

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _socksPort = MutableStateFlow<Int?>(null)
    val socksPort: StateFlow<Int?> = _socksPort.asStateFlow()

    private val _httpPort = MutableStateFlow<Int?>(null)
    val httpPort: StateFlow<Int?> = _httpPort.asStateFlow()

    fun lanAddress(): String? = null

    fun start() {
        // Listeners are localhost-bound on desktop; keep the flag off so the
        // panel does not advertise unreachable endpoints.
        _active.value = false
    }

    fun stop() {
        _active.value = false
        _socksPort.value = null
        _httpPort.value = null
    }
}
