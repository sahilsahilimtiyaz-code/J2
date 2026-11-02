package com.jarvis.assistant.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: tap to start or stop background listening. */
class JarvisTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (JarvisListeningService.isRunning) {
            // Use the same explicit latch as the in-app mic button so the tile
            // cannot leave a foreground recognizer running behind a stopped service.
            com.jarvis.assistant.core.JarvisCore.get(this).stopListening()
        } else {
            ListeningServiceController.start(this)
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val running = JarvisListeningService.isRunning
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(com.jarvis.assistant.R.string.tile_label)
        tile.updateTile()
    }
}
