package com.nitroboost.app.service

import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.nitroboost.app.R
import com.nitroboost.app.data.Prefs

/**
 * Quick Settings one-tap: start/stop the boost session.
 */
class QuickTileService : TileService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (BoosterService.active) {
            BoosterService.stop(this)
        } else {
            BoosterService.start(this, Prefs.activeProfile(this))
        }
        // UI state settles after the service reacts.
        handler.postDelayed({ refresh() }, 1500)
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_name)
        tile.state = if (BoosterService.active) Tile.STATE_ON else Tile.STATE_OFF
        tile.updateTile()
    }
}
