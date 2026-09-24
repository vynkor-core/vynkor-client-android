package dev.vynkor.agent.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
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
        // Streaming providers (cloud drives, some pickers) report
        // UNKNOWN_LENGTH (-1): that is not an empty file — copy and measure.
        val declared = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        if (declared > MAX_FILE_BYTES) return null
        val mime = mimeHint?.takeIf { it.isNotBlank() }
            ?: resolver.getType(uri)
            ?: "application/octet-stream"
        // Document-provider URIs end in an opaque id ("image:1115"), not a
        // file name — ask the provider for the name the user sees.
        val name = displayName?.takeIf { it.isNotBlank() }
            ?: queryDisplayName(context, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "file"
        val draft = Attachment(name = name, mime = mime, sizeBytes = 0)
        val target = fileFor(context, chatId, draft)
        val copied = resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> copyCapped(input, output) }
        } ?: return null
        val complete = copied in 1..MAX_FILE_BYTES && (declared < 0 || copied == declared)
        if (!complete) {
            target.delete()
            return null
        }
        draft.copy(sizeBytes = copied)
    }.getOrNull()

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Copies at most [MAX_FILE_BYTES] + 1 bytes; the caller rejects anything over. */
    internal fun copyCapped(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        maxBytes: Long = MAX_FILE_BYTES,
    ): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (total <= maxBytes) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            total += n
        }
        return total
    }

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
