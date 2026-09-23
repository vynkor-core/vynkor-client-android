package dev.vynkor.agent

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService

/** One call site for "device state changed — repaint every widget and the QS tile". */
object WidgetSync {
    fun pushAll(context: Context) {
        AgentStatusWidget.pushAll(context)
        // The tile only re-reads state in onStartListening; without this it
        // kept showing the pre-toggle state until the shade was reopened.
        runCatching {
            TileService.requestListeningState(context, ComponentName(context, AgentTileService::class.java))
        }
    }
}
