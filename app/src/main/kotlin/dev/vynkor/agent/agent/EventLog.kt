package dev.vynkor.agent.agent

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer of notable app events (connect/disconnect/errors),
 * shown in Settings -> Diagnostics and attached to the shareable report.
 * Process-local by design — nothing persists, nothing leaves the device
 * unless the user taps Share.
 */
object EventLog {
    private const val MAX = 250
    private val lines = ArrayDeque<String>(MAX)
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun push(tag: String, message: String) {
        synchronized(lines) {
            if (lines.size >= MAX) lines.pollFirst()
            lines.addLast("${fmt.format(Date())} $tag: ${message.take(300)}")
        }
    }

    /** Most recent last. */
    fun dump(maxLines: Int = 40): String = synchronized(lines) {
        val all = ArrayList(lines)
        val from = (all.size - maxLines).coerceAtLeast(0)
        all.subList(from, all.size).joinToString("\n")
    }

    fun clear() = synchronized(lines) { lines.clear() }
}
