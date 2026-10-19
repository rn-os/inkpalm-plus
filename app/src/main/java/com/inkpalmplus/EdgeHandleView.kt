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
 * down along it opens the quick panel. Only along: swiping inward from the edge is
 * the system's back gesture on the InkPalm, so it's left alone.
 *
 * Once a touch starts inside this window, Android keeps delivering the rest of
 * the gesture here even after the finger leaves the strip.
 */
@SuppressLint("ViewConstructor")
class EdgeHandleView(
    context: Context,
    private val right: Boolean,
    private val visible: Boolean,
    /** Settings is changing the strip: draw all of it in black so its size and place can be seen. */
    private val solid: Boolean = false,
    private val onTrigger: () -> Unit,
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.INK }
    private val threshold = context.dp(28).toFloat()
    private var downX = 0f
    private var downY = 0f
    private var fired = false

    override fun onDraw(canvas: Canvas) {
        if (solid) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            return
        }
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
                if (abs(dy) > threshold * 1.5f && abs(dy) > 2 * abs(dx)) {
                    fired = true
                    onTrigger()
                }
            }
        }
        return true
    }
}
