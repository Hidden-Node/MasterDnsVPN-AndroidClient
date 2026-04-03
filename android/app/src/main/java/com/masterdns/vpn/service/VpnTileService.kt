package com.masterdns.vpn.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.masterdns.vpn.R
import com.masterdns.vpn.util.VpnManager

@RequiresApi(Build.VERSION_CODES.N)
class VpnTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val state = VpnManager.state.value
        if (state == VpnManager.VpnState.CONNECTED) {
            VpnManager.disconnect(this)
        } else if (state == VpnManager.VpnState.DISCONNECTED) {
            // Can't start VPN from tile without VPN permission
            // Just update the tile state
        }
        updateTile()
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
