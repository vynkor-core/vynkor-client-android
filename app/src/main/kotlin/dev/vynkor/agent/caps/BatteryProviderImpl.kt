package dev.vynkor.agent.caps

import android.content.Context
import androidx.core.content.ContextCompat
import android.content.IntentFilter
import android.content.Intent
import android.os.BatteryManager
import dev.vynkor.agent.BatteryProvider

/** Reads battery state via BatteryManager. */
class BatteryProviderImpl(context: Context) : BatteryProvider {
    private val appContext = context.applicationContext
    private val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    override fun levelPercent(): UByte {
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level.toUByte() else 0u
    }

    /**
     * Same definition as the pushed battery_status event (charging, full, or
     * on external power): BatteryManager.isCharging() is false while plugged
     * into a weak USB port, so the host saw "charging" in events and
     * "not charging" in replies at the same time.
     */
    override fun isCharging(): Boolean {
        val sticky = stickyBattery() ?: return bm.isCharging
        val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun stickyBattery(): Intent? = runCatching {
        appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }.getOrNull()

    /**
     * From the sticky ACTION_BATTERY_CHANGED extra (tenths of a degree). The
     * old hidden BatteryManager property id answered Integer.MIN_VALUE on
     * real devices, so the host only ever saw null.
     */
    override fun temperatureC(): Float {
        val sticky = stickyBattery()
        val tenths = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        return if (tenths == Int.MIN_VALUE) Float.NaN else tenths / 10f
    }
}
