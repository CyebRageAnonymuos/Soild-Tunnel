package com.soildtunnel.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.soildtunnel.desktop.core.DiagnosticsLog
import com.soildtunnel.desktop.core.ServerPinger
import com.soildtunnel.desktop.core.UpdateChecker
import com.soildtunnel.desktop.core.UsageStore
import com.soildtunnel.desktop.data.DesktopProfileStore
import com.soildtunnel.desktop.model.ConnectionProfile
import com.soildtunnel.desktop.model.isBusy
import com.soildtunnel.desktop.model.isConnected
import com.soildtunnel.desktop.tunnel.SoildTunnelController
import com.soildtunnel.desktop.ui.HomeScreen
import com.soildtunnel.desktop.ui.OnboardingScreen
import com.soildtunnel.desktop.ui.common.DesktopToast
import com.soildtunnel.desktop.ui.theme.SoildTunnelTheme
import kotlinx.coroutines.delay

private val onboardingFlag by lazy {
    androidx.compose.runtime.mutableStateOf(readOnboardingPref())
}

private fun readOnboardingPref(): Boolean =
    DesktopPrefs("onboarding").getString("completed", "0") == "1"

private fun markOnboardingDone() {
    DesktopPrefs("onboarding").edit { it.putString("completed", "1") }
    onboardingFlag.value = true
}

fun main() {
    DiagnosticsLog.init()
    ServerPinger.init()
    UsageStore.init()
    val profileStore = DesktopProfileStore()

    application {
        val profile by profileStore.profileFlow.collectAsState()

        Window(
            onCloseRequest = ::exitApplication,
            title = "SoildTunnel",
            state = rememberWindowState(width = 430.dp, height = 880.dp),
            resizable = true,
        ) {
            SoildTunnelTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        profile = profile,
                        onProfileChange = { updated -> profileStore.save(updated) },
                    )
                    ToastHost()
                }
            }
        }
    }
}

@Composable
private fun AppRoot(
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
) {
    if (!onboardingFlag.value) {
        OnboardingScreen(onFinished = ::markOnboardingDone)
        return
    }

    val state by SoildTunnelController.state.collectAsState()
    val connectedSince by SoildTunnelController.connectedSince.collectAsState()
    val ipInfo by SoildTunnelController.ipInfo.collectAsState()
    val ipLoading by SoildTunnelController.ipLoading.collectAsState()

    LaunchedEffect(Unit) { ServerPinger.maybeAutoRefresh() }
    LaunchedEffect(Unit) { UpdateChecker.checkIfNeeded() }

    HomeScreen(
        state = state,
        profile = profile,
        connectedSince = connectedSince,
        ipInfo = ipInfo,
        ipLoading = ipLoading,
        onProfileChange = onProfileChange,
        onToggleConnection = {
            if (state.isConnected || state.isBusy) {
                SoildTunnelController.disconnect()
            } else {
                SoildTunnelController.connect(profile)
            }
        },
    )
}

@Composable
private fun ToastHost() {
    val msg = DesktopToast.message ?: return
    LaunchedEffect(msg) {
        delay(2_500)
        DesktopToast.consume()
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp)
                .background(Color(0xEE10141B), shape = MaterialTheme.shapes.medium)
                .padding(horizontal = 18.dp, vertical = 11.dp),
        ) {
            Text(
                text = msg,
                color = Color(0xFFF2F8FC),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
