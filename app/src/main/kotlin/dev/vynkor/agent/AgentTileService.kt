package dev.vynkor.agent

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.vynkor.agent.agent.AgentHolder
import dev.vynkor.agent.agent.AgentService

/**
 * One-tap agent toggle from the quick settings panel (IDEAS #1). Starting
 * from here skips permission prompts on purpose: providers re-check grants
 * per call (R-10), so a partially-permitted start degrades gracefully and
 * missing capabilities are visible on the permissions screen.
 */
class AgentTileService : TileService() {

    override fun onStartListening() {
        updateTile()
    }

    override fun onClick() {
        if (AgentHolder.agent != null) {
            AgentService.stop(applicationContext)
        } else {
            AgentService.start(applicationContext)
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val running = AgentHolder.agent != null
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                if (running) R.string.tile_subtitle_on else R.string.tile_subtitle_off,
            )
        }
        tile.updateTile()
    }
}
