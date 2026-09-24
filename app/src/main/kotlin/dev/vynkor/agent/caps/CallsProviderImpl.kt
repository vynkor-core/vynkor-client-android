package dev.vynkor.agent.caps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import dev.vynkor.agent.CallLogEntry
import dev.vynkor.agent.CallsProvider

/**
 * Read-only call log. R-05/R-10: hard LIMIT, per-call permission check.
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

    private fun granted(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val MAX_LIMIT: UInt = 100u
    }
}
