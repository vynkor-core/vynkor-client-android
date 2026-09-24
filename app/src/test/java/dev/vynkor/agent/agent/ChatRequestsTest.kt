package dev.vynkor.agent.agent

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Replies must survive the chat activity going away mid-request (rotation):
 * with no listener attached they land in the chat store; Stop drops them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatRequestsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val profileId = "p1"

    @Before
    fun reset() {
        ChatStore.resetForTests()
        context.getSharedPreferences("vynkor_chat", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        ChatRequests.abort()
        ChatRequests.pendingConfirm?.let { ChatRequests.takeConfirm(it.confirm.goalId) }
    }

    private fun savedChat(): Chat {
        val chat = Chat(messages = listOf(ChatMessage("user", "hi")))
        ChatStore.save(context, profileId, chat)
        return chat
    }

    /** Runs main-looper tasks until the request has finished. */
    private fun awaitIdle() {
        val deadline = System.currentTimeMillis() + 5_000
        while (ChatRequests.inFlight.value != null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `reply with no listener attached is saved into the chat`() {
        val chat = savedChat()
        assertTrue(ChatRequests.start(context, profileId, chat.id, "err") { ChatRequests.Reply.Text("hello") })
        awaitIdle()

        val stored = ChatStore.load(context, profileId, chat.id)!!
        assertEquals(listOf("user", "assistant"), stored.messages.map { it.role })
        assertEquals("hello", stored.messages.last().content)
    }

    @Test
    fun `thrown call becomes an error message`() {
        val chat = savedChat()
        ChatRequests.start(context, profileId, chat.id, "fallback") { throw IllegalStateException() }
        awaitIdle()

        val last = ChatStore.load(context, profileId, chat.id)!!.messages.last()
        assertEquals("error", last.role)
        assertEquals("fallback", last.content)
    }

    @Test
    fun `attached listener gets the reply instead of the store`() {
        val chat = savedChat()
        var got: ChatRequests.Reply? = null
        val listener = ChatRequests.Listener { _, _, reply -> got = reply }
        ChatRequests.attach(listener)
        try {
            ChatRequests.start(context, profileId, chat.id, "err") { ChatRequests.Reply.Text("x") }
            awaitIdle()
        } finally {
            ChatRequests.detach(listener)
        }

        assertEquals(ChatRequests.Reply.Text("x"), got)
        assertEquals(1, ChatStore.load(context, profileId, chat.id)!!.messages.size)
    }

    @Test
    fun `abort drops the late reply and frees the slot`() {
        val chat = savedChat()
        val release = CountDownLatch(1)
        ChatRequests.start(context, profileId, chat.id, "err") {
            release.await(5, TimeUnit.SECONDS)
            ChatRequests.Reply.Text("late")
        }
        assertFalse(ChatRequests.start(context, profileId, chat.id, "err") { ChatRequests.Reply.Text("2nd") })
        assertTrue(ChatRequests.abort())
        assertNull(ChatRequests.inFlight.value)
        release.countDown()
        Thread.sleep(50)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, ChatStore.load(context, profileId, chat.id)!!.messages.size)
    }

    @Test
    fun `confirm stays parked until taken`() {
        val chat = savedChat()
        ChatRequests.start(context, profileId, chat.id, "err") { ChatRequests.Reply.Confirm("g1", "sms.send") }
        awaitIdle()

        assertNotNull(ChatRequests.pendingConfirm)
        assertNull(ChatRequests.takeConfirm("other"))
        assertEquals("sms.send", ChatRequests.takeConfirm("g1")!!.confirm.tool)
        assertNull(ChatRequests.pendingConfirm)
    }
}
