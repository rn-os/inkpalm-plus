package com.inkpalmplus

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

/**
 * Home: whether InkPalm+ can reach Moaan's settings, the whole-device tweaks,
 * and every app with its e-ink settings. Tap an app to tune it ([AppActivity]).
 */
open class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var accessBox: LinearLayout
    private lateinit var deviceBox: LinearLayout
    private lateinit var touchBox: LinearLayout
    private lateinit var appsBox: LinearLayout
    private lateinit var panelBox: LinearLayout
    private var filter = ""
    private var fontsOpen = false
    private var apps: List<AppEntry> = emptyList()
    private var profiles: Map<String, EinkDb.Profile> = emptyMap()

    /** Icon loaded with the list, off the main thread: ~100 icons are slow to decode. */
    data class AppEntry(val pkg: String, val label: String, val icon: android.graphics.drawable.Drawable?)

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> reload() }
    private val binderListener = Shizuku.OnBinderReceivedListener { Shell.main.post { reload() } }

    /** Which screen this is: null for home, else [SECTION_EINK], [SECTION_TOUCH] or [SECTION_KEEP_ON]. */
    private val section: String? get() = intent.getStringExtra(EXTRA_SECTION)
    private lateinit var keepBox: LinearLayout
    private lateinit var navBox: LinearLayout
    private val askBox by lazy { vbox() }
    /** The first-start question's pick so far: a reader, or null for No reader ([askPicked] says whether one was tapped). */
    private var askPick: Readers.App? = null
    private var askPicked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = vbox()
        accessBox = vbox(); panelBox = vbox(); deviceBox = vbox(); touchBox = vbox(); appsBox = vbox(); keepBox = vbox(); navBox = vbox()
        val (title, sub) = when (section) {
            SECTION_EINK -> "E-ink settings" to "Per-app DPI, refresh, contrast, presets, font and refresh helpers"
            SECTION_TOUCH -> "Touch button" to "The button below the screen"
            SECTION_KEEP_ON -> "Keeping the panel on" to "So the side panel survives Clear Memory and restarts"
            else -> getString(R.string.app_name) to "A side panel of recent apps for the InkPalm Plus"
        }
        root.addView(hbox().apply {
            setPadding(dp(8), dp(12), dp(16), dp(4))
            if (section != null) addView(inkIconButton(R.drawable.ic_back, "Back", 44) { finish() }, lp(dp(44), dp(44)).apply { marginEnd = dp(10) })
            else setPadding(dp(16), dp(16), dp(16), dp(4))
            addView(text(title, 26f, bold = true), lp(0, WRAP, 1f))
        })
        root.addView(text(sub, 14f, color = Ink.MUTED).apply { setPadding(dp(16), 0, dp(16), dp(8)) })
        when (section) {
            SECTION_EINK -> {
                root.addView(accessBox)
                root.addView(sectionTitle("Whole device"))
                root.addView(deviceBox)
                root.addView(sectionTitle("Apps"))
                root.addView(EditText(this).apply {
                    hint = "Search apps"
                    textSize = 16f
                    setTextColor(Ink.INK)
                    setSingleLine()
                    background = outline(2, 6)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            filter = s?.toString().orEmpty().trim()
                            renderApps()
                        }
                    })
                }, lp(MATCH, WRAP).apply { setMargins(dp(16), dp(8), dp(16), dp(4)) })
                root.addView(appsBox)
            }
            SECTION_TOUCH -> root.addView(touchBox)
            SECTION_KEEP_ON -> root.addView(keepBox)
            else -> {
                root.addView(askBox)
                root.addView(sectionTitle("Side panel"))
                root.addView(panelBox)
                root.addView(sectionTitle("More"))
                root.addView(navBox)
            }
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // The panel may have been switched off by a force-stop (Moaan's Clear Memory): put it back.
        Shell.background({ Survival.repair(this, "app opened") }) { reload() }
    }

    private fun reload() {
        val eink = section == SECTION_EINK
        Shell.background({
            // The per-app list (~100 icons) only loads on the E-ink screen.
            if (eink) { val (via, rows) = EinkDb.load(this); Triple(via, launchableApps(), rows) } else Triple(null, emptyList(), emptyMap())
        }) { (via, list, rows) ->
            apps = list
            profiles = rows
            when (section) {
                SECTION_EINK -> { renderAccess(via); renderDevice(); renderApps() }
                SECTION_TOUCH -> renderTouch()
                SECTION_KEEP_ON -> renderKeepOn()
                else -> { renderAsk(); renderPanel(); renderNav() }
            }
        }
    }

    private fun openSection(name: String) = startActivity(Intent(this, SectionActivity::class.java).putExtra(EXTRA_SECTION, name))

    /** Home: one row per sub-screen, with what's set there. */
    private fun renderNav() {
        navBox.removeAllViews()
        fun nav(title: String, detail: String, name: String) = navBox.addView(hbox().apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            pressable()
            addView(vbox().apply {
                addView(text(title, 17f, medium = true))
                addView(text(detail, 13f, color = Ink.MUTED))
            }, lp(0, WRAP, 1f))
            addView(inkButton("Open", 15f) { openSection(name) })
            setOnClickListener { openSection(name) }
        })
        val touch = if (!TouchButton.canChange(this)) "Needs a permission" else if (!TouchButton.on(this)) "Off"
            else "Tap: ${TouchButton.TAP_ACTIONS[TouchButton.tap(this)]} · Hold: ${TouchButton.HOLD_ACTIONS[TouchButton.hold(this)]}"
        nav("Touch button", touch, SECTION_TOUCH)
        nav("E-ink settings", "Per-app DPI, refresh, contrast, presets, font, refresh helpers", SECTION_EINK)
        val keep = listOfNotNull(if (Survival.inWhitelist(this)) "Survives Clear Memory" else null,
            if (Survival.canRepair(this)) "turns itself back on" else null).joinToString(" · ").ifEmpty { "Not set up" }
        nav("Keeping the panel on", keep, SECTION_KEEP_ON)
    }

    private fun launchableApps(): List<AppEntry> {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != packageName }
            .map { pkg ->
                val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                AppEntry(pkg, info?.let { pm.getApplicationLabel(it).toString() } ?: pkg, info?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() })
            }
            .sortedBy { it.label.lowercase() }
    }

    // ---- Access -------------------------------------------------------------------------------

    private fun renderAccess(via: EinkDb.Via?) {
        accessBox.removeAllViews()
        val box = vbox().apply {
            background = outline(2, 8)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        accessBox.addView(box, lp(MATCH, WRAP).apply { setMargins(dp(16), dp(8), dp(16), dp(4)) })
        fun line(s: String, bold: Boolean = false) = box.addView(text(s, if (bold) 16f else 14f, bold = bold, color = if (bold) Ink.INK else Ink.MUTED))
        when {
            via == EinkDb.Via.APP -> {
                line("Connected to Moaan's e-ink settings", bold = true)
                if (!Shell.allowed()) line("Per-app settings need nothing else. Animations and text size below use Shizuku when it's running.")
            }
            via == EinkDb.Via.SHIZUKU -> {
                line("Connected through Shizuku", bold = true)
                line("Start Shizuku again after each restart to change settings. Saved settings keep working without it.")
            }
            !Shell.installed(this) -> {
                line("Can't reach Moaan's e-ink settings", bold = true)
                line("This firmware keeps them from ordinary apps. Install Shizuku (github.com/RikkaApps/Shizuku) and start it with Wireless debugging.")
            }
            !Shell.running() -> {
                line("Start Shizuku", bold = true)
                line("Open Shizuku and start it with Wireless debugging, then come back.")
                box.addView(inkButton("Open Shizuku") {
                    packageManager.getLaunchIntentForPackage(Shell.SHIZUKU_PACKAGE)?.let { startActivity(it) }
                }, lp(WRAP, WRAP).apply { topMargin = dp(8) })
            }
            !Shell.allowed() -> {
                line("Allow InkPalm+ in Shizuku", bold = true)
                box.addView(inkButton("Allow") { Shell.requestPermission() }, lp(WRAP, WRAP).apply { topMargin = dp(8) })
            }
            else -> line("Couldn't read Moaan's e-ink settings on this firmware", bold = true)
        }
    }

    // ---- Quick panel ----------------------------------------------------------------------------

    private fun renderPanel() {
        panelBox.removeAllViews()
        panelBox.addView(text("Swipe up or down along the screen edge in any app for your recent apps.", 14f, color = Ink.MUTED).apply {
            setPadding(dp(16), dp(8), dp(16), dp(4))
        })
        val on = QuickPanelService.enabled(this)
        panelBox.addView(toggleRow("Side panel", if (on) "On" else "Turn on InkPalm+ in Accessibility", on) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        val prefs = Prefs(this)
        renderReaderRow(prefs)
        fun previewStrip() = sendBroadcast(Intent(QuickPanelService.ACTION_STRIP).setPackage(packageName).putExtra(QuickPanelService.EXTRA_PREVIEW, true))
        fun choices(title: String, detail: String, labels: List<String>, current: Int, pick: (Int) -> Unit) = panelBox.addView(hbox().apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(vbox().apply {
                addView(text(title, 17f, medium = true))
                if (detail.isNotEmpty()) addView(text(detail, 13f, color = Ink.MUTED))
            }, lp(0, WRAP, 1f))
            labels.forEachIndexed { i, l -> addView(chip(l, i == current) { pick(i); renderPanel() }, lp(WRAP, WRAP).apply { marginStart = dp(6) }) }
        })
        choices("Strip side", "Where you swipe", listOf("Left", "Right"), if (prefs.edgeRight) 1 else 0) { prefs.edgeRight = it == 1; previewStrip() }
        val w = prefs.stripWidthDp
        panelBox.addView(stepperRow("Strip width", "$w dp",
            onMinus = { prefs.stripWidthDp = w - 2; previewStrip(); renderPanel() },
            onPlus = { prefs.stripWidthDp = w + 2; previewStrip(); renderPanel() }))
        panelBox.addView(toggleRow("Show strip", "A thin black line where you swipe", prefs.showStrip) {
            prefs.showStrip = !prefs.showStrip
            sendBroadcast(Intent(QuickPanelService.ACTION_STRIP).setPackage(packageName))
            renderPanel()
        })
        val widths = listOf(66, 78, 90)
        choices("Panel width", "How much of the screen it covers", widths.map { "$it%" }, widths.indexOf(prefs.panelWidthPct)) { prefs.panelWidthPct = widths[it] }
        val kept = listOf(9, 18, 27)
        choices("Apps kept", "Recent apps the panel remembers", kept.map { "$it" }, kept.indexOf(prefs.recentKept)) { prefs.recentKept = kept[it] }
        panelBox.addView(hbox().apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(vbox().apply {
                addView(text("Clear recent apps", 17f, medium = true))
                addView(text("Start the list again", 13f, color = Ink.MUTED))
            }, lp(0, WRAP, 1f))
            addView(inkButton("Clear", 15f) {
                getSharedPreferences("recent_apps", MODE_PRIVATE).edit().clear().apply()
                sendBroadcast(Intent(QuickPanelService.ACTION_CLEAR_RECENTS).setPackage(packageName))
                toast("Recent apps cleared")
            })
        })
    }

    // ---- Reader app (docs/mockups/14-reader-app.png) -------------------------------------------

    /** First start: which reader's last book the side panel shows. Only installed readers; skipped with none. */
    private fun renderAsk() {
        askBox.removeAllViews()
        val apps = Readers.installedApps(this)
        if (Prefs(this).readerApp != null || apps.isEmpty()) return
        askBox.addView(vbox().apply {
            background = outline(3, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(text("Which reader do you use?", 19f, bold = true))
            addView(text("The side panel keeps the last book you read in it one tap away. You can change this later.", 13f, color = Ink.MUTED)
                .apply { setPadding(0, dp(2), 0, dp(8)) })
            fun option(app: Readers.App?, sub: String) {
                val on = askPicked && askPick == app
                addView(hbox().apply {
                    pressable(outline(if (on) 3 else 2, 12))
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    gravity = Gravity.CENTER_VERTICAL
                    addView(vbox().apply {
                        addView(text(app?.label ?: "No reader", 17f, bold = true))
                        addView(text(sub, 13f, color = Ink.MUTED))
                    }, lp(0, WRAP, 1f))
                    addView(text(if (on) "●" else "○", 22f, bold = true))
                    setOnClickListener { askPick = app; askPicked = true; renderAsk() }
                }, lp(MATCH, WRAP).apply { bottomMargin = dp(7) })
            }
            for (app in apps) option(app, if (Readers.hasAccess(app)) "Installed · works as is" else "Installed · asks once for All files access")
            option(null, "No book card in the panel")
            addView(inkButton("Continue", 16f) {
                if (!askPicked) return@inkButton
                Prefs(this@MainActivity).readerApp = askPick?.key ?: "none"
                askPick?.let { if (!Readers.hasAccess(it)) runCatching { startActivity(Readers.accessIntent(this@MainActivity)) } }
                renderAsk(); renderPanel()
            }.apply { isEnabled = askPicked; alpha = if (askPicked) 1f else 0.4f }, lp(MATCH, WRAP))
        }, lp(MATCH, WRAP).apply { setMargins(dp(16), dp(8), dp(16), dp(8)) })
    }

    /** Reader app row: NeoReader · KOReader · None (installed readers only). */
    private fun renderReaderRow(prefs: Prefs) {
        val apps = Readers.installedApps(this)
        if (apps.isEmpty()) return
        val chosen = Readers.chosen(this)
        val needsAccess = chosen != null && !Readers.hasAccess(chosen)
        panelBox.addView(vbox().apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(vbox().apply {
                addView(text("Reader app", 17f, medium = true))
                addView(text(if (needsAccess) "Needs All files access · tap here to allow" else "Its last book sits at the top of the side panel", 13f,
                    color = if (needsAccess) Ink.INK else Ink.MUTED, bold = needsAccess))
                if (needsAccess) { pressable(); setOnClickListener { runCatching { startActivity(Readers.accessIntent(this@MainActivity)) } } }
            })
            val chips = hbox().apply { setPadding(0, dp(6), 0, 0) }
            addView(chips)
            val current = if (prefs.readerApp == "none") apps.size else apps.indexOf(chosen)
            (apps.map { it.label } + "None").forEachIndexed { i, l ->
                chips.addView(chip(l, i == current) {
                    val app = apps.getOrNull(i)
                    prefs.readerApp = app?.key ?: "none"
                    if (app != null && !Readers.hasAccess(app)) runCatching { startActivity(Readers.accessIntent(this@MainActivity)) }
                    renderAsk(); renderPanel()
                }, lp(WRAP, WRAP).apply { if (i > 0) marginStart = dp(6) })
            }
        })
    }

    /** Keeping the panel on: Clear Memory whitelist and turning the service back on. */
    private fun renderKeepOn() {
        keepBox.removeAllViews()
        val listed = Survival.inWhitelist(this)
        keepBox.addView(toggleRow("Survives Clear Memory",
            if (listed) "InkPalm+ is on Moaan's Clear Memory whitelist"
            else if (Shell.allowed()) "Tap to add InkPalm+ to Moaan's whitelist; Clear Memory would switch the panel off"
            else "Clear Memory would switch the panel off. Start Shizuku, or run: ${Survival.whitelistAdbCommand(this)}", listed) {
            if (!listed) Shell.background({ Survival.addToWhitelist(this) }) { ok -> if (!ok) toast("Needs Shizuku"); renderKeepOn() }
        })
        val repair = Survival.canRepair(this)
        keepBox.addView(toggleRow("Turns itself back on",
            if (repair) "If the panel is ever switched off by mistake, InkPalm+ turns it back on"
            else if (Shell.allowed()) "Tap to let InkPalm+ turn the panel back on if it's ever switched off"
            else "Start Shizuku, or run: ${Survival.grantAdbCommand(this)}", repair) {
            if (!repair) Shell.background({ Survival.grantRepair(this) }) { ok -> if (!ok) toast("Needs Shizuku"); renderKeepOn() }
        })
    }

    // ---- Whole device -------------------------------------------------------------------------

    // ---- Touch button (docs/mockups/10-touch-button.png) ------------------------------------------

    private fun renderTouch() {
        touchBox.removeAllViews()
        touchBox.addView(text("The button below the screen. These are the actions built into the device; changes apply on the next touch.", 14f, color = Ink.MUTED).apply {
            setPadding(dp(16), dp(8), dp(16), dp(4))
        })
        if (!TouchButton.canChange(this)) {
            touchBox.addView(text(if (Shell.allowed()) "Allow it first under Keeping the panel on → Turns itself back on."
                else "Needs the same permission as Keeping the panel on → Turns itself back on. Start Shizuku, or run: ${Survival.grantAdbCommand(this)}", 14f).apply {
                setPadding(dp(16), dp(4), dp(16), dp(8))
            })
            return
        }
        val on = TouchButton.on(this)
        touchBox.addView(toggleRow("Touch button", if (on) "On" else "Off: touching it does nothing", on) {
            TouchButton.setOn(this, !on); renderTouch()
        })
        if (!on) return
        fun options(title: String, sub: String, labels: List<String>, current: Int, perRow: Int, pick: (Int) -> Unit) {
            touchBox.addView(vbox().apply {
                setPadding(dp(16), dp(8), dp(16), dp(2))
                addView(text(title, 17f, medium = true))
                addView(text(sub, 13f, color = Ink.MUTED))
            })
            labels.indices.chunked(perRow).forEach { row ->
                touchBox.addView(hbox().apply {
                    setPadding(dp(12), dp(4), dp(12), dp(4))
                    for (i in row) addView(chip(labels[i], i == current) { pick(i); renderTouch() }.apply { maxLines = 2 },
                        lp(0, WRAP, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                    repeat(perRow - row.size) { addView(View(this@MainActivity), lp(0, 1, 1f)) }
                })
            }
        }
        options("Tap", "A short touch", TouchButton.TAP_ACTIONS, TouchButton.tap(this), 4) { TouchButton.setTap(this, it) }
        options("Hold", "Touch for about a second", TouchButton.HOLD_ACTIONS, TouchButton.hold(this), 3) { TouchButton.setHold(this, it) }
        touchBox.addView(text("For anything else (open an app, switch the panel), pick Page up or Volume up and map that key in Key Mapper.", 13f, color = Ink.MUTED).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
        })
    }

    private fun renderDevice() {
        deviceBox.removeAllViews()
        val live = Live.canNudge(this)
        deviceBox.addView(toggleRow("Live size changes", if (live) "Apps redraw at the new DPI in place" else "Allow \"Modify system settings\" so apps redraw at the new DPI without reopening", live) {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:$packageName")))
        })
        val animOff = ANIMATION_KEYS.all { Settings.Global.getFloat(contentResolver, it, 1f) == 0f }
        deviceBox.addView(toggleRow("Animations off", "Window, transition and animator scales at 0. Less flashing on e-ink.", animOff) { on ->
            val v = if (on) "0" else "1"
            Shell.background({ ANIMATION_KEYS.all { k -> putGlobal(k, v) } }) { ok ->
                if (!ok) toast("Needs Shizuku")
                renderDevice()
            }
        })
        // Refresh helpers (RefreshHelpers); an app's own page can override them.
        val prefs = Prefs(this)
        for ((key, title, detail) in Prefs.HELPERS) {
            val on = prefs.helperDefault(key)
            deviceBox.addView(toggleRow(title, detail, on) { prefs.setHelperDefault(key, !on); renderDevice() })
        }
        // Font: Android's own fonts, applied live (Fonts). Heavier ones hold up better at 16 grays.
        val fonts = Fonts.list(this)
        val now = Fonts.current()
        val nowTitle = fonts.firstOrNull { it.path == now }?.title ?: "Moaan font"
        deviceBox.addView(hbox().apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            pressable()
            addView(vbox().apply {
                addView(text("Font", 17f, medium = true))
                addView(text("Every app's text. Heavier fonts hold up better on e-ink.", 13f, color = Ink.MUTED))
            }, lp(0, WRAP, 1f))
            addView(text(nowTitle + if (fontsOpen) "  ▴" else "  ▾", 15f, bold = true))
            setOnClickListener { fontsOpen = !fontsOpen; renderDevice() }
        })
        if (fontsOpen) for (f in fonts) {
            val on = f.path == now
            deviceBox.addView(hbox().apply {
                setPadding(dp(24), dp(6), dp(16), dp(6))
                pressable()
                addView(text(if (on) "●" else "○", 16f, bold = true), lp(dp(28), WRAP))
                addView(text(f.title, 13f, bold = on, medium = !on), lp(dp(120), WRAP))
                addView(text("The quick brown fox jumps", 17f).apply {
                    Fonts.typeface(f)?.let { typeface = it }
                    maxLines = 1
                }, lp(0, WRAP, 1f))
                setOnClickListener {
                    Shell.background({ Fonts.apply(this@MainActivity, f) }) { ok ->
                        if (!ok) toast("Couldn't change the font")
                        renderDevice()
                    }
                }
            })
        }
        val scale = Settings.System.getFloat(contentResolver, Settings.System.FONT_SCALE, 1f)
        deviceBox.addView(stepperRow("Text size", "%.2f×".format(scale),
            onMinus = { setFontScale((scale - 0.05f).coerceAtLeast(0.7f)) },
            onPlus = { setFontScale((scale + 0.05f).coerceAtMost(1.6f)) }))
    }

    private fun putGlobal(key: String, value: String): Boolean =
        runCatching { Settings.Global.putString(contentResolver, key, value) }.getOrDefault(false) ||
            Shell.run("settings put global $key $value").first == 0

    private fun setFontScale(v: Float) {
        val value = "%.2f".format(java.util.Locale.US, v)
        Shell.background({
            runCatching { Settings.System.canWrite(this) && Settings.System.putFloat(contentResolver, Settings.System.FONT_SCALE, value.toFloat()) }.getOrDefault(false) ||
                Shell.run("settings put system font_scale $value").first == 0
        }) { ok ->
            if (!ok) toast("Needs Shizuku")
            renderDevice()
        }
    }

    // ---- Apps ---------------------------------------------------------------------------------

    private fun renderApps() {
        appsBox.removeAllViews()
        val shown = apps.filter { filter.isEmpty() || it.label.contains(filter, true) || it.pkg.contains(filter, true) }
            // Tuned apps first, then alphabetical.
            .sortedBy { if (profiles[it.pkg]?.isDefault == false) 0 else 1 }
        for (app in shown) appsBox.addView(appRow(app, profiles[app.pkg]))
        if (shown.isEmpty()) appsBox.addView(text("No apps match", 15f, color = Ink.MUTED).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) })
    }

    private fun appRow(app: AppEntry, p: EinkDb.Profile?): View = hbox().apply {
        setPadding(dp(16), dp(10), dp(16), dp(10))
        pressable()
        addView(icon(app.icon, 40, tint = false))
        addView(vbox().apply {
            addView(text(app.label, 17f, medium = true))
            val summary = p?.takeIf { !it.isDefault }?.summary()
            addView(text(summary ?: "Default", 13f, bold = summary != null, color = if (summary != null) Ink.INK else Ink.MUTED))
        }, lp(0, WRAP, 1f).apply { marginStart = dp(14) })
        addView(icon(getDrawable(android.R.drawable.ic_media_play), 18))
        setOnClickListener {
            startActivity(Intent(this@MainActivity, AppActivity::class.java).putExtra(AppActivity.EXTRA_PKG, app.pkg))
        }
    }

    // ---- Rows ---------------------------------------------------------------------------------

    private fun toggleRow(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit): View = hbox().apply {
        setPadding(dp(16), dp(10), dp(16), dp(10))
        pressable()
        addView(vbox().apply {
            addView(text(title, 17f, medium = true))
            addView(text(detail, 13f, color = Ink.MUTED))
        }, lp(0, WRAP, 1f))
        addView(chip(if (on) "On" else "Off", on) { onChange(!on) })
        setOnClickListener { onChange(!on) }
    }

    private fun stepperRow(title: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit): View = hbox().apply {
        setPadding(dp(16), dp(10), dp(16), dp(10))
        addView(text(title, 17f, medium = true), lp(0, WRAP, 1f))
        addView(inkButton("−", 20f) { onMinus() }, lp(dp(52), WRAP))
        addView(text(value, 17f, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(72)
        })
        addView(inkButton("+", 20f) { onPlus() }, lp(dp(52), WRAP))
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_SECTION = "section"
        const val SECTION_EINK = "eink"
        const val SECTION_TOUCH = "touch"
        const val SECTION_KEEP_ON = "keep_on"

        val ANIMATION_KEYS = listOf(
            Settings.Global.WINDOW_ANIMATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            Settings.Global.ANIMATOR_DURATION_SCALE,
        )
    }
}

/** Outlined choice button; filled black when selected. */
fun android.content.Context.chip(label: String, selected: Boolean, onClick: () -> Unit): TextView =
    inkButton(label, 15f, onClick).apply {
        if (selected) {
            background = outline(2, 6, Ink.INK)
            setTextColor(Ink.PAPER)
        }
        minWidth = dp(56)
    }

/**
 * The home screen's sub-screens (E-ink settings, Touch button, Keeping the panel on): the same screen code,
 * as its own activity because the home screen is single-task and would swallow the intent.
 */
class SectionActivity : MainActivity()
