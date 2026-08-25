package com.soildtunnel.app.core

import com.soildtunnel.app.model.ConnectionProfile
import com.soildtunnel.app.model.EndpointMode

/**
 * One selectable edge node of the built-in server list.
 *
 * A node maps 1:1 to one Cloudflare WARP/MASQUE edge /24 taken from the
 * engine's own built-in range tables (see prober.rs MASQUE_CIDRS_V4 and the
 * Smart Auto EDGES list). Selecting a node pins the engine's scan to exactly
 * that /24 (EndpointMode.MANUAL_RANGE), so the engine still picks a LIVE
 * gateway inside the chosen neighbourhood — a single pinned IP would go dark
 * the moment that one host stops answering.
 *
 * [probeHost] is one representative address used for the live latency badge;
 * it is only a measuring target, never what the engine connects to.
 */
data class ServerNode(
    val id: String,
    /** Display codename shown in the picker. */
    val name: String,
    /** Short console-style code, e.g. "ND-01". */
    val code: String,
    /** What gets written into ConnectionProfile.manualRange on selection. */
    val cidrs: List<String>,
    /** Representative IP for the live TCP latency measurement. */
    val probeHost: String,
) {
    /** The exact profile value this node serialises to. */
    val rangeSpec: String get() = cidrs.joinToString(", ")
}

/**
 * The internal server list. Deliberately small and curated: every entry is a
 * real, engine-supported edge range — nothing here is invented, so any node
 * the user picks is guaranteed to be scannable by the core.
 */
object ServerCatalog {

    /** Pinned port for latency probes. 443 is served by every listed edge. */
    private const val PROBE_PORT = 443

    val AUTO_ID = "__auto__"

    /** "Auto" is not a node; it clears any pin and lets the engine scan everything. */
    val auto: ServerNode = ServerNode(
        id = AUTO_ID,
        name = "Auto",
        code = "AUTO",
        cidrs = emptyList(),
        probeHost = "1.1.1.1",
    )

    val nodes: List<ServerNode> = listOf(
        ServerNode("atlas", "ATLAS", "ND-01", listOf("162.159.192.0/24"), "162.159.192.1"),
        ServerNode("vega", "VEGA", "ND-02", listOf("162.159.193.0/24"), "162.159.193.1"),
        ServerNode("orion", "ORION", "ND-03", listOf("162.159.195.0/24"), "162.159.195.1"),
        ServerNode("lyra", "LYRA", "ND-04", listOf("162.159.196.0/24"), "162.159.196.1"),
        ServerNode("titan", "TITAN", "ND-05", listOf("162.159.204.0/24"), "162.159.204.1"),
        ServerNode("helix", "HELIX", "ND-06", listOf("172.65.251.0/24"), "172.65.251.1"),
        ServerNode("pulse", "PULSE", "ND-07", listOf("188.114.96.0/24"), "188.114.96.1"),
        ServerNode("nova", "NOVA", "ND-08", listOf("188.114.97.0/24"), "188.114.97.1"),
        ServerNode("flux", "FLUX", "ND-09", listOf("188.114.98.0/24"), "188.114.98.1"),
        ServerNode("zeta", "ZETA", "ND-10", listOf("188.114.99.0/24"), "188.114.99.1"),
        ServerNode("apex", "APEX", "ND-11", listOf("8.6.112.0/24"), "8.6.112.1"),
    )

    /** Everything the picker shows, in display order. */
    val all: List<ServerNode> = listOf(auto) + nodes

    fun byId(id: String): ServerNode? = all.firstOrNull { it.id == id }

    fun probePort(): Int = PROBE_PORT

    /**
     * Resolves the node currently encoded in [profile]:
     *  - EndpointMode.AUTO                     → [auto]
     *  - EndpointMode.MANUAL_RANGE matching    → the matching node
     *  - anything else (pinned peer, foreign
     *    range typed in Advanced settings)     → null (the UI shows "custom")
     */
    fun selectedIn(profile: ConnectionProfile): ServerNode? = when (profile.endpointMode) {
        EndpointMode.AUTO -> auto
        EndpointMode.MANUAL_RANGE -> {
            val raw = profile.manualRange.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            nodes.firstOrNull { node -> node.cidrs == raw }
        }
        else -> null
    }

    /** Applies [node] to [profile], producing the profile that pins it. */
    fun applyTo(profile: ConnectionProfile, node: ServerNode): ConnectionProfile =
        if (node.id == AUTO_ID) {
            profile.copy(
                endpointMode = EndpointMode.AUTO,
                manualRange = "",
            )
        } else {
            profile.copy(
                endpointMode = EndpointMode.MANUAL_RANGE,
                manualRange = node.rangeSpec,
            )
        }
}
