package dev.vynkor.agent.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A project = a named folder grouping chats (feature request). Chats carry a
 * `projectId`; empty string means "no project" (the default inbox).
 */
data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Per-host-profile project persistence. Projects are few, so unlike
 * [ChatStore] this is a plain synchronized read-modify-write without an
 * in-memory cache.
 */
object ProjectStore {
    private const val PREFS = "vynkor_projects"
    private const val QUARANTINE_PREFIX = "corrupt_"

    private val lock = Any()

    fun list(context: Context, profileId: String): List<Project> = synchronized(lock) {
        val raw = prefs(context).getString(profileId, null) ?: return emptyList()
        try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                Project(
                    id = p.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = p.optString("name"),
                    createdAt = p.optLong("created_at", System.currentTimeMillis()),
                )
            }
            .sortedBy { it.createdAt }
        } catch (_: Exception) {
            quarantine(context, profileId, raw)
            emptyList()
        }
    }

    fun save(context: Context, profileId: String, project: Project) {
        synchronized(lock) {
            val all = list(context, profileId).toMutableList()
            val idx = all.indexOfFirst { it.id == project.id }
            if (idx >= 0) all[idx] = project else all.add(project)
            persist(context, profileId, all)
        }
    }

    fun rename(context: Context, profileId: String, projectId: String, name: String) {
        synchronized(lock) {
            val all = list(context, profileId).toMutableList()
            val idx = all.indexOfFirst { it.id == projectId }
            if (idx < 0) return
            all[idx] = all[idx].copy(name = name)
            persist(context, profileId, all)
        }
    }

    /** Deletes the project; chats keep living — caller clears their projectId. */
    fun delete(context: Context, profileId: String, projectId: String) {
        synchronized(lock) {
            val all = list(context, profileId).toMutableList()
            all.removeAll { it.id == projectId }
            persist(context, profileId, all)
        }
    }

    fun wipeAll(context: Context) {
        val p = prefs(context)
        p.all.keys
            .filter { it.startsWith(QUARANTINE_PREFIX).not() }
            .forEach { p.edit().remove(it).apply() }
    }

    private fun persist(context: Context, profileId: String, projects: List<Project>) {
        val arr = JSONArray()
        projects.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("created_at", p.createdAt),
            )
        }
        prefs(context).edit().putString(profileId, arr.toString()).apply()
    }

    /** Corrupt store is moved aside, never wiped (same policy as ChatStore). */
    private fun quarantine(context: Context, profileId: String, raw: String) {
        prefs(context).edit()
            .putString("$QUARANTINE_PREFIX${System.currentTimeMillis()}_$profileId", raw)
            .remove(profileId)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
