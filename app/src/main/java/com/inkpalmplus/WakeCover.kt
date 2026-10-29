package com.inkpalmplus

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A full-screen picture over everything for the screen wakes InkPalm+ causes while asleep (KompaktOS): the always-on
 * display's redraw ([Aod.update]) and auto power-off's predraw ([AutoOff]). The bridge wakes the screen with the front
 * light held off and puts it back to sleep a few seconds later, so the HWC draws its sleep picture; without a cover
 * that wake shows the home screen or the last app for 2-5 s. The cover shows the very picture the HWC is about to
 * draw, so the panel goes straight from the old picture to the new one.
 *
 * It's added while the screen is still off (the wake's first frame is the cover), taken away 1 s after the wake's
 * SCREEN_OFF (the HWC's own draw), at once on a wake of the user's, and after [TIMEOUT_MS] in any case. An accessibility
 * overlay: above the keyguard and the status bar, not touchable, no animations.
 *
 * The picture is the panel's portrait buffer (720x1440, landscape faces already turned into it, see [Aod.degreesFor]).
 * The display turns a window's content by its rotation onto the panel, so the cover turns the buffer back by as much
 * ([turnFor]) for the rotation at the moment it draws, and draws again whenever the display turns.
 *
 * Asleep the display is at rotation 0, and an app held in landscape turns it back at the wake, 1-3 s in; Android hides
 * overlays during a turn until they have drawn again, which showed the app for a moment. So the cover asks for the
 * orientation held at the user's sleep ([orientationFor]): the display turns while still asleep, under the cover, and
 * the wake itself doesn't turn. If the phone was turned while asleep (auto-rotate on), [Aod] has already moved the
 * held orientation to the physical one before drawing, so the face, the held orientation and the counter-turn all
 * follow the same new rotation.
 */
object WakeCover {
    private const val TAG = "InkPalmPlus"
    const val TIMEOUT_MS = 15_000L

    // ---- Pure parts (unit tested) -------------------------------------------------------------------

    /** Clockwise turn from the panel's portrait buffer to a window's content at display [rotation]: the display turns
     *  window content clockwise by [Aod.degreesFor] onto the panel, so this undoes it. */
    fun turnFor(rotation: Int): Int = (360 - Aod.degreesFor(rotation)) % 360

    /**
     * The affine map of a clockwise turn by [deg] (0/90/180/270) of a [w]x[h] picture into the turned frame, as
     * (a, b, c, d, tx, ty): x' = a·x + b·y + tx, y' = c·x + d·y + ty. Pixel edges, so a turned picture fills its
     * frame exactly ((0,0)-(w,h) becomes (0,0)-(h,w) for 90 and 270).
     */
    fun turn(deg: Int, w: Float, h: Float): FloatArray = when (((deg % 360) + 360) % 360) {
        90 -> floatArrayOf(0f, -1f, 1f, 0f, h, 0f)
        180 -> floatArrayOf(-1f, 0f, 0f, -1f, w, h)
        270 -> floatArrayOf(0f, 1f, -1f, 0f, 0f, w)
        else -> floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
    }

    /** The orientation that keeps the display at [rotation] (a naturally portrait panel). */
    fun orientationFor(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    // ---- The window ----------------------------------------------------------------------------------

    private var view: Cover? = null
    private var shownAt = 0L
    private val timeout: Runnable = Runnable { hide("timeout") }

    /** The cover is up. Main thread. */
    val showing: Boolean get() = view != null

    /**
     * Shows [bmp] (a 720x1440 panel buffer; the cover owns it from now on) over everything, holding the display at
     * [rotation] (the one held at the user's sleep; null: as it is). Any thread; off the main thread it waits until the
     * window is added and, up to 1 s, drawn. False without the accessibility service.
     */
    fun show(bmp: Bitmap, why: String, rotation: Int?): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return add(bmp, why, rotation, null)
        val added = CountDownLatch(1)
        val drawn = CountDownLatch(1)
        var ok = false
        Shell.main.post { ok = add(bmp, why, rotation) { drawn.countDown() }; added.countDown(); if (!ok) drawn.countDown() }
        added.await(2, TimeUnit.SECONDS)
        val t0 = SystemClock.uptimeMillis()
        val inTime = drawn.await(1, TimeUnit.SECONDS)
        if (ok) Log.i(TAG, "cover: $why ${if (inTime) "drawn in ${SystemClock.uptimeMillis() - t0} ms" else "not drawn yet (screen off)"}")
        return ok
    }

    private fun add(bmp: Bitmap, why: String, rotation: Int?, onDrawn: (() -> Unit)?): Boolean {
        val svc = QuickPanelService.instance
        if (svc == null) { Log.w(TAG, "cover: $why: no accessibility service"); bmp.recycle(); return false }
        removeView()
        val wm = svc.getSystemService(WindowManager::class.java)
        val v = Cover(svc, bmp, onDrawn)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            windowAnimations = 0
            rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT
            title = "InkPalmPlus:cover"
            if (rotation != null) screenOrientation = orientationFor(rotation)
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            if (Build.VERSION.SDK_INT >= 31) setCanPlayMoveAnimation(false)
        }
        return runCatching { wm.addView(v, lp) }.onSuccess {
            view = v
            shownAt = SystemClock.uptimeMillis()
            Shell.main.removeCallbacks(timeout)
            Shell.main.postDelayed(timeout, TIMEOUT_MS)
            Log.i(TAG, "cover: $why shown (held rotation $rotation)")
        }.onFailure { Log.w(TAG, "cover: $why: $it"); bmp.recycle() }.isSuccess
    }

    /** Takes the cover away now. Any thread. */
    fun hide(why: String): Boolean = Shell.main.post {
        Shell.main.removeCallbacks(delayedHide)
        if (view != null) Log.i(TAG, "cover: removed ($why) after ${SystemClock.uptimeMillis() - shownAt} ms")
        removeView()
    }

    private val delayedHide: Runnable = Runnable { hide("after sleep") }

    /** Takes the cover away in [ms] (after the wake's SCREEN_OFF, once the HWC has drawn). Any thread. */
    fun hideAfter(ms: Long): Boolean = Shell.main.post {
        if (view == null) return@post
        Shell.main.removeCallbacks(delayedHide)
        Shell.main.postDelayed(delayedHide, ms)
    }

    private fun removeView() {
        Shell.main.removeCallbacks(timeout)
        val v = view ?: return
        view = null
        runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) }
        v.release()
    }

    /** Draws the buffer turned for the display's rotation now, filling the window. */
    private class Cover(c: Context, private var bmp: Bitmap?, private var onDrawn: (() -> Unit)?) : View(c) {
        private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
        private val m = Matrix()
        private val dm = c.getSystemService(DisplayManager::class.java)
        private val turned = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) {}
            override fun onDisplayRemoved(id: Int) {}
            override fun onDisplayChanged(id: Int) { if (id == Display.DEFAULT_DISPLAY) invalidate() }
        }
        private val drawListener = ViewTreeObserver.OnDrawListener {
            val f = onDrawn ?: return@OnDrawListener
            onDrawn = null
            post { f() }
        }

        init {
            setBackgroundColor(Color.WHITE)
            dm?.registerDisplayListener(turned, Shell.main)
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); viewTreeObserver.addOnDrawListener(drawListener) }

        override fun onDraw(canvas: Canvas) {
            val b = bmp ?: return
            val rot = display?.rotation ?: Surface.ROTATION_0
            val deg = turnFor(rot)
            val w = b.width.toFloat(); val h = b.height.toFloat()
            val t = turn(deg, w, h)
            m.setValues(floatArrayOf(t[0], t[1], t[4], t[2], t[3], t[5], 0f, 0f, 1f))
            val (tw, th) = if (deg % 180 == 0) w to h else h to w
            m.postScale(width / tw, height / th)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(b, m, paint)
            if (drawnRot != rot) { drawnRot = rot; Log.i(TAG, "cover: drawn for rotation $rot (turned $deg°) at ${width}x$height") }
        }

        private var drawnRot = -1

        fun release() {
            runCatching { dm?.unregisterDisplayListener(turned) }
            runCatching { viewTreeObserver.removeOnDrawListener(drawListener) }
            onDrawn?.invoke(); onDrawn = null
            bmp = null   // not recycled: the render thread may still hold it; the GC frees it
        }
    }
}
