package com.soildtunnel.app.core

import android.util.Log
import com.soildtunnel.app.BuildConfig
import com.soildtunnel.app.model.ConnectionProfile
import java.io.File

/**
 * Runs the native `soildtunnel` engine (shipped as libsoildtunnel-core.so) as a
 * child process. On Android an executable packaged in jniLibs is extracted to
 * nativeLibraryDir with the exec bit set, which is exactly what we run.
 */
class SoildTunnelProcess(
    private val nativeLibDir: String,
    private val workingDir: File,
) {
    private var process: Process? = null

    fun start(profile: ConnectionProfile) {
        val bin = File(nativeLibDir, "libsoildtunnel-core.so")
        if (!bin.exists()) {
            throw IllegalStateException("Engine binary missing: ${bin.absolutePath}")
        }

        val command = mutableListOf(bin.absolutePath).apply { addAll(profile.toArgs()) }
        val builder = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
        builder.environment().apply {
            putAll(profile.toEnv())
            put("HOME", workingDir.absolutePath)
            put("TMPDIR", workingDir.absolutePath)
        }

        val proc = builder.start()
        process = proc

        DiagnosticsLog.i("engine", "Spawned ${bin.name} ${profile.toArgs().joinToString(" ")}")
        // Drain stdout/stderr so a full pipe never blocks the engine, mirroring
        // every line into both logcat and the in-app diagnostics panel.
        Thread({
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach {
                        // SECURITY FIX: the engine's stdout (endpoints, exit
                        // IPs, config echo) must not be mirrored to Logcat in
                        // release builds — Logcat is world-readable via adb and
                        // ends up in bug reports. The in-app diagnostics panel
                        // (app-private file) still receives every line below.
                        if (BuildConfig.DEBUG) Log.i("soildtunnel-engine", it)
                        DiagnosticsLog.d("engine", it)
                        // Desktop-parity info row: pick out the endpoint the
                        // engine selected (no-op for every other line).
                        EngineMeta.ingest(it)
                    }
                }
            } catch (_: Exception) {
            } finally {
                DiagnosticsLog.w("engine", "Engine output stream closed.")
            }
        }, "soildtunnel-log").apply { isDaemon = true }.start()
    }

    fun isAlive(): Boolean = process?.isAlive == true

    /**
     * Blocks until the engine exits or [timeoutMs] elapses; returns true if it
     * exited.
     *
     *  CPU FIX: the VpnService supervisor used to poll `isAlive()` every
     * two seconds for the whole life of the tunnel. That is thousands of
     * pointless wake-ups per session on a connection that is perfectly
     * healthy, and it keeps the CPU out of deep idle. `Process.waitFor` parks
     * the supervisor coroutine on the OS instead: it costs nothing while the
     * engine is running and returns the moment the engine actually dies, so
     * crash detection is FASTER than the old poll while using less power.
     *
     * The bounded overload is used so the supervisor still re-checks its own
     * cancellation state periodically.
     */
    suspend fun awaitExit(timeoutMs: Long): Boolean =
        runCatching {
            // DISCONNECT-LATENCY ROOT CAUSE (fixed): `Process.waitFor` is a
            // BLOCKING java call. Coroutine cancellation cannot interrupt a
            // blocking call, so when the user tapped disconnect the service
            // sat inside this wait until the whole 60 s window expired — which
            // is exactly the 30–50 s "Disconnecting…" freeze. `runInterruptible`
            // maps cancellation onto a real thread interrupt, so `waitFor`
            // throws immediately and the teardown continues within
            // milliseconds — while still costing zero polling when idle.
            kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                process?.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) ?: true
            }
        }.getOrDefault(false)

    /**
     * Stops the engine and does not return until the OS has really reaped it.
     *
     *  PROTOCOL-SWITCH FIX: this used to be a fire-and-forget
     * `destroy()`. `destroy()` only *asks* the process to exit, so the old
     * engine was often still alive — and still holding the local SOCKS5
     * listener on 127.0.0.1:1819 — while the next connect was already
     * spawning a new engine. The new engine then either failed to bind or the
     * app's port probe saw the DYING engine's socket and declared "port is
     * up" far too early, which is exactly why switching protocols felt like it
     * hung for tens of seconds and then had to retry. We now wait for the
     * process to actually exit and escalate to SIGKILL if it does not.
     */
    fun stop() {
        val proc = process ?: return
        process = null
        runCatching {
            proc.destroy()
            // Give the engine a very short, fixed grace period, then SIGKILL.
            // Deliberately short: the user is waiting for the button to turn
            // grey, and the next connect independently waits for the local
            // proxy port to be released, so nothing depends on a long wait
            // here.
            if (!proc.waitFor(GRACEFUL_EXIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
            }
        }
    }

    private companion object {
        /** How long a polite SIGTERM gets before we escalate to SIGKILL. */
        const val GRACEFUL_EXIT_MS = 250L
    }
}
