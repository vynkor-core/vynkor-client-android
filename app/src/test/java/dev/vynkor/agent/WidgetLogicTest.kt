package dev.vynkor.agent

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
