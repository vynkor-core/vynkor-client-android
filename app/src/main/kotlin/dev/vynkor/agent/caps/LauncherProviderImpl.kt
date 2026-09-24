package dev.vynkor.agent.caps

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dev.vynkor.agent.R
import android.net.Uri
import dev.vynkor.agent.AppEntry
import dev.vynkor.agent.LauncherProvider

/**
 * App listing + launch. The launcher-intent `<queries>` declaration in the
 * manifest keeps package visibility working on API 30+.
 */
class LauncherProviderImpl(context: Context) : LauncherProvider {
    private val ctx = context.applicationContext

    override fun apps(): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        return resolved.asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .map { AppEntry(packageName = it.packageName, appName = pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.appName.lowercase() }
            .toList()
    }

    /**
     * In the foreground the app opens directly. From the background Android
     * blocks activity starts (BAL, hardened again in 17) *silently* — the old
     * code reported success for a launch that never happened — so the user
     * gets a tap-to-open notification instead.
     */
    override fun launch(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (packageName == ctx.packageName) return false
        val intent = ctx.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(Lifecycle.State.STARTED)
        if (foreground) {
            return runCatching { ctx.startActivity(intent); true }.getOrDefault(false)
        }
        return notifyOpen(packageName, intent)
    }

    private fun notifyOpen(packageName: String, intent: Intent): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.areNotificationsEnabled()) return false
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ACTIONS,
                ctx.getString(R.string.channel_host_actions),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val label = runCatching {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(packageName, 0))
        }.getOrDefault(packageName)
        val pending = PendingIntent.getActivity(
            ctx,
            packageName.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ACTIONS)
            .setSmallIcon(R.drawable.ic_stat_vynkor)
            .setContentTitle(ctx.getString(R.string.launch_request_title, label))
            .setContentText(ctx.getString(R.string.launch_request_text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        return runCatching { nm.notify(packageName.hashCode(), notification); true }.getOrDefault(false)
    }

    /** Settings deep link used by the permissions screen for special grants. */
    companion object {
        private const val CHANNEL_ACTIONS = "vynkor_host_actions"

        fun appDetailsSettingsIntent(context: Context): Intent = Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
    }
}
