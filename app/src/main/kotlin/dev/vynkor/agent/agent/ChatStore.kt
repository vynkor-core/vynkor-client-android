package dev.vynkor.agent.agent

import android.content.Context
import dev.vynkor.agent.R
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Multi-chat persistence for the `ai` plugin's transcripts. All chats for one
 * host profile live under a single SharedPreferences entry keyed by
 * `profileId`, as a JSON array of `Chat` objects:
 *
 *   [{ id, title, created_at, updated_at, messages: [{role, content, timestamp}] }]
 *
 * R-12 contract:
 * - every public op runs under one lock over an in-memory cache — concurrent
 *   saves can no longer lose updates;
 * - the cache is parsed once per profile per process; mutations are memory-only
 *   and disk writes happen asynchronously on a single IO thread (no more
 *   parse+serialize of the whole store on the UI thread per message);
 * - a corrupted store is quarantined (raw value moved to a `corrupt_<ts>_`
 *   key), never silently wiped;
 * - each write keeps the previous raw value under a `<profileId>_bak` key.
 *
 * Older app versions stored a bare JSON array of `{role, content}` messages
 * under the same key; that shape is migrated into a single `Chat` on load.
 */
object ChatStore {
    private const val TAG = "ChatStore"
    private const val PREFS = "vynkor_chat"
    private const val QUARANTINE_PREFIX = "corrupt_"
    private const val BAK_SUFFIX = "_bak"

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "chat-store-io") }
    private val cache = HashMap<String, MutableList<Chat>>()

    /** Drawer order: pinned chats on top, then most recently updated. */
    private val drawerOrder = compareByDescending<Chat> { it.pinned }
        .thenByDescending { it.updatedAt }

    fun list(context: Context, profileId: String): List<Chat> = synchronized(lock) {
        ensureLoaded(context, profileId)
        cache.getValue(profileId).sortedWith(drawerOrder)
    }

    /**
     * Chats whose title or any message content matches [query] as a
     * case-insensitive substring; blank query = no filtering. Reads the
     * in-memory cache under [lock] — no extra parsing on the hot path.
     */
    fun search(context: Context, profileId: String, query: String): List<Chat> =
        synchronized(lock) {
            ensureLoaded(context, profileId)
            val chats = cache.getValue(profileId).sortedWith(drawerOrder)
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                chats
            } else {
                val needle = trimmed.lowercase()
                chats.filter { chat ->
                    chat.title.lowercase().contains(needle) ||
                        chat.messages.any { it.content.lowercase().contains(needle) }
                }
            }
        }

    fun load(context: Context, profileId: String, chatId: String): Chat? = synchronized(lock) {
        ensureLoaded(context, profileId)
        cache.getValue(profileId).firstOrNull { it.id == chatId }
    }

    fun save(context: Context, profileId: String, chat: Chat) {
        synchronized(lock) {
            ensureLoaded(context, profileId)
            val chats = cache.getValue(profileId)
            val idx = chats.indexOfFirst { it.id == chat.id }
            if (idx >= 0) chats[idx] = chat else chats.add(chat)
            scheduleWrite(context, profileId)
        }
    }

    fun delete(context: Context, profileId: String, chatId: String) {
        synchronized(lock) {
            ensureLoaded(context, profileId)
            if (cache.getValue(profileId).removeAll { it.id == chatId }) {
                scheduleWrite(context, profileId)
                AttachmentStore.deleteChatDir(context, chatId)
            }
        }
    }

    /** Drops every chat of [profileId] (Settings -> Data -> Clear history). */
    fun clear(context: Context, profileId: String) {
        synchronized(lock) {
            ensureLoaded(context, profileId)
            val chats = cache.getValue(profileId)
            if (chats.isNotEmpty()) {
                chats.forEach { AttachmentStore.deleteChatDir(context, it.id) }
                chats.clear()
                scheduleWrite(context, profileId)
            }
        }
    }

    /**
     * All chats of [profileId] as a JSON array — the backup export format.
     * Caller holds no lock; serialization happens under [lock].
     */
    fun exportJson(context: Context, profileId: String): String = synchronized(lock) {
        ensureLoaded(context, profileId)
        val arr = JSONArray()
        cache.getValue(profileId).forEach { arr.put(chatToJson(it)) }
        arr.toString()
    }

    /** Replaces [profileId]'s chats with the exported array; returns count. */
    fun importJson(context: Context, profileId: String, json: String): Int = synchronized(lock) {
        val parsed = try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { chatFromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "backup import: bad chats json", e)
            return 0
        }
        ensureLoaded(context, profileId)
        cache[profileId] = parsed.toMutableList()
        scheduleWrite(context, profileId)
        parsed.size
    }

    fun rename(context: Context, profileId: String, chatId: String, title: String) {
        synchronized(lock) {
            ensureLoaded(context, profileId)
            val chats = cache.getValue(profileId)
            val idx = chats.indexOfFirst { it.id == chatId }
            if (idx < 0) return
            chats[idx] = chats[idx].copy(title = title)
            scheduleWrite(context, profileId)
        }
    }

    /** Returns a copy titled from the first user message, trimmed to 40 chars. */
    fun autoTitle(chat: Chat): Chat {
        if (chat.title.isNotBlank()) return chat
        val firstUser = chat.messages.firstOrNull { it.role == "user" } ?: return chat
        return chat.copy(title = firstUser.content.trim().take(40))
    }

    /** Moves a chat into a project (null = back to the default inbox). */
    fun moveToProject(context: Context, profileId: String, chatId: String, projectId: String?) {
        synchronized(lock) {
            ensureLoaded(context, profileId)
            val chats = cache.getValue(profileId)
            val idx = chats.indexOfFirst { it.id == chatId }
            if (idx < 0) return
            chats[idx] = chats[idx].copy(projectId = projectId.orEmpty())
            scheduleWrite(context, profileId)
        }
    }

    /**
     * Full clone of [chatId]: fresh ids everywhere (chat, messages,
     * attachments) and attachment bytes copied into the new chat's store
     * directory, so deleting either copy never orphans or breaks the other.
     * Returns the new chat id, or null when the source is missing.
     */
    fun cloneChat(context: Context, profileId: String, chatId: String): String? {
        synchronized(lock) {
            ensureLoaded(context, profileId)
            val src = cache.getValue(profileId).firstOrNull { it.id == chatId } ?: return null
            val newId = java.util.UUID.randomUUID().toString()
            val copiedMessages = src.messages.map { msg ->
                val atts = msg.attachments.mapNotNull { att ->
                    val from = AttachmentStore.fileFor(context, chatId, att)
                    if (!from.exists()) return@mapNotNull null
                    val copy = att.copy(id = java.util.UUID.randomUUID().toString())
                    val to = AttachmentStore.fileFor(context, newId, copy)
                    runCatching { from.copyTo(to, overwrite = true) }
                        .getOrNull()?.takeIf { it.length() == from.length() } ?: return@mapNotNull null
                    copy
                }
                msg.copy(id = java.util.UUID.randomUUID().toString(), attachments = atts)
            }
            val clone = src.copy(
                id = newId,
                // Title marks the twin; pinned state is not inherited.
                title = context.getString(R.string.duplicate_title_fmt, src.title.ifBlank { context.getString(R.string.new_chat) }),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                pinned = false,
                messages = copiedMessages,
            )
            val chats = cache.getValue(profileId)
            chats.add(clone)
            scheduleWrite(context, profileId)
            return newId
        }
    }

    fun setPinned(context: Context, profileId: String, chatId: String, pinned: Boolean) {
        synchronized(lock) {
            ensureLoaded(context, profileId)
            val chats = cache.getValue(profileId)
            val idx = chats.indexOfFirst { it.id == chatId }
            if (idx < 0) return
            chats[idx] = chats[idx].copy(pinned = pinned)
            scheduleWrite(context, profileId)
        }
    }

    /** Project deleted: its chats fall back to the default inbox. */
    fun clearProject(context: Context, profileId: String, projectId: String) {
        synchronized(lock) {
            ensureLoaded(context, profileId)
            val chats = cache.getValue(profileId)
            var changed = false
            for (i in chats.indices) {
                if (chats[i].projectId == projectId) {
                    chats[i] = chats[i].copy(projectId = "")
                    changed = true
                }
            }
            if (changed) scheduleWrite(context, profileId)
        }
    }

    /** Test-only: block until all scheduled disk writes have completed. */
    internal fun awaitPendingWrites() {
        io.submit {}.get()
    }

    /** Test-only: drop the in-memory cache (Robolectric resets prefs per test). */
    internal fun resetForTests() {
        synchronized(lock) { cache.clear() }
    }

    // ------------------------------------------------------------------ io

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Caller holds [lock]. Parses the stored JSON once, quarantines garbage. */
    private fun ensureLoaded(context: Context, profileId: String) {
        if (cache.containsKey(profileId)) return
        val raw = prefs(context).getString(profileId, null)
        if (raw == null) {
            cache[profileId] = mutableListOf()
            return
        }
        val parsed = parse(raw)
        if (parsed == null) {
            cache[profileId] = mutableListOf()
            quarantine(context, profileId, raw)
            return
        }
        cache[profileId] = parsed
        // Legacy shape was rewritten to a single Chat on load in previous
        // versions — keep that persistence parity.
        if (isLegacyArray(raw)) scheduleWrite(context, profileId)
    }

    /**
     * Caller holds [lock]. Serializing happens on the IO thread under the
     * same lock, so the snapshot is consistent and the calling (often main)
     * thread only pays for the cache mutation.
     */
    private fun scheduleWrite(context: Context, profileId: String) {
        val app = context.applicationContext
        io.execute {
            val json: String? = synchronized(lock) {
                val chats = cache[profileId]
                if (chats == null) {
                    null
                } else {
                    val arr = JSONArray()
                    chats.forEach { arr.put(chatToJson(it)) }
                    arr.toString()
                }
            }
            if (json == null) return@execute
            val p = prefs(app)
            val previous = p.getString(profileId, null)
            // Already on the IO thread: commit() makes the write durable
            // before the next one; apply() re-queued it and a process kill
            // right after a reply could lose the message.
            p.edit()
                .putString(profileId, json)
                .putString(profileId + BAK_SUFFIX, previous)
                .commit()
        }
    }

    private fun quarantine(context: Context, profileId: String, raw: String) {
        prefs(context).edit()
            .putString("$QUARANTINE_PREFIX${System.currentTimeMillis()}_$profileId", raw)
            .remove(profileId)
            .apply()
        Log.w(TAG, "chat store unreadable, quarantined ($profileId)")
    }

    /** New format first; falls back to the legacy bare-message-array shape. */
    private fun parse(raw: String): MutableList<Chat>? {
        return try {
            val arr = JSONArray(raw)
            val first = arr.optJSONObject(0)
            if (first != null && !first.has("id")) {
                mutableListOf(wrapLegacy(arr))
            } else {
                (0 until arr.length()).map { chatFromJson(arr.getJSONObject(it)) }.toMutableList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse chats", e)
            null
        }
    }

    private fun isLegacyArray(raw: String): Boolean = try {
        val arr = JSONArray(raw)
        val first = arr.optJSONObject(0)
        arr.length() > 0 && (first == null || !first.has("id"))
    } catch (_: Exception) {
        false
    }

    // ---------------------------------------------------------- (de)serialize

    private fun chatToJson(chat: Chat): JSONObject = JSONObject().apply {
        put("id", chat.id)
        put("title", chat.title)
        put("created_at", chat.createdAt)
        put("updated_at", chat.updatedAt)
        put("project_id", chat.projectId)
        val msgs = JSONArray()
        chat.messages.forEach { m ->
            val msgJson = JSONObject()
                .put("id", m.id)
                .put("role", m.role)
                .put("content", m.content)
                .put("timestamp", m.timestamp)
            if (m.attachments.isNotEmpty()) {
                val atts = JSONArray()
                m.attachments.forEach { a ->
                    atts.put(
                        JSONObject()
                            .put("id", a.id)
                            .put("name", a.name)
                            .put("mime", a.mime)
                            .put("size_bytes", a.sizeBytes),
                    )
                }
                msgJson.put("attachments", atts)
            }
            msgs.put(msgJson)
        }
        put("messages", msgs)
        put("pinned", chat.pinned)
    }

    private fun chatFromJson(o: JSONObject): Chat {
        val messages = mutableListOf<ChatMessage>()
        val msgs = o.optJSONArray("messages")
        if (msgs != null) {
            for (i in 0 until msgs.length()) {
                val m = msgs.getJSONObject(i)
                val attachments = mutableListOf<Attachment>()
                m.optJSONArray("attachments")?.let { atts ->
                    for (j in 0 until atts.length()) {
                        val a = atts.getJSONObject(j)
                        attachments.add(
                            Attachment(
                                id = a.optString("id").ifBlank { UUID.randomUUID().toString() },
                                name = a.optString("name"),
                                mime = a.optString("mime", "application/octet-stream"),
                                sizeBytes = a.optLong("size_bytes"),
                            ),
                        )
                    }
                }
                messages.add(
                    ChatMessage(
                        // R-25: legacy rows have no id — a fresh UUID here is
                        // the migration; the next save persists it.
                        id = m.optString("id").ifBlank { UUID.randomUUID().toString() },
                        role = m.optString("role"),
                        content = m.optString("content"),
                        timestamp = m.optLong("timestamp", System.currentTimeMillis()),
                        attachments = attachments,
                    ),
                )
            }
        }
        return Chat(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            title = o.optString("title"),
            createdAt = o.optLong("created_at", System.currentTimeMillis()),
            updatedAt = o.optLong("updated_at", System.currentTimeMillis()),
            messages = messages,
            // Legacy rows have no project_id → "" = default inbox (migration).
            projectId = o.optString("project_id"),
            // Legacy rows have no pinned flag → false.
            pinned = o.optBoolean("pinned"),
        )
    }

    /** Legacy bare `{role, content}` array → one [Chat]. */
    private fun wrapLegacy(arr: JSONArray): Chat {
        val messages = mutableListOf<ChatMessage>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            messages.add(
                ChatMessage(
                    role = m.optString("role"),
                    content = m.optString("content"),
                ),
            )
        }
        val updatedAt = messages.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis()
        return autoTitle(Chat(messages = messages, updatedAt = updatedAt))
    }
}
