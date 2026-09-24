package dev.vynkor.agent.caps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import dev.vynkor.agent.CallLogEntry
import dev.vynkor.agent.CallsProvider
import dev.vynkor.agent.ConfirmedActionResult
import dev.vynkor.agent.R

/**
 * Call log plus approval-gated dialing. R-05/R-10: hard LIMIT, per-call
 * permission check; every call also needs the user's tap ([ConfirmGate]).
 */
class CallsProviderImpl(context: Context) : CallsProvider {
    private val ctx = context.applicationContext

    override fun recent(limit: UInt): List<CallLogEntry> {
        if (!granted()) return emptyList()
        val effective = if (limit in 1u..MAX_LIMIT) limit else MAX_LIMIT
        val cursor = ctx.contentResolver.queryLimited(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
            ),
            null,
            null,
            "${CallLog.Calls.DATE} DESC",
            effective.toInt(),
        ) ?: return emptyList()
        val number = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
        val name = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
        val type = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
        val date = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
        val duration = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
        return cursor.rows(effective.toInt()) { c ->
            CallLogEntry(
                number = c.getString(number) ?: "",
                name = c.getString(name) ?: "",
                callType = typeName(c.getInt(type)),
                timestampMs = c.getLong(date),
                durationS = c.getLong(duration).coerceAtLeast(0).toUInt(),
            )
        }
    }

    private fun typeName(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        else -> "other"
    }

    /**
     * TelecomManager.placeCall, not an ACTION_CALL activity: the request comes
     * in while the app is usually in the background, where Android blocks
     * activity starts but still lets a CALL_PHONE holder place calls.
     */
    override fun dial(number: String): ConfirmedActionResult {
        if (!granted(Manifest.permission.CALL_PHONE)) {
            return ConfirmedActionResult.Failed("CALL_PHONE not granted on the device")
        }
        val telecom = ctx.getSystemService(TelecomManager::class.java)
            ?: return ConfirmedActionResult.Failed("telephony is not available on this device")
        val answer = ConfirmGate.ask(
            ctx,
            ctx.getString(R.string.confirm_call_title),
            ctx.getString(R.string.confirm_call_body, number),
        )
        if (answer is ConfirmGate.Answer.Denied) return ConfirmedActionResult.Declined(answer.reason)
        return runCatching {
            @Suppress("MissingPermission") // checked above
            telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
            ConfirmedActionResult.Done
        }.getOrElse { ConfirmedActionResult.Failed("call failed: ${it.message ?: it.javaClass.simpleName}") }
    }

    private fun granted(permission: String = Manifest.permission.READ_CALL_LOG): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val MAX_LIMIT: UInt = 100u
    }
}
