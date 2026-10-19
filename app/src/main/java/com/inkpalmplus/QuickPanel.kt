package com.inkpalmplus

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
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
        // Portrait: a sheet along the bottom. Landscape (720 px tall): a sheet down the strip's side,
        // 42% of the width, so most of the app stays visible beside it.
        val landscape = ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val right = Prefs(ctx).edgeRight
        val scroll = android.widget.ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(body) }
        if (landscape) {
            root.addView(ctx.hbox().apply {
                gravity = Gravity.TOP
                val rule = View(ctx).apply { setBackgroundColor(Ink.INK) }
                if (right) addView(rule, lp(ctx.dp(3), MATCH))
                addView(scroll, lp(0, MATCH, 1f))
                if (!right) addView(rule, lp(ctx.dp(3), MATCH))
                setBackgroundColor(Ink.PAPER)
            })
        } else {
            root.addView(ctx.vbox().apply {
                addView(ctx.divider(3))
                addView(scroll)
            })
        }
        root.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) onClose(); false }
        val lp = WindowManager.LayoutParams(
            if (landscape) (ctx.resources.displayMetrics.widthPixels * 0.42f).toInt() else WindowManager.LayoutParams.MATCH_PARENT,
            if (landscape) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.OPAQUE,
        ).apply { gravity = if (!landscape) Gravity.BOTTOM else if (right) Gravity.END else Gravity.START }
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
        service.helpers.onProfileChanged(snapshot)
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
            "list" -> { listing = Kind.valueOf(value); render() }
            "pick" -> { val k = listing ?: return false; val ch = choices(k).getOrNull(value.toInt()) ?: return false; listing = null; ch.pick(); render() }
            "arrow" -> {
                // "REFRESH:+" or "REFRESH:-": what ◀ ▶ do on that line.
                val (k, dir) = value.split(":").let { Kind.valueOf(it[0]) to it[1] }
                val all = choices(k); val i = all.indexOfFirst { it.selected }.coerceAtLeast(0)
                all.getOrNull(if (dir == "+") i + 1 else i - 1)?.pick?.invoke() ?: return false
            }
            else -> return false
        }
        return true
    }

    // ---- Views --------------------------------------------------------------------------------
    //
    // One line per setting: name, ◀, value, ▶ (and Sys where there is one). Tapping the value swaps
    // the rows for that setting's full list; picking applies it and comes back. Nothing scrolls
    // sideways, and the whole panel fits without scrolling in both orientations
    // (mockups: docs/mockups/02-arrows.png).

    /** The panel shows PRESET, SIZE, CONTRAST, ROTATE; REFRESH and FULL remain for the app's page and the test hooks. */
    enum class Kind(val title: String) { PRESET("Preset"), SIZE("Size"), CONTRAST("Contrast"), REFRESH("Refresh"), FULL("Full refresh"), ROTATE("Rotate") }

    private class Choice(val title: String, val detail: String?, val selected: Boolean, val pick: () -> Unit)

    /** The setting whose full list is showing instead of the rows; null for the rows. */
    private var listing: Kind? = null

    private val dpiChoices = listOf(-1, 200, 220, 240, 260, EinkDb.NATIVE_DPI, 300, 320, 340, 360, 400)

    private fun rotationNow(): Rotation.Mode =
        service.currentRotation().takeIf { service.foreground == pkg } ?: Prefs(ctx).rotation(pkg)

    private fun applyPreset(preset: Preset) {
        preset.applyHelpers(Prefs(ctx), pkg)
        change(preset.applyTo(p))
    }

    private fun openMore() {
        ctx.startActivity(Intent(ctx, AppActivity::class.java).putExtra(AppActivity.EXTRA_PKG, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        onClose()
    }

    private fun choices(kind: Kind): List<Choice> = when (kind) {
        Kind.PRESET -> {
            val now = Preset.of(ctx, p)
            Preset.entries.map { pr -> Choice(pr.title, pr.detail, now == pr) { applyPreset(pr) } } +
                (if (now == null) listOf(Choice("Custom", "Set on this app's page (More)", true) { openMore() }) else emptyList())
        }
        Kind.SIZE -> dpiChoices.map { d ->
            Choice(if (d < 0) "System" else "$d dpi", if (d == EinkDb.NATIVE_DPI) "the screen's own" else null,
                if (d < 0) p.dpi < 0 else p.dpi == d) { changeDpi(d) }
        }
        Kind.CONTRAST -> listOf(Choice("System", "Moaan's contrast setting", p.contrast == null) { change(p.copy(contrast = null)) }) +
            EinkDb.CONTRAST_STEPS.map { g ->
                Choice(contrastShort(g), null, p.contrast?.let { kotlin.math.abs(it - g) < 0.01f } == true) { change(p.copy(contrast = g)) }
            }
        Kind.REFRESH -> EinkDb.Refresh.entries.map { r -> Choice(r.title, r.detail, p.refresh == r) { change(p.copy(refresh = r)) } }
        Kind.FULL -> listOf(-1, 5, 10, 15, 20, 30, 40, 50).map { n ->
            Choice(if (n < 0) "System" else "Every $n", if (n < 0) "Moaan's own (every 40)" else null, p.fullEvery == n || (n < 0 && p.fullEvery < 0)) {
                change(p.copy(fullEvery = n))
            }
        }
        Kind.ROTATE -> Rotation.Mode.entries.map { m ->
            Choice(m.title, if (m == Rotation.Mode.AUTO) "The app decides" else null, rotationNow() == m) { service.setRotation(pkg, m); render() }
        }
    }

    /** Landscape is 720 px tall: shorter buttons and spacing so all the lines and the footer fit. */
    private val compact get() = ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private fun android.widget.TextView.sized(): android.widget.TextView = apply {
        if (compact) { minHeight = ctx.dp(36); minimumHeight = ctx.dp(36); setPadding(ctx.dp(6), ctx.dp(2), ctx.dp(6), ctx.dp(2)) }
    }

    private fun contrastShort(g: Float): String = EinkDb.contrastLabel(g).let { if (it == "Contrast default") "Default" else it }

    private fun render() {
        body.removeAllViews()
        val kind = listing
        if (kind != null && loaded) return renderList(kind)

        body.addView(ctx.hbox().apply {
            setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(8), ctx.dp(2))
            addView(ctx.vbox().apply {
                addView(ctx.text(label, 17f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(ctx.text(if (status.isNotEmpty()) status else if (loaded) "Changes apply right away" else "Loading…", 11f, color = Ink.MUTED))
            }, lp(0, WRAP, 1f))
            if (compact) setPadding(ctx.dp(14), ctx.dp(4), ctx.dp(8), 0)
            // ⚙: InkPalm+'s home screen (strip, whole-device settings, all apps).
            addView(ctx.inkButton("⚙", 21f) {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                onClose()
            }.sized().apply { contentDescription = "InkPalm+ settings" }, lp(ctx.dp(44), WRAP).apply { marginEnd = ctx.dp(6) })
            addView(ctx.inkButton("More", 13f) { openMore() }.sized(), lp(WRAP, WRAP).apply { marginEnd = ctx.dp(6) })
            addView(ctx.inkButton("✕", 15f) { onClose() }.sized(), lp(ctx.dp(44), WRAP))
        })
        if (!loaded) return

        // Preset: one choice for refresh and the refresh helpers (docs/mockups/08-presets.png).
        run {
            val now = Preset.of(ctx, p)
            val presets = Preset.entries
            val i = now?.let { presets.indexOf(it) } ?: -1
            val note = if (now == null) "set on this app's page" else if (now == Preset.SYSTEM) "Moaan's own settings" else
                listOf(p.refresh.title, if (p.fullEvery > 0) "full refresh every ${p.fullEvery}" else null).filterNotNull().joinToString(" · ")
            body.addView(line(Kind.PRESET, now?.title ?: "Custom", note,
                prev = "◀" to { presets.getOrNull(i - 1)?.let { applyPreset(it) } },
                next = "▶" to { presets.getOrNull(i + 1)?.let { applyPreset(it) } },
                sys = null))
        }

        // Size: −/+ step by 10 dpi.
        val dpi = p.dpi
        body.addView(line(Kind.SIZE, if (dpi > 0) "$dpi dpi" else "System", if (dpi == EinkDb.NATIVE_DPI) "the screen's own" else null,
            prev = "−" to { changeDpi((if (dpi > 0) dpi else EinkDb.NATIVE_DPI) - 10) },
            next = "+" to { changeDpi((if (dpi > 0) dpi else EinkDb.NATIVE_DPI) + 10) },
            sys = (dpi < 0) to { changeDpi(-1) }))

        // Contrast: ◀ lighter, ▶ darker; from System it starts at Moaan's default.
        val steps = EinkDb.CONTRAST_STEPS
        val base = steps.indexOfFirst { kotlin.math.abs(it - EinkDb.DEFAULT_CONTRAST) < 0.01f }
        val idx = p.contrast?.let { g -> steps.indexOfFirst { kotlin.math.abs(it - g) < 0.01f } }?.takeIf { it >= 0 } ?: base
        body.addView(line(Kind.CONTRAST, p.contrast?.let { contrastShort(it) } ?: "System", null,
            prev = "◀" to { change(p.copy(contrast = steps[(idx - 1).coerceAtLeast(0)])) },
            next = "▶" to { change(p.copy(contrast = steps[(idx + 1).coerceAtMost(steps.size - 1)])) },
            sys = (p.contrast == null) to { change(p.copy(contrast = null)) }))

        // Rotation: ◀ ▶ step through the same list the value opens.
        for (k in listOf(Kind.ROTATE)) {
            val all = choices(k)
            val i = all.indexOfFirst { it.selected }.coerceAtLeast(0)
            val note = when (k) {
                Kind.ROTATE -> "kept for this app"
                else -> all[i].detail
            }
            body.addView(line(k, all[i].title, note,
                prev = "◀" to { all.getOrNull(i - 1)?.pick?.invoke() },
                next = "▶" to { all.getOrNull(i + 1)?.pick?.invoke() },
                sys = null))
        }

        body.addView(ctx.hbox().apply {
            setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), 0)
            if (compact) setPadding(ctx.dp(12), ctx.dp(4), ctx.dp(12), 0)
            addView(ctx.inkButton("Full refresh now", 13f) { Shell.io.execute { Live.fullRefresh() } }.sized(), lp(0, WRAP, 1f).apply { marginEnd = ctx.dp(6) })
            addView(ctx.inkButton("Reset app", 13f) {
                val wasDpi = p.dpi
                change(EinkDb.Profile(pkg, exists = p.exists))
                if (wasDpi > 0) changeDpi(-1)
                service.setRotation(pkg, Rotation.Mode.AUTO)
                Preset.SYSTEM.applyHelpers(Prefs(ctx), pkg)
            }.sized(), lp(0, WRAP, 1f))
        })
    }

    /** name · prev · value (tap: list) · next · Sys slot. */
    private fun line(kind: Kind, value: String, note: String?, prev: Pair<String, () -> Unit>, next: Pair<String, () -> Unit>,
                     sys: Pair<Boolean, () -> Unit>?): View = ctx.hbox().apply {
        val v = if (compact) 2 else 4
        setPadding(ctx.dp(14), ctx.dp(v), ctx.dp(12), ctx.dp(v))
        addView(ctx.text(kind.title, 13f, medium = true).apply { maxLines = 2 }, lp(ctx.dp(62), WRAP))
        addView(ctx.inkButton(prev.first, 15f) { prev.second() }.sized(), lp(ctx.dp(38), WRAP))
        addView(ctx.vbox().apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(4), ctx.dp(2), ctx.dp(4), ctx.dp(3))
            addView(ctx.text(value, 14f, bold = true).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, lp(MATCH, WRAP))
            if (note != null) addView(ctx.text(note, 9.5f, color = Ink.MUTED).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, lp(MATCH, WRAP))
            addView(View(ctx).apply { setBackgroundColor(Ink.INK) }, lp(MATCH, ctx.dp(2)).apply { topMargin = ctx.dp(2) })
            pressable()
            contentDescription = "${kind.title}: $value. Tap for all options"
            setOnClickListener { listing = kind; render() }
        }, lp(0, WRAP, 1f).apply { marginStart = ctx.dp(4); marginEnd = ctx.dp(4) })
        addView(ctx.inkButton(next.first, 15f) { next.second() }.sized(), lp(ctx.dp(38), WRAP))
        if (sys != null) addView(ctx.chip("Sys", sys.first) { sys.second() }.sized().apply {
            minWidth = 0; minimumWidth = 0; setPadding(0, paddingTop, 0, paddingBottom); maxLines = 1
        }, lp(ctx.dp(44), WRAP).apply { marginStart = ctx.dp(6) })
        else addView(View(ctx), lp(ctx.dp(50), 1))
    }

    /** A setting's full list in place of the rows: two columns when it's long. Picking one applies it and goes back. */
    private fun renderList(kind: Kind) {
        body.addView(ctx.hbox().apply {
            setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(8), ctx.dp(4))
            addView(ctx.inkButton("◀", 15f) { listing = null; render() }, lp(ctx.dp(40), WRAP))
            addView(ctx.vbox().apply {
                addView(ctx.text(kind.title, 16f, bold = true))
                addView(ctx.text("$label · tap one to pick it", 11f, color = Ink.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            }, lp(0, WRAP, 1f).apply { marginStart = ctx.dp(10) })
        })
        val all = choices(kind)
        val cols = if (all.size > 8) 2 else 1
        val rows = (all.size + cols - 1) / cols
        val grid = ctx.hbox().apply { gravity = Gravity.TOP; setPadding(ctx.dp(6), 0, ctx.dp(6), ctx.dp(6)) }
        for (c in 0 until cols) {
            val col = ctx.vbox()
            for (r in 0 until rows) {
                val ch = all.getOrNull(c * rows + r) ?: break
                col.addView(ctx.hbox().apply {
                    setPadding(ctx.dp(8), ctx.dp(5), ctx.dp(8), ctx.dp(5))
                    pressable()
                    addView(ctx.text(if (ch.selected) "●" else "○", 15f, bold = true), lp(ctx.dp(24), WRAP))
                    addView(ctx.vbox().apply {
                        addView(ctx.text(ch.title, 14f, bold = ch.selected, medium = !ch.selected))
                        if (ch.detail != null && cols == 1) addView(ctx.text(ch.detail, 10.5f, color = Ink.MUTED))
                    }, lp(0, WRAP, 1f))
                    setOnClickListener { listing = null; ch.pick(); render() }
                })
            }
            grid.addView(col, lp(0, WRAP, 1f))
        }
        body.addView(grid)
    }
}
