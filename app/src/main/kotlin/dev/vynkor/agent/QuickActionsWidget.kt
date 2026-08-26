package dev.vynkor.agent

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import dev.vynkor.agent.caps.FlashlightProviderImpl

/**
 * 4×1 quick actions (IDEAS #17 "Action Buttons"): torch, DND, ringer cycle
 * and the agent toggle, each an independent broadcast — no app UI needed.
 * DND without the policy grant deep-links to its settings screen instead of
 * failing silently.
 */
class QuickActionsWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = views(context)
        appWidgetIds.forEach { id -> manager.updateAppWidget(id, views) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TORCH -> toggleTorch(context)
            ACTION_DND -> toggleDnd(context)
            ACTION_RINGER -> cycleRinger(context)
            ACTION_AGENT ->
                if (dev.vynkor.agent.agent.AgentHolder.agent != null) {
                    dev.vynkor.agent.agent.AgentService.stop(context)
                } else {
                    dev.vynkor.agent.agent.AgentService.start(context)
                }
            else -> return
        }
        pushAll(context)
        AgentStatusWidget.pushAll(context)
    }

    private fun toggleTorch(context: Context) {
        FlashlightProviderImpl(context).toggle()
    }

    private fun toggleDnd(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        if (nm == null || !nm.isNotificationPolicyAccessGranted) {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val target = if (nm.currentInterruptionFilter ==
            android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        ) {
            android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
        } else {
            android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        }
        nm.setInterruptionFilter(target)
    }

    private fun cycleRinger(context: Context) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val next = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        runCatching { audio.ringerMode = next }
    }

    companion object {
        private const val ACTION_TORCH = "dev.vynkor.agent.widget.QA_TORCH"
        private const val ACTION_DND = "dev.vynkor.agent.widget.QA_DND"
        private const val ACTION_RINGER = "dev.vynkor.agent.widget.QA_RINGER"
        private const val ACTION_AGENT = "dev.vynkor.agent.widget.QA_AGENT"

        fun pushAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, QuickActionsWidget::class.java))
            if (ids.isEmpty()) return
            val views = views(context)
            ids.forEach { id -> manager.updateAppWidget(id, views) }
        }

        private fun agentOn(): Boolean =
            dev.vynkor.agent.agent.AgentHolder.agent != null

        private fun views(context: Context): RemoteViews {
            val accent = ContextCompat.getColor(context, R.color.primary)
            val torch = FlashlightProviderImpl(context)
            return RemoteViews(context.packageName, R.layout.quick_actions_widget).apply {
                setTextViewText(
                    R.id.qaAgentLabel,
                    context.getString(if (agentOn()) R.string.widget_stop else R.string.widget_start),
                )
                setTextColor(R.id.qaAgentLabel, if (agentOn()) accent else 0xFFE6E1E5.toInt())
                // Torch state is process-shared; a fresh process reads "off".
                setTextColor(
                    R.id.qaTorchLabel,
                    if (torch.isOn()) accent else 0xFFE6E1E5.toInt(),
                )
                setOnClickPendingIntent(R.id.qaTorch, pending(context, ACTION_TORCH, 11))
                setOnClickPendingIntent(R.id.qaDnd, pending(context, ACTION_DND, 12))
                setOnClickPendingIntent(R.id.qaRinger, pending(context, ACTION_RINGER, 13))
                setOnClickPendingIntent(R.id.qaAgent, pending(context, ACTION_AGENT, 14))
            }
        }

        private fun pending(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, QuickActionsWidget::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
