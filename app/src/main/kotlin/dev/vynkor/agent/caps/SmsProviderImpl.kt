package dev.vynkor.agent.caps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import dev.vynkor.agent.SmsMessage
import dev.vynkor.agent.SmsProvider

/**
 * Read-only SMS inbox. R-05/R-10: hard LIMIT on every query, READ_SMS is
 * re-checked per call. Sensitive — the permission is requested only from the
 * capabilities screen, never at service start.
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

    private fun granted(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val MAX_LIMIT: UInt = 100u
    }
}
