package com.inkpalmplus

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * The quick panel over the app in front: its DPI, refresh, full-refresh interval and
 * contrast. Every change is saved to Moaan's table and applied to the open app at once
 * ([Live]); DPI waits a moment so several taps make one redraw. Tap outside to close.
 */
class QuickPanel(
    private val service: QuickPanelService,
    val pkg: String,
    private val onClose: () -> Unit,
) {
    private val ctx = service
    private val label = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    private var p = EinkDb.Profile(pkg)
    private var loaded = false
    private var status = ""
    private lateinit var root: FrameLayout
    private lateinit var body: LinearLayout
    private val applyDpi = Runnable { commitDpi() }

    @SuppressLint("ClickableViewAccessibility")
    fun show(wm: WindowManager) {
        root = object : FrameLayout(ctx) {
            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) onClose()
                return super.onTouchEvent(e)
            }
        }
        body = ctx.vbox().apply {
            setBackgroundColor(Ink.PAPER)
            setPadding(0, 0, 0, ctx.dp(8))
        }
        root.addView(ctx.vbox().apply {
            addView(ctx.divider(3))
            addView(body)
        })
        root.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) onClose(); false }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.OPAQUE,
        ).apply { gravity = Gravity.BOTTOM }
        wm.addView(root, lp)
        render()
        Shell.background({ EinkDb.read(ctx, pkg) }) { loadedProfile ->
            p = loadedProfile
            loaded = true
            render()
        }
    }

    fun dismiss(wm: WindowManager) {
        Shell.main.removeCallbacks(applyDpi)
        if (pendingDpi) commitDpi()
        runCatching { wm.removeView(root) }
    }

    // ---- Changes ------------------------------------------------------------------------------

    private var pendingDpi = false

    /** Saves and applies refresh/contrast changes now. */
    private fun change(next: EinkDb.Profile) {
        p = next
        status = ""
        render()
        val snapshot = p
        Shell.background({
            val via = EinkDb.write(ctx, snapshot)
            if (via != null) Live.applyDisplay(ctx, snapshot)
            via
        }) { via -> if (via == null) { status = "Couldn't save"; render() } }
    }

    private fun changeDpi(dpi: Int) {
        p = p.copy(dpi = if (dpi < 0) -1 else dpi.coerceIn(120, 560))
        pendingDpi = true
        status = "Applying in a moment…"
        render()
        Shell.main.removeCallbacks(applyDpi)
        Shell.main.postDelayed(applyDpi, 1200)
    }

    private fun commitDpi() {
        pendingDpi = false
        val snapshot = p
        Shell.background({
            val via = EinkDb.write(ctx, snapshot)
            val live = via != null && Live.nudgeConfiguration(ctx)
            via to live
        }) { (via, live) ->
            status = when {
                via == null -> "Couldn't save"
                live -> ""
                else -> "Saved. Allow \"Modify system settings\" for live DPI, or reopen $label"
            }
            if (::body.isInitialized) render()
        }
    }

    /** Debug test hooks ([QuickPanelService], debuggable builds only): the same paths the buttons take. */
    fun test(cmd: String, value: String): Boolean {
        if (!loaded) return false
        when (cmd) {
            "dpi" -> changeDpi(value.toInt())
            "refresh" -> change(p.copy(refresh = EinkDb.Refresh.of(value.toInt())))
            "full" -> change(p.copy(fullEvery = value.toInt()))
            "contrast" -> change(p.copy(contrast = value.toFloatOrNull()))
            else -> return false
        }
        return true
    }

    // ---- Views --------------------------------------------------------------------------------

    private fun render() {
        body.removeAllViews()
        body.addView(ctx.hbox().apply {
            setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(8), ctx.dp(4))
            addView(ctx.vbox().apply {
                addView(ctx.text(label, 18f, bold = true))
                addView(ctx.text(if (status.isNotEmpty()) status else if (loaded) "Changes apply right away" else "Loading…", 12f, color = Ink.MUTED))
            }, lp(0, WRAP, 1f))
            addView(ctx.inkButton("More", 14f) {
                ctx.startActivity(Intent(ctx, AppActivity::class.java).putExtra(AppActivity.EXTRA_PKG, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                onClose()
            }, lp(WRAP, WRAP).apply { marginEnd = ctx.dp(6) })
            addView(ctx.inkButton("✕", 16f) { onClose() }, lp(ctx.dp(48), WRAP))
        })
        if (!loaded) return

        // DPI
        val dpi = p.dpi
        body.addView(row("Size") {
            addView(ctx.inkButton("−", 18f) { changeDpi((if (dpi > 0) dpi else EinkDb.NATIVE_DPI) - 10) }, lp(ctx.dp(52), WRAP))
            addView(ctx.text(if (dpi > 0) "$dpi" else "System", 17f, bold = true).apply { gravity = Gravity.CENTER }, lp(0, WRAP, 1f))
            addView(ctx.inkButton("+", 18f) { changeDpi((if (dpi > 0) dpi else EinkDb.NATIVE_DPI) + 10) }, lp(ctx.dp(52), WRAP))
            addView(ctx.chip("Sys", dpi < 0) { changeDpi(-1) }, lp(WRAP, WRAP).apply { marginStart = ctx.dp(6) })
        })

        // Refresh
        body.addView(sub("Refresh"))
        body.addView(chips(EinkDb.Refresh.entries, { it.title }, p.refresh) { change(p.copy(refresh = it)) })
        body.addView(sub("Full refresh"))
        body.addView(chips(listOf(-1, 5, 10, 20, 30, 50), { if (it < 0) "System" else "Every $it" }, p.fullEvery) { change(p.copy(fullEvery = it)) })

        // Contrast
        val steps = EinkDb.CONTRAST_STEPS
        val base = steps.indexOfFirst { kotlin.math.abs(it - EinkDb.DEFAULT_CONTRAST) < 0.01f }
        val idx = p.contrast?.let { g -> steps.indexOfFirst { kotlin.math.abs(it - g) < 0.01f } }?.takeIf { it >= 0 } ?: base
        body.addView(row("Contrast") {
            addView(ctx.inkButton("Lighter", 14f) { change(p.copy(contrast = steps[(idx - 1).coerceAtLeast(0)])) }, lp(0, WRAP, 1f))
            addView(ctx.text(p.contrast?.let { EinkDb.contrastLabel(it).removePrefix("Contrast ") } ?: "System", 15f, bold = true).apply { gravity = Gravity.CENTER }, lp(0, WRAP, 1f))
            addView(ctx.inkButton("Darker", 14f) { change(p.copy(contrast = steps[(idx + 1).coerceAtMost(steps.size - 1)])) }, lp(0, WRAP, 1f))
            addView(ctx.chip("Sys", p.contrast == null) { change(p.copy(contrast = null)) }, lp(WRAP, WRAP).apply { marginStart = ctx.dp(6) })
        })

        body.addView(ctx.hbox().apply {
            setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), 0)
            addView(ctx.inkButton("Full refresh now", 14f) { Shell.io.execute { Live.fullRefresh() } }, lp(0, WRAP, 1f).apply { marginEnd = ctx.dp(6) })
            addView(ctx.inkButton("Reset app", 14f) {
                val wasDpi = p.dpi
                change(EinkDb.Profile(pkg, exists = p.exists))
                if (wasDpi > 0) changeDpi(-1)
            }, lp(0, WRAP, 1f))
        })
    }

    private fun row(title: String, content: LinearLayout.() -> Unit): View = ctx.hbox().apply {
        setPadding(ctx.dp(16), ctx.dp(6), ctx.dp(16), ctx.dp(6))
        addView(ctx.text(title, 15f, medium = true), lp(ctx.dp(84), WRAP))
        content()
    }

    private fun sub(title: String): View = ctx.text(title, 13f, bold = true, color = Ink.MUTED).apply {
        setPadding(ctx.dp(16), ctx.dp(6), ctx.dp(16), 0)
    }

    private fun <T> chips(options: List<T>, title: (T) -> String, selected: T, onPick: (T) -> Unit): View =
        HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(ctx.hbox().apply {
                setPadding(ctx.dp(12), ctx.dp(4), ctx.dp(12), ctx.dp(4))
                for (o in options) addView(ctx.chip(title(o), o == selected) { onPick(o) }, lp(WRAP, WRAP).apply { marginStart = ctx.dp(4); marginEnd = ctx.dp(4) })
            })
        }
}
