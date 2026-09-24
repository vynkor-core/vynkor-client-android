package dev.vynkor.agent.caps

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.vynkor.agent.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-phone approval for outward actions a host asked for (send an SMS, place
 * a call). The host — agent, script, anything holding a device token — can
 * only *request* them; nothing leaves the phone until the user taps Allow.
 *
 * Shown as a high-priority notification because the request usually arrives
 * while the app is in the background, where Android blocks activity starts.
 * Allow requires an unlocked device, so a locked phone cannot be talked into
 * sending from its lock screen. Swiping the prompt away counts as Deny.
 *
 * [ask] blocks its caller (a Rust provider thread, never main) until the user
 * answers or [TIMEOUT_MS] passes — kept under the kernel's 30 s default action
 * timeout so an approval can never land after the host has given up.
 */
object ConfirmGate {

    sealed interface Answer {
        data object Approved : Answer
        data class Denied(val reason: String) : Answer
    }

    const val TIMEOUT_MS = 20_000L
    private const val CHANNEL = "vynkor_confirm"
    internal const val EXTRA_ID = "confirm_id"
    internal const val EXTRA_APPROVE = "confirm_approve"

    private class Pending(val latch: CountDownLatch = CountDownLatch(1), val approved: AtomicBoolean = AtomicBoolean(false))

    private val waiting = ConcurrentHashMap<Int, Pending>()
    private val nextId = AtomicInteger(0x7C0000)

    fun ask(context: Context, title: String, body: String): Answer {
        check(Looper.myLooper() != Looper.getMainLooper()) { "ConfirmGate.ask blocks; call it off the main thread" }
        val ctx = context.applicationContext
        val nm = NotificationManagerCompat.from(ctx)
        val canPost = nm.areNotificationsEnabled() &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!canPost) return Answer.Denied("notifications are disabled, so approval cannot be asked")
        ensureChannel(ctx)

        val id = nextId.incrementAndGet()
        val pending = Pending()
        waiting[id] = pending
        try {
            val allow = NotificationCompat.Action.Builder(
                R.drawable.ic_send,
                ctx.getString(R.string.confirm_allow),
                answerIntent(ctx, id, approve = true),
            ).setAuthenticationRequired(true).build()
            val deny = NotificationCompat.Action.Builder(
                R.drawable.ic_stop,
                ctx.getString(R.string.confirm_deny),
                answerIntent(ctx, id, approve = false),
            ).build()
            val publicVersion = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_vynkor)
                .setContentTitle(ctx.getString(R.string.confirm_public_title))
                .build()
            val notification = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_vynkor)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setTimeoutAfter(TIMEOUT_MS)
                .setAutoCancel(true)
                .setDeleteIntent(answerIntent(ctx, id, approve = false))
                .addAction(deny)
                .addAction(allow)
                .build()
            @Suppress("MissingPermission") // checked above
            nm.notify(id, notification)

            val answered = pending.latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            return when {
                !answered -> Answer.Denied("no answer within ${TIMEOUT_MS / 1000} s")
                pending.approved.get() -> Answer.Approved
                else -> Answer.Denied("denied by the user")
            }
        } finally {
            waiting.remove(id)
            nm.cancel(id)
        }
    }

    /** First answer wins: a dismiss right after Allow must not flip it. */
    @Synchronized
    internal fun answer(id: Int, approve: Boolean) {
        val pending = waiting[id] ?: return // late tap on an expired prompt
        if (pending.latch.count == 0L) return
        pending.approved.set(approve)
        pending.latch.countDown()
    }

    private fun answerIntent(ctx: Context, id: Int, approve: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            // Distinct request codes: allow, deny and dismiss must not collapse.
            id * 2 + if (approve) 1 else 0,
            Intent(ctx, ConfirmReceiver::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_APPROVE, approve),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                ctx.getString(R.string.confirm_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }
}

/** Not exported: only our own PendingIntents (Allow/Deny/dismiss) reach it. */
class ConfirmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(ConfirmGate.EXTRA_ID, -1)
        if (id < 0) return
        ConfirmGate.answer(id, intent.getBooleanExtra(ConfirmGate.EXTRA_APPROVE, false))
    }
}
