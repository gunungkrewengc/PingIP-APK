package com.pingip.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class PingTileService : TileService() {

    companion object {
        fun requestSync(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, PingTileService::class.java))
            } catch (_: Throwable) {}
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        if (PingService.isRunning) {
            startService(Intent(this, PingService::class.java).setAction(PingService.ACTION_STOP))
        } else {
            val intent = Intent(this, PingService::class.java).setAction(PingService.ACTION_START)
            startForegroundService(intent)
        }
        syncTile()
    }

    private fun syncTile() {
        val tile = qsTile ?: return
        tile.state = if (PingService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "PingIP"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (PingService.isRunning) PingService.currentStatus else "Mati"
        }
        tile.updateTile()
    }
}
