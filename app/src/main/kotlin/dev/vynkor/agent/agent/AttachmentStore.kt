package dev.vynkor.agent.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * App-private storage for chat attachments:
 * `filesDir/attachments/{chatId}/{attachmentId}_{name}`.
 *
 * Copies happen at attach time so the message never depends on a content
 * resolver grant that can expire. Orphaned files (attachment removed, chat
 * deleted) are swept on demand.
 */
object AttachmentStore {
    private const val DIR = "attachments"

    /** Anything bigger is refused — attachments are context material. */
    const val MAX_FILE_BYTES: Long = 100L * 1024 * 1024

    fun fileFor(context: Context, chatId: String, attachment: Attachment): File {
        val safe = attachment.name.replace('/', '_').replace('\\', '_').ifBlank { "file" }
        return dir(context, chatId).resolve("${attachment.id}_$safe")
    }

    /**
     * Copies [uri] into the store and returns its descriptor, or null when
     * reading failed or the file exceeds [MAX_FILE_BYTES].
     */
    fun copyIn(
        context: Context,
        chatId: String,
        uri: Uri,
        displayName: String?,
        mimeHint: String?,
    ): Attachment? = runCatching {
        val resolver = context.contentResolver
        val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: return null
        if (size <= 0 || size > MAX_FILE_BYTES) return null
        val mime = mimeHint?.takeIf { it.isNotBlank() }
            ?: resolver.getType(uri)
            ?: "application/octet-stream"
        val name = displayName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "file"
        val attachment = Attachment(name = name, mime = mime, sizeBytes = size)
        val target = fileFor(context, chatId, attachment)
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        if (target.length() != size) {
            target.delete()
            return null
        }
        attachment
    }.getOrNull()

    /** Removes the bytes of every listed attachment (best effort). */
    fun deleteAll(context: Context, chatId: String, attachments: List<Attachment>) {
        attachments.forEach { fileFor(context, chatId, it).delete() }
        if (dir(context, chatId).list().isNullOrEmpty()) dir(context, chatId).delete()
    }

    /** Deletes everything under [chatId] (chat removal). */
    fun deleteChatDir(context: Context, chatId: String) {
        dir(context, chatId).deleteRecursively()
    }

    /**
     * Textual content for AI context: text-ish files up to [TEXT_INLINE_LIMIT]
     * chars; null for binary or oversized ones.
     */
    fun readTextForContext(context: Context, chatId: String, attachment: Attachment): String? {
        if (!attachment.isTextLike()) return null
        if (attachment.sizeBytes > TEXT_INLINE_LIMIT_BYTES) return null
        return runCatching {
            fileFor(context, chatId, attachment).readText().take(TEXT_INLINE_LIMIT_CHARS)
        }.getOrNull()
    }

    private fun isTextLike(mime: String): Boolean =
        mime.startsWith("text/") ||
            mime.contains("json") || mime.contains("xml") || mime.contains("yaml") ||
            mime.contains("csv") || mime.contains("javascript")

    private fun Attachment.isTextLike(): Boolean = isTextLike(mime) ||
        name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

    /** ACTION_VIEW intent over the FileProvider so any viewer app can open it. */
    fun viewIntent(context: Context, chatId: String, attachment: Attachment): Intent? {
        val file = fileFor(context, chatId, attachment)
        if (!file.exists()) return null
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, attachment.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun dir(context: Context, chatId: String): File =
        File(context.filesDir, "$DIR/$chatId").apply { mkdirs() }

    internal const val TEXT_INLINE_LIMIT_BYTES: Long = 64L * 1024
    internal const val TEXT_INLINE_LIMIT_CHARS = 16_000

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "log", "kt", "java", "rs", "py", "js", "ts", "c", "cpp", "h",
        "go", "sh", "gradle", "kts", "toml", "yml", "ini", "conf", "sql",
    )
}
