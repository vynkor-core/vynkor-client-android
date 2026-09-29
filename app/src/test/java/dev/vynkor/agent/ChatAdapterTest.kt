package dev.vynkor.agent

import android.os.Looper
import dev.vynkor.agent.agent.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatAdapterTest {

    private fun adapter() = ChatAdapter(
        onUserLongPress = { _, _ -> },
        onCopy = {},
        onMore = { _, _ -> },
        onSpeak = {},
    )

    /** The diff runs on a background thread and lands via the main looper. */
    private fun settle(adapter: ChatAdapter, expected: List<ChatMessage>) {
        val deadline = System.currentTimeMillis() + 5_000
        while (adapter.currentList != expected && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    // CD-03 on a phone: the stream ended, the live bubble was dropped by one
    // submit and the final reply appended before that diff landed — the
    // user saw the answer twice.
    @Test
    fun `append after a pending submit builds on the newer list`() {
        val adapter = adapter()
        val ask = ChatMessage("user", "poem?")
        val live = ChatMessage("assistant", "Here is", id = "live")
        val final = ChatMessage("assistant", "Here is a poem")

        adapter.submit(listOf(ask, live))
        adapter.submit(listOf(ask))
        adapter.append(final)

        settle(adapter, listOf(ask, final))
        assertEquals(listOf(ask, final), adapter.currentList)
    }
}
