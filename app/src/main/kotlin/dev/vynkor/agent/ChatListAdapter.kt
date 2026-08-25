package dev.vynkor.agent

import android.content.Context
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
import dev.vynkor.agent.databinding.ItemDayHeaderBinding

/**
 * One drawer row: a chat plus the active search query (null = plain list).
 * Carrying [query] inside the item lets DiffUtil rebind rows when only the
 * search text changes; [Chat] is immutable (R-33), so structural equality is
 * a safe content signal.
 */
data class ChatRow(val chat: Chat, val query: String?)

/** Drawer list item: either a day header (DeepSeek-style grouping) or a chat. */
sealed class DrawerItem {
    data class Header(val label: String) : DrawerItem()
    data class ChatEntry(val row: ChatRow) : DrawerItem()

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_CHAT = 1

        /**
         * Flat display list with header rows inserted whenever the day label
         * changes; [rows] must already be sorted newest-first.
         */
        fun build(rows: List<ChatRow>, dayLabel: (Long) -> String): List<DrawerItem> {
            val items = mutableListOf<DrawerItem>()
            var lastLabel: String? = null
            rows.forEach { row ->
                val label = dayLabel(row.chat.updatedAt)
                if (label != lastLabel) {
                    items += Header(label)
                    lastLabel = label
                }
                items += ChatEntry(row)
            }
            return items
        }

        /** 0 = today, 1 = yesterday, 2 = older. */
        fun bucketDay(timestampMs: Long): Int {
            val todayMidnight = java.util.Calendar.getInstance().let { midnight(it) }
            val targetMidnight = java.util.Calendar.getInstance().apply {
                timeInMillis = timestampMs
            }.let { midnight(it) }
            return when {
                targetMidnight >= todayMidnight -> 0
                targetMidnight >= todayMidnight - DAY_MS -> 1
                else -> 2
            }
        }

        private fun midnight(source: java.util.Calendar): Long =
            (source.clone() as java.util.Calendar).apply {
                set(
                    get(java.util.Calendar.YEAR),
                    get(java.util.Calendar.MONTH),
                    get(java.util.Calendar.DAY_OF_MONTH),
                    0,
                    0,
                    0,
                )
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

class ChatListAdapter(
    private val onOpen: (Chat, String?) -> Unit,
    private val onLongPress: (Chat) -> Unit,
) : ListAdapter<DrawerItem, RecyclerView.ViewHolder>(DIFF) {

    private var query: String? = null
    private var contextRef: Context? = null

    fun submit(list: List<Chat>, query: String? = null) {
        this.query = query
        submitList(buildList(list))
    }

    private fun buildList(chats: List<Chat>): List<DrawerItem> {
        val ctx = contextRef ?: return chats.map { DrawerItem.ChatEntry(ChatRow(it, query)) }
        val rows = chats.map { ChatRow(it, query) }
        if (query?.isNotBlank() == true) return rows.map { DrawerItem.ChatEntry(it) }
        val fmt = java.text.SimpleDateFormat("d MMMM", java.util.Locale.getDefault())
        return DrawerItem.build(rows) { ts ->
            when (DrawerItem.bucketDay(ts)) {
                0 -> ctx.getString(R.string.day_today)
                1 -> ctx.getString(R.string.day_yesterday)
                else -> fmt.format(java.util.Date(ts))
            }
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        contextRef = recyclerView.context
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        contextRef = null
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is DrawerItem.Header -> DrawerItem.TYPE_HEADER
        is DrawerItem.ChatEntry -> DrawerItem.TYPE_CHAT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == DrawerItem.TYPE_HEADER) {
            HeaderHolder(ItemDayHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            Holder(ItemChatBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is DrawerItem.Header -> (holder as HeaderHolder).bind(item.label)
            is DrawerItem.ChatEntry -> (holder as Holder).bind(item.row)
        }
    }

    inner class HeaderHolder(private val binding: ItemDayHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(label: String) {
            binding.root.text = label
        }
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
            val q = row.query?.trim()?.takeIf { it.isNotEmpty() }
            binding.chatTitle.text =
                highlighted(
                    chat.title.ifBlank { binding.root.context.getString(R.string.new_chat) },
                    q,
                    highlightColor,
                )
            binding.chatPreview.text =
                if (q == null) {
                    chat.messages.lastOrNull()?.content.orEmpty()
                } else {
                    snippet(chat, q)?.let { highlighted(it, q, highlightColor) } ?: ""
                }
            itemView.setOnClickListener { onOpen(chat, firstMatchId(row)) }
            itemView.setOnLongClickListener {
                onLongPress(chat)
                true
            }
        }
    }

    companion object {
        private const val SNIPPET_BEFORE = 24
        private const val SNIPPET_AFTER = 40

        private val DIFF = object : DiffUtil.ItemCallback<DrawerItem>() {
            override fun areItemsTheSame(oldItem: DrawerItem, newItem: DrawerItem) = when {
                oldItem is DrawerItem.Header && newItem is DrawerItem.Header ->
                    oldItem.label == newItem.label
                oldItem is DrawerItem.ChatEntry && newItem is DrawerItem.ChatEntry ->
                    oldItem.row.chat.id == newItem.row.chat.id
                else -> false
            }

            override fun areContentsTheSame(oldItem: DrawerItem, newItem: DrawerItem) =
                oldItem == newItem
        }

        private fun indexOfMatch(text: String, query: String): Int =
            text.lowercase().indexOf(query.lowercase())

        /** Id of the first message matching the row's search query, if any. */
        private fun firstMatchId(row: ChatRow): String? {
            val query = row.query?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return row.chat.messages
                .firstOrNull { indexOfMatch(it.content, query) >= 0 }
                ?.id
        }

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
