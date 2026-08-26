package com.soildtunnel.desktop.tunnel

import com.soildtunnel.desktop.Paths
import com.soildtunnel.desktop.core.AutoCandidate
import com.soildtunnel.desktop.core.NetworkFingerprinter
import com.soildtunnel.desktop.core.Diagnostics
import com.soildtunnel.desktop.core.DiagnosticsLog
import com.soildtunnel.desktop.core.EngineMeta
import com.soildtunnel.desktop.core.IpEndpoint
import com.soildtunnel.desktop.core.NetProbe
import com.soildtunnel.desktop.model.Noize
import com.soildtunnel.desktop.core.PortProbe
import com.soildtunnel.desktop.model.Protocol
import com.soildtunnel.desktop.core.SmartAuto
import com.soildtunnel.desktop.core.StickyServer
import com.soildtunnel.desktop.Strings
import com.soildtunnel.desktop.core.TunnelConfig
import com.soildtunnel.desktop.core.UsageStore
import com.soildtunnel.desktop.model.ConnectionProfile
import com.soildtunnel.desktop.model.ConnectionState
import com.soildtunnel.desktop.model.EndpointMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    fun setState(s: ConnectionState) { state.value = s }
    fun setIpInfo(v: IpEndpoint?) { ipInfo.value = v }
    fun setIpLoading(v: Boolean) { ipLoading.value = v }

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

    // ── Pre-flight checks ──────────────────────────────────────────────

    private fun preflightCheck(): String? {
        val bin = Paths.engineBinary()
        if (!bin.exists()) {
            return "Engine binary not found at: ${bin.absolutePath}\n" +
                "Please reinstall the SoildTunnel package."
        }
        if (!bin.canExecute()) {
            return "Engine binary is not executable: ${bin.absolutePath}\n" +
                "Run: chmod +x ${bin.absolutePath}"
        }
        return null
    }

    // ── Session ────────────────────────────────────────────────────────

    private suspend fun session(userProfile: ConnectionProfile) {
        try {
            val preflightError = preflightCheck()
            if (preflightError != null) {
                DiagnosticsLog.e(TAG, preflightError)
                setState(ConnectionState.Error(preflightError))
                return
            }

            setState(ConnectionState.Launching)
            DiagnosticsLog.i(TAG, "Connecting…")
            PortProbe.awaitClosed(TunnelConfig.SOCKS_HOST, TunnelConfig.SOCKS_PORT, 5_000)

            val sticky = StickyServer.usable()

            val resolved: ConnectionProfile = try {
                if (userProfile.protocol == Protocol.AUTO) {
                    smartAutoLadder(userProfile, sticky?.range)
                } else {
                    directLadder(userProfile)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticsLog.e(TAG, "Connection failed: ${e.message}")
                setState(ConnectionState.Error("Connection failed: ${e.message ?: "unknown error"}"))
                return
            } ?: run {
                setState(ConnectionState.Error(Strings.get("err_auto_failed")))
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticsLog.e(TAG, "Connect failed: ${e.javaClass.simpleName}: ${e.message}")
            setState(ConnectionState.Error("Connect failed: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            stopEngine()
            stopTunHelper()
            UsageStore.endSession()
            connectedSince.value = null
        }
    }

    // ── Ladder ─────────────────────────────────────────────────────────

    private suspend fun smartAutoLadder(
        userProfile: ConnectionProfile,
        stickyRange: String?,
    ): ConnectionProfile? {
        setState(ConnectionState.Connecting)
        DiagnosticsLog.i(TAG, "Fingerprinting network…")
        updateNotification(Strings.get("state_analyzing"))
        val fp = NetworkFingerprinter.fingerprint()
        val plan = SmartAuto.buildPlan(userProfile, fp, stickyRange)
        return runLadder(plan, "Smart Auto")
    }

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
        return kotlinx.coroutines.runBlocking { runLadder(plan, profile.protocol.name) }
    }

    private suspend fun runLadder(plan: List<AutoCandidate>, label: String): ConnectionProfile? {
        var lastEngineLog = ""
        for ((index, cand) in plan.withIndex()) {
            if (!currentRunActive()) return null
            setState(ConnectionState.Connecting)
            updateNotification(cand.label)
            DiagnosticsLog.i(TAG, "Strategy ${index + 1}/${plan.size}: ${cand.label}")
            try {
                val ok = attempt(cand.profile, cand.timeoutMs)
                if (ok) return cand.profile
                lastEngineLog = DiagnosticsLog.recentEngineLines(3)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticsLog.w(TAG, "Strategy ${cand.label} threw: ${e.message}")
                lastEngineLog = DiagnosticsLog.recentEngineLines(3)
            }
            stopEngine()
        }
        DiagnosticsLog.e(TAG, "All ${plan.size} $label strategies failed.\nLast engine output:\n$lastEngineLog")
        return null
    }

    // ── Single attempt ─────────────────────────────────────────────────

    private suspend fun attempt(profile: ConnectionProfile, budgetMs: Long): Boolean {
        spawnEngine(profile) ?: return false
        val opened = PortProbe.awaitOpen(
            TunnelConfig.SOCKS_HOST,
            TunnelConfig.SOCKS_PORT,
            budgetMs,
        ) { engineAlive() }
        if (!opened) {
            val alive = engineAlive()
            val detail = if (!alive) "Engine exited prematurely."
            else "Engine still scanning — SOCKS5 port never opened."
            DiagnosticsLog.w(TAG, "Port probe failed: $detail")
            return false
        }
        setState(ConnectionState.Verifying)
        return runCatching { Diagnostics.run() }.getOrDefault(false)
    }

    // ── Engine process ─────────────────────────────────────────────────

    private fun spawnEngine(profile: ConnectionProfile): Process? {
        val bin = Paths.engineBinary()
        DiagnosticsLog.i(TAG, "Engine binary: ${bin.absolutePath} (exists=${bin.exists()}, exec=${bin.canExecute()})")
        if (!bin.exists()) {
            setState(ConnectionState.Error("Engine binary not found: ${bin.name}"))
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
            DiagnosticsLog.e(TAG, "Engine spawn failed: ${e.javaClass.simpleName}: ${e.message}")
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

    // ── Supervise ──────────────────────────────────────────────────────

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

    // ── TUN helper ─────────────────────────────────────────────────────

    private fun startTunHelper(profile: ConnectionProfile) {
        if (tunActive) return
        val candidate = Paths.hevHelper() ?: run {
            DiagnosticsLog.i(TAG, "TUN helper not found — running in proxy mode.")
            return
        }
        if (!supportsPkexec()) {
            DiagnosticsLog.w(TAG, "pkexec not available — system-wide routing requires a polkit agent.")
            return
        }
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
