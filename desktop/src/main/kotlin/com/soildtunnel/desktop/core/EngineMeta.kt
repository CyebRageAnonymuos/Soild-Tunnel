package com.soildtunnel.desktop.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live connection metadata for the desktop-parity info row: the
 * protocol actually in use and the endpoint the engine picked.
 *
 * The endpoint is ground truth from the engine itself: [ingest] picks out the
 * selection lines the core prints:
 *   - "[+] selected WireGuard endpoint 162.159.195.96:946 (rtt ...)"
 *   - "[+] selected WireGuard endpoint 162.159.195.96:946 using soildtunnelnoize ..."
 *   - "[+] selected MASQUE gateway 162.159.198.1:443 (rtt ...)"
 * The protocol comes from the RESOLVED profile (Smart Auto resolves AUTO to
 * a concrete protocol before launch), published via [setProtocol].
 */
object EngineMeta {

    data class Snapshot(
        val protocol: String? = null,
        val endpoint: String? = null,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** Called by the engine runner once the winning strategy is known. */
    fun setProtocol(protocol: String) {
        _state.value = _state.value.copy(protocol = protocol)
    }

    /** Called for a pinned (manual) peer, which never logs a selection line. */
    fun setEndpoint(endpoint: String) {
        _state.value = _state.value.copy(endpoint = endpoint)
    }

    /** Clears both fields (new connect, disconnect, lockdown). */
    fun reset() {
        _state.value = Snapshot()
    }

    /**
     * Scans one engine stdout line for the endpoint-selection messages.
     * Cheap string work on the log-drain thread; everything else is ignored.
     */
    fun ingest(line: String) {
        val endpoint = WG_MARKER.find(line)?.groupValues?.get(1)
            ?: MASQUE_MARKER.find(line)?.groupValues?.get(1)
            ?: return
        _state.value = _state.value.copy(endpoint = endpoint)
    }

    private val WG_MARKER = Regex("selected WireGuard endpoint (\\S+:\\d+)")
    private val MASQUE_MARKER = Regex("selected MASQUE gateway (\\S+:\\d+)")
}
