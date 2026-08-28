package dev.vynkor.agent.agent

import java.util.UUID

/**
 * One file attached to a chat message. The bytes live in the app-private
 * store ([AttachmentStore]) under the owning chat's directory; only this
 * descriptor is persisted inside [Chat].
 */
data class Attachment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mime: String,
    val sizeBytes: Long,
) {
    val isImage: Boolean get() = mime.startsWith("image/")
    val isVideo: Boolean get() = mime.startsWith("video/")

    fun humanSize(): String {
        if (sizeBytes < 1024) return "$sizeBytes B"
        val kb = sizeBytes / 1024.0
        if (kb < 1024) return "%.0f KB".format(kb)
        return "%.1f MB".format(kb / 1024)
    }
}
