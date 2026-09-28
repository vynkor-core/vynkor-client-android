package dev.vynkor.agent.agent

import dev.vynkor.agent.ActionReply
import dev.vynkor.agent.ActionReplyStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** CD-03: stream frames → deltas + final reply. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatStreamTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun `deltas are forwarded and the done result wins`() {
        val deltas = mutableListOf<String>()
        val s = ChatStream { deltas += it }
        s.onAccepted(bytes("""{"model":"m"}"""))
        s.onChunk(0u, bytes("""{"type":"delta","text":"При"}"""))
        s.onChunk(1u, bytes("""{"type":"delta","text":"вет"}"""))
        s.onChunk(2u, bytes("""{"type":"done","result":{"content":"Привет","stop_reason":"stop","usage":{"input_tokens":3,"output_tokens":2}}}"""))
        s.onClosed("done")
        val reply = s.await(1_000)
        assertEquals(listOf("При", "вет"), deltas)
        assertEquals(AiReply("Привет", "stop", 3, 2), reply)
        assertFalse(s.answeredWhole)
    }

    @Test
    fun `stop returns the text so far`() {
        val s = ChatStream {}
        s.onAccepted(bytes("{}"))
        s.onChunk(0u, bytes("""{"type":"delta","text":"half"}"""))
        s.stop()
        assertEquals(AiReply("half", ChatStream.STOPPED, 0, 0), s.await(1_000))
    }

    @Test(expected = AiException::class)
    fun `failure surfaces as AiException`() {
        val s = ChatStream {}
        s.onFailed(ActionReply(ActionReplyStatus.ERROR, ByteArray(0), "provider returned HTTP 401"))
        s.await(1_000)
    }

    @Test
    fun `a host without streaming answers whole in the acceptance`() {
        val s = ChatStream {}
        s.onAccepted(bytes("""{"content":"full","stop_reason":"stop","usage":{"input_tokens":1,"output_tokens":1}}"""))
        assertEquals("full", s.await(1_000).content)
        assertTrue(s.answeredWhole)
    }

    @Test(expected = AiException::class)
    fun `silence past the idle bound fails`() {
        ChatStream {}.await(50)
    }
}
