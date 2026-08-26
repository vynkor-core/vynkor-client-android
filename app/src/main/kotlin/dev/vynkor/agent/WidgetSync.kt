package dev.vynkor.agent

import android.content.Context

/** One call site for "device state changed — repaint every widget". */
object WidgetSync {
    fun pushAll(context: Context) {
        AgentStatusWidget.pushAll(context)
        ActionGridWidget.pushAll(context)
        QuickActionsWidget.pushAll(context)
    }
}
