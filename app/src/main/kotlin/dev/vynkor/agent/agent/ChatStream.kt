package dev.vynkor.agent.agent

import dev.vynkor.agent.ActionReply
import dev.vynkor.agent.StreamListener
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * CD-03: one streamed `ai.chat_completion`, as the core's [StreamListener].
 *
 * The core calls the listener on its runtime thread and must not be
 * blocked, so callbacks only enqueue; [await] drains the queue on the
 * caller's (IO) thread, hands each text delta to `onDelta` and returns the
 * final reply. Host contract (vynkor-plugins `ai` README): accept →
 * `{"type":"delta","text"}` chunks → one `{"type":"done","result"}` chunk →
 * close.
 */
class ChatStream(private val onDelta: (String) -> Unit) : StreamListener {

    private sealed interface Event {
        data class Accepted(val data: String) : Event
        data class Chunk(val data: String) : Event
        data class Closed(val reason: String) : Event
        data class Failed(val error: String) : Event
        data object Stopped : Event
    }

    private val events = LinkedBlockingQueue<Event>()

    /**
     * Set when the host answered the whole completion in the acceptance
     * itself — an `ai` without streaming support treats the request as a
     * plain one. The kernel then holds a session nobody will close, so the
     * caller should cancel it.
     */
    @Volatile
    var answeredWhole = false
        private set

    override fun onAccepted(dataJson: ByteArray) {
        events.put(Event.Accepted(String(dataJson, Charsets.UTF_8)))
    }

    override fun onChunk(seq: UInt, chunk: ByteArray) {
        events.put(Event.Chunk(String(chunk, Charsets.UTF_8)))
    }

    override fun onClosed(reason: String) {
        events.put(Event.Closed(reason))
    }

    override fun onFailed(reply: ActionReply) {
        events.put(Event.Failed(reply.error.ifBlank { reply.status.name }))
    }

    /** Stop pressed: [await] returns what arrived so far. */
    fun stop() {
        events.put(Event.Stopped)
    }

    /**
     * Blocks until the stream ends. `idleTimeoutMs` bounds each wait for the
     * next frame, not the whole answer. Throws [AiException] on failure.
     */
    fun await(idleTimeoutMs: Long): AiReply {
        val text = StringBuilder()
        var result: JSONObject? = null
        while (true) {
            val event = events.poll(idleTimeoutMs, TimeUnit.MILLISECONDS)
                ?: throw AiException("no reply from host for ${idleTimeoutMs / 1000}s")
            when (event) {
                is Event.Accepted -> {
                    val data = runCatching { JSONObject(event.data) }.getOrNull()
                    if (data != null && data.has("content")) {
                        answeredWhole = true
                        return parseReply(data)
                    }
                }
                is Event.Chunk -> {
                    val chunk = runCatching { JSONObject(event.data) }.getOrNull() ?: continue
                    when (chunk.optString("type")) {
                        "delta" -> chunk.optString("text").takeIf { it.isNotEmpty() }?.let {
                            text.append(it)
                            onDelta(it)
                        }
                        "done" -> result = chunk.optJSONObject("result")
                    }
                }
                is Event.Closed -> return result?.let(::parseReply)
                    ?: AiReply(text.toString(), event.reason, 0, 0)
                is Event.Failed -> throw AiException(event.error)
                Event.Stopped -> return AiReply(text.toString(), STOPPED, 0, 0)
            }
        }
    }

    companion object {
        /** [AiReply.stopReason] of an answer the user cut short. */
        const val STOPPED = "stopped"

        internal fun parseReply(data: JSONObject): AiReply {
            val usage = data.optJSONObject("usage")
            return AiReply(
                content = data.optString("content"),
                stopReason = data.optString("stop_reason"),
                inputTokens = usage?.optLong("input_tokens") ?: 0L,
                outputTokens = usage?.optLong("output_tokens") ?: 0L,
            )
        }
    }
}
