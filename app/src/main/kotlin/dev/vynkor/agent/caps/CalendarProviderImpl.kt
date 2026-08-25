package dev.vynkor.agent.caps

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dev.vynkor.agent.CalendarEvent
import dev.vynkor.agent.CalendarProvider
import dev.vynkor.agent.CalendarWriteResult
import java.util.TimeZone

/**
 * Calendar read (upcoming occurrences) + write. Permissions re-checked per
 * call; writes need a writable calendar and pick the first synced one.
 */
class CalendarProviderImpl(context: Context) : CalendarProvider {
    private val ctx = context.applicationContext

    override fun upcoming(daysAhead: UInt, limit: UInt): List<CalendarEvent> {
        if (!granted(Manifest.permission.READ_CALENDAR)) return emptyList()
        val effectiveDays = if (daysAhead in 1u..MAX_DAYS) daysAhead else MAX_DAYS
        val effectiveLimit = if (limit in 1u..MAX_LIMIT) limit else MAX_LIMIT
        val now = System.currentTimeMillis()
        val until = now + effectiveDays.toLong() * 24 * 60 * 60 * 1000
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString())
            .appendPath(until.toString())
            .build()
        val cursor = ctx.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.DESCRIPTION,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            ),
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC LIMIT $effectiveLimit",
        ) ?: return emptyList()
        val result = mutableListOf<CalendarEvent>()
        cursor.use { c ->
            while (c.moveToNext()) {
                result.add(
                    CalendarEvent(
                        title = c.getStringOrEmpty(CalendarContract.Instances.TITLE),
                        description = c.getStringOrEmpty(CalendarContract.Instances.DESCRIPTION),
                        location = c.getStringOrEmpty(CalendarContract.Instances.EVENT_LOCATION),
                        startMs = c.getLongOrNull(CalendarContract.Instances.BEGIN) ?: 0L,
                        endMs = c.getLongOrNull(CalendarContract.Instances.END) ?: 0L,
                        calendarName = c.getStringOrEmpty(CalendarContract.Instances.CALENDAR_DISPLAY_NAME),
                    )
                )
            }
        }
        return result
    }

    override fun addEvent(
        title: String,
        description: String,
        location: String,
        startMs: Long,
        endMs: Long,
    ): CalendarWriteResult {
        if (!granted(Manifest.permission.WRITE_CALENDAR)) {
            return CalendarWriteResult.Failed("WRITE_CALENDAR not granted")
        }
        val calendarId = firstWritableCalendarId()
            ?: return CalendarWriteResult.Failed("no writable calendar found")
        return runCatching {
            val values = android.content.ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.EVENT_LOCATION, location)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return@runCatching CalendarWriteResult.Failed("calendar insert returned null")
            CalendarWriteResult.Added(ContentUris.parseId(uri))
        }.getOrElse { CalendarWriteResult.Failed(it.message ?: "insert failed") }
    }

    private fun firstWritableCalendarId(): Long? {
        if (!granted(Manifest.permission.READ_CALENDAR)) return null
        val cursor = ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.SYNC_EVENTS,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            ),
            "${CalendarContract.Calendars.SYNC_EVENTS} = 1",
            null,
            null,
        ) ?: return null
        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val accessIdx = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            while (c.moveToNext()) {
                val access = c.getInt(accessIdx)
                if (access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    return c.getLong(idIdx)
                }
            }
        }
        return null
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    private fun android.database.Cursor.getStringOrEmpty(column: String): String =
        try {
            getString(getColumnIndexOrThrow(column)) ?: ""
        } catch (_: Exception) {
            ""
        }

    private fun android.database.Cursor.getLongOrNull(column: String): Long? =
        runCatching { getLong(getColumnIndexOrThrow(column)) }.getOrNull()

    private companion object {
        const val MAX_DAYS: UInt = 31u
        const val MAX_LIMIT: UInt = 100u
    }
}
