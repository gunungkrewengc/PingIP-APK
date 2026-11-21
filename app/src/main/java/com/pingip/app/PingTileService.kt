package com.pingip.app

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

class PingTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        if (PingStateStore.isRunning.value) {
            val stopIntent = Intent(this, PingService::class.java).apply {
                action = PingService.ACTION_STOP
            }
            startService(stopIntent)
            updateTileState(false, "Berhenti")
        } else {
            val startIntent = Intent(this, PingService::class.java).apply {
                action = PingService.ACTION_START
            }
            try {
                ContextCompat.startForegroundService(this, startIntent)
                updateTileState(true, "Memulai...")
            } catch (_: Exception) {
                val openIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(openIntent)
            }
        }
    }

    private fun syncTile() {
        updateTileState(PingStateStore.isRunning.value, PingStateStore.statusText.value)
    }

    private fun updateTileState(running: Boolean, subtitleText: String) {
        val tile = qsTile ?: return
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "PingIP"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitleText
        }
        tile.updateTile()
    }
}
