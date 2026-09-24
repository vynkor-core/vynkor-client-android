package dev.vynkor.agent.caps

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import dev.vynkor.agent.ConfirmedActionResult
import dev.vynkor.agent.R
import dev.vynkor.agent.SmsMessage
import dev.vynkor.agent.SmsProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * SMS inbox search plus approval-gated sending. R-05/R-10: hard LIMIT on
 * every query, READ_SMS / SEND_SMS re-checked per call. Sensitive — the
 * permissions are requested only from the capabilities screen, never at
 * service start; every send also needs the user's tap ([ConfirmGate]).
 */
class SmsProviderImpl(context: Context) : SmsProvider {
    private val ctx = context.applicationContext

    override fun inbox(query: String, limit: UInt): List<SmsMessage> {
        if (!granted()) return emptyList()
        val effective = if (limit in 1u..MAX_LIMIT) limit else MAX_LIMIT
        val selection: String?
        val args: Array<String>?
        if (query.isNotBlank()) {
            selection = "(${Telephony.Sms.Inbox.BODY} LIKE ? OR ${Telephony.Sms.Inbox.ADDRESS} LIKE ?)"
            args = arrayOf("%$query%", "%$query%")
        } else {
            selection = null
            args = null
        }
        val cursor = ctx.contentResolver.queryLimited(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(
                Telephony.Sms.Inbox.ADDRESS,
                Telephony.Sms.Inbox.BODY,
                Telephony.Sms.Inbox.DATE,
            ),
            selection,
            args,
            "${Telephony.Sms.Inbox.DATE} DESC",
            effective.toInt(),
        ) ?: return emptyList()
        val addr = cursor.getColumnIndexOrThrow(Telephony.Sms.Inbox.ADDRESS)
        val body = cursor.getColumnIndexOrThrow(Telephony.Sms.Inbox.BODY)
        val date = cursor.getColumnIndexOrThrow(Telephony.Sms.Inbox.DATE)
        return cursor.rows(effective.toInt()) { c ->
            SmsMessage(
                sender = c.getString(addr) ?: "",
                body = c.getString(body) ?: "",
                timestampMs = c.getLong(date),
            )
        }
    }

    override fun send(to: String, text: String): ConfirmedActionResult {
        if (!granted(Manifest.permission.SEND_SMS)) {
            return ConfirmedActionResult.Failed("SEND_SMS not granted on the device")
        }
        val sms = smsManager() ?: return ConfirmedActionResult.Failed("SMS is not available on this device")
        // The number goes in the body: a title would ellipsize it, and the
        // user must see exactly where the message is going.
        val answer = ConfirmGate.ask(
            ctx,
            ctx.getString(R.string.confirm_sms_title),
            ctx.getString(R.string.confirm_sms_body, to, text),
        )
        if (answer is ConfirmGate.Answer.Denied) return ConfirmedActionResult.Declined(answer.reason)
        return runCatching { sendAndAwait(sms, to, text) }
            .getOrElse { ConfirmedActionResult.Failed("sms send failed: ${it.message ?: it.javaClass.simpleName}") }
    }

    /**
     * Hands the parts to the radio and waits briefly for its per-part result.
     * No verdict in time still counts as sent: the platform has queued it,
     * and reporting failure would invite the caller to send a duplicate.
     */
    private fun sendAndAwait(sms: SmsManager, to: String, text: String): ConfirmedActionResult {
        val parts = sms.divideMessage(text)
        val action = "${ctx.packageName}.SMS_SENT.${sendSeq.incrementAndGet()}"
        val done = CountDownLatch(parts.size)
        val failures = mutableListOf<Int>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (resultCode != Activity.RESULT_OK) synchronized(failures) { failures += resultCode }
                done.countDown()
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val sent = ArrayList(
                parts.indices.map { i ->
                    PendingIntent.getBroadcast(
                        ctx,
                        i,
                        Intent(action).setPackage(ctx.packageName),
                        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                    )
                },
            )
            sms.sendMultipartTextMessage(to, null, parts, sent, null)
            done.await(SEND_RESULT_WAIT_MS, TimeUnit.MILLISECONDS)
        } finally {
            ctx.unregisterReceiver(receiver)
        }
        val failed = synchronized(failures) { failures.toList() }
        return if (failed.isEmpty()) {
            ConfirmedActionResult.Done
        } else {
            ConfirmedActionResult.Failed("radio refused the SMS (result codes $failed)")
        }
    }

    private fun smsManager(): SmsManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

    private fun granted(permission: String = Manifest.permission.READ_SMS): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val MAX_LIMIT: UInt = 100u
        const val SEND_RESULT_WAIT_MS = 8_000L
        val sendSeq = AtomicInteger()
    }
}
