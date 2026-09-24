package dev.vynkor.agent.agent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-scoped runner for chat AI requests.
 *
 * Requests used to run in the chat activity's lifecycleScope: rotating the
 * phone (or a theme/language switch) mid-request cancelled the coroutine and
 * the host's reply was silently dropped. Here the request outlives the
 * activity. Its reply goes to the attached [Listener]; with none attached
 * (activity being recreated, or closed) it is written straight into the chat
 * so the next screen that opens it sees the answer.
 *
 * One request at a time, like the composer UI. All state is touched on the
 * main thread only.
 */
object ChatRequests {

    /** What one host round-trip produced. */
    sealed interface Reply {
        data class Text(val content: String) : Reply
        data class Confirm(val goalId: String, val tool: String) : Reply
        data class Error(val message: String) : Reply
    }

    data class InFlight(val profileId: String, val chatId: String)

    /** Agent tool confirmation waiting for the user's allow/deny. */
    data class PendingConfirm(val profileId: String, val chatId: String, val confirm: Reply.Confirm)

    fun interface Listener {
        /** Called on the main thread; the listener owns persisting the reply. */
        fun onReply(profileId: String, chatId: String, reply: Reply)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _inFlight = MutableStateFlow<InFlight?>(null)
    val inFlight: StateFlow<InFlight?> = _inFlight.asStateFlow()

    /**
     * Kept until [takeConfirm] — a confirm dialog dismissed by rotation is
     * shown again by the recreated activity instead of stalling the goal.
     */
    var pendingConfirm: PendingConfirm? = null
        private set

    private var listener: Listener? = null

    /** Bumped per request and on abort: stale replies are dropped. */
    private var seq = 0

    fun attach(l: Listener) {
        listener = l
    }

    fun detach(l: Listener) {
        if (listener === l) listener = null
    }

    /** Starts [call] off the main thread; false while another request runs. */
    fun start(
        context: Context,
        profileId: String,
        chatId: String,
        errorFallback: String,
        call: () -> Reply,
    ): Boolean {
        if (_inFlight.value != null) return false
        val app = context.applicationContext
        val mySeq = ++seq
        _inFlight.value = InFlight(profileId, chatId)
        scope.launch {
            val reply = withContext(Dispatchers.IO) {
                runCatching(call).getOrElse { e -> Reply.Error(e.message ?: errorFallback) }
            }
            if (mySeq != seq) return@launch // aborted: UI already reset
            _inFlight.value = null
            deliver(app, profileId, chatId, reply)
        }
        return true
    }

    /**
     * Composer Stop: unlocks the UI and drops the reply when it arrives. The
     * request itself still completes host-side — a real cancel needs the
     * kernel-side chat.cancel (docs/CLIENT_DRIVEN_KERNEL_TASKS.md).
     */
    fun abort(): Boolean {
        if (_inFlight.value == null) return false
        seq++
        _inFlight.value = null
        return true
    }

    /** Claims the pending confirmation once the user has answered it. */
    fun takeConfirm(goalId: String): PendingConfirm? =
        pendingConfirm?.takeIf { it.confirm.goalId == goalId }?.also { pendingConfirm = null }

    private fun deliver(app: Context, profileId: String, chatId: String, reply: Reply) {
        if (reply is Reply.Error) EventLog.push("ai", "completion failed: ${reply.message}")
        if (reply is Reply.Confirm) pendingConfirm = PendingConfirm(profileId, chatId, reply)
        val l = listener
        if (l != null) {
            l.onReply(profileId, chatId, reply)
            return
        }
        val message = when (reply) {
            is Reply.Text -> ChatMessage("assistant", reply.content)
            is Reply.Error -> ChatMessage("error", reply.message)
            is Reply.Confirm -> return // parked in pendingConfirm
        }
        val stored = ChatStore.load(app, profileId, chatId) ?: return
        ChatStore.save(
            app,
            profileId,
            stored.copy(messages = stored.messages + message, updatedAt = System.currentTimeMillis()),
        )
    }
}
