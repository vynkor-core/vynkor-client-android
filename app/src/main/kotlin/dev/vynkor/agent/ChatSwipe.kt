package dev.vynkor.agent

import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import dev.vynkor.agent.agent.Chat

/**
 * Swipe actions on chat rows: right = pin/unpin (instant), left = ask for
 * deletion (the row springs back; nothing is removed until confirmed).
 */
object ChatSwipe {

    fun attach(
        recycler: RecyclerView,
        chatAt: (Int) -> Chat?,
        onPin: (Chat) -> Unit,
        onDeleteAsk: (Chat) -> Unit,
    ) {
        val pinColor = ContextCompat.getColor(recycler.context, R.color.primary)
        val delColor = ContextCompat.getColor(recycler.context, R.color.error)
        val onColor = ContextCompat.getColor(recycler.context, R.color.on_primary)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = onColor
            textSize = 13f * recycler.context.resources.displayMetrics.density
            isFakeBoldText = true
        }

        val helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int {
                val pos = viewHolder.bindingAdapterPosition
                // Out-of-range and day headers are not swipeable.
                if (pos < 0 || recycler.adapter?.getItemViewType(pos) == 0) return 0
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                val chat = chatAt(pos)
                // Always spring the row back; actions are applied separately.
                recycler.adapter?.notifyItemChanged(pos)
                when (direction) {
                    ItemTouchHelper.RIGHT -> chat?.let(onPin)
                    else -> chat?.let(onDeleteAsk)
                }
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean,
            ) {
                val view = viewHolder.itemView
                if (dX != 0f) {
                    val w = view.width.toFloat()
                    val h = view.height.toFloat()
                    val frac = kotlin.math.min(kotlin.math.abs(dX) / w, 1f)
                    paint.color = if (dX > 0) pinColor else delColor
                    c.drawRect(
                        if (dX > 0) 0f else w + dX,
                        view.top.toFloat(),
                        if (dX > 0) dX else w,
                        view.bottom.toFloat(),
                        paint,
                    )
                    val label = if (dX > 0) "★" else "✕"
                    val alpha = (frac * 255).toInt().coerceIn(0, 255)
                    textPaint.alpha = alpha
                    val tw = textPaint.measureText(label)
                    val cx = if (dX > 0) dX / 2f - tw / 2f else w + dX / 2f - tw / 2f
                    val cy = view.top + h / 2f + (textPaint.textSize - textPaint.descent()) / 2f
                    c.drawText(label, cx, cy, textPaint)
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        })
        helper.attachToRecyclerView(recycler)
    }
}
