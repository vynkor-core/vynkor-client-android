package dev.vynkor.agent.caps

import android.app.NotificationManager
import android.content.Context
import dev.vynkor.agent.DndProvider

/**
 * Do-not-disturb behind `device.dnd`. Reading the filter works without
 * special access; changing it requires the user-granted Notification policy
 * access, so [setFilter] honestly reports false when it is missing.
 */
class DndProviderImpl(context: Context) : DndProvider {
    private val ctx = context.applicationContext

    override fun filter(): String = when (nm()?.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
        else -> "unknown"
    }

    override fun setFilter(mode: String): Boolean {
        val manager = nm() ?: return false
        val target = when (mode) {
            "off" -> NotificationManager.INTERRUPTION_FILTER_ALL
            "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
            else -> return false
        }
        if (!manager.isNotificationPolicyAccessGranted) return false
        return runCatching {
            manager.setInterruptionFilter(target)
            filter() == mode
        }.getOrDefault(false)
    }

    private fun nm() = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
}
