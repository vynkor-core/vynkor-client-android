package dev.vynkor.agent.caps

import android.content.Context
import android.os.BatteryManager
import dev.vynkor.agent.BatteryProvider

/** Reads battery state via BatteryManager. */
class BatteryProviderImpl(context: Context) : BatteryProvider {
    private val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    override fun levelPercent(): UByte {
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level.toUByte() else 0u
    }

    override fun isCharging(): Boolean =
        bm.isCharging

    override fun temperatureC(): Float {
        // BATTERY_PROPERTY_TEMPERATURE is @hide in the SDK; use the numeric id
        val t = bm.getIntProperty(BATTERY_PROPERTY_TEMPERATURE)
        // tenths of a degree Celsius
        return t / 10f
    }

    private companion object {
        const val BATTERY_PROPERTY_TEMPERATURE = 5
    }
}
