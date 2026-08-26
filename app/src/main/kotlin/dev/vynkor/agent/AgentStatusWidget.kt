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
            val accent = WidgetAccent.color(context)
            val online = running()
            val host = dev.vynkor.agent.agent.ProfileStore.active(context)?.name?.ifBlank { null }
            val statusLine = when {
                online && host != null -> context.getString(R.string.widget_online_host_fmt, host.take(20))
                online -> context.getString(R.string.widget_online)
                else -> context.getString(R.string.widget_offline)
            }
            return RemoteViews(context.packageName, R.layout.agent_status_widget).apply {
                // ImageView.setColorFilter(int) is reflection-safe on every API.
                setInt(R.id.widgetDot, "setColorFilter", if (online) accent else GRAY)
                setTextColor(
                    R.id.widgetStatus,
                    androidx.core.content.ContextCompat.getColor(
                        context,
                        if (online) R.color.widget_text_primary else R.color.widget_text_secondary,
                    ),
                )
                setTextViewText(R.id.widgetStatus, statusLine)
                if (online) {
                    setTextViewText(R.id.widgetToggle, context.getString(R.string.widget_stop))
                    setTextColor(
                        R.id.widgetToggle,
                        androidx.core.content.ContextCompat.getColor(context, R.color.widget_btn_stop_text),
                    )
                } else {
                    setTextViewText(R.id.widgetToggle, context.getString(R.string.widget_start))
                    setTextColor(
                        R.id.widgetToggle,
                        androidx.core.content.ContextCompat.getColor(context, R.color.widget_btn_text),
                    )
                }
                setOnClickPendingIntent(R.id.widgetToggle, togglePendingIntent(context))
                setOnClickPendingIntent(R.id.widgetRoot, openAppPendingIntent(context))
            }
        }

        private const val GRAY = 0xFF948F99.toInt()

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
