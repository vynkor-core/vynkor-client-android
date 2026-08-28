package dev.vynkor.agent.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AttachmentStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        File(context.filesDir, "attachments").deleteRecursively()
    }

    private fun writeSource(name: String, content: String): File =
        java.io.File(context.cacheDir, name).apply { writeText(content) }

    private fun copyIn(
        chatId: String,
        file: File,
        mime: String = "text/plain",
    ): Attachment? = AttachmentStore.copyIn(
        context,
        chatId,
        android.net.Uri.fromFile(file),
        file.name,
        mime,
    )

    @Test
    fun copyInOutRoundTripKeepsBytesAndDescriptor() {
        val src = writeSource("note.txt", "hello attachments")
        val chatId = "chat-1"

        val attachment = copyIn(chatId, src) ?: error("copy failed")

        assertEquals("note.txt", attachment.name)
        assertEquals("text/plain", attachment.mime)
        assertEquals("hello attachments".length.toLong(), attachment.sizeBytes)

        val stored = AttachmentStore.fileFor(context, chatId, attachment)
        assertTrue(stored.exists())
        assertEquals("hello attachments", stored.readText())

        assertEquals(
            "hello attachments",
            AttachmentStore.readTextForContext(context, chatId, attachment),
        )
    }

    @Test
    fun textContextIsBoundedByCharLimit() {
        val big = "a".repeat(AttachmentStore.TEXT_INLINE_LIMIT_BYTES.toInt() + 10)
        val src = writeSource("big.txt", big)
        val attachment = copyIn("chat-2", src) ?: error("copy failed")
        assertNull(AttachmentStore.readTextForContext(context, "chat-2", attachment))
    }

    @Test
    fun binaryMimeIsNotInlined() {
        val src = writeSource("img.bin", "\u0000\u0001binary")
        val attachment = copyIn("chat-3", src, mime = "application/octet-stream")
        assertNotNull(attachment)
        assertNull(AttachmentStore.readTextForContext(context, "chat-3", attachment!!))
    }

    @Test
    fun deleteAllRemovesFilesAndEmptyChatDir() {
        val src = writeSource("f.txt", "data")
        val attachment = copyIn("chat-4", src)!!
        val storedFile = AttachmentStore.fileFor(context, "chat-4", attachment)
        assertTrue(storedFile.exists())

        AttachmentStore.deleteAll(context, "chat-4", listOf(attachment))

        assertFalse(storedFile.exists())
        assertFalse(java.io.File(java.io.File(context.filesDir, "attachments"), "chat-4").exists())
    }

    @Test
    fun humanSizeFormats() {
        assertEquals("512 B", Attachment(sizeBytes = 512, name = "x", mime = "t").humanSize())
        assertEquals("2 KB", Attachment(sizeBytes = 2048, name = "x", mime = "t").humanSize())
        assertEquals("1.5 MB", Attachment(sizeBytes = 1_572_864, name = "x", mime = "t").humanSize())
    }
}
