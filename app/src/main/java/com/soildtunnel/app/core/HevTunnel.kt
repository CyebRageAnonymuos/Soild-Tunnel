package com.soildtunnel.app.core

/**
 * Thin facade over [TProxyService] (hev-socks5-tunnel's own JNI interface).
 *
 * hev MUST run inside the *same process* that owns the VpnService TUN file
 * descriptor (fds are per-process), and its event loop MUST run on a native
 * pthread — not a Java thread. TProxyStartService satisfies both: it returns
 * immediately after spawning hev's own native thread.
 *
 * See TProxyService.kt for the full root-cause history (custom-wrapper SIGSEGV
 * on ART-attached threads).
 */
object HevTunnel {
    @Volatile
    private var running = false

    /** True if the native core is available to run. */
    fun isAvailable(): Boolean = TProxyService.available

    /** Starts hev on its own native thread. No-op if already running. */
    fun start(configPath: String, tunFd: Int) {
        if (!TProxyService.available) {
            val detail = TProxyService.loadFailure ?: "unknown loader error"
            DiagnosticsLog.e(
                "tunnel",
                "libsoildtunnel-jni.so (native tunnel bridge) not loaded — cannot start tunnel. $detail",
            )
            throw IllegalStateException("hev native library unavailable: $detail")
        }
        if (running) return
        DiagnosticsLog.i("tunnel", "hev-socks5-tunnel starting on native thread (fd=$tunFd)")
        // Upstream's TProxyStartService now returns whether the tunnel really
        // started (bad YAML config, invalid fd, …). Surface a hard failure
        // instead of pretending the tunnel is up and "connecting" forever.
        val started = TProxyService.TProxyStartService(configPath, tunFd)
        if (!started) {
            DiagnosticsLog.e("tunnel", "hev-socks5-tunnel refused to start (config/fd rejected)")
            throw IllegalStateException("hev-socks5-tunnel failed to start")
        }
        running = true
    }

    fun isAlive(): Boolean = running

    /**
     * Raw cumulative counters from hev's JNI, or null when the core isn't
     * running. The array is [tx_packets, tx_bytes, rx_packets, rx_bytes].
     * Prefer [traffic] over indexing this directly — it fixes the direction.
     */
    fun stats(): LongArray? {
        if (!TProxyService.available || !running) return null
        return runCatching { TProxyService.TProxyGetStats() }.getOrNull()
    }

    /** Cumulative download/upload byte + packet counters, direction-corrected. */
    data class Traffic(
        val downloadBytes: Long,
        val uploadBytes: Long,
        val downloadPackets: Long,
        val uploadPackets: Long,
    )

    /**
     * Direction-correct traffic totals since the core started, or null when the
     * core isn't running.
     *
     * DIRECTION FIX: hev-socks5-tunnel's TProxyGetStats() returns
     * [tx_packets, tx_bytes, rx_packets, rx_bytes] measured on the SOCKS side:
     *   - TX = bytes the core SENT to the proxy  = device → internet = UPLOAD
     *   - RX = bytes the core RECEIVED from proxy = internet → device = DOWNLOAD
     * The previous UI mapped TX→download / RX→upload, so the meters were swapped
     * (a heavy downloader saw their big number under “upload” and vice-versa).
     * The single source of truth for the mapping now lives here.
     */
    fun traffic(): Traffic? {
        val s = stats() ?: return null
        if (s.size < 4) return null
        val uploadPackets = s[0].coerceAtLeast(0L)
        val uploadBytes = s[1].coerceAtLeast(0L)
        val downloadPackets = s[2].coerceAtLeast(0L)
        val downloadBytes = s[3].coerceAtLeast(0L)
        return Traffic(
            downloadBytes = downloadBytes,
            uploadBytes = uploadBytes,
            downloadPackets = downloadPackets,
            uploadPackets = uploadPackets,
        )
    }

    /** Requests hev to quit. Safe to call repeatedly. */
    fun stop() {
        if (!TProxyService.available) return
        if (!running) return
        try {
            TProxyService.TProxyStopService()
            DiagnosticsLog.i("tunnel", "hev-socks5-tunnel stop requested")
        } catch (t: Throwable) {
            DiagnosticsLog.w("tunnel", "TProxyStopService failed: ${t.message}")
        }
        running = false
    }
}
