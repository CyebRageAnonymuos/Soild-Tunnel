package com.soildtunnel.app.vpn

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.soildtunnel.app.MainActivity
import com.soildtunnel.app.R
import com.soildtunnel.app.core.SoildTunnelController
import com.soildtunnel.app.data.ProfileStore
import com.soildtunnel.app.model.ConnectionState
import com.soildtunnel.app.model.isBusy
import com.soildtunnel.app.model.isConnected

/**
 * Quick Settings tile: swipe down the notification shade and tap the SoildTunnel
 * tile to connect/disconnect without opening the app.
 *
 * Behaviour, mapped to the QS tile contract:
 *  - onStartListening/onStopListening: the tile is visible, so we mirror the
 *    live [SoildTunnelController.state] into the tile (ACTIVE while connected or
 *    busy, INACTIVE otherwise, with the state name as subtitle).
 *  - onClick:
 *      * connected/busy  -> disconnect (also acts as "cancel" mid-connect),
 *      * idle + consent already granted -> connect with the saved profile,
 *      * idle + consent missing -> the consent dialog can only be shown from
 *        an Activity, so we open MainActivity which immediately starts the
 *        normal connect flow (and shows the system VPN dialog).
 */
class SoildTunnelTileService : TileService() {

    private var listenScope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        listenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope ->
            scope.launch {
                SoildTunnelController.state.collect { render(it) }
            }
        }
    }

    override fun onStopListening() {
        listenScope?.cancel()
        listenScope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val state = SoildTunnelController.state.value
        if (state.isConnected || state.isBusy) {
            SoildTunnelController.disconnect(this)
            return
        }
        // The VPN consent dialog can only be launched from an Activity.
        if (VpnService.prepare(this) != null) {
            openAppForConsent()
            return
        }
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val profile = ProfileStore(appContext).profile.first()
            SoildTunnelController.connect(appContext, profile)
        }
    }

    private fun openAppForConsent() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_CONNECT_ON_LAUNCH, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    REQ_CONSENT,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(state: ConnectionState) {
        val tile = qsTile ?: return
        tile.state = when {
            state.isConnected || state.isBusy -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.qs_tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                state.isConnected -> getString(R.string.state_connected)
                state.isBusy -> getString(R.string.state_connecting)
                state is ConnectionState.Error -> getString(R.string.state_error)
                else -> getString(R.string.state_idle)
            }
        }
        tile.updateTile()
    }

    companion object {
        private const val REQ_CONSENT = 2

        /**
         * Nudges the system to refresh the tile even while the QS panel is
         * closed, so the next swipe-down shows the correct state instantly.
         */
        fun requestUpdate(context: Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, SoildTunnelTileService::class.java),
                )
            }
        }
    }
}
