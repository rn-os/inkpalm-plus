package com.inkpalmplus

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The screen and light panel (mockup: docs/mockups/19-eink-panel.png), from the strip on the edge opposite the recent
 * apps (the left one by default), on KompaktOS: the six screen modes as big buttons, where a pick goes (this app or
 * everywhere) and where the mode in use comes from, Full refresh, contrast, and the front light.
 */
class ScreenPanel(private val service: QuickPanelService, private val onClose: () -> Unit) {
    private val ctx = service
    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    /** The app in front when the panel opened (null: none known yet, so only Use everywhere). */
    private val pkg: String? = service.foreground
    private val appLabel = pkg?.let { RecentApps.Info.label(ctx, it) }
    /** Where the next tap on a mode goes: the app in front, or everywhere. Starts on the app when it has its own. */
    private var forApp = pkg != null && ScreenModes.own(ctx, pkg) != null
    private var light: Light.State? = null
    private var lightBlock: LightBlock? = null
    private val landscape get() = ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    /** On the left unless the recent apps took the left edge. */
    private val onLeft = Prefs(ctx).edgeRight

    @SuppressLint("ClickableViewAccessibility")
    fun show(wm: WindowManager) {
        val r = ctx.dp(18).toFloat()
        root = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(Ink.PAPER)
                setStroke(ctx.dp(3), Ink.INK)
                cornerRadii = if (onLeft) floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f) else floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
            setPadding(if (onLeft) 0 else ctx.dp(3), ctx.dp(3), if (onLeft) ctx.dp(3) else 0, ctx.dp(3))
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) onClose(); false }
        }
        content = ctx.vbox()
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        render()
        val w = ctx.resources.displayMetrics.widthPixels * (if (landscape) 72 else Prefs(ctx).panelWidthPct) / 100
        val lp = WindowManager.LayoutParams(
            w, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or if (onLeft) Gravity.START else Gravity.END }
        wm.addView(root, lp)
    }

    fun dismiss(wm: WindowManager) {
        runCatching { wm.removeView(root) }
    }

    // ---- Actions ------------------------------------------------------------------------------

    private fun pick(m: ScreenMode) {
        ScreenModes.choose(ctx, m, if (forApp) pkg else null, pkg)
        render()
    }

    /** Use everywhere: also drops the app's own mode, so it follows the device-wide one again. */
    private fun scope(app: Boolean) {
        forApp = app && pkg != null
        if (!forApp && pkg != null && ScreenModes.own(ctx, pkg) != null) ScreenModes.choose(ctx, null, pkg, pkg)
        render()
    }

    // ---- Views --------------------------------------------------------------------------------

    private fun render() {
        content.removeAllViews()
        val (now, source) = ScreenModes.resolve(ctx, pkg)
        val header = ctx.hbox().apply {
            setPadding(ctx.dp(14), ctx.dp(if (landscape) 6 else 10), ctx.dp(10), ctx.dp(if (landscape) 4 else 8))
            addView(ctx.text("Screen & light", 19f, bold = true).apply {
                maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(15, 19, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }, lp(0, WRAP, 1f))
            addView(ctx.inkButton("⚙", 21f) {
                ctx.startActivity(Intent(ctx, SectionActivity::class.java).putExtra(MainActivity.EXTRA_SECTION, MainActivity.SECTION_SCREEN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                onClose()
            }.apply { contentDescription = "Screen and light settings" }, lp(ctx.dp(48), WRAP).apply { marginEnd = ctx.dp(6) })
            addView(ctx.inkButton("✕", 16f) { onClose() }, lp(ctx.dp(48), WRAP))
        }
        content.addView(header)
        content.addView(View(ctx).apply { setBackgroundColor(Ink.INK) }, lp(MATCH, ctx.dp(2)))

        val label = ctx.text("SCREEN MODE", 10.5f, bold = true).apply { letterSpacing = 0.06f; setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(4)) }
        val modes = ctx.vbox().apply { setPadding(ctx.dp(8), 0, ctx.dp(8), 0) }
        if (landscape) ScreenMode.entries.chunked(2).forEach { pair ->
            modes.addView(ctx.hbox().apply {
                pair.forEach { addView(modeButton(it, it == now, compact = true), lp(0, WRAP, 1f).apply { setMargins(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2)) }) }
            }, lp(MATCH, WRAP))
        } else ScreenMode.entries.forEach {
            modes.addView(modeButton(it, it == now, compact = false), lp(MATCH, WRAP).apply { setMargins(0, ctx.dp(1), 0, ctx.dp(1)) })
        }
        val scopeRow = ctx.hbox().apply {
            setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(8), 0)
            if (pkg != null) addView(ctx.chip("Use for $appLabel", forApp) { scope(true) }.oneLine(),
                lp(0, WRAP, 1f).apply { marginEnd = ctx.dp(6) })
            addView(ctx.chip("Use everywhere", !forApp) { scope(false) }.oneLine(), lp(0, WRAP, 1f))
        }
        val hint = when {
            forApp && source != ScreenModes.Source.APP -> " · tap a mode to keep it for $appLabel"
            source == ScreenModes.Source.APP -> " · Use everywhere clears it"
            else -> ""
        }
        val sourceLine = ctx.text("${now.title} · ${if (source == ScreenModes.Source.APP) "$appLabel's own" else source.label}$hint", 12f, color = Ink.MUTED)
            .apply { setPadding(ctx.dp(12), ctx.dp(4), ctx.dp(12), ctx.dp(6)) }
        val actions = ctx.hbox().apply {
            setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(8), ctx.dp(6))
            addView(ctx.inkButton("Full refresh", 14f) { ScreenModes.fullRefresh() }.oneLine(), lp(WRAP, WRAP).apply { marginEnd = ctx.dp(6) })
            val level = ScreenModes.contrast(ctx)
            addView(ctx.inkButton("−", 18f) { ScreenModes.setContrast(ctx, level - ScreenModes.CONTRAST_STEP); render() }.apply {
                contentDescription = "Less contrast"; isEnabled = level > 0
            }, lp(ctx.dp(44), WRAP))
            // Two short lines, so the value never gets cut off between − and +.
            addView(ctx.text(ScreenModes.contrastLabel(level).replaceFirst(" ", "\n").replace("normal", "Normal"), 13f, bold = true).apply {
                gravity = Gravity.CENTER; maxLines = 2
            }, lp(0, WRAP, 1f))
            addView(ctx.inkButton("+", 18f) { ScreenModes.setContrast(ctx, level + ScreenModes.CONTRAST_STEP); render() }.apply {
                contentDescription = "More contrast"; isEnabled = level < ScreenModes.CONTRAST_MAX
            }, lp(ctx.dp(44), WRAP))
        }
        light = Light.read(ctx)
        lightBlock = light?.let { LightBlock(ctx, it, onClose) }
        val lightView = lightBlock?.view

        if (landscape) {
            val left = ctx.vbox().apply { addView(label); addView(modes) }
            val right = ctx.vbox().apply {
                addView(scopeRow); addView(sourceLine); addView(actions)
                addView(View(ctx), lp(MATCH, 0, 1f))
                lightView?.let { addView(it) }
            }
            content.addView(ctx.hbox().apply {
                gravity = Gravity.TOP
                addView(left, lp(0, MATCH, 1f))
                addView(View(ctx).apply { setBackgroundColor(Ink.INK) }, lp(ctx.dp(2), MATCH))
                addView(right, lp(0, MATCH, 1f))
            }, lp(MATCH, 0, 1f))
        } else {
            content.addView(label)
            content.addView(modes)
            content.addView(scopeRow)
            content.addView(sourceLine)
            content.addView(View(ctx).apply { setBackgroundColor(Ink.INK) }, lp(MATCH, ctx.dp(2)))
            content.addView(actions)
            content.addView(View(ctx), lp(MATCH, 0, 1f))
            lightView?.let {
                content.addView(View(ctx).apply { setBackgroundColor(Ink.INK) }, lp(MATCH, ctx.dp(2)))
                content.addView(it)
            }
        }
    }

    private fun TextView.oneLine() = apply { maxLines = 1; setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP) }

    /** A big labelled button: the mode's name and one line on what it's for; filled black when it's in use. */
    private fun modeButton(m: ScreenMode, on: Boolean, compact: Boolean): View = ctx.vbox().apply {
        val fg = if (on) Ink.PAPER else Ink.INK
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ctx.outline(if (on) 3 else 2, 12, Ink.PRESSED))
            addState(intArrayOf(), ctx.outline(if (on) 3 else 2, 12, if (on) Ink.INK else Ink.PAPER))
        }
        setPadding(ctx.dp(12), ctx.dp(4), ctx.dp(12), ctx.dp(4))
        addView(ctx.text(m.title, if (compact) 15f else 17f, bold = true, color = fg))
        addView(ctx.text(m.detail, if (compact) 11f else 12.5f, color = if (on) Ink.PAPER else Ink.MUTED).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        contentDescription = "${m.title}: ${m.detail}${if (on) ", in use" else ""}"
        isClickable = true
        setOnClickListener { pick(m) }
    }

    // ---- Test hooks -----------------------------------------------------------------------------

    fun testMode(key: String): Boolean = ScreenMode.ofKey(key)?.let { pick(it); true } ?: false
    fun testScope(app: Boolean): Boolean { scope(app); return true }
    fun describeLight(): String = lightBlock?.describe() ?: "light none"
    fun describe(): String = ScreenModes.resolve(ctx, pkg).let { (m, s) -> "pkg=$pkg forApp=$forApp global=${ScreenModes.global(ctx).key} mode=${m.key} code=${m.code} source=${s.name} contrast=${ScreenModes.contrast(ctx)}" }
}

/**
 * Brightness and Warmth, each − [label over slider] +, the same in both orientations (the slider applies when you let
 * go, so e-ink redraws once; the buttons step by 10). Brightness 0% is off: − to 0 turns the light off, + or the
 * slider turns it back on at the level it had. The Auto pill (mockup 18) is Android's adaptive brightness and the only
 * thing that turns it on or off; with it on, Brightness is the maximum, a Sensitivity − + row shows, with the
 * lux readout under its label (mockup 22). Targets are 48 dp.
 */
class LightBlock(private val ctx: QuickPanelService, private val s: Light.State, private val onClose: () -> Unit) {
    private var state = s
    val view: View = build()

    private fun build(): View = ctx.vbox().apply {
        setPadding(ctx.dp(10), ctx.dp(4), ctx.dp(10), ctx.dp(6))
        var level = if (s.on) s.level else 0
        var lastLevel = s.level.takeIf { it > 0 } ?: 50
        var warmth = s.warmth
        fun send() {
            if (level > 0) lastLevel = level
            state = Light.State(lastLevel, warmth, level > 0, s.follows, Light.auto(ctx))
            Light.set(ctx, lastLevel, warmth, level > 0)
        }
        val pills = mutableListOf<() -> Unit>()
        fun slider(label: () -> String, get: () -> Int, put: (Int) -> Unit, extra: View? = null, reserve: Boolean = false) = addView(ctx.hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            val text = ctx.text(label(), 13f, bold = true).apply {
                gravity = Gravity.CENTER; maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(10, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }
            val bar = android.widget.SeekBar(ctx).apply {
                max = 100
                progress = get()
                progressTintList = android.content.res.ColorStateList.valueOf(Ink.INK)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ink.MUTED)
                thumbTintList = android.content.res.ColorStateList.valueOf(Ink.INK)
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(b: android.widget.SeekBar, p: Int, fromUser: Boolean) {}
                    override fun onStartTrackingTouch(b: android.widget.SeekBar) {}
                    override fun onStopTrackingTouch(b: android.widget.SeekBar) { put(b.progress); send(); text.text = label(); pills.forEach { it() } }
                })
            }
            pills += { text.text = label() }
            fun step(d: Int) { put((get() + d).coerceIn(0, 100)); bar.progress = get(); send(); text.text = label(); pills.forEach { it() } }
            addView(ctx.inkButton("−", 18f) { step(-10) }.apply { minHeight = ctx.dp(48) }, lp(ctx.dp(48), WRAP))
            addView(ctx.vbox().apply {
                addView(text, lp(MATCH, WRAP))
                addView(bar, lp(MATCH, WRAP))
            }, lp(0, WRAP, 1f))
            // Auto sits between the slider and +, a 48 dp box like them, one 6 dp gap on each side. The Warmth row gets
            // an empty box of the same width, so both sliders end in the same place.
            if (extra != null) addView(extra, lp(WRAP, ctx.dp(48)).apply { marginStart = ctx.dp(6); marginEnd = ctx.dp(6) })
            else if (reserve) addView(View(ctx), lp(ctx.dp(60), ctx.dp(48)))
            addView(ctx.inkButton("+", 18f) { step(10) }.apply { minHeight = ctx.dp(48) }, lp(ctx.dp(48), WRAP))
        }, lp(MATCH, WRAP).apply { topMargin = ctx.dp(2) })
        // Auto: only when the light follows Android's brightness. Only this pill turns it on or off; Brightness is then the maximum.
        val readout = ctx.text("", 12f, color = Ink.MUTED).apply { gravity = Gravity.CENTER_VERTICAL; maxLines = 1 }
        val sens = ctx.hbox()
        val sensValue = ctx.text("", 13f, bold = true).apply { gravity = Gravity.CENTER; minWidth = ctx.dp(48) }
        fun refresh() {
            val on = Light.auto(ctx)
            readout.text = if (on) Light.readout().orEmpty() else ""
            sens.visibility = if (on) View.VISIBLE else View.GONE
            val v = Light.sensitivity(ctx)
            sensValue.text = if (v > 0) "+$v" else "$v"
        }
        val pill = if (!s.follows) null else ctx.text("Auto", 12f, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
            minWidth = ctx.dp(48); minHeight = ctx.dp(48)
            fun look(on: Boolean) {
                background = ctx.outline(2, 6, if (on) Ink.INK else Ink.PAPER)
                setTextColor(if (on) Ink.PAPER else Ink.INK)
                contentDescription = if (on) "Auto brightness on" else "Auto brightness off"
            }
            look(s.auto)
            pills += { look(Light.auto(ctx)); refresh() }
            setOnClickListener {
                val on = !Light.auto(ctx)
                if (Light.setAuto(ctx, on)) {
                    pills.forEach { it() }
                    Light.apply(ctx)
                } else {
                    // Modify system settings isn't allowed yet: Android's screen for it.
                    runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        android.net.Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    onClose()
                }
            }
        }
        slider({ if (level == 0) "Light off" else if (Light.auto(ctx)) "Maximum $level%" else "Brightness $level%" }, { level }, { level = it }, pill)
        slider({ "Warmth $warmth%" }, { warmth }, { warmth = it }, reserve = pill != null)
        if (pill != null) {
            sens.apply {
                gravity = Gravity.CENTER_VERTICAL
                // The lux readout sits under the label (the Brightness line has no room for it beside the pill).
                addView(ctx.vbox().apply {
                    addView(ctx.text("Sensitivity", 13f, bold = true))
                    addView(readout)
                }, lp(0, WRAP, 1f))
                addView(ctx.inkButton("−", 18f) { Light.setSensitivity(ctx, Light.sensitivity(ctx) - 1); refresh() }.apply { minHeight = ctx.dp(48) }, lp(ctx.dp(48), WRAP))
                addView(sensValue, lp(WRAP, WRAP))
                addView(ctx.inkButton("+", 18f) { Light.setSensitivity(ctx, Light.sensitivity(ctx) + 1); refresh() }.apply { minHeight = ctx.dp(48) }, lp(ctx.dp(48), WRAP))
            }
            addView(sens, lp(MATCH, WRAP).apply { topMargin = ctx.dp(2) })
            refresh()
            // The readout follows the light while the panel is open.
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { Light.listener = { refresh() } }
                override fun onViewDetachedFromWindow(v: View) { Light.listener = null }
            })
        }
    }.also { it.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP) }

    fun describe(): String = "light level=${state.level} warmth=${state.warmth} on=${state.on} auto=${Light.auto(ctx)}"
}
