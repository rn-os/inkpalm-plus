package com.inkpalmplus

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Manual screen rotation. The InkPalm has no accelerometer, so Android never rotates on its
 * own. A 1-pixel invisible overlay window that asks for an orientation makes the display
 * turn (the trick Orientation Faker uses), overriding what the app in front asks for.
 * Removing the window gives control back to the app and the system.
 */
class Rotation(private val context: Context, private val wm: WindowManager) {
    enum class Mode(val code: Int, val title: String) {
        AUTO(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, "Auto"),
        PORTRAIT(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "Portrait"),
        LANDSCAPE(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, "Landscape"),
        PORTRAIT_FLIPPED(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, "Portrait ↻"),
        LANDSCAPE_FLIPPED(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, "Landscape ↻");

        companion object {
            fun of(name: String?): Mode = entries.firstOrNull { it.name == name } ?: AUTO
        }
    }

    private var view: View? = null
    var mode: Mode = Mode.AUTO
        private set

    fun apply(next: Mode) {
        if (next == mode && (next == Mode.AUTO) == (view == null)) return
        mode = next
        if (next == Mode.AUTO) {
            view?.let { runCatching { wm.removeView(it) } }
            view = null
        } else {
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { screenOrientation = next.code }
            val v = view
            if (v == null) {
                val nv = View(context)
                runCatching { wm.addView(nv, lp) }.onSuccess { view = nv }.onFailure { Log.w(QuickPanelService.TAG, "rotation: $it") }
            } else {
                runCatching { wm.updateViewLayout(v, lp) }
            }
        }
        Log.i(QuickPanelService.TAG, "rotation ${next.name}")
    }

    fun release() = apply(Mode.AUTO)
}
