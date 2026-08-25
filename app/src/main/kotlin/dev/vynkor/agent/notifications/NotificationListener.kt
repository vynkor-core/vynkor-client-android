package dev.vynkor.agent.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.vynkor.agent.agent.AgentHolder
import dev.vynkor.agent.agent.AppPrefs

/** Forwards incoming notifications to the agent as device events. */
class NotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // ignore our own foreground notification
        if (sbn.packageName == packageName) return
        // per-app forward filter (Settings -> Notifications)
        if (AppPrefs.isMuted(this, sbn.packageName)) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
        AgentHolder.agent?.onNotification(sbn.packageName, title, text)
    }

    override fun onListenerConnected() = Unit
    override fun onListenerDisconnected() = Unit
}
