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
    private val radioViews = LinkedHashMap<Radios.Radio, TextView>()
    /** A switch in flight: what to show (Turning on…) and the state that ends it. */
    private val pending = HashMap<Radios.Radio, Pair<Radios.State, Radios.State>>()
    private var shown = false
    private var light: Light.State? = null
    private var lightBlock: LightBlock? = null
    private val landscape get() = ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    /** On the left unless the recent apps took the left edge. */
    private val onLeft = Prefs(ctx).edgeRight

    @SuppressLint("ClickableViewAccessibility")
    fun show(wm: WindowManager) {
        val r = ctx.dp(Radius.LARGE).toFloat()
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
        shown = true
        ctx.registerReceiver(radioReceiver, android.content.IntentFilter().apply {
            addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        })
        refreshRadios()
    }

    fun dismiss(wm: WindowManager) {
        shown = false
        runCatching { ctx.unregisterReceiver(radioReceiver) }
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
        fun gear() = ctx.inkButton("⚙", Type.XL) {
            ctx.startActivity(Intent(ctx, SectionActivity::class.java).putExtra(MainActivity.EXTRA_SECTION, MainActivity.SECTION_SCREEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            onClose()
        }.apply { contentDescription = "Screen and light settings" }
        fun close() = ctx.inkButton("✕", Type.L) { onClose() }
        val title = ctx.text("SCREEN MODE", Type.XS, bold = true).apply { letterSpacing = 0.06f }
        // Landscape: [Wi-Fi] [Bluetooth] [Airplane] ⚙ ✕ in one row. Portrait is too narrow for that, so the three
        // toggles take the top row and ⚙ ✕ share the SCREEN MODE label's row.
        val label: View = if (landscape) title.apply { setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(4)) }
        else ctx.hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(12), 0, ctx.dp(8), ctx.dp(2))
            addView(title, lp(0, WRAP, 1f))
            addView(gear(), lp(ctx.dp(48), WRAP).apply { marginEnd = ctx.dp(6) })
            addView(close(), lp(ctx.dp(48), WRAP))
        }
        if (landscape) content.addView(ctx.hbox().apply {
            setPadding(ctx.dp(8), statusBarInset() + ctx.dp(Space.GUTTER), ctx.dp(10), ctx.dp(4))
            addView(radioRow(), lp(0, WRAP, 1f).apply { marginEnd = ctx.dp(6) })
            addView(gear(), lp(ctx.dp(48), WRAP).apply { marginEnd = ctx.dp(6) })
            addView(close(), lp(ctx.dp(48), WRAP))
        }) else content.addView(radioRow(), lp(MATCH, WRAP).apply { setMargins(ctx.dp(8), statusBarInset() + ctx.dp(Space.GUTTER), ctx.dp(8), ctx.dp(3)) })
        content.addView(View(ctx).apply { setBackgroundColor(Ink.INK) }, lp(MATCH, ctx.dp(2)))

        val modes = ctx.vbox().apply { setPadding(ctx.dp(8), 0, ctx.dp(8), 0) }
        if (landscape) ScreenMode.entries.chunked(2).forEach { pair ->
            modes.addView(ctx.hbox().apply {
                pair.forEach { addView(modeButton(it, it == now, compact = true), lp(0, WRAP, 1f).apply { setMargins(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2)) }) }
            }, lp(MATCH, WRAP))
        } else ScreenMode.entries.forEach {
            modes.addView(modeButton(it, it == now, compact = false), lp(MATCH, WRAP).apply { setMargins(0, ctx.dp(1), 0, 0) })
        }
        val scopeRow = ctx.hbox().apply {
            setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), 0)
            if (pkg != null) addView(ctx.chip("Use for $appLabel", forApp) { scope(true) }.oneLine(),
                lp(0, WRAP, 1f).apply { marginEnd = ctx.dp(6) })
            addView(ctx.chip("Use everywhere", !forApp) { scope(false) }.oneLine(), lp(0, WRAP, 1f))
        }
        val hint = when {
            forApp && source != ScreenModes.Source.APP -> " · tap a mode to keep it for $appLabel"
            source == ScreenModes.Source.APP -> " · Use everywhere clears it"
            else -> ""
        }
        val sourceLine = ctx.text("${now.title} · ${if (source == ScreenModes.Source.APP) "$appLabel's own" else source.label}$hint", Type.S, color = Ink.MUTED)
            .apply { setPadding(ctx.dp(12), ctx.dp(2), ctx.dp(12), ctx.dp(3)) }
        val actions = ctx.hbox().apply {
            setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(4))
            addView(ctx.inkButton("Full refresh", Type.M) { ScreenModes.fullRefresh() }.oneLine(), lp(WRAP, WRAP).apply { marginEnd = ctx.dp(6) })
            val level = ScreenModes.contrast(ctx)
            addView(ctx.inkButton("−", Type.XL) { ScreenModes.setContrast(ctx, level - ScreenModes.CONTRAST_STEP); render() }.apply {
                contentDescription = "Less contrast"; isEnabled = level > 0
            }, lp(ctx.dp(44), WRAP))
            // Two short lines, so the value never gets cut off between − and +.
            addView(ctx.text(ScreenModes.contrastLabel(level).replaceFirst(" ", "\n").replace("normal", "Normal"), Type.S, bold = true).apply {
                gravity = Gravity.CENTER; maxLines = 2
                setAutoSizeTextTypeUniformWithConfiguration(10, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }, lp(0, WRAP, 1f))
            addView(ctx.inkButton("+", Type.XL) { ScreenModes.setContrast(ctx, level + ScreenModes.CONTRAST_STEP); render() }.apply {
                contentDescription = "More contrast"; isEnabled = level < ScreenModes.CONTRAST_MAX
            }, lp(ctx.dp(44), WRAP))
        }
        light = Light.read(ctx)
        lightBlock = light?.let { LightBlock(ctx, it, onClose) }
        val lightView = lightBlock?.view

        if (landscape) {
            // Full refresh and contrast sit under the modes (the empty space there), so the light block fits on the right.
            val left = ctx.vbox().apply { addView(label); addView(modes); addView(actions) }
            val right = ctx.vbox().apply {
                addView(scopeRow); addView(sourceLine)
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

    /** The real status bar height (the panel is drawn over it): the window's inset, else Android's dimen. */
    private fun statusBarInset(): Int {
        val w = runCatching { root.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.statusBars())?.top }.getOrNull() ?: 0
        if (w > 0) return w
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
    }

    /** [Wi-Fi] [Bluetooth]: two equal 48 dp toggles in the header's place; filled black while on (or turning on). */
    private fun radioRow(): View {
        radioViews.clear()
        return ctx.hbox().apply {
            Radios.Radio.entries.forEachIndexed { i, r ->
                val v = ctx.text("", Type.S, bold = true).apply {
                    gravity = Gravity.CENTER; maxLines = 2; minHeight = ctx.dp(48)
                    setPadding(ctx.dp(2), 0, ctx.dp(2), 0)
                    setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                    setOnClickListener { toggleRadio(r) }
                }
                radioViews[r] = v
                addView(v, lp(0, ctx.dp(48), 1f).apply { if (i < Radios.Radio.entries.size - 1) marginEnd = ctx.dp(4) })
            }
            refreshRadios()
        }
    }

    private fun refreshRadios() = radioViews.forEach { (r, v) ->
        val s = pending[r]?.takeIf { Radios.state(ctx, r) != it.second }?.first ?: Radios.state(ctx, r)
        val on = with(Radios) { s.wantsOn() }
        v.text = Radios.label(r, s)
        v.background = ctx.outline(2, Radius.SMALL, if (on) Ink.INK else Ink.PAPER)
        v.setTextColor(if (on) Ink.PAPER else Ink.INK)
        v.contentDescription = "${r.title} ${Radios.label(r, s).substringAfter('\n').lowercase()}"
        v.isEnabled = s != Radios.State.NONE
    }

    private fun toggleRadio(r: Radios.Radio) {
        val now = with(Radios) { state(ctx, r).wantsOn() }
        val target = !now
        pending[r] = (if (target) Radios.State.TURNING_ON else Radios.State.TURNING_OFF) to
            (if (target) Radios.State.ON else Radios.State.OFF)
        refreshRadios()
        Shell.background({ Radios.set(ctx, r, target) }) { asked ->
            if (!asked) { pending.remove(r); onClose() }   // Android's own screen opened over the panel
            else Shell.main.postDelayed({ pending.remove(r); if (shown) refreshRadios() }, 8000)
            if (shown) refreshRadios()
        }
    }

    private val radioReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) { refreshRadios() }
    }

    private fun TextView.oneLine() = apply { maxLines = 1; setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP) }

    /** A big labelled button: the mode's name and one line on what it's for; filled black when it's in use. */
    private fun modeButton(m: ScreenMode, on: Boolean, compact: Boolean): View = ctx.vbox().apply {
        val fg = if (on) Ink.PAPER else Ink.INK
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ctx.outline(if (on) 3 else 2, Radius.MEDIUM, Ink.PRESSED))
            addState(intArrayOf(), ctx.outline(if (on) 3 else 2, Radius.MEDIUM, if (on) Ink.INK else Ink.PAPER))
        }
        setPadding(ctx.dp(12), ctx.dp(if (compact) 4 else 1), ctx.dp(12), ctx.dp(if (compact) 4 else 1))
        addView(ctx.text(m.title, if (compact) Type.M else Type.L, bold = true, color = fg))
        addView(ctx.text(m.detail, if (compact) Type.XS else Type.S, color = if (on) Ink.PAPER else Ink.MUTED).apply {
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
 * thing that turns it on or off; with it on, the lux curves set brightness and warmth, both sliders become offsets
 * (Brighter / Dimmer than auto, Warmer / Cooler than auto, -50..+50, the buttons step by 5), a Sensitivity − + row shows, with the
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
        var on = s.on
        fun autoOn() = s.follows && Light.auto(ctx)
        fun send() {
            if (autoOn()) return
            if (level > 0) lastLevel = level
            on = level > 0
            state = Light.State(lastLevel, warmth, on, s.follows, false)
            Light.set(ctx, lastLevel, warmth, on)
        }
        val pills = mutableListOf<() -> Unit>()
        fun off(v: Int, more: String, less: String, same: String) =
            if (v > 0) "$more +$v" else if (v < 0) "$less −${-v}" else same
        // − [label over slider] +, full width. Under Auto the slider is an offset: -50..+50 points around the curve,
        // centred (progress 0..100 = offset + 50), and the buttons step by 5.
        fun slider(bright: Boolean, label: () -> String, get: () -> Int, put: (Int) -> Unit) = addView(ctx.hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            val text = ctx.text(label(), Type.S, bold = true).apply {
                gravity = Gravity.CENTER; maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(10, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }
            fun shown() = if (autoOn()) get() + Light.OFFSET_MAX else get()
            val bar = android.widget.SeekBar(ctx).apply {
                max = 100
                progress = shown()
                progressTintList = android.content.res.ColorStateList.valueOf(Ink.INK)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ink.MUTED)
                thumbTintList = android.content.res.ColorStateList.valueOf(Ink.INK)
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(b: android.widget.SeekBar, p: Int, fromUser: Boolean) {}
                    override fun onStartTrackingTouch(b: android.widget.SeekBar) {}
                    override fun onStopTrackingTouch(b: android.widget.SeekBar) {
                        put(if (autoOn()) b.progress - Light.OFFSET_MAX else b.progress); send(); pills.forEach { it() }
                    }
                })
            }
            pills += { text.text = label(); bar.progress = shown() }
            fun step(d: Int) {
                if (autoOn()) put((get() + d / 2).coerceIn(-Light.OFFSET_MAX, Light.OFFSET_MAX)) else put((get() + d).coerceIn(0, 100))
                send(); pills.forEach { it() }
            }
            addView(ctx.inkButton("−", Type.XL) { step(-10) }.apply { minHeight = ctx.dp(48) }, lp(ctx.dp(48), WRAP))
            addView(ctx.vbox().apply {
                addView(text, lp(MATCH, WRAP))
                addView(bar, lp(MATCH, WRAP))
            }, lp(0, WRAP, 1f))
            addView(ctx.inkButton("+", Type.XL) { step(10) }.apply { minHeight = ctx.dp(48) }, lp(ctx.dp(48), WRAP))
        }, lp(MATCH, WRAP).apply { topMargin = ctx.dp(2) })

        // The bar above the sliders: [Light on/off] [Auto], two equal outlined 48 dp buttons, filled black when active.
        fun toggle(title: String, isOn: () -> Boolean, desc: (Boolean) -> String, click: () -> Unit) = ctx.text(title, Type.S, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
            minHeight = ctx.dp(48)
            fun look() {
                val v = isOn()
                background = ctx.outline(2, Radius.SMALL, if (v) Ink.INK else Ink.PAPER)
                setTextColor(if (v) Ink.PAPER else Ink.INK)
                contentDescription = desc(v)
            }
            look()
            pills += { look() }
            setOnClickListener { click() }
        }
        val readout = ctx.text("", Type.S, color = Ink.MUTED).apply {
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(8, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        val sens = ctx.hbox()
        val sensValue = ctx.text("", Type.S, bold = true).apply { gravity = Gravity.CENTER; minWidth = ctx.dp(28) }
        fun refresh() {
            val a = autoOn()
            readout.text = if (a) Light.readout(ctx) ?: "Waiting for the light sensor" else ""
            sens.visibility = if (a) View.VISIBLE else View.GONE
            val v = Light.sensitivity(ctx)
            sensValue.text = if (v > 0) "+$v" else "$v"
        }
        pills += { refresh() }
        val onButton = toggle("Light: ${if (on) "On" else "Off"}", { Light.isOn(ctx) }, { "Front light ${if (it) "on" else "off"}" }) {
            val now = !Light.isOn(ctx)
            if (now && level == 0) level = lastLevel
            if (!now) level = 0 else if (level == 0) level = lastLevel
            if (autoOn()) Light.setOn(ctx, now) else { Light.set(ctx, lastLevel, warmth, now) }
            pills.forEach { it() }
        }
        onButton.apply { pills += { text = "Light: ${if (Light.isOn(ctx)) "On" else "Off"}" } }
        val autoButton = if (!s.follows) null else toggle("Auto", { Light.auto(ctx) }, { if (it) "Auto brightness on" else "Auto brightness off" }) {
            val on2 = !Light.auto(ctx)
            if (Light.setAuto(ctx, on2)) {
                pills.forEach { it() }
                Light.apply(ctx)
            } else {
                // Modify system settings isn't allowed yet: Android's screen for it.
                runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    android.net.Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                onClose()
            }
        }
        addView(ctx.hbox().apply {
            addView(onButton, lp(0, ctx.dp(48), 1f).apply { marginEnd = if (autoButton != null) ctx.dp(6) else 0 })
            autoButton?.let { addView(it, lp(0, ctx.dp(48), 1f)) }
        }, lp(MATCH, WRAP).apply { topMargin = ctx.dp(2) })
        if (autoButton != null) {
            sens.apply {
                gravity = Gravity.CENTER_VERTICAL
                // The readout sits directly under the bar, with the Sensitivity label under it.
                addView(ctx.vbox().apply {
                    addView(readout)
                    addView(ctx.text("Sensitivity", Type.S, bold = true))
                }, lp(0, WRAP, 1f))
                addView(ctx.inkButton("−", Type.XL) { Light.setSensitivity(ctx, Light.sensitivity(ctx) - 1); refresh() }.apply { minHeight = ctx.dp(48) }, lp(ctx.dp(48), WRAP))
                addView(sensValue, lp(WRAP, WRAP))
                addView(ctx.inkButton("+", Type.XL) { Light.setSensitivity(ctx, Light.sensitivity(ctx) + 1); refresh() }.apply { minHeight = ctx.dp(48) }, lp(ctx.dp(48), WRAP))
            }
            addView(sens, lp(MATCH, WRAP).apply { topMargin = ctx.dp(2) })
        }
        slider(true, {
            if (autoOn()) off(Light.autoOffset(ctx, true), "Brighter than auto", "Dimmer than auto", "Brightness: as auto")
            else if (level == 0) "Light off" else "Brightness $level%"
        }, { if (autoOn()) Light.autoOffset(ctx, true) else level },
            { if (autoOn()) Light.setAutoOffset(ctx, true, it) else level = it })
        slider(false, {
            if (autoOn()) off(Light.autoOffset(ctx, false), "Warmer than auto", "Cooler than auto", "Warmth: as auto")
            else "Warmth $warmth%"
        }, { if (autoOn()) Light.autoOffset(ctx, false) else warmth },
            { if (autoOn()) Light.setAutoOffset(ctx, false, it) else warmth = it })
        refresh()
        // The readout follows the light while the panel is open.
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { Light.listener = { refresh() } }
            override fun onViewDetachedFromWindow(v: View) { Light.listener = null }
        })
    }.also { it.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP) }

    fun describe(): String = "light level=${state.level} warmth=${state.warmth} on=${state.on} auto=${Light.auto(ctx)}"
}
