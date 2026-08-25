package dev.vynkor.agent

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import dev.vynkor.agent.agent.Chat
import dev.vynkor.agent.databinding.ItemChatBinding

/**
 * One drawer row: a chat plus the active search query (null = plain list).
 * Carrying [query] inside the item lets DiffUtil rebind rows when only the
 * search text changes; [Chat] is immutable (R-33), so structural equality is
 * a safe content signal.
 */
data class ChatRow(val chat: Chat, val query: String?)

class ChatListAdapter(
    private val onOpen: (Chat) -> Unit,
    private val onLongPress: (Chat) -> Unit,
) : ListAdapter<ChatRow, ChatListAdapter.Holder>(DIFF) {

    fun submit(list: List<Chat>, query: String? = null) {
        submitList(list.map { ChatRow(it, query) })
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemChatBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemChatBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val highlightColor =
            MaterialColors.getColor(
                binding.root,
                com.google.android.material.R.attr.colorSecondaryContainer,
                android.graphics.Color.TRANSPARENT,
            )

        fun bind(row: ChatRow) {
            val chat = row.chat
            val query = row.query?.trim()?.takeIf { it.isNotEmpty() }
            binding.chatTitle.text =
                highlighted(
                    chat.title.ifBlank { binding.root.context.getString(R.string.new_chat) },
                    query,
                    highlightColor,
                )
            binding.chatPreview.text =
                if (query == null) {
                    chat.messages.lastOrNull()?.content.orEmpty()
                } else {
                    snippet(chat, query)?.let { highlighted(it, query, highlightColor) } ?: ""
                }
            itemView.setOnClickListener { onOpen(chat) }
            itemView.setOnLongClickListener {
                onLongPress(chat)
                true
            }
        }
    }

    companion object {
        private const val SNIPPET_BEFORE = 24
        private const val SNIPPET_AFTER = 40

        private val DIFF = object : DiffUtil.ItemCallback<ChatRow>() {
            override fun areItemsTheSame(oldItem: ChatRow, newItem: ChatRow) =
                oldItem.chat.id == newItem.chat.id

            override fun areContentsTheSame(oldItem: ChatRow, newItem: ChatRow) =
                oldItem == newItem
        }

        private fun indexOfMatch(text: String, query: String): Int =
            text.lowercase().indexOf(query.lowercase())

        private fun snippet(chat: Chat, query: String): String? {
            val content = chat.messages
                .firstOrNull { indexOfMatch(it.content, query) >= 0 }
                ?.content
                ?.replace('\n', ' ')
                ?: return null
            val idx = indexOfMatch(content, query)
            val start = maxOf(0, idx - SNIPPET_BEFORE)
            val end = minOf(content.length, idx + query.length + SNIPPET_AFTER)
            var text = content.substring(start, end)
            if (start > 0) text = "…$text"
            if (end < content.length) text += "…"
            return text
        }

        /** Every case-insensitive occurrence of [query] gets a highlight span. */
        private fun highlighted(text: String, query: String?, color: Int): CharSequence {
            if (query.isNullOrEmpty()) return text
            val lower = text.lowercase()
            val needle = query.lowercase()
            if (needle.isEmpty()) return text
            var at = lower.indexOf(needle)
            if (at < 0) return text
            val spannable = SpannableStringBuilder(text)
            while (at >= 0) {
                spannable.setSpan(
                    BackgroundColorSpan(color),
                    at,
                    at + needle.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                at = lower.indexOf(needle, at + needle.length)
            }
            return spannable
        }
    }
}
