package dev.vynkor.agent

import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import dev.vynkor.agent.agent.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** Binds [message] into a real row and returns its root view. */
    private fun bindRow(adapter: ChatAdapter, message: ChatMessage): View {
        val ctx = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Vynkor)
        adapter.submit(listOf(message))
        settle(adapter, listOf(message))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        return holder.itemView
    }

    // A footer on a still-growing answer copies or speaks half of it.
    @Test
    fun `live bubble has no footer, a finished reply does`() {
        val adapter = adapter().apply { liveId = "live" }
        val live = bindRow(adapter, ChatMessage("assistant", "Here is", id = "live"))
        assertEquals(View.GONE, live.findViewById<View>(R.id.footerRow).visibility)
        val done = bindRow(adapter, ChatMessage("assistant", "Here is a poem"))
        assertEquals(View.VISIBLE, done.findViewById<View>(R.id.footerRow).visibility)
    }

    // Poems lost their line breaks: CommonMark joins single-newline lines.
    @Test
    fun `single newlines stay line breaks`() {
        val row = bindRow(adapter(), ChatMessage("assistant", "Roses are red,\nviolets are blue"))
        val text = row.findViewById<TextView>(R.id.messageText).text.toString()
        assertTrue(text, text.contains("red,\nviolets"))
    }
}
