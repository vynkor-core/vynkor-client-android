package dev.vynkor.agent.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.vynkor.agent.agent.AgentHolder
import dev.vynkor.agent.agent.AppPrefs

/** Forwards incoming notifications to the agent as device events. */
class NotificationListener : NotificationListenerService() {

    /** Last forwarded title+text per notification key: re-posts are updates, not news. */
    private val lastSent = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) =
            size > DEDUPE_KEYS
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // ignore our own foreground notification
        if (sbn.packageName == packageName) return
        // Ongoing notices (media players, navigation, downloads) are re-posted
        // every few seconds, and group summaries duplicate their children —
        // both used to flood the host with non-events.
        if (sbn.isOngoing) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        // per-app forward filter (Settings -> Notifications)
        if (AppPrefs.isMuted(this, sbn.packageName)) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isEmpty() && text.isEmpty()) return
        val fingerprint = "$title\u0000$text"
        synchronized(lastSent) {
            if (lastSent[sbn.key] == fingerprint) return
            lastSent[sbn.key] = fingerprint
        }
        AgentHolder.agent?.onNotification(sbn.packageName, title, text)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(lastSent) { lastSent.remove(sbn.key) }
    }

    override fun onListenerConnected() = Unit
    override fun onListenerDisconnected() = Unit

    private companion object {
        const val DEDUPE_KEYS = 256
    }
}
