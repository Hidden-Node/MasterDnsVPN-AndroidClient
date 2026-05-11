package com.masterdns.vpn.service

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.masterdns.vpn.MainActivity
import com.masterdns.vpn.R
import com.masterdns.vpn.data.local.AppDatabase
import com.masterdns.vpn.util.VpnManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class VpnTileService : TileService() {

    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val state = VpnManager.state.value
        when (state) {
            VpnManager.VpnState.CONNECTED -> VpnManager.disconnect(this)
            VpnManager.VpnState.DISCONNECTED -> connectFromTileIfReady()
            else -> Unit
        }
        updateTile()
    }

    override fun onDestroy() {
        tileScope.cancel()
        super.onDestroy()
    }

    private fun connectFromTileIfReady() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            startActivityAndCollapse(prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }

        tileScope.launch(Dispatchers.IO) {
            val selectedProfile = AppDatabase.getInstance(this@VpnTileService)
                .profileDao()
                .getSelectedProfile()

            launch(Dispatchers.Main) {
                if (selectedProfile != null) {
                    VpnManager.connect(this@VpnTileService, selectedProfile)
                    updateTile()
                } else {
                    openApp()
                }
            }
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(intent)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        when (VpnManager.state.value) {
            VpnManager.VpnState.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.app_name)
                tile.subtitle = "Connected"
            }
            VpnManager.VpnState.CONNECTING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.app_name)
                tile.subtitle = "Connecting..."
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.app_name)
                tile.subtitle = "Disconnected"
            }
        }
        tile.updateTile()
    }
}
