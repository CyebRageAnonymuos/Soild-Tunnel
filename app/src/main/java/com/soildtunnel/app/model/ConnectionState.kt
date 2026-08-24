package com.soildtunnel.app.model

/** The single source of truth for what the UI shows. */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Launching : ConnectionState
    data object Connecting : ConnectionState

    /**
     * Tunnel/proxy is up but the 4-step end-to-end self-test is still
     * running. The UI must NOT present this as ready — Connected is only
     * reported once all four checks pass.
     */
    data object Verifying : ConnectionState
    data class Connected(val socksAddr: String) : ConnectionState
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : ConnectionState
    data object Disconnecting : ConnectionState
    data class Error(val message: String) : ConnectionState
}

val ConnectionState.isConnected: Boolean
    get() = this is ConnectionState.Connected

val ConnectionState.isBusy: Boolean
    get() = this is ConnectionState.Launching ||
        this is ConnectionState.Connecting ||
        this is ConnectionState.Verifying ||
        this is ConnectionState.Reconnecting ||
        this is ConnectionState.Disconnecting
