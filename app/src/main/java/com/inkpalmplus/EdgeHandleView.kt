package com.inkpalmplus

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * The thin touch strip along the screen edge (from SuperBoox): a swipe up or
 * down along it, or inward from it, opens the quick panel.
 *
 * Once a touch starts inside this window, Android keeps delivering the rest of
 * the gesture here even after the finger leaves the strip.
 */
@SuppressLint("ViewConstructor")
class EdgeHandleView(
    context: Context,
    private val right: Boolean,
    private val visible: Boolean,
    private val onTrigger: () -> Unit,
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.INK }
    private val threshold = context.dp(28).toFloat()
    private var downX = 0f
    private var downY = 0f
    private var fired = false

    override fun onDraw(canvas: Canvas) {
        if (!visible) return
        // A short dark pill hugging the screen edge.
        val w = dp(4).toFloat()
        val inset = dp(2).toFloat()
        val left = if (right) width - w - inset else inset
        canvas.drawRoundRect(left, height * 0.2f, left + w, height * 0.8f, w / 2, w / 2, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                fired = false
            }
            MotionEvent.ACTION_MOVE -> if (!fired) {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                val inward = if (right) -dx else dx
                val along = abs(dy) > threshold * 1.5f && abs(dy) > 2 * abs(dx)
                val swipeIn = inward > threshold && inward > abs(dy)
                if (along || swipeIn) {
                    fired = true
                    onTrigger()
                }
            }
        }
        return true
    }
}
