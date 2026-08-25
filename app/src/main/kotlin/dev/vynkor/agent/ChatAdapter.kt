package dev.vynkor.agent

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

/**
 * R-16: [ListAdapter] + DiffUtil — inserts animate, scroll position holds,
 * and only changed rows rebind (no more full `notifyDataSetChanged`).
 * Stable ids come from [ChatMessage.id] (R-25).
 */
class ChatAdapter(
    private val onUserLongPress: (ChatMessage, View) -> Unit,
    private val onCopy: (ChatMessage) -> Unit,
    private val onMore: (ChatMessage, View) -> Unit,
    private val onSpeak: (ChatMessage) -> Unit,
) : ListAdapter<ChatMessage, ChatAdapter.Holder>(DIFF) {

    private var speaking: ChatMessage? = null

    fun submit(messages: List<ChatMessage>) {
        submitList(messages.toList())
        speaking = null
    }

    fun append(message: ChatMessage) {
        submitList(currentList + message)
    }

    fun clear() {
        submitList(emptyList())
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), speaking == getItem(position))
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
                    itemView.setOnLongClickListener(null)
                }
                else -> {
                    lp.gravity = Gravity.START
                    binding.bubble.setBackgroundResource(R.drawable.bubble_assistant)
                    markwon().setMarkdown(binding.messageText, message.content)
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

        private fun markwon(): Markwon = markwon ?: Markwon.create(ctx).also { markwon = it }

        private fun color(resId: Int): Int = ContextCompat.getColor(ctx, resId)
    }

    companion object {
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
