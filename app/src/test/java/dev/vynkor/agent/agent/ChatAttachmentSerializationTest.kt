package dev.vynkor.agent.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatAttachmentSerializationTest {

    private fun chatWithAttachments(): Chat {
        val message = ChatMessage(
            role = "user",
            content = "look at this",
            attachments = listOf(
                Attachment(name = "photo.jpg", mime = "image/jpeg", sizeBytes = 2048),
                Attachment(name = "clip.mp4", mime = "video/mp4", sizeBytes = 4096),
            ),
        )
        return Chat(title = "with attachments", messages = listOf(message))
    }

    @Test
    fun chatStoreRoundTripsAttachments() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("vynkor_chat", Context.MODE_PRIVATE).edit().clear().commit()
        ChatStore.resetForTests()

        val chat = chatWithAttachments()
        ChatStore.save(context, "p-att", chat)
        ChatStore.awaitPendingWrites()

        val restored = ChatStore.load(context, "p-att", chat.id) ?: error("chat missing")
        val attachments = restored.messages.single().attachments
        assertEquals(2, attachments.size)
        assertEquals("photo.jpg", attachments[0].name)
        assertEquals("image/jpeg", attachments[0].mime)
        assertEquals(2048L, attachments[0].sizeBytes)
        assertTrue(attachments[1].isVideo)
    }

    @Test
    fun attachmentFlags() {
        assertTrue(Attachment(name = "a.jpg", mime = "image/png", sizeBytes = 1).isImage)
        assertFalse(Attachment(name = "a.jpg", mime = "image/png", sizeBytes = 1).isVideo)
        assertFalse(Attachment(name = "v.mp4", mime = "video/mp4", sizeBytes = 1).isImage)
    }
}
