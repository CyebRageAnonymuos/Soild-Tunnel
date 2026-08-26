package com.soildtunnel.desktop.tunnel

import com.soildtunnel.desktop.Paths
import com.soildtunnel.desktop.core.AutoCandidate
import com.soildtunnel.desktop.core.Diagnostics
import com.soildtunnel.desktop.core.DiagnosticsLog
import com.soildtunnel.desktop.core.EngineMeta
import com.soildtunnel.desktop.core.IpEndpoint
import com.soildtunnel.desktop.core.NetProbe
import com.soildtunnel.desktop.core.Noize
import com.soildtunnel.desktop.core.PortProbe
import com.soildtunnel.desktop.core.Protocol
import com.soildtunnel.desktop.core.SmartAuto
import com.soildtunnel.desktop.core.StickyServer
import com.soildtunnel.desktop.Strings
import com.soildtunnel.desktop.core.TunnelConfig
import com.soildtunnel.desktop.core.UsageStore
import com.soildtunnel.desktop.model.ConnectionProfile
import com.soildtunnel.desktop.model.ConnectionState
import com.soildtunnel.desktop.model.EndpointMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Desktop twin of the Android VPN service: owns the engine subprocess,
 * walks the connect ladder, verifies end-to-end and supervises restarts.
 * System-wide TUN routing is delegated to the privileged helper when the
 * user grants it; without it the session still works as a local proxy.
 */
object SoildTunnelController {

    val state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectedSince = MutableStateFlow<Long?>(null)
    val ipInfo = MutableStateFlow<IpEndpoint?>(null)
    val ipLoading = MutableStateFlow(false)

    @Volatile var tunActive: Boolean = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runJob: Job? = null

    @Volatile
    private var engine: Process? = null

    fun setState(s: ConnectionState) {
        state.value = s
    }

    fun setIpInfo(v: IpEndpoint?) {
        ipInfo.value = v
    }

    fun setIpLoading(v: Boolean) {
        ipLoading.value = v
    }

    fun offerTunnelIpInfo(v: IpEndpoint) {
        if (ipInfo.value?.viaTunnel != true || ipInfo.value?.ip != v.ip) ipInfo.value = v
    }

    fun isConnectedOrBusy(): Boolean = with(state.value) {
        this is ConnectionState.Connected ||
            this is ConnectionState.Launching ||
            this is ConnectionState.Connecting ||
            this is ConnectionState.Verifying ||
            this is ConnectionState.Reconnecting
    }

    fun connect(profile: ConnectionProfile) {
        if (isConnectedOrBusy()) return
        runJob?.cancel()
        runJob = scope.launch { session(profile) }
    }

    fun disconnect() {
        val job = runJob
        runJob = null
        job?.cancel()
        scope.launch {
            stopEngine()
            Diagnostics.resetChecks()
            EngineMeta.setProtocol("")
            EngineMeta.setEndpoint("")
            UsageStore.endSession()
            stopTunHelper()
            connectedSince.value = null
            ipInfo.value = null
            setState(ConnectionState.Idle)
        }
    }

    private suspend fun session(userProfile: ConnectionProfile) {
        try {
            setState(ConnectionState.Launching)
            DiagnosticsLog.i(TAG, "Connecting…")
            PortProbe.awaitClosed(TunnelConfig.SOCKS_HOST, TunnelConfig.SOCKS_PORT, 5_000)

            // 24h sticky pin, same policy as the Android app.
            val sticky = StickyServer.usable()

            val resolved: ConnectionProfile = if (userProfile.protocol == Protocol.AUTO) {
                smartAutoLadder(userProfile, sticky?.range)
            } else {
                directLadder(userProfile)
            } ?: run {
                setState(ConnectionState.Error(Strings.get("err_engine_died")))
                return
            }

            EngineMeta.setProtocol(resolved.protocol.name)
            if (resolved.manualPeer.isNotBlank()) EngineMeta.setEndpoint(resolved.manualPeer)

            if (!userProfile.hasManualPeer && userProfile.endpointMode == EndpointMode.AUTO &&
                resolved.manualRange.isNotBlank()
            ) {
                if (sticky != null) {
                    if (!resolved.manualRange.contains(sticky.range)) StickyServer.clear()
                } else {
                    StickyServer.save(resolved.manualRange.trim())
                }
            }

            connectedSince.value = System.currentTimeMillis()
            UsageStore.startSession()
            startTunHelper(resolved)
            setState(ConnectionState.Connected("${TunnelConfig.SOCKS_HOST}:${TunnelConfig.SOCKS_PORT}"))
            DiagnosticsLog.i(TAG, "All checks passed - tunnel is ready.")

            // Exit IP + flag for the telemetry card, fetched through the tunnel.
            ipLoading.value = true
            scope.launch {
                val info = runCatching {
                    NetProbe.fetchIpInfoViaSocksWithRetry(
                        TunnelConfig.SOCKS_HOST,
                        TunnelConfig.SOCKS_PORT,
                    )
                }.getOrNull()
                if (currentRunActive() && info != null) {
                    offerTunnelIpInfo(IpEndpoint(info.ip, info.countryCode, true))
                }
                ipLoading.value = false
            }

            supervise(resolved)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticsLog.e(TAG, "Connect failed: ${e.message}")
            setState(ConnectionState.Error(e.message ?: "connection failed"))
        } finally {
            stopEngine()
            stopTunHelper()
            UsageStore.endSession()
            connectedSince.value = null
        }
    }

    /** SMART AUTO: fingerprint, then walk the strategy ladder. */
    private suspend fun smartAutoLadder(
        userProfile: ConnectionProfile,
        stickyRange: String?,
    ): ConnectionProfile? {
        setState(ConnectionState.Connecting)
        updateNotification(Strings.get("state_analyzing"))
        val fp = SmartAuto.fingerprint()
        val plan = SmartAuto.buildPlan(userProfile, fp, stickyRange)
        return runLadder(plan)
    }

    /**
     * Hand-picked protocol: one pass as configured on a capped budget, then
     * the same protocol hardened (anti-DPI). The user's choice never swaps.
     */
    private fun directLadder(profile: ConnectionProfile): ConnectionProfile? {
        val fullBudget = profile.connectTimeoutMs()
        val hardenedNoize = if (profile.noize == Noize.OFF) Noize.FIREWALL else profile.noize
        val masque = profile.protocol == Protocol.MASQUE
        val hardened = profile.copy(
            noize = hardenedNoize,
            masqueHttp2 = profile.masqueHttp2 || masque,
            fragment = profile.fragment || masque,
            ech = profile.ech || masque,
        )
        val plan = if (hardened == profile) {
            listOf(AutoCandidate(profile, fullBudget, "${profile.protocol.name} · as configured"))
        } else {
            listOf(
                AutoCandidate(profile, fullBudget.coerceAtMost(FIRST_PASS_MAX_MS), "${profile.protocol.name} · as configured"),
                AutoCandidate(hardened, fullBudget, "${profile.protocol.name} · hardened pass"),
            )
        }
        return runBlockingLadder(plan)
    }

    private suspend fun runLadder(plan: List<AutoCandidate>): ConnectionProfile? {
        for ((index, cand) in plan.withIndex()) {
            if (!currentRunActive()) return null
            setState(ConnectionState.Connecting)
            updateNotification(cand.label)
            DiagnosticsLog.i(TAG, "Strategy ${index + 1}/${plan.size}: ${cand.label}")
            val ok = attempt(cand.profile, cand.budgetMs)
            if (ok) return cand.profile
            stopEngine()
        }
        return null
    }

    private fun runBlockingLadder(plan: List<AutoCandidate>): ConnectionProfile? =
        kotlinx.coroutines.runBlocking { runLadder(plan) }

    /** One engine attempt: spawn, wait for the SOCKS port, self-test. */
    private suspend fun attempt(profile: ConnectionProfile, budgetMs: Long): Boolean {
        spawnEngine(profile) ?: return false
        val opened = PortProbe.awaitOpen(
            TunnelConfig.SOCKS_HOST,
            TunnelConfig.SOCKS_PORT,
            budgetMs,
        ) { engineAlive() }
        if (!opened) return false
        setState(ConnectionState.Verifying)
        return runCatching { Diagnostics.run() }.getOrDefault(false)
    }

    private fun spawnEngine(profile: ConnectionProfile): Process? {
        val bin = Paths.engineBinary()
        if (!bin.exists()) {
            DiagnosticsLog.e(TAG, "Engine binary missing at ${bin.absolutePath}")
            setState(ConnectionState.Error("Engine binary not found: " + bin.name))
            return null
        }
        bin.setExecutable(true)
        return try {
            val pb = ProcessBuilder(listOf(bin.absolutePath) + profile.toArgs())
            pb.directory(Paths.workDir)
            val env = pb.environment()
            env.putAll(profile.toEnv())
            env["HOME"] = Paths.workDir.absolutePath
            env["TMPDIR"] = Paths.workDir.absolutePath
            pb.redirectErrorStream(true)
            val p = pb.start()
            engine = p
            Thread({
                p.inputStream.bufferedReader().forEachLine { line ->
                    DiagnosticsLog.i("engine", line)
                    EngineMeta.ingest(line)
                }
            }, "soildtunnel-engine-log").apply { isDaemon = true }.start()
            DiagnosticsLog.i(TAG, "Engine started (pid ${p.pid()}).")
            p
        } catch (e: Exception) {
            DiagnosticsLog.e(TAG, "Engine spawn failed: ${e.message}")
            null
        }
    }

    private fun engineAlive(): Boolean = engine?.isAlive == true

    private suspend fun stopEngine() = withContext(Dispatchers.IO) {
        val p = engine ?: return@withContext
        engine = null
        runCatching {
            p.destroy()
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
        PortProbe.awaitClosed(TunnelConfig.SOCKS_HOST, TunnelConfig.SOCKS_PORT, 3_000)
    }

    /** Keeps the engine alive; retries with backoff if it dies. */
    private suspend fun supervise(profile: ConnectionProfile) {
        var attempt = 0
        val backoff = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)
        val maxRetries = if (profile.smartReconnect) {
            profile.reconnectRetryLimit.coerceIn(1, 50)
        } else 50
        while (currentRunActive()) {
            val p = engine
            if (p != null && p.isAlive) {
                delay(WATCHDOG_MS)
                continue
            }
            if (attempt >= maxRetries) {
                throw IllegalStateException(Strings.get("err_engine_died"))
            }
            val wait = backoff[attempt.coerceAtMost(backoff.size - 1)]
            attempt++
            setState(ConnectionState.Reconnecting(attempt, maxRetries))
            delay(wait)
            val spawned = spawnEngine(profile)
            if (spawned != null && PortProbe.awaitOpen(
                    TunnelConfig.SOCKS_HOST,
                    TunnelConfig.SOCKS_PORT,
                    profile.connectTimeoutMs(),
                ) { engineAlive() }
            ) {
                setState(ConnectionState.Verifying)
                if (runCatching { Diagnostics.run() }.getOrDefault(false)) {
                    attempt = 0
                    setState(ConnectionState.Connected("${TunnelConfig.SOCKS_HOST}:${TunnelConfig.SOCKS_PORT}"))
                } else {
                    DiagnosticsLog.w(TAG, "Self-test failed after engine restart - retrying.")
                    stopEngine()
                }
            }
        }
    }

    private fun currentRunActive(): Boolean = runJob?.isActive == true

    private fun updateNotification(label: String) {
        DiagnosticsLog.d(TAG, label)
    }

    /**
     * TUN helper via pkexec. Failure is non-fatal: the session continues as a
     * local SOCKS/HTTP proxy and the user just sees no system-wide routing.
     */
    private fun startTunHelper(profile: ConnectionProfile) {
        if (tunActive) return
        val helper = File(Paths.dataDir.parentFile, "bin/hev-tun-helper")
        val candidate = sequenceOf(
            helper,
            File("/usr/lib/soildtunnel/hev-tun-helper"),
            File(System.getProperty("compose.application.dir"), "hev-tun-helper"),
            File(System.getProperty("user.dir"), "hev-tun-helper"),
        ).firstOrNull { it.exists() } ?: return
        if (!supportsPkexec()) return
        try {
            val args = mutableListOf(
                "pkexec", "--disable-internal-agent",
                candidate.absolutePath,
                "--mtu", profile.mtu.coerceIn(576, 9000).toString(),
                "--socks", "${TunnelConfig.SOCKS_HOST}:${TunnelConfig.SOCKS_PORT}",
            )
            if (profile.ipv6LeakProtection) args += "--ipv6"
            tunProcess = ProcessBuilder(args).start()
            tunActive = true
            DiagnosticsLog.i(TAG, "TUN helper started (system-wide routing on).")
        } catch (e: Exception) {
            DiagnosticsLog.w(TAG, "TUN helper refused or failed: ${e.message}")
        }
    }

    private fun stopTunHelper() {
        val p = tunProcess ?: return
        tunProcess = null
        tunActive = false
        runCatching {
            p.destroy()
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    private fun supportsPkexec(): Boolean = runCatching {
        ProcessBuilder("which", "pkexec").start().waitFor() == 0
    }.getOrDefault(false)

    @Volatile
    private var tunProcess: Process? = null

    private const val TAG = "controller"
    private const val FIRST_PASS_MAX_MS = 45_000L
    private const val WATCHDOG_MS = 10_000L
}
