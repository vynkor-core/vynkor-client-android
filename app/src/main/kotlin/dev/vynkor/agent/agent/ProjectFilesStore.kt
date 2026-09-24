package dev.vynkor.agent.agent

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Per-project file folder. Files are copied into
 * `filesDir/project_files/{profileId}/{projectId}/` and their textual
 * contents are injected into every AI request made from the project's chats.
 */
object ProjectFilesStore {
    private const val DIR = "project_files"
    private const val PREFS = "vynkor_project_files"
    private const val MAX_FILE_BYTES: Long = 10L * 1024 * 1024
    private const val MAX_FILES_PER_PROJECT = 20

    data class ProjectFile(
        val id: String,
        val name: String,
        val mime: String,
        val sizeBytes: Long,
        val addedAt: Long,
    )

    fun list(context: Context, profileId: String, projectId: String): List<ProjectFile> =
        prefs(context).getString(key(profileId, projectId), null)?.let { raw ->
            runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ProjectFile(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        mime = o.optString("mime", "application/octet-stream"),
                        sizeBytes = o.optLong("size_bytes"),
                        addedAt = o.optLong("added_at"),
                    )
                }.sortedBy { it.addedAt }
            }.getOrDefault(emptyList())
        } ?: emptyList()

    /** Copies [uri] into the project folder; null on failure / over limits. */
    fun add(
        context: Context,
        profileId: String,
        projectId: String,
        uri: Uri,
        displayName: String?,
        mimeHint: String?,
    ): ProjectFile? = runCatching {
        val existing = list(context, profileId, projectId)
        if (existing.size >= MAX_FILES_PER_PROJECT) return null
        val resolver = context.contentResolver
        // UNKNOWN_LENGTH (-1) from streaming providers is not "empty".
        val declared = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        if (declared > MAX_FILE_BYTES) return null
        val mime = mimeHint?.takeIf { it.isNotBlank() }
            ?: resolver.getType(uri)
            ?: "application/octet-stream"
        val name = (displayName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "file")
            .replace('/', '_')
        val draft = ProjectFile(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            mime = mime,
            sizeBytes = 0,
            addedAt = System.currentTimeMillis(),
        )
        val target = fileFor(context, profileId, projectId, draft)
        val copied = resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> AttachmentStore.copyCapped(input, output, MAX_FILE_BYTES) }
        } ?: return null
        // A truncated or oversized copy was recorded as a valid file before.
        if (copied !in 1..MAX_FILE_BYTES || (declared >= 0 && copied != declared)) {
            target.delete()
            return null
        }
        val file = draft.copy(sizeBytes = copied)
        persist(context, profileId, projectId, existing + file)
        file
    }.getOrNull()

    fun remove(context: Context, profileId: String, projectId: String, fileId: String) {
        val existing = list(context, profileId, projectId)
        val victim = existing.firstOrNull { it.id == fileId } ?: return
        fileFor(context, profileId, projectId, victim).delete()
        persist(context, profileId, projectId, existing - victim)
    }

    fun deleteProjectDir(context: Context, profileId: String, projectId: String) {
        dir(context, profileId, projectId).deleteRecursively()
        prefs(context).edit().remove(key(profileId, projectId)).apply()
    }

    fun fileFor(
        context: Context,
        profileId: String,
        projectId: String,
        file: ProjectFile,
    ): File = dir(context, profileId, projectId).resolve("${file.id}_${file.name.replace('/', '_')}")

    /**
     * Context sources for the AI block: text-ish files inline their content,
     * binary ones contribute metadata only.
     */
    fun contextSources(
        context: Context,
        profileId: String,
        projectId: String?,
    ): List<AiContext.Source> {
        if (projectId.isNullOrBlank()) return emptyList()
        return list(context, profileId, projectId).mapNotNull { f ->
            val target = fileFor(context, profileId, projectId, f)
            if (!target.exists()) return@mapNotNull null
            AiContext.Source(
                name = f.name,
                typeLabel = typeLabel(f.mime),
                sizeLabel = humanSize(f.sizeBytes),
                textContent = readTextBounded(target),
            )
        }
    }

    private fun readTextBounded(file: File): String? {
        val textLike = file.extension.lowercase() in TEXT_EXTENSIONS ||
            file.length() in 1..TEXT_INLINE_BYTES && looksTextual(file)
        if (!textLike || file.length() > TEXT_INLINE_BYTES) return null
        return runCatching { file.readText().take(TEXT_INLINE_CHARS) }.getOrNull()
    }

    private fun looksTextual(file: File): Boolean = runCatching {
        val probe = file.inputStream().use { input ->
            val buf = ByteArray(512)
            val n = input.read(buf)
            if (n <= 0) return@runCatching false
            buf.copyOf(n)
        }
        probe.none { it == 0.toByte() }
    }.getOrDefault(false)

    internal fun typeLabel(mime: String): String = when {
        mime.startsWith("image/") -> "image"
        mime.startsWith("video/") -> "video"
        mime.startsWith("text/") -> "text"
        else -> mime.substringAfter('/').ifBlank { "file" }
    }

    internal fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.0f KB".format(kb)
        return "%.1f MB".format(kb / 1024)
    }

    private fun persist(
        context: Context,
        profileId: String,
        projectId: String,
        files: List<ProjectFile>,
    ) {
        val arr = JSONArray()
        files.forEach { f ->
            arr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("name", f.name)
                    .put("mime", f.mime)
                    .put("size_bytes", f.sizeBytes)
                    .put("added_at", f.addedAt),
            )
        }
        prefs(context).edit().putString(key(profileId, projectId), arr.toString()).apply()
    }

    private fun key(profileId: String, projectId: String) = "${profileId}_$projectId"

    private fun dir(context: Context, profileId: String, projectId: String): File =
        File(File(context.filesDir, DIR), "$profileId/$projectId").apply { mkdirs() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val TEXT_INLINE_BYTES: Long = 256L * 1024
    private const val TEXT_INLINE_CHARS = 16_000

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "log", "kt", "java", "rs", "py", "js", "ts", "c", "cpp", "h",
        "go", "sh", "gradle", "kts", "toml", "yml", "yaml", "ini", "conf", "sql",
        "json", "xml", "csv",
    )
}
