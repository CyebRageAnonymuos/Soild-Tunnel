package com.soildtunnel.desktop.core

import com.soildtunnel.desktop.model.ConnectionProfile
import com.soildtunnel.desktop.model.EndpointMode
import com.soildtunnel.desktop.model.Noize
import com.soildtunnel.desktop.model.Protocol
import com.soildtunnel.desktop.model.ScanMode

/** One concrete, ready-to-launch strategy in the Smart Auto ladder. */
data class AutoCandidate(
    val profile: ConnectionProfile,
    val timeoutMs: Long,
    val label: String,
)

/**
 * ROOT-CAUSE FIX for "Auto never connects": the old AUTO simply passed NO
 * protocol flag to the engine and hoped its default worked — there was no
 * intelligence and no fallback, so on any filtered network it just hung while
 * every manually chosen protocol worked fine.
 *
 * Smart Auto instead works like an engineer would:
 *
 * 1. FINGERPRINT ([NetworkFingerprinter.fingerprint]) — before the engine even
 * launches, probe the real network DIRECTLY:
 *       - UDP health: real DNS queries over UDP/53 to 1.1.1.1 and 8.8.8.8.
 *       - SNI DPI: a full TLS handshake to 1.1.1.1:443 carrying the SNI
 * "www.cloudflare.com" (with hostname verification, no data sent).
 *       - WARP edge reachability: TCP connect latency to one representative
 * host in each built-in Cloudflare WARP range.
 * 2. CLASSIFY the DPI behaviour into a [DpiClass].
 * 3. PLAN ([buildPlan]) — build an ordered ladder of concrete strategies
 * (protocol + noize + fragment/ECH + the ranges that actually answered),
 * most-likely-to-succeed first, plus a full-range last resort.
 * 4. The caller then walks the ladder: each candidate gets a real connect
 * attempt gated by the 4-step self-test; the first one that passes wins.
 *
 * Every probe result and every decision is written to the in-app log, so the
 * user can see exactly WHY Smart Auto picked what it picked.
 */
object SmartAuto {
    private const val TAG = "auto"

    // ---- Stage 3: turn the fingerprint into an ordered strategy ladder ----

    fun buildPlan(user: ConnectionProfile, fp: NetworkFingerprint, stickyRange: String? = null): List<AutoCandidate> {
        // Prefer the ranges that actually answered, fastest first. Narrowing
        // the scan to live ranges is what makes each attempt FAST; the last
        // resort below still covers the full built-in ranges.
        val reachable = fp.edgeLatencyMs.filterValues { it >= 0 }.entries.sortedBy { it.value }
        var bestRanges = reachable.take(2).joinToString(", ") { it.key }
        // A 24h sticky pin overrides fresh probing so the exit stays put.
        if (!stickyRange.isNullOrBlank()) bestRanges = stickyRange.trim()
        // NEVER override an endpoint the user pinned manually in Settings.
        val keepUserEndpoint = user.endpointMode != EndpointMode.AUTO

        fun cand(
            proto: Protocol,
            noize: Noize,
            h2: Boolean = false,
            frag: Boolean = false,
            ech: Boolean = false,
        ): AutoCandidate {
            // Respect a stronger user-chosen obfuscation; bias bare profiles to
            // LIGHT noize on Iranian cellular where fingerprinting is routine.
            var mergedNoize = if (user.noize.ordinal >= noize.ordinal) user.noize else noize
            if (mergedNoize == Noize.OFF && fp.iranCellular) mergedNoize = Noize.LIGHT
            var p = user.copy(
                protocol = proto,
                noize = mergedNoize,
                masqueHttp2 = user.masqueHttp2 || (h2 && proto == Protocol.MASQUE),
                fragment = user.fragment || frag,
                ech = user.ech || ech,
                // TURBO per attempt: the ladder's speed comes from trying the
                // NEXT strategy quickly, not from one long exhaustive scan.
                scanMode = ScanMode.TURBO,
            )
            if (!keepUserEndpoint && bestRanges.isNotEmpty()) {
                p = p.copy(endpointMode = EndpointMode.MANUAL_RANGE, manualRange = bestRanges)
            }
            val label = buildString {
                append(proto.name)
                append(" · noize=").append(p.noize.name.lowercase())
                if (p.masqueHttp2) append(" · h2")
                if (p.fragment) append(" · fragment")
                if (p.ech) append(" · ech")
                if (!keepUserEndpoint && bestRanges.isNotEmpty()) append(" · ranges[").append(bestRanges).append("]")
                append(" · scan=turbo")
            }
            return AutoCandidate(p, p.connectTimeoutMs(), label)
        }

        val ladder = when (fp.dpiClass) {
            DpiClass.OPEN -> listOf(
                cand(Protocol.WIREGUARD, Noize.OFF),
                cand(Protocol.MASQUE, Noize.OFF),
                cand(Protocol.GOOL, Noize.LIGHT),
            )
            DpiClass.SNI_FILTERING -> listOf(
                cand(Protocol.MASQUE, Noize.BALANCED, frag = true, ech = true),
                cand(Protocol.GOOL, Noize.BALANCED),
                cand(Protocol.WIREGUARD, Noize.BALANCED),
            )
            DpiClass.UDP_THROTTLED -> listOf(
                cand(Protocol.MASQUE, Noize.LIGHT, h2 = true, frag = true, ech = true),
                cand(Protocol.GOOL, Noize.AGGRESSIVE),
                cand(Protocol.WIREGUARD, Noize.GFW),
            )
            DpiClass.HOSTILE -> listOf(
                cand(Protocol.MASQUE, Noize.GFW, h2 = true, frag = true, ech = true),
                cand(Protocol.GOOL, Noize.AGGRESSIVE),
                cand(Protocol.WIREGUARD, Noize.AGGRESSIVE),
            )
        }

        // Last resort: the top strategy again, but scanning the engine's FULL
        // built-in ranges with the user's own scan mode — covers the rare case
        // where the probe-narrowed ranges themselves were the problem.
        val first = ladder.first()
        var fbProfile = first.profile.copy(scanMode = user.scanMode)
        if (!keepUserEndpoint) {
            fbProfile = fbProfile.copy(endpointMode = EndpointMode.AUTO, manualRange = user.manualRange)
        }
        val fallback = AutoCandidate(
            fbProfile,
            fbProfile.connectTimeoutMs(),
            "${fbProfile.protocol.name} · noize=${fbProfile.noize.name.lowercase()} · full built-in ranges " +
                "· scan=${user.scanMode.name.lowercase()} (last resort)",
        )

        val plan = (ladder + fallback).distinctBy { it.profile }
        DiagnosticsLog.i(TAG, "Strategy ladder for ${fp.dpiClass} (${plan.size} steps):")
        plan.forEachIndexed { i, c -> DiagnosticsLog.i(TAG, "  ${i + 1}. ${c.label}") }
        return plan
    }
}
