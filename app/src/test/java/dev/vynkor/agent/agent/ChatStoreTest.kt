package dev.vynkor.agent.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-30: covers the R-12 store contract — atomicity of the visible API,
 * corrupt-JSON quarantine (no silent wipe), .bak retention, async persistence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        ChatStore.resetForTests()
        context.getSharedPreferences("vynkor_chat", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun msg(role: String, content: String) =
        ChatMessage(role = role, content = content)

    @Test
    fun `save then load round-trips messages with stable ids`() {
        val chat = Chat(
            title = "t",
            messages = listOf(msg("user", "hello"), msg("assistant", "hi")),
        )
        ChatStore.save(context, "p1", chat)
        ChatStore.awaitPendingWrites()

        ChatStore.resetForTests()
        val restored = ChatStore.load(context, "p1", chat.id)
        assertNotNull(restored)
        assertEquals(2, restored!!.messages.size)
        assertEquals(chat.messages[0].id, restored.messages[0].id)
        assertEquals("hi", restored.messages[1].content)
    }

    @Test
    fun `concurrent saves do not lose chats`() {
        repeat(50) { i ->
            Thread {
                ChatStore.save(
                    context,
                    "p2",
                    Chat(id = "chat-$i", title = "c$i"),
                )
            }.start()
        }
        ChatStore.awaitPendingWrites()
        assertEquals(50, ChatStore.list(context, "p2").size)
    }

    @Test
    fun `corrupt json is quarantined and not wiped`() {
        context.getSharedPreferences("vynkor_chat", Context.MODE_PRIVATE)
            .edit().putString("p3", "{definitely not json").commit()

        assertTrue(ChatStore.list(context, "p3").isEmpty())

        val prefs = context.getSharedPreferences("vynkor_chat", Context.MODE_PRIVATE)
        val quarantined = prefs.all.entries.filter { it.key.startsWith("corrupt_") }
        assertEquals(1, quarantined.size)
        assertEquals("{definitely not json", quarantined.single().value)
        assertNull(prefs.getString("p3", null))
    }

    @Test
    fun `previous raw value is kept as bak after rewrite`() {
        val a = Chat(id = "a", title = "first")
        ChatStore.save(context, "p4", a)
        ChatStore.awaitPendingWrites()
        val prefs = context.getSharedPreferences("vynkor_chat", Context.MODE_PRIVATE)
        val firstRaw = prefs.getString("p4", null)!!

        val b = Chat(id = "b", title = "second")
        ChatStore.save(context, "p4", b)
        ChatStore.awaitPendingWrites()

        val bak = prefs.getString("p4_bak", null)
        assertNotNull(bak)
        assertEquals(firstRaw, bak)
        assertNotEquals(firstRaw, prefs.getString("p4", null))
    }

    @Test
    fun `delete removes only the target chat`() {
        listOf("x1", "x2").forEach { id -> ChatStore.save(context, "p5", Chat(id = id)) }
        ChatStore.awaitPendingWrites()

        ChatStore.delete(context, "p5", "x1")
        ChatStore.awaitPendingWrites()

        assertNull(ChatStore.load(context, "p5", "x1"))
        assertNotNull(ChatStore.load(context, "p5", "x2"))
    }

    @Test
    fun `rename updates the stored title`() {
        ChatStore.save(context, "p6", Chat(id = "r", title = "old"))
        ChatStore.awaitPendingWrites()

        ChatStore.rename(context, "p6", "r", "new")
        ChatStore.awaitPendingWrites()

        assertEquals("new", ChatStore.load(context, "p6", "r")!!.title)
    }

    @Test
    fun `list sorts by updatedAt descending`() {
        val oldChat = Chat(id = "old", updatedAt = 1_000)
        val newChat = Chat(id = "new", updatedAt = 2_000)
        ChatStore.save(context, "p7", oldChat)
        ChatStore.save(context, "p7", newChat)
        ChatStore.awaitPendingWrites()

        assertEquals(listOf("new", "old"), ChatStore.list(context, "p7").map { it.id })
    }

    @Test
    fun `legacy bare message array migrates into one chat`() {
        val legacy = """[{"role":"user","content":"hi"},{"role":"assistant","content":"yo"}]"""
        context.getSharedPreferences("vynkor_chat", Context.MODE_PRIVATE)
            .edit().putString("p8", legacy).commit()

        val chats = ChatStore.list(context, "p8")
        assertEquals(1, chats.size)
        assertEquals(2, chats[0].messages.size)
        assertFalse(chats[0].title.isBlank()) // autoTitle filled from first user msg

        // migrated shape is persisted back
        ChatStore.awaitPendingWrites()
        val raw = context.getSharedPreferences("vynkor_chat", Context.MODE_PRIVATE)
            .getString("p8", null)!!
        assertTrue(raw.contains("\"id\""))
    }

    @Test
    fun `autoTitle trims from first user message`() {
        val chat = Chat(messages = listOf(msg("user", "  explain rust ownership in depth  ")))
        val titled = ChatStore.autoTitle(chat)
        assertEquals("explain rust ownership in depth".take(40), titled.title)
    }

    @Test
    fun `load on unknown profile returns null and empty list elsewhere`() {
        assertNull(ChatStore.load(context, "none", "whatever"))
        assertTrue(ChatStore.list(context, "none").isEmpty())
    }

    @Test
    fun `search finds chats by title ignoring case`() {
        ChatStore.save(context, "s1", Chat(id = "a", title = "Rust Ownership"))
        ChatStore.save(context, "s1", Chat(id = "b", title = "grocery list"))

        assertEquals("a", ChatStore.search(context, "s1", "rust").single().id)
        assertEquals(listOf("a", "b"), ChatStore.search(context, "s1", "S").map { it.id })
    }

    @Test
    fun `search finds chats by message content including cyrillic case`() {
        ChatStore.save(
            context,
            "s2",
            Chat(id = "m", messages = listOf(msg("user", "напомни про паспорт"))),
        )
        ChatStore.save(
            context,
            "s2",
            Chat(id = "n", messages = listOf(msg("user", "hello world"))),
        )

        assertEquals("m", ChatStore.search(context, "s2", "Паспорт").single().id)
        assertTrue(ChatStore.search(context, "s2", "мир").isEmpty())
    }

    @Test
    fun `blank search query returns everything`() {
        ChatStore.save(context, "s3", Chat(id = "x", title = "alpha"))
        ChatStore.save(context, "s3", Chat(id = "y", title = "beta"))

        assertEquals(2, ChatStore.search(context, "s3", "").size)
        assertEquals(2, ChatStore.search(context, "s3", "   ").size)
    }

    @Test
    fun `search with no matches returns empty`() {
        ChatStore.save(context, "s4", Chat(id = "q", title = "alpha"))

        assertTrue(ChatStore.search(context, "s4", "omega").isEmpty())
    }

    @Test
    fun `search keeps updatedAt descending order`() {
        ChatStore.save(context, "s5", Chat(id = "old", title = "match", updatedAt = 1_000))
        ChatStore.save(context, "s5", Chat(id = "new", title = "match", updatedAt = 2_000))

        assertEquals(
            listOf("new", "old"),
            ChatStore.search(context, "s5", "match").map { it.id },
        )
    }

    @Test
    fun `search is scoped to the requested profile`() {
        ChatStore.save(context, "s6a", Chat(id = "mine", title = "secret plan"))
        ChatStore.save(context, "s6b", Chat(id = "theirs", title = "secret plan"))

        assertEquals(
            listOf("mine"),
            ChatStore.search(context, "s6a", "secret").map { it.id },
        )
    }

    @Test
    fun `pinned chats sort above others regardless of updatedAt`() {
        ChatStore.save(context, "s7", Chat(id = "a", updatedAt = 3_000))
        ChatStore.save(context, "s7", Chat(id = "b", updatedAt = 1_000, pinned = true))
        ChatStore.save(context, "s7", Chat(id = "c", updatedAt = 2_000))

        assertEquals(listOf("b", "a", "c"), ChatStore.list(context, "s7").map { it.id })
    }

    @Test
    fun `setPinned toggles the flag and persists across reload`() {
        ChatStore.save(context, "s8", Chat(id = "p"))
        ChatStore.setPinned(context, "s8", "p", true)
        ChatStore.awaitPendingWrites()

        ChatStore.resetForTests()
        assertTrue(ChatStore.load(context, "s8", "p")!!.pinned)
    }

    @Test
    fun `search keeps pinned chats on top`() {
        ChatStore.save(
            context,
            "s9",
            Chat(id = "old-pinned", title = "match", updatedAt = 100, pinned = true),
        )
        ChatStore.save(context, "s9", Chat(id = "new", title = "match", updatedAt = 200))

        assertEquals(
            listOf("old-pinned", "new"),
            ChatStore.search(context, "s9", "match").map { it.id },
        )
    }
}
