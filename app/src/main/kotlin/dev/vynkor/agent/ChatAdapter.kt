package dev.vynkor.agent

import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import android.widget.FrameLayout
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Gravity
import android.content.res.ColorStateList
import dev.vynkor.agent.agent.AttachmentStore
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
    private val onAttachmentTap: ((String, dev.vynkor.agent.agent.Attachment) -> Unit)? = null,
) : ListAdapter<ChatMessage, ChatAdapter.Holder>(DIFF) {

    /** Chat id owning the current list; used to resolve attachment paths. */
    var chatId: String = ""

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
            renderAttachments(message)
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

        private fun renderAttachments(message: ChatMessage) {
            val row = binding.attachmentsRow
            row.removeAllViews()
            val attachments = message.attachments
            relayoutForAttachments(attachments.isNotEmpty())
            if (attachments.isEmpty()) {
                row.visibility = View.GONE
                return
            }
            row.visibility = View.VISIBLE
            val density = ctx.resources.displayMetrics.density
            attachments.forEach { attachment ->
                val card = com.google.android.material.card.MaterialCardView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                        bottomMargin = (6 * density).toInt()
                    }
                    radius = 12 * density
                    strokeWidth = 0
                    cardElevation = 0f
                    setCardBackgroundColor(
                        if (message.role == "user") 0x33FFFFFF else {
                            ContextCompat.getColor(ctx, R.color.surface_variant)
                        },
                    )
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onAttachmentTap?.invoke(chatId, attachment) }
                }
                if (attachment.isImage) {
                    card.addView(ImageView(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams((220 * density).toInt(), (150 * density).toInt())
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        contentDescription = ctx.getString(R.string.attachment_image_desc, attachment.name)
                        clipToOutline = true
                        decodeThumb(chatId, attachment)?.let { bitmap ->
                            setImageBitmap(bitmap)
                        } ?: setImageResource(R.drawable.ic_file)
                    })
                } else {
                    val line = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(10 * density.toInt(), 8 * density.toInt(), 10 * density.toInt(), 8 * density.toInt())
                    }
                    line.addView(ImageView(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt())
                        setImageResource(R.drawable.ic_file)
                        imageTintList = ColorStateList.valueOf(
                            ContextCompat.getColor(
                                ctx,
                                if (message.role == "user") R.color.on_primary else R.color.on_surface,
                            ),
                        )
                    })
                    line.addView(TextView(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                            marginStart = 8 * density.toInt()
                        }
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                        maxWidth = (200 * density).toInt()
                        text = "${attachment.name} · ${attachment.humanSize()}" +
                            if (attachment.isVideo) " · ${ctx.getString(R.string.attachment_video_badge)}" else ""
                        setTextColor(
                            ContextCompat.getColor(
                                ctx,
                                if (message.role == "user") R.color.on_primary else R.color.on_surface,
                            ),
                        )
                    })
                    card.addView(line)
                }
                row.addView(card)
            }
            binding.messageText.visibility =
                if (message.content.isBlank() && message.role == "user") View.GONE else View.VISIBLE
        }

        private fun relayoutForAttachments(hasAttachments: Boolean) {
            val lp = binding.messageText.layoutParams as ConstraintLayout.LayoutParams
            if (hasAttachments) {
                lp.topToTop = ConstraintLayout.LayoutParams.UNSET
                lp.topToBottom = binding.attachmentsRow.id
            } else {
                lp.topToBottom = ConstraintLayout.LayoutParams.UNSET
                lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            }
            binding.messageText.layoutParams = lp
        }

        private fun decodeThumb(
            chatId: String,
            attachment: dev.vynkor.agent.agent.Attachment,
        ): android.graphics.Bitmap? {
            val path = AttachmentStore.fileFor(ctx, chatId, attachment).absolutePath
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 440 || bounds.outHeight / (sample * 2) >= 300) {
                sample *= 2
            }
            return BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inSampleSize = sample },
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
                    oldItem.timestamp == newItem.timestamp &&
                    oldItem.attachments == newItem.attachments
        }
    }
}
