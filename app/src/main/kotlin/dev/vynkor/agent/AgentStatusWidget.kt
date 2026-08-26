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

        /**
         * Strings resolve through a context reconfigured to the in-app
         * locale (AppCompatDelegate), so the widget follows language changes
         * immediately — not only after the next launcher restore.
         */
        private fun localized(context: Context): Context {
            val locales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
            if (locales.isEmpty) return context
            val config = android.content.res.Configuration(context.resources.configuration)
            @Suppress("DEPRECATION")
            val list = android.os.LocaleList.forLanguageTags(locales.toLanguageTags())
            config.setLocales(list)
            return context.createConfigurationContext(config)
        }

        private fun views(context: Context): RemoteViews {
            val ctx = localized(context)
            val accent = WidgetAccent.color(ctx)
            val online = running()
            val agentOn = dev.vynkor.agent.agent.AgentHolder.agent != null
            val profile = dev.vynkor.agent.agent.ProfileStore.active(ctx)
            val host = profile?.name?.ifBlank { null }
            val statusLine = when {
                // No paired host yet — say so instead of a bare "offline".
                profile == null -> ctx.getString(R.string.no_profile)
                online && host != null ->
                    ctx.getString(R.string.widget_online_host_fmt, host.take(20))
                online -> ctx.getString(R.string.widget_online)
                !agentOn -> ctx.getString(R.string.widget_offline)
                else -> when (val hs = dev.vynkor.agent.agent.AgentHolder.hostStatus.value) {
                    is dev.vynkor.agent.agent.HostStatus.Connecting ->
                        ctx.getString(R.string.status_connecting)
                    is dev.vynkor.agent.agent.HostStatus.Reconnecting ->
                        ctx.getString(R.string.status_reconnecting)
                    is dev.vynkor.agent.agent.HostStatus.Unreachable ->
                        ctx.getString(R.string.status_unreachable_fmt, hs.reason.take(24))
                    else -> ctx.getString(R.string.widget_offline)
                }
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
                    setTextViewText(R.id.widgetToggle, ctx.getString(R.string.widget_stop))
                    setTextColor(
                        R.id.widgetToggle,
                        androidx.core.content.ContextCompat.getColor(ctx, R.color.widget_btn_stop_text),
                    )
                } else {
                    setTextViewText(R.id.widgetToggle, ctx.getString(R.string.widget_start))
                    setTextColor(
                        R.id.widgetToggle,
                        androidx.core.content.ContextCompat.getColor(ctx, R.color.widget_btn_text),
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
