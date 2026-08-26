package dev.vynkor.agent

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Widget logic without a launcher: broadcast handling, battery debounce,
 * ringer cycle. Rendering itself needs placed instances — covered on device.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetLogicTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        PowerStateReceiver.prefs(context).edit().clear().commit()
    }

    private fun batteryBroadcast(percent: Int, charging: Boolean): Intent =
        Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra("level", percent)
            .putExtra("scale", 100)
            .putExtra("plugged", if (charging) 1 else 0)

    @Test
    fun powerReceiverDebouncesUnchangedSnapshots() {
        val receiver = PowerStateReceiver()
        val first = batteryBroadcast(77, charging = false)
        receiver.onReceive(context, first)

        val prefs = PowerStateReceiver.prefs(context)
        assertEquals(77, prefs.getInt("widget_battery_percent", -1))
        assertFalse(prefs.getBoolean("widget_battery_charging", true))

        // Same snapshot again must not touch prefs' timestamp-worthy state.
        receiver.onReceive(context, batteryBroadcast(77, charging = false))
        assertEquals(77, prefs.getInt("widget_battery_percent", -1))

        receiver.onReceive(context, batteryBroadcast(76, charging = false))
        assertEquals(76, prefs.getInt("widget_battery_percent", -1))
    }

    @Test
    fun quickActionsRingerCyclesNormalVibrateSilent() {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL

        val widget = QuickActionsWidget()
        val ring = Intent(context, QuickActionsWidget::class.java)
            .setAction("dev.vynkor.agent.widget.QA_RINGER")

        widget.onReceive(context, ring.setAction("dev.vynkor.agent.widget.QA_RINGER"))
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.ringerMode)

        widget.onReceive(context, ring)
        assertEquals(AudioManager.RINGER_MODE_SILENT, audio.ringerMode)

        widget.onReceive(context, ring)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
    }

    @Test
    fun quickActionsDndWithoutAccessOpensSettingsInsteadOfCrashing() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertFalse(nm.isNotificationPolicyAccessGranted)

        val widget = QuickActionsWidget()
        widget.onReceive(
            context,
            Intent(context, QuickActionsWidget::class.java)
                .setAction("dev.vynkor.agent.widget.QA_DND"),
        )
        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_ALL,
            nm.currentInterruptionFilter,
        )
    }
}
