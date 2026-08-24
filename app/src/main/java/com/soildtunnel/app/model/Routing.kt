package com.soildtunnel.app.model

/**
 * Per-flow routing verdict, merged into SoildTunnel for the userspace tunnel
 * bridge (per-app blocking). Domain/IP routing rules themselves are enforced
 * inside the Rust engine via `--route-block` / `--route-direct`; these types
 * exist so the bridge can classify flows when it is active.
 */
enum class RoutingMode {
    TUNNEL,
    DIRECT,
    BLOCK
}

data class RoutingRule(
    val pattern: String,
    val mode: RoutingMode
)
