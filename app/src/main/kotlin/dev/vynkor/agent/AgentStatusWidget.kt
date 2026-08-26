package dev.vynkor.agent

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import dev.vynkor.agent.agent.AgentHolder
import dev.vynkor.agent.agent.AgentService

/**
 * Home-screen status widget (IDEAS #17): live agent state, tap opens the
 * chat, button starts/stops the agent. RemoteViews cannot observe flows —
 * every lifecycle change pushes a snapshot via [pushAll]; [onUpdate] covers
 * fresh placement and launcher restores.
 */
class AgentStatusWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = views(context)
        appWidgetIds.forEach { id -> manager.updateAppWidget(id, views) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            if (AgentHolder.agent != null) {
                AgentService.stop(context)
            } else {
                AgentService.start(context)
            }
        }
    }

    companion object {
        private const val ACTION_TOGGLE = "dev.vynkor.agent.widget.TOGGLE"

        /** Re-renders every placed instance from [AgentHolder]'s live state. */
        fun pushAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, AgentStatusWidget::class.java))
            if (ids.isEmpty()) return
            val views = views(context)
            ids.forEach { id -> manager.updateAppWidget(id, views) }
        }

        private fun running(): Boolean =
            AgentHolder.agent != null && AgentHolder.connectionState.value

        private fun views(context: Context): RemoteViews {
            val accent = ContextCompat.getColor(context, R.color.primary)
            return RemoteViews(context.packageName, R.layout.agent_status_widget).apply {
                val online = running()
                setTextColor(R.id.widgetStatus, if (online) accent else 0xFF948F99.toInt())
                setTextViewText(
                    R.id.widgetStatus,
                    context.getString(if (online) R.string.widget_online else R.string.widget_offline),
                )
                setTextViewText(
                    R.id.widgetToggle,
                    context.getString(if (online) R.string.widget_stop else R.string.widget_start),
                )
                setOnClickPendingIntent(R.id.widgetToggle, togglePendingIntent(context))
                setOnClickPendingIntent(R.id.widgetRoot, openAppPendingIntent(context))
            }
        }

        private fun togglePendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, AgentStatusWidget::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun openAppPendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, ChatActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
