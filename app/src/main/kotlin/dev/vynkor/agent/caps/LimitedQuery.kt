package dev.vynkor.agent.caps

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Bounded provider query. `"... LIMIT n"` smuggled into sortOrder is rejected
 * by strict providers — CallLogProvider throws "Invalid token LIMIT" — so
 * the limit travels as [ContentResolver.QUERY_ARG_LIMIT]. Providers that
 * ignore that arg still get bounded: read [rows] stops at [limit].
 */
internal fun ContentResolver.queryLimited(
    uri: Uri,
    projection: Array<String>,
    selection: String?,
    selectionArgs: Array<String>?,
    sortOrder: String,
    limit: Int,
): Cursor? {
    val args = Bundle().apply {
        if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        if (selectionArgs != null) {
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
        }
        putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
        putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
    }
    return query(uri, projection, args, null)
}

/** Iterates at most [limit] rows of the cursor, closing it afterwards. */
internal inline fun <T> Cursor.rows(limit: Int, read: (Cursor) -> T): List<T> = use { c ->
    val out = ArrayList<T>(minOf(limit, c.count.coerceAtLeast(0)))
    while (out.size < limit && c.moveToNext()) out.add(read(c))
    out
}
