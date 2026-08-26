package dev.vynkor.agent

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.BatteryManager

/**
 * Manifest-registered battery watcher feeding [DeviceStatusWidget].
 *
 * ACTION_BATTERY_CHANGED fires on every voltage wiggle, so updates are
 * debounced: the widget is repainted only when the shown percent or the
 * charging flag actually changed (last values kept in prefs — the process
 * may have been dead between broadcasts).
 */
class PowerStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BATTERY_CHANGED) return

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

        val prefs = prefs(context)
        val lastPercent = prefs.getInt(KEY_PERCENT, Int.MIN_VALUE)
        val lastCharging = prefs.getBoolean(KEY_CHARGING, !charging)
        if (percent == lastPercent && charging == lastCharging) return

        prefs.edit()
            .putInt(KEY_PERCENT, percent)
            .putBoolean(KEY_CHARGING, charging)
            .apply()
        DeviceStatusWidget.pushAll(context)
    }

    companion object {
        private const val KEY_PERCENT = "widget_battery_percent"
        private const val KEY_CHARGING = "widget_battery_charging"

        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences("vynkor_widget_state", Context.MODE_PRIVATE)
    }
}
