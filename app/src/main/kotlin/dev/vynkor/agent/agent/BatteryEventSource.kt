package dev.vynkor.agent.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.vynkor.agent.Agent

/**
 * Drives [Agent.pushBatteryStatus] from the sticky ACTION_BATTERY_CHANGED
 * broadcast. The raw broadcast fires constantly; events go to the host only
 * on a meaningful transition:
 * - charging state flipped (first snapshot after registration always sends),
 * - or level moved by >= [LEVEL_DELTA_PERCENT].
 */
class BatteryEventSource(
    context: Context,
    private val push: (levelPercent: UByte, charging: Boolean) -> Unit,
) {
    private val appContext = context.applicationContext

    @Volatile
    private var lastLevel = -1

    @Volatile
    private var lastCharging: Boolean? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return
            val percent = (level * 100 / scale).coerceIn(0, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val charging =
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL ||
                    plugged != 0

            val firstSnapshot = lastCharging == null
            val levelJump = kotlin.math.abs(percent - lastLevel) >= LEVEL_DELTA_PERCENT
            val chargingFlipped = lastCharging != null && charging != lastCharging
            if (!firstSnapshot && !levelJump && !chargingFlipped) return

            Log.i(TAG, "battery event: level=$percent charging=$charging")
            push(percent.toUByte(), charging)
            lastLevel = percent
            lastCharging = charging
        }
    }

    fun start() {
        // Protected system broadcast: NOT_EXPORTED still receives it, while
        // blocking any app-originated spoofing of the action.
        appContext.registerReceiver(
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Re-sends the current state (e.g. after a reconnect) from the sticky broadcast. */
    fun resend() {
        lastCharging = null
        val sticky = runCatching {
            appContext.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.getOrNull() ?: return
        receiver.onReceive(appContext, sticky)
    }

    fun stop() {
        runCatching { appContext.unregisterReceiver(receiver) }
            .onFailure { Log.w(TAG, "receiver not registered?", it) }
    }

    companion object {
        private const val TAG = "BatteryEvents"
        private const val LEVEL_DELTA_PERCENT = 5
    }
}
