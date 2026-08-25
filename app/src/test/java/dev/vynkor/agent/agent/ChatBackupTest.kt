package dev.vynkor.agent.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Backup codec + chat export/import contract. Profile persistence itself is
 * AndroidKeyStore-bound (fail-closed) and therefore smoke-tested on device,
 * not here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatBackupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        context.getSharedPreferences("vynkor_chat", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("vynkor_projects", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `encrypt-decrypt round trip and wrong password fails`() {
        val payload = org.json.JSONObject().put("greeting", "привет мир").toString()
        val envelope = ChatBackup.encryptToJson(payload, "pass1234".toCharArray())

        assertNull(ChatBackup.decryptToJson(envelope, "wrong".toCharArray()))
        assertEquals(payload, ChatBackup.decryptToJson(envelope, "pass1234".toCharArray()))
    }

    @Test
    fun `damaged envelope fails closed`() {
        val envelope = ChatBackup.encryptToJson("{}", "pass1234".toCharArray())
        // flip a byte inside the base64 payload region
        val broken = envelope.replaceRange(envelope.length / 2, envelope.length / 2 + 1, "A")

        assertNull(ChatBackup.decryptToJson(broken, "pass1234".toCharArray()))
        assertNull(ChatBackup.decryptToJson("{}", "pass1234".toCharArray()))
    }

    @Test
    fun `chats survive export-clear-import cycle`() {
        ChatStore.save(
            context,
            "p1",
            Chat(id = "c1", title = "t", messages = listOf(ChatMessage("user", "hi"))),
        )

        val json = ChatStore.exportJson(context, "p1")
        ChatStore.clear(context, "p1")
        assertTrue(ChatStore.list(context, "p1").isEmpty())

        assertEquals(1, ChatStore.importJson(context, "p1", json))
        val restored = ChatStore.load(context, "p1", "c1")!!
        assertEquals("t", restored.title)
        assertEquals(listOf("hi"), restored.messages.map { it.content })
        assertEquals(restored.messages.single().id, ChatStore.load(context, "p1", "c1")!!.messages.single().id)
    }

    @Test
    fun `import of corrupt chats json imports nothing`() {
        assertEquals(0, ChatStore.importJson(context, "px", "{oops"))
        assertTrue(ChatStore.list(context, "px").isEmpty())
    }

    @Test
    fun `peekStats counts profiles chats projects`() {
        val root = org.json.JSONObject()
            .put(
                "profiles",
                org.json.JSONArray().put(org.json.JSONObject()).put(org.json.JSONObject()),
            )
            .put(
                "chats",
                org.json.JSONObject().put("p1", org.json.JSONArray().put(org.json.JSONObject())),
            )
            .put(
                "projects",
                org.json.JSONObject().put(
                    "p1",
                    org.json.JSONArray().put(org.json.JSONObject()).put(org.json.JSONObject()),
                ),
            )

        val stats = ChatBackup.peekStats(root.toString())!!
        assertEquals(2, stats.profiles)
        assertEquals(1, stats.chats)
        assertEquals(2, stats.projects)
    }
}
