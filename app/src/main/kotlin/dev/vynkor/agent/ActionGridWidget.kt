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
 * 2×2 action grid: Chat / Voice / Camera tiles open the chat and kick the
 * matching flow off immediately (via [ChatActivity.EXTRA_AUTO_ACTION]);
 * Agent starts/stops the service. No passive info — every tile is a button.
 */
class ActionGridWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = views(context)
        appWidgetIds.forEach { id -> manager.updateAppWidget(id, views) }
    }

    companion object {
        fun pushAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, ActionGridWidget::class.java))
            if (ids.isEmpty()) return
            val views = views(context)
            ids.forEach { id -> manager.updateAppWidget(id, views) }
        }

        private fun agentOn(): Boolean = AgentHolder.agent != null

        private fun views(context: Context): RemoteViews {
            val accent = WidgetAccent.color(context)
            return RemoteViews(context.packageName, R.layout.action_grid_widget).apply {
                setTextViewText(
                    R.id.agAgentLabel,
                    context.getString(if (agentOn()) R.string.widget_stop else R.string.widget_start),
                )
                setTextColor(R.id.agAgentLabel, if (agentOn()) accent else 0xFFE6E1E5.toInt())
                setOnClickPendingIntent(
                    R.id.agChat,
                    openChat(context, 21, autoAction = null),
                )
                setOnClickPendingIntent(
                    R.id.agVoice,
                    openChat(context, 22, autoAction = ChatActivity.AUTO_VOICE),
                )
                setOnClickPendingIntent(
                    R.id.agCamera,
                    openChat(context, 23, autoAction = ChatActivity.AUTO_CAMERA),
                )
                setOnClickPendingIntent(R.id.agAgent, toggleAgent(context))
            }
        }

        private fun openChat(context: Context, requestCode: Int, autoAction: String?): PendingIntent {
            val intent = Intent(context, ChatActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                autoAction?.let { putExtra(ChatActivity.EXTRA_AUTO_ACTION, it) }
            }
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun toggleAgent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                24,
                Intent(context, ActionGridWidget::class.java)
                    .setAction("dev.vynkor.agent.widget.GRID_AGENT"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == "dev.vynkor.agent.widget.GRID_AGENT") {
            if (AgentHolder.agent != null) {
                AgentService.stop(context)
            } else {
                AgentService.start(context)
            }
            pushAll(context)
            AgentStatusWidget.pushAll(context)
            QuickActionsWidget.pushAll(context)
        }
    }
}
