package dev.vynkor.agent

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import dev.vynkor.agent.agent.AgentHolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 2×2 device card (IDEAS #17 "Device Status"): agent connection state +
 * battery level/charging + refresh time. Battery changes arrive through
 * [PowerStateReceiver]; agent transitions through [WidgetSync.pushAll].
 */
class DeviceStatusWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = views(context)
        appWidgetIds.forEach { id -> manager.updateAppWidget(id, views) }
    }

    companion object {
        /** Live battery snapshot without an active receiver. */
        fun battery(context: Context): Pair<Int, Boolean> {
            val intent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            return percent to (plugged != 0)
        }

        fun pushAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, DeviceStatusWidget::class.java))
            if (ids.isEmpty()) return
            val views = views(context)
            ids.forEach { id -> manager.updateAppWidget(id, views) }
        }

        private fun online(): Boolean =
            AgentHolder.agent != null && AgentHolder.connectionState.value

        private fun views(context: Context): RemoteViews {
            val accent = ContextCompat.getColor(context, R.color.primary)
            val dim = 0xFF948F99.toInt()
            val host = dev.vynkor.agent.agent.ProfileStore.active(context)
                ?.name?.ifBlank { null }
            val (percent, charging) = battery(context)

            val statusLine = when {
                online() && host != null ->
                    context.getString(R.string.widget_online_host_fmt, host.take(16))
                online() -> context.getString(R.string.widget_online)
                else -> context.getString(R.string.widget_offline)
            }
            val batteryLine = when {
                percent < 0 -> context.getString(R.string.widget_battery_unknown)
                charging -> context.getString(R.string.widget_battery_charging_fmt, percent)
                else -> context.getString(R.string.widget_battery_fmt, percent)
            }
            val updated = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

            return RemoteViews(context.packageName, R.layout.device_status_widget).apply {
                setTextColor(R.id.widgetAgentStatus, if (online()) accent else dim)
                setTextViewText(R.id.widgetAgentStatus, statusLine)
                setTextViewText(R.id.widgetBattery, batteryLine)
                setTextViewText(R.id.widgetUpdated, context.getString(R.string.widget_updated_fmt, updated))
                setOnClickPendingIntent(
                    R.id.widgetRoot,
                    PendingIntent.getActivity(
                        context,
                        1,
                        Intent(context, ChatActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
        }
    }
}
