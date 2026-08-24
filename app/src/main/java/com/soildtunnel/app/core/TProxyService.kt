package com.soildtunnel.app.core

import android.util.Log

/**
 * JNI binding for the hev-socks5-tunnel core — via SoildTunnel's OWN bridge
 * library (libsoildtunnel-jni.so, built from scripts/soildtunneltun-jni.c).
 *
 * WHY OUR OWN BRIDGE (root cause of the old "VPN mode never connects" bug):
 * the app used to System.loadLibrary("hev-socks5-tunnel") and rely on hev's
 * bundled hev-jni.c to register the TProxy* natives onto this class. That
 * coupled these Kotlin declarations to WHATEVER JNI signatures the upstream
 * default branch happens to use. Upstream then changed TProxyStartService
 * from '(Ljava/lang/String;I)V' to '(Ljava/lang/String;I)Z', so
 * RegisterNatives inside its JNI_OnLoad failed with:
 * NoSuchMethodError: no static or non-static method
 * "Lcom/soildtunnel/app/core/TProxyService;.TProxyStartService(Ljava/lang/String;I)Z"
 * System.loadLibrary() threw, `available` stayed false, and VPN mode died
 * with "hev native library unavailable" while proxy mode (which never loads
 * hev) kept working.
 *
 * libsoildtunnel-jni.so removes that failure mode categorically:
 *  - It binds ONLY hev's stable public C API (hev_socks5_tunnel_main/quit/
 * stats), which is resolved at LINK time in CI (-Wl,--no-undefined) — any
 * upstream break fails the BUILD, never the user's device.
 *  - It exports conventional Java_* symbols that WE control, matching the
 * external declarations below exactly (also verified at build time).
 *  - It defines its own JNI_OnLoad. ART finds JNI_OnLoad via dlsym() on the
 * loaded library's handle, and dlsym() ALSO searches DT_NEEDED
 * dependencies — so without our own JNI_OnLoad, hev's one ran anyway and
 * its RegisterNatives failed again (seen in the field as
 * 'TProxyStopService()Z'). Ours shadows it, and the build additionally
 * strips hev-jni.c out of libhev-socks5-tunnel.so entirely, so there is
 * no foreign JNI_OnLoad left to find.
 *
 * THREADING (unchanged, do not "simplify"): the tunnel event loop runs on a
 * NATIVE pthread created inside the bridge. hev-task-system swaps the
 * thread's stack pointer for its coroutines; doing that on an ART-attached
 * Java thread corrupts what the runtime expects and kills the app with a
 * native SIGSEGV shortly after real traffic starts.
 *
 * IMPORTANT: this object must NOT be renamed or moved to another package —
 * the bridge's exported symbols encode the exact name
 * "com/soildtunnel/app/core/TProxyService".
 */
object TProxyService {
    /** True if libsoildtunnel-jni.so (+ libhev-socks5-tunnel.so) loaded successfully. */
    @Volatile
    var available: Boolean = false
        private set

    /** Human-readable loader failure, kept for the in-app diagnostics log. */
    @Volatile
    var loadFailure: String? = null
        private set

    init {
        available = try {
            // Loads our bridge; the Android linker automatically pulls in its
            // DT_NEEDED dependency libhev-socks5-tunnel.so from the same APK
            // native-library directory.
            System.loadLibrary("soildtunnel-jni")
            // Build marker: lets diagnostics logs prove WHICH build is on the
            // device (versionName alone cannot distinguish rebuilds of ).
            Log.i("soildtunnel-tunnel", "native bridge libsoildtunnel-jni r3 loaded")
            runCatching {
                DiagnosticsLog.i("tunnel", "native bridge libsoildtunnel-jni r3 loaded")
            }
            loadFailure = null
            true
        } catch (t: Throwable) {
            // UnsatisfiedLinkError is an Error (NOT an Exception) and would
            // otherwise escape `catch (Exception)` blocks and crash the app.
            Log.e("soildtunnel-tunnel", "Failed to load libsoildtunnel-jni.so", t)
            loadFailure = "${t::class.java.simpleName}: ${t.message ?: "unknown native loader error"}"
            runCatching {
                DiagnosticsLog.e("tunnel", "FATAL: could not load libsoildtunnel-jni.so: $loadFailure")
            }
            false
        }
    }

    /**
     * Starts the tunnel event loop on a native pthread created by the bridge.
     * Returns true when the tunnel thread was spawned (or is already running),
     * false when spawning failed.
     */
    @JvmStatic
    @Suppress("FunctionName")
    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    @JvmStatic
    @Suppress("FunctionName")
    external fun TProxyStopService()

    /** [tx_packets, tx_bytes, rx_packets, rx_bytes] from hev's stats API. */
    @JvmStatic
    @Suppress("FunctionName")
    external fun TProxyGetStats(): LongArray?
}
