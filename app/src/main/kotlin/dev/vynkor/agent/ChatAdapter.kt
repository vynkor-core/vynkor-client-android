package dev.vynkor.agent

import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import android.widget.FrameLayout
import android.view.Gravity
import android.content.res.ColorStateList
import dev.vynkor.agent.agent.ChatMessage
import dev.vynkor.agent.databinding.ItemMessageBinding
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import io.noties.markwon.syntax.Prism4jThemeDarkula
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.Prism4j

/**
 * R-16: [ListAdapter] + DiffUtil — inserts animate, scroll position holds,
 * and only changed rows rebind (no more full `notifyDataSetChanged`).
 * Stable ids come from [ChatMessage.id] (R-25).
 *
 * Assistant messages support a typewriter reveal ([startTyping]/[stepTyping]):
 * the full text is already persisted — only the rendered prefix grows.
 */
class ChatAdapter(
    private val onUserLongPress: (ChatMessage, View) -> Unit,
    private val onCopy: (ChatMessage) -> Unit,
    private val onMore: (ChatMessage, View) -> Unit,
    private val onSpeak: (ChatMessage) -> Unit,
    private val onTypingTap: (() -> Unit)? = null,
) : ListAdapter<ChatMessage, ChatAdapter.Holder>(DIFF) {

    private var speaking: ChatMessage? = null

    private var typingId: String? = null
    private var typingRevealed: Int = 0

    fun submit(messages: List<ChatMessage>) {
        submitList(messages.toList())
        typingId = null
        typingRevealed = 0
        speaking = null
    }

    fun append(message: ChatMessage) {
        submitList(currentList + message)
    }

    fun clear() {
        submitList(emptyList())
        typingId = null
        typingRevealed = 0
        speaking = null
    }

    /** Marks [message] as currently spoken (or none) and refreshes the
     * affected rows so the speak button reflects the toggle state. */
    fun setSpeaking(message: ChatMessage?) {
        if (speaking == message) return
        val prev = speaking
        speaking = message
        prev?.let { p -> currentList.indexOf(p).takeIf { it >= 0 }?.let { notifyItemChanged(it) } }
        message?.let { m -> currentList.indexOf(m).takeIf { it >= 0 }?.let { notifyItemChanged(it) } }
    }

    fun startTyping(message: ChatMessage) {
        finishTyping()
        typingId = message.id
        typingRevealed = 0
        currentList.indexOfFirst { it.id == message.id }
            .takeIf { it >= 0 }
            ?.let { notifyItemChanged(it) }
    }

    /** Advances the reveal by [chars]; true when nothing is left to reveal. */
    fun stepTyping(messageId: String, chars: Int): Boolean {
        if (typingId != messageId || chars <= 0) return true
        val idx = currentList.indexOfFirst { it.id == messageId }
        val total = currentList.getOrNull(idx)?.content?.length ?: return true
        typingRevealed += chars
        if (idx in 0 until itemCount) notifyItemChanged(idx, TYPING_PAYLOAD)
        return typingRevealed >= total
    }

    fun finishTyping() {
        val id = typingId ?: return
        typingId = null
        typingRevealed = 0
        currentList.indexOfFirst { it.id == id }
            .takeIf { it >= 0 }
            ?.let { notifyItemChanged(it) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), speaking == getItem(position))
    }

    override fun onBindViewHolder(
        holder: Holder,
        position: Int,
        payloads: List<Any>,
    ) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
        } else {
            holder.renderAssistantText(getItem(position))
        }
    }

    inner class Holder(private val binding: ItemMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private val ctx = binding.root.context
        private var markwon: Markwon? = null

        fun bind(message: ChatMessage, isSpeaking: Boolean) {
            val lp = binding.bubble.layoutParams as FrameLayout.LayoutParams
            when (message.role) {
                "user" -> {
                    lp.gravity = Gravity.END
                    binding.bubble.setBackgroundResource(R.drawable.bubble_user)
                    markwon().setMarkdown(binding.messageText, message.content)
                    binding.messageText.setTextColor(color(R.color.on_primary))
                    binding.footerRow.visibility = View.GONE
                    itemView.setOnClickListener(null)
                    itemView.setOnLongClickListener {
                        onUserLongPress(message, itemView)
                        true
                    }
                }
                "error" -> {
                    lp.gravity = Gravity.CENTER
                    binding.bubble.setBackgroundResource(R.drawable.bubble_error)
                    binding.messageText.text = message.content
                    binding.messageText.setTextColor(color(R.color.error))
                    binding.footerRow.visibility = View.GONE
                    itemView.setOnClickListener(null)
                    itemView.setOnLongClickListener(null)
                }
                else -> {
                    lp.gravity = Gravity.START
                    binding.bubble.setBackgroundResource(R.drawable.bubble_assistant)
                    renderAssistantText(message)
                    binding.messageText.setTextColor(color(R.color.on_surface))
                    binding.footerRow.visibility = View.VISIBLE
                    val tint = color(R.color.on_surface_variant)
                    val activeTint = if (isSpeaking) color(R.color.primary) else tint
                    listOf(
                        binding.copyAction,
                        binding.moreAction,
                        binding.speakAction,
                    ).forEach {
                        it.imageTintList = ColorStateList.valueOf(tint)
                        it.visibility = View.VISIBLE
                    }
                    binding.speakAction.imageTintList = ColorStateList.valueOf(activeTint)
                    binding.speakAction.contentDescription = ctx.getString(
                        if (isSpeaking) R.string.stop_speak else R.string.speak_message,
                    )
                    binding.copyAction.setOnClickListener { onCopy(message) }
                    binding.moreAction.setOnClickListener { onMore(message, binding.moreAction) }
                    binding.speakAction.setOnClickListener { onSpeak(message) }
                    itemView.setOnLongClickListener(null)
                }
            }
            binding.bubble.layoutParams = lp
        }

        /** Payload path (typewriter ticks): text-only rebind, no full reset. */
        fun renderAssistantText(message: ChatMessage) {
            val revealed = if (message.id == typingId) typingRevealed else -1
            val typing = revealed in 0 until message.content.length
            if (typing) {
                // Partial reveal renders plain flowing text; markdown lands once.
                binding.messageText.text = message.content.take(revealed)
            } else {
                markwon().setMarkdown(binding.messageText, message.content)
            }
            itemView.setOnClickListener(
                if (message.id == typingId) View.OnClickListener { onTypingTap?.invoke() }
                else null,
            )
        }

        private fun markwon(): Markwon = markwon ?: createMarkwon(ctx).also { markwon = it }

        private fun color(resId: Int): Int = ContextCompat.getColor(ctx, resId)
    }

    companion object {
        private const val TYPING_PAYLOAD = "typing"

        private fun createMarkwon(ctx: android.content.Context): Markwon {
            val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val prismTheme =
                if (night) Prism4jThemeDarkula.create() else Prism4jThemeDefault.create()
            return Markwon.builder(ctx)
                .usePlugin(TablePlugin.create(ctx))
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(LinkifyPlugin.create())
                .usePlugin(SyntaxHighlightPlugin.create(Prism4j(ChatGrammarLocator()), prismTheme))
                .build()
        }

        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage) =
                oldItem.role == newItem.role &&
                    oldItem.content == newItem.content &&
                    oldItem.timestamp == newItem.timestamp
        }
    }
}
