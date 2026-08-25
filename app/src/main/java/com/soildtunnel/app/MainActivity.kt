package com.soildtunnel.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.soildtunnel.app.core.SoildTunnelController
import com.soildtunnel.app.core.IpEndpoint
import com.soildtunnel.app.core.NetProbe
import com.soildtunnel.app.core.TunnelConfig
import com.soildtunnel.app.data.OnboardingStore
import com.soildtunnel.app.data.ProfileStore
import com.soildtunnel.app.model.ConnectionProfile
import com.soildtunnel.app.model.ConnectionState
import com.soildtunnel.app.model.isBusy
import com.soildtunnel.app.model.isConnected
import com.soildtunnel.app.ui.HomeScreen
import com.soildtunnel.app.ui.OnboardingScreen
import com.soildtunnel.app.ui.theme.SoildTunnelTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var profileStore: ProfileStore

    /** Feature merge: first-run onboarding gate. */
    private lateinit var onboardingStore: OnboardingStore

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.soildtunnel.app.core.LocaleStore.wrap(newBase))
    }

    // Holds the profile to connect with once VPN consent is granted.
    private var pendingProfile: ConnectionProfile? = null

    // ------------------------------------------------------------------
    // SCRAMBLED-INPUT FIX (root cause): the UI used to render the settings —
    // including the ip:port / CIDR text fields — straight from the DataStore
    // flow while every keystroke was saved asynchronously. Fast typing raced
    // that disk round-trip: a keystroke was applied on top of a STALE value
    // that echoed back a moment later, so digits were dropped/reordered
    // ("127.0.0.1" -> "27.0.0.11") in EVERY locale, English and Persian alike.
    //
    // Fix: the UI owns a synchronous in-memory profile state updated
    // immediately on every change. DataStore is demoted to plain background
    // persistence: a single collector writes the LATEST snapshot (conflated),
    // so saves can never interleave or feed stale values back into the UI.
    // ------------------------------------------------------------------
    private val uiProfile = MutableStateFlow<ConnectionProfile?>(null)
    private val profileSaves = MutableSharedFlow<ConnectionProfile>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                pendingProfile?.let { SoildTunnelController.connect(this, it) }
            }
            pendingProfile = null
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        profileStore = ProfileStore(applicationContext)
        onboardingStore = OnboardingStore(applicationContext)

        // Load the persisted profile ONCE as the initial UI state; from then
        // on the in-memory state is the single source of truth for the UI.
        // compareAndSet: if the user already changed something before the
        // initial load finished, never overwrite their edit.
        lifecycleScope.launch {
            uiProfile.compareAndSet(null, profileStore.profile.first())
        }
        // Single background writer persisting the latest profile snapshot.
        lifecycleScope.launch {
            profileSaves.conflate().collect { snapshot -> profileStore.save(snapshot) }
        }

        maybeRequestNotificationPermission()

        // Feature merge: a previous run died with an uncaught JVM
        // exception — open the saved crash report once so the user can see and
        // copy it. The report file deletes itself on dismiss.
        if (savedInstanceState == null && File(filesDir, SoildTunnelApp.CRASH_FILE).exists()) {
            startActivity(Intent(this, CrashReportActivity::class.java))
        }

        // Launched from the Quick Settings tile while VPN consent was still
        // missing: run the normal connect flow, which shows the system's VPN
        // consent dialog and then connects.
        if (intent?.getBooleanExtra(EXTRA_CONNECT_ON_LAUNCH, false) == true) {
            intent.removeExtra(EXTRA_CONNECT_ON_LAUNCH)
            lifecycleScope.launch {
                val current = SoildTunnelController.state.value
                if (!current.isConnected && !current.isBusy) {
                    toggleConnection(current)
                }
            }
        }

        setContent {
            SoildTunnelTheme {
                // Feature merge: first-run onboarding gate. initial =
                // true so upgrading users never see a flash of the pager; a
                // fresh install flips to the pager as soon as the (fast)
                // DataStore read lands.
                val onboardingDone by onboardingStore.completed.collectAsState(initial = true)
                val state by SoildTunnelController.state.collectAsState()
                // Synchronous UI profile state (see uiProfile above); null
                // only until the one-time initial load completes.
                val profile by uiProfile.collectAsState()
                val connectedSince by SoildTunnelController.connectedSince.collectAsState()
                val ipInfo by SoildTunnelController.ipInfo.collectAsState()
                val ipLoading by SoildTunnelController.ipLoading.collectAsState()

                // Refresh the shown IP whenever the connection phase flips:
                // - connected -> exit server IP (through the SOCKS proxy) + flag
                // - idle -> the user's real operator IP (direct)
                // NOTE: any resting, non-busy state (Idle OR Error/failed
                // connect) must show the real IP — previously Error fell into
                // "busy" and the operator IP was never fetched after a failure.
                val phase = when {
                    state.isConnected -> "connected"
                    state.isBusy -> "busy"
                    else -> "idle"
                }
                LaunchedEffect(phase) {
                    when (phase) {
                        "connected" -> {
                            // FLAG-FLICKER FIX: the automatic self-test
                            // (Diagnostics) is the single owner of the exit-IP
                            // lookup. This block used to fire its OWN parallel
                            // lookup; whichever finished last overwrote the
                            // badge, and because geo providers can disagree
                            // about the exit country, the flag flickered or
                            // suddenly changed. Now we only WAIT for the
                            // self-test's result and fetch ourselves purely as
                            // a last-resort fallback (guarded by
                            // offerTunnelIpInfo, so it can never overwrite).
                            SoildTunnelController.setIpLoading(true)
                            // CPU FIX: this used to busy-poll a StateFlow
                            // every 250 ms for up to 100 s — as many as 400
                            // pointless wake-ups on the UI dispatcher right
                            // after connecting, exactly when the device is
                            // already busy. StateFlow is observable, so we now
                            // SUSPEND until the value we are waiting for
                            // actually arrives (zero wake-ups in between) and
                            // simply bound that wait with a timeout.
                            withTimeoutOrNull(100_000L) {
                                SoildTunnelController.ipInfo.first { it?.viaTunnel == true }
                            }
                            if (SoildTunnelController.ipInfo.value?.viaTunnel != true) {
                                val info = withContext(Dispatchers.IO) {
                                    NetProbe.fetchIpInfoViaSocksWithRetry(
                                        TunnelConfig.SOCKS_HOST,
                                        TunnelConfig.SOCKS_PORT,
                                    )
                                }
                                if (info != null) {
                                    SoildTunnelController.offerTunnelIpInfo(
                                        IpEndpoint(info.ip, info.countryCode, true),
                                    )
                                }
                            }
                            SoildTunnelController.setIpLoading(false)
                        }
                        "idle" -> {
                            SoildTunnelController.setIpInfo(null)
                            SoildTunnelController.setIpLoading(true)
                            val info = withContext(Dispatchers.IO) { NetProbe.fetchIpInfoDirectWithRetry() }
                            SoildTunnelController.setIpInfo(info?.let { IpEndpoint(it.ip, it.countryCode, false) })
                            SoildTunnelController.setIpLoading(false)
                        }
                        else -> {
                            SoildTunnelController.setIpInfo(null)
                            SoildTunnelController.setIpLoading(false)
                        }
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    if (!onboardingDone) {
                        OnboardingScreen(
                            onFinished = {
                                lifecycleScope.launch { onboardingStore.markCompleted() }
                            },
                        )
                    } else {
                        HomeScreen(
                            state = state,
                            profile = profile ?: ConnectionProfile(),
                            connectedSince = connectedSince,
                            ipInfo = ipInfo,
                            ipLoading = ipLoading,
                            onProfileChange = { updated ->
                                // Update the UI synchronously — keystrokes must
                                // never wait for disk I/O — then persist in the
                                // background.
                                uiProfile.value = updated
                                profileSaves.tryEmit(updated)
                            },
                            onToggleConnection = { toggleConnection(state) },
                        )
                    }
                }
            }
        }
    }

    private fun toggleConnection(state: ConnectionState) {
        if (state.isConnected || state.isBusy) {
            SoildTunnelController.disconnect(this)
            return
        }
        lifecycleScope.launch {
            val profile = uiProfile.value ?: profileStore.profile.first()
            val consent = SoildTunnelController.prepare(this@MainActivity)
            if (consent != null) {
                pendingProfile = profile
                vpnPermissionLauncher.launch(consent)
            } else {
                SoildTunnelController.connect(this@MainActivity, profile)
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        /** Set by the Quick Settings tile when it needs the consent dialog. */
        const val EXTRA_CONNECT_ON_LAUNCH = "com.soildtunnel.app.CONNECT_ON_LAUNCH"
    }
}
