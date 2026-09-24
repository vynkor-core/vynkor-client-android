package dev.vynkor.agent

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.google.android.material.color.MaterialColors

/** Three phase-shifted pulsing dots — the live "AI is working" indicator. */
class TypingDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(
            this@TypingDotsView,
            androidx.appcompat.R.attr.colorPrimary,
            DEFAULT_COLOR,
        )
    }
    private val dotRadiusPx = resources.displayMetrics.density * DOT_RADIUS_DP
    private val dotSpacingPx = resources.displayMetrics.density * DOT_SPACING_DP

    private var animator: ValueAnimator? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val fraction = animator?.animatedFraction ?: 0f
        val cy = height / 2f
        val startX = width / 2f - dotSpacingPx
        for (i in 0 until DOT_COUNT) {
            val wave = ((fraction - i * PHASE_OFFSET) % 1f + 1f) % 1f
            val t = (1 - Math.cos(2 * Math.PI * wave)).toFloat() / 2f
            paint.alpha = MIN_ALPHA + ((MAX_ALPHA - MIN_ALPHA) * t).toInt()
            canvas.drawCircle(
                startX + i * dotSpacingPx,
                cy,
                dotRadiusPx * (MIN_SCALE + (MAX_SCALE - MIN_SCALE) * t),
                paint,
            )
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun start() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CYCLE_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { invalidate() }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
    }

    companion object {
        private const val DOT_COUNT = 3
        private const val DOT_RADIUS_DP = 3f
        private const val DOT_SPACING_DP = 10f
        private const val CYCLE_MS = 900L
        private const val PHASE_OFFSET = 0.18f
        private const val MIN_ALPHA = 60
        private const val MAX_ALPHA = 255
        private const val MIN_SCALE = 0.7f
        private const val MAX_SCALE = 1f
        private const val DEFAULT_COLOR = 0xFF6750A4.toInt()
    }
}
