package com.inkpalmplus

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

/**
 * Home: a short list of categories, each a sub-screen ([SectionActivity]) with the back-button header:
 * Side panel (with Keeping the panel on), Screen & e-ink (KompaktOS: screen modes; stock: per-app e-ink and the app
 * list), Front light, Sleep & power (sleep screen faces, power-off screen, auto power-off), Reading, Buttons, About.
 * The sleep screen has its own screen (mockup 21). Tap an app to tune it ([AppActivity]).
 */
open class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var accessBox: LinearLayout
    private lateinit var deviceBox: LinearLayout
    private lateinit var touchBox: LinearLayout
    private lateinit var appsBox: LinearLayout
    /** The section's own rows (every screen but E-ink, which keeps its search field across reloads). */
    private lateinit var body: LinearLayout
    private var filter = ""
    private var fontsOpen = false
    private var apps: List<AppEntry> = emptyList()
    private var profiles: Map<String, EinkDb.Profile> = emptyMap()

    /** Icon loaded with the list, off the main thread: ~100 icons are slow to decode. */
    data class AppEntry(val pkg: String, val label: String, val icon: android.graphics.drawable.Drawable?)

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> reload() }
    private val binderListener = Shizuku.OnBinderReceivedListener { Shell.main.post { reload() } }

    /** Which screen this is: null for home, else one of the SECTION_ names. */
    private val section: String? get() = intent.getStringExtra(EXTRA_SECTION)
    private val askBox by lazy { vbox() }
    /** The first-start question's pick so far: a reader, or null for No reader ([askPicked] says whether one was tapped). */
    private var askPick: Readers.App? = null
    private var askPicked = false

    private val g get() = dp(Space.GUTTER)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = vbox()
        accessBox = vbox(); deviceBox = vbox(); touchBox = vbox(); appsBox = vbox(); body = vbox()
        val (title, sub) = when (section) {
            SECTION_PANEL, SECTION_KEEP_ON -> "Side panel" to "Recent apps along the screen edge"
            SECTION_EINK -> "Screen & e-ink" to "Per-app DPI, refresh, contrast, presets, font and refresh helpers"
            SECTION_SCREEN -> "Screen & e-ink" to "Screen modes and refresh"
            SECTION_LIGHT -> "Front light" to "How the light follows Android"
            SECTION_SLEEP -> "Sleep & power" to "Sleep screen, power-off screen, auto power-off"
            SECTION_AOD -> "Sleep screen" to "What the screen shows while the phone is off"
            SECTION_READING -> "Reading" to "Your reader app and its last book"
            SECTION_TOUCH -> "Buttons" to "The touch button below the screen"
            SECTION_ABOUT -> "About" to "Version, permissions and privacy"
            else -> getString(R.string.app_name) to "A side panel of recent apps for the InkPalm Plus"
        }
        root.addView(hbox().apply {
            // The back button lines up with the content (the gutter).
            setPadding(g, dp(if (section != null) 12 else 16), g, dp(4))
            if (section != null) addView(inkIconButton(R.drawable.ic_back, "Back", 48) { finish() }, lp(dp(48), dp(48)).apply { marginEnd = dp(10) })
            addView(text(title, Type.XXL, bold = true), lp(0, WRAP, 1f))
        })
        root.addView(text(sub, Type.M, color = Ink.MUTED).apply { setPadding(g, dp(4), g, dp(8)) })
        when (section) {
            SECTION_EINK -> {
                root.addView(accessBox)
                root.addView(sectionTitle("Whole device"))
                root.addView(deviceBox)
                root.addView(sectionTitle("Apps"))
                root.addView(EditText(this).apply {
                    hint = "Search apps"
                    textSize = Type.L
                    setTextColor(Ink.INK)
                    setSingleLine()
                    background = outline(2, Radius.SMALL)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            filter = s?.toString().orEmpty().trim()
                            renderApps()
                        }
                    })
                }, lp(MATCH, WRAP).apply { setMargins(g, dp(8), g, dp(4)) })
                root.addView(appsBox)
            }
            SECTION_TOUCH -> root.addView(touchBox)
            null -> { root.addView(askBox); root.addView(body) }
            else -> root.addView(body)
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
            render(via)
        }
    }

    private fun render(via: EinkDb.Via? = null) {
        when (section) {
            SECTION_EINK -> { renderAccess(via); renderDevice(); renderApps() }
            SECTION_TOUCH -> renderTouch()
            SECTION_PANEL, SECTION_KEEP_ON -> renderPanel()
            SECTION_SCREEN -> renderScreen()
            SECTION_LIGHT -> renderLight()
            SECTION_SLEEP -> renderSleep()
            SECTION_AOD -> renderAod()
            SECTION_READING -> renderReading()
            SECTION_ABOUT -> renderAbout()
            else -> { renderAsk(); renderHome() }
        }
    }

    private fun openSection(name: String) = startActivity(Intent(this, SectionActivity::class.java).putExtra(EXTRA_SECTION, name))

    /** A row that opens a sub-screen: title, what's set there, and a ▶. */
    private fun navRow(title: String, detail: String, name: String): View = hbox().apply {
        setPadding(g, dp(12), g, dp(12))
        minimumHeight = dp(64)
        pressable()
        addView(vbox().apply {
            addView(text(title, Type.XL, medium = true))
            addView(text(detail, Type.S, color = Ink.MUTED))
        }, lp(0, WRAP, 1f).apply { marginEnd = dp(12) })
        addView(icon(getDrawable(android.R.drawable.ic_media_play), 18))
        setOnClickListener { openSection(name) }
    }

    /** Home: one row per category, with what's set there. */
    private fun renderHome() {
        body.removeAllViews()
        body.addView(View(this), lp(MATCH, dp(8)))
        val prefs = Prefs(this)
        val on = QuickPanelService.enabled(this)
        body.addView(navRow("Side panel", if (!on) "Off · turn it on in Accessibility"
            else "On · ${if (prefs.edgeRight) "right" else "left"} edge · ${prefs.recentKept} apps", SECTION_PANEL))
        body.addView(divider())
        if (Eink.isKompakt) body.addView(navRow("Screen & e-ink", "${ScreenModes.global(this).title} everywhere · refresh", SECTION_SCREEN))
        else body.addView(navRow("Screen & e-ink", "Per-app DPI, refresh, contrast, presets, font, refresh helpers", SECTION_EINK))
        if (Light.supported()) {
            body.addView(divider())
            body.addView(navRow("Front light", if (Light.followSystem(this)) "Follows Android's brightness" else "Set in the screen panel", SECTION_LIGHT))
        }
        if (SleepImages.supported()) {
            body.addView(divider())
            val f = Aod.face(this)
            val sleep = if (f == Aod.Face.DEFAULT) "Moaan's" else f.title + if (f.drawn) " · ${updateLabel(Aod.interval(this))}" else ""
            val auto = if (AutoOff.supported()) " · auto off ${AutoOff.hours(this).let { if (it == 0) "never" else "$it h" }}" else ""
            body.addView(navRow("Sleep & power", "Sleep screen: $sleep · power-off: ${SleepImages.powerOff(this).title}$auto", SECTION_SLEEP))
        }
        body.addView(divider())
        val reader = Readers.chosen(this)?.label ?: if (Readers.installedApps(this).isEmpty()) "No reader installed" else "No reader"
        body.addView(navRow("Reading", "Reader app: $reader", SECTION_READING))
        body.addView(divider())
        val touch = if (Eink.isKompakt) "Tap: Home · Hold: ${if (prefs.holdRefresh) "Full refresh" else "nothing"}"
            else if (!TouchButton.canChange(this)) "Needs a permission" else if (!TouchButton.on(this)) "Off"
            else "Tap: ${TouchButton.TAP_ACTIONS[TouchButton.tap(this)]} · Hold: ${TouchButton.HOLD_ACTIONS[TouchButton.hold(this)]}"
        body.addView(navRow("Buttons", "Touch button · $touch", SECTION_TOUCH))
        body.addView(divider())
        body.addView(navRow("About", "Version ${version()} · no internet permission", SECTION_ABOUT))
    }

    private fun version() = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

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
            background = outline(2)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        accessBox.addView(box, lp(MATCH, WRAP).apply { setMargins(g, dp(8), g, dp(4)) })
        fun line(s: String, bold: Boolean = false) = box.addView(text(s, if (bold) Type.L else Type.M, bold = bold, color = if (bold) Ink.INK else Ink.MUTED))
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

    // ---- Rows shared by the sections ------------------------------------------------------------

    /** Label on the left, choice chips on the right. */
    private fun choicesRow(title: String, detail: String, labels: List<String>, current: Int, pick: (Int) -> Unit): View = hbox().apply {
        setPadding(g, dp(10), g, dp(10))
        addView(vbox().apply {
            addView(text(title, Type.L, medium = true))
            if (detail.isNotEmpty()) addView(text(detail, Type.S, color = Ink.MUTED))
        }, lp(0, WRAP, 1f))
        labels.forEachIndexed { i, l -> addView(chip(l, i == current) { pick(i) }.apply { maxLines = 1 }, lp(WRAP, WRAP).apply { marginStart = dp(6) }) }
    }

    /** Label above, chips sharing the width below (for longer or more choices). */
    private fun choicesBlock(title: String, detail: String, labels: List<String>, current: Int, pick: (Int) -> Unit): View = vbox().apply {
        setPadding(g, dp(10), g, dp(10))
        if (title.isNotEmpty()) addView(text(title, Type.L, medium = true))
        if (detail.isNotEmpty()) addView(text(detail, Type.S, color = Ink.MUTED))
        addView(hbox().apply {
            setPadding(0, dp(6), 0, 0)
            labels.forEachIndexed { i, l -> addView(chip(l, i == current) { pick(i) }.apply { maxLines = 1; minWidth = 0; setPadding(dp(4), dp(8), dp(4), dp(8)) },
                lp(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(6) }) }
        })
    }

    private fun note(s: String): View = text(s, Type.M, color = Ink.MUTED).apply { setPadding(g, dp(8), g, dp(4)) }

    // ---- Side panel -----------------------------------------------------------------------------

    private fun renderPanel() {
        body.removeAllViews()
        body.addView(note("Swipe up or down along the screen edge in any app for your recent apps."))
        val on = QuickPanelService.enabled(this)
        body.addView(toggleRow("Side panel", if (on) "On" else "Turn on InkPalm+ in Accessibility", on) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        val prefs = Prefs(this)
        fun previewStrip() = sendBroadcast(Intent(QuickPanelService.ACTION_STRIP).setPackage(packageName).putExtra(QuickPanelService.EXTRA_PREVIEW, true))
        body.addView(choicesRow("Newest app", "Where the app you're in sits in the list", listOf("Top", "Bottom"), if (prefs.newestAtBottom) 1 else 0) {
            prefs.newestAtBottom = it == 1; renderPanel()
        })
        body.addView(choicesRow("Strip side", if (Eink.isKompakt) "Where you swipe for recent apps" else "Where you swipe", listOf("Left", "Right"), if (prefs.edgeRight) 1 else 0) {
            prefs.edgeRight = it == 1; previewStrip(); renderPanel()
        })
        if (Eink.isKompakt) body.addView(toggleRow("Screen & light panel",
            "Swipe along the ${if (prefs.edgeRight) "left" else "right"} edge for screen modes and the front light", prefs.screenPanel) {
            prefs.screenPanel = !prefs.screenPanel; previewStrip(); renderPanel()
        })
        body.addView(toggleRow("Edge Back", if (Eink.isKompakt) "Swipe in from either edge to go back (KompaktOS has no back gesture)"
            else "Swipe in from either edge to go back. Android's own back gesture already does this here", prefs.edgeBack) {
            prefs.edgeBack = !prefs.edgeBack; previewStrip(); renderPanel()
        })
        val w = prefs.stripWidthDp
        body.addView(stepperRow("Strip width", "$w dp",
            onMinus = { prefs.stripWidthDp = w - 2; previewStrip(); renderPanel() },
            onPlus = { prefs.stripWidthDp = w + 2; previewStrip(); renderPanel() }))
        body.addView(toggleRow("Show strip", "A thin black line where you swipe", prefs.showStrip) {
            prefs.showStrip = !prefs.showStrip
            sendBroadcast(Intent(QuickPanelService.ACTION_STRIP).setPackage(packageName))
            renderPanel()
        })
        val widths = listOf(66, 78, 90)
        body.addView(choicesRow("Panel width", "How much of the screen it covers", widths.map { "$it%" }, widths.indexOf(prefs.panelWidthPct)) {
            prefs.panelWidthPct = widths[it]; renderPanel()
        })
        val kept = listOf(9, 18, 27)
        body.addView(choicesRow("Apps kept", "Recent apps the panel remembers", kept.map { "$it" }, kept.indexOf(prefs.recentKept)) {
            prefs.recentKept = kept[it]; renderPanel()
        })
        body.addView(hbox().apply {
            setPadding(g, dp(10), g, dp(10))
            addView(vbox().apply {
                addView(text("Clear recent apps", Type.L, medium = true))
                addView(text("Start the list again", Type.S, color = Ink.MUTED))
            }, lp(0, WRAP, 1f))
            addView(inkButton("Clear") {
                getSharedPreferences("recent_apps", MODE_PRIVATE).edit().clear().apply()
                sendBroadcast(Intent(QuickPanelService.ACTION_CLEAR_RECENTS).setPackage(packageName))
                toast("Recent apps cleared")
            })
        })
        // Keeping the panel on: Clear Memory whitelist and turning the service back on.
        body.addView(sectionTitle("Keeping the panel on"))
        body.addView(note("So the side panel survives Clear Memory and restarts. Both are optional."))
        val listed = Survival.inWhitelist(this)
        body.addView(toggleRow("Survives Clear Memory",
            if (listed) "InkPalm+ is on Moaan's Clear Memory whitelist"
            else if (Shell.allowed()) "Tap to add InkPalm+ to Moaan's whitelist; Clear Memory would switch the panel off"
            else "Clear Memory would switch the panel off. Start Shizuku, or run: ${Survival.whitelistAdbCommand(this)}", listed) {
            if (!listed) Shell.background({ Survival.addToWhitelist(this) }) { ok -> if (!ok) toast("Needs Shizuku"); renderPanel() }
        })
        val repair = Survival.canRepair(this)
        body.addView(toggleRow("Turns itself back on",
            if (repair) "If the panel is ever switched off by mistake, InkPalm+ turns it back on"
            else if (Shell.allowed()) "Tap to let InkPalm+ turn the panel back on if it's ever switched off"
            else "Start Shizuku, or run: ${Survival.grantAdbCommand(this)}", repair) {
            if (!repair) Shell.background({ Survival.grantRepair(this) }) { ok -> if (!ok) toast("Needs Shizuku"); renderPanel() }
        })
    }

    // ---- Reader app (docs/mockups/14-reader-app.png) -------------------------------------------

    /** First start: which reader's last book the side panel shows. Only installed readers; skipped with none. */
    private fun renderAsk() {
        askBox.removeAllViews()
        val apps = Readers.installedApps(this)
        if (Prefs(this).readerApp != null || apps.isEmpty()) return
        askBox.addView(vbox().apply {
            background = outline(3, Radius.LARGE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(text("Which reader do you use?", Type.XL, bold = true))
            addView(text("The side panel keeps the last book you read in it one tap away. You can change this later.", Type.S, color = Ink.MUTED)
                .apply { setPadding(0, dp(2), 0, dp(8)) })
            fun option(app: Readers.App?, sub: String) {
                val on = askPicked && askPick == app
                addView(hbox().apply {
                    pressable(outline(if (on) 3 else 2))
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    gravity = Gravity.CENTER_VERTICAL
                    addView(vbox().apply {
                        addView(text(app?.label ?: "No reader", Type.L, bold = true))
                        addView(text(sub, Type.S, color = Ink.MUTED))
                    }, lp(0, WRAP, 1f))
                    addView(text(if (on) "●" else "○", Type.XL, bold = true))
                    setOnClickListener { askPick = app; askPicked = true; renderAsk() }
                }, lp(MATCH, WRAP).apply { bottomMargin = dp(7) })
            }
            for (app in apps) option(app, if (Readers.hasAccess(app)) "Installed · works as is" else "Installed · asks once for All files access")
            option(null, "No book card in the panel")
            addView(inkButton("Continue", Type.L) {
                if (!askPicked) return@inkButton
                Prefs(this@MainActivity).readerApp = askPick?.key ?: "none"
                askPick?.let { if (!Readers.hasAccess(it)) runCatching { startActivity(Readers.accessIntent(this@MainActivity)) } }
                renderAsk(); renderHome()
            }.apply { isEnabled = askPicked }, lp(MATCH, WRAP))
        }, lp(MATCH, WRAP).apply { setMargins(g, dp(8), g, dp(8)) })
    }

    /** Reading: the reader app (NeoReader · KOReader · None; installed readers only), chips on the right. */
    private fun renderReading() {
        body.removeAllViews()
        val prefs = Prefs(this)
        val apps = Readers.installedApps(this)
        if (apps.isEmpty()) { body.addView(note("No reader app is installed. NeoReader and KOReader are supported.")); return }
        val chosen = Readers.chosen(this)
        val needsAccess = chosen != null && !Readers.hasAccess(chosen)
        val current = if (prefs.readerApp == "none") apps.size else apps.indexOf(chosen)
        body.addView(choicesRow("Reader", if (needsAccess) "Needs All files access" else "",
            apps.map { it.label } + "None", current) { i ->
            val app = apps.getOrNull(i)
            prefs.readerApp = app?.key ?: "none"
            if (app != null && !Readers.hasAccess(app)) runCatching { startActivity(Readers.accessIntent(this@MainActivity)) }
            renderReading()
        })
        if (needsAccess) body.addView(hbox().apply {
            setPadding(g, dp(4), g, dp(10))
            addView(text("${chosen!!.label} keeps its history in shared storage, so InkPalm+ needs All files access to read it.", Type.S, bold = true), lp(0, WRAP, 1f))
            addView(inkButton("Allow") { runCatching { startActivity(Readers.accessIntent(this@MainActivity)) } }, lp(WRAP, WRAP).apply { marginStart = dp(8) })
        })
        body.addView(note("Its last book sits in the side panel, one tap away, and on the Reading sleep screen."))
        body.addView(note("Covers on the Reading sleep screen come from the book file (EPUB) and need All files access too."))
    }

    // ---- Screen & e-ink (KompaktOS; from InkControl) ----------------------------------------------

    private fun renderScreen() {
        body.removeAllViews()
        val status = vbox().apply {
            background = outline(2)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(text("E-ink control: ${Eink.describe()}", Type.L, bold = true))
            addView(text(if (Eink.bridgeAvailable()) "Front light, sleep and power-off screens go through KompaktOS's bridge." else "No KompaktOS bridge: front light, sleep and power-off screens aren't available.", Type.M, color = Ink.MUTED))
            if (Light.inkControlInstalled(this@MainActivity)) {
                addView(text("InkControl is still installed. InkPalm+ does everything it did now; uninstall InkControl so the two don't both drive the screen and light.", Type.M, bold = true)
                    .apply { setPadding(0, dp(8), 0, 0) })
                addView(inkButton("Uninstall InkControl") {
                    runCatching { startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:org.neoport.inkcontrol"))) }
                }, lp(WRAP, WRAP).apply { topMargin = dp(8) })
            }
        }
        body.addView(status, lp(MATCH, WRAP).apply { setMargins(g, dp(8), g, dp(4)) })
        body.addView(note("Swipe along the ${if (Prefs(this).edgeRight) "left" else "right"} edge for screen modes, full refresh, contrast and the light. A mode can be for one app or everywhere."))
        body.addView(sectionTitle("Screen"))
        if (KompaktSync.get(filesDir) != null) body.addView(toggleRow("Follow KompaktOS's per-app modes", "Apps you set in KompaktOS's settings use that mode, unless you pick one here", ScreenModes.followKompakt(this)) {
            ScreenModes.setFollowKompakt(this, !ScreenModes.followKompakt(this)); renderScreen()
        })
        body.addView(toggleRow("Real refresh for KompaktOS's Refresh", "KompaktOS's own refresh tile only flashes black here; this adds a full refresh", ScreenModes.flashOnKompaktRefresh(this)) {
            ScreenModes.setFlashOnKompaktRefresh(this, !ScreenModes.flashOnKompaktRefresh(this)); renderScreen()
        })
    }

    // ---- Front light ----------------------------------------------------------------------------

    private fun renderLight() {
        body.removeAllViews()
        if (!Light.supported()) { body.addView(note("No front light control on this firmware.")); return }
        body.addView(note("Brightness, Warmth and Auto are in the screen panel: swipe along the ${if (Prefs(this).edgeRight) "left" else "right"} edge."))
        body.addView(toggleRow("Follows Android's brightness", "The brightness slider and adaptive brightness dim the light too", Light.followSystem(this)) {
            Light.setFollowSystem(this, !Light.followSystem(this)); renderLight()
        })
        body.addView(note("The light goes off with the screen and comes back with it. It stays off while the sleep screen updates."))
    }

    // ---- Sleep & power ----------------------------------------------------------------------------

    private fun updateLabel(minutes: Int) = if (minutes <= 0) "Frozen" else "every $minutes min"

    private fun renderSleep() {
        body.removeAllViews()
        if (!SleepImages.supported()) { body.addView(note("Needs KompaktOS's bridge.")); return }
        val f = Aod.face(this)
        body.addView(navRow("Sleep screen", when {
            f == Aod.Face.DEFAULT -> "Moaan's picture"
            f.drawn -> "${f.title} · ${updateLabel(Aod.interval(this)).replaceFirstChar { it.uppercase() }}"
            else -> "Your picture"
        }, SECTION_AOD))
        body.addView(divider())
        val off = SleepImages.PowerOff.entries
        body.addView(choicesBlock("Power-off screen", "What stays on the screen when it's off. Panda: Moaan's panda in English", off.map { it.title + if (it == SleepImages.PowerOff.CUSTOM) "…" else "" },
            off.indexOf(SleepImages.powerOff(this))) { i ->
            if (off[i] == SleepImages.PowerOff.CUSTOM) pickImage(PICK_POWEROFF)
            else work({ SleepImages.setPowerOff(this, off[i]) }, "Power-off screen: ${off[i].title}")
        })
        if (AutoOff.supported()) {
            val hours = AutoOff.HOURS
            body.addView(choicesBlock("Auto power-off", "After this long asleep on battery, like the stock firmware", hours.map { if (it == 0) "Never" else "$it h" },
                hours.indexOf(AutoOff.hours(this)).coerceAtLeast(0)) { i -> AutoOff.setHours(this, hours[i]); renderSleep() })
        }
    }

    // ---- Sleep screen (docs/mockups/21-aod-settings.png) --------------------------------------------

    /** Face thumbnails, drawn with today's data off the main thread; kept while the screen is open. */
    private var thumbs: Map<Aod.Face, Bitmap?>? = null

    private fun renderAod() {
        body.removeAllViews()
        if (!Aod.supported()) { body.addView(note("Needs KompaktOS's bridge.")); return }
        val face = Aod.face(this)
        body.addView(sectionTitle("Face"))
        val t = thumbs
        if (t == null) Shell.background({ Aod.Face.entries.associateWith { Aod.thumbnail(this, it) } }) { thumbs = it; renderAod() }
        val faces = Aod.Face.entries
        faces.chunked(4).forEach { row ->
            body.addView(hbox().apply {
                setPadding(dp(12), dp(4), dp(12), dp(4))
                gravity = Gravity.TOP
                for (f in row) addView(faceTile(f, f == face, t?.get(f)), lp(0, WRAP, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                repeat(4 - row.size) { addView(View(this@MainActivity), lp(0, 1, 1f).apply { marginStart = dp(4); marginEnd = dp(4) }) }
            })
        }
        if (face == Aod.Face.PHOTO || face == Aod.Face.PICTURE) body.addView(hbox().apply {
            setPadding(g, dp(8), g, dp(4))
            addView(text(if (Aod.hasPicture(this@MainActivity)) "Your picture, in gray" else "No picture yet: the Photo face shows the clock and date", Type.S, color = Ink.MUTED), lp(0, WRAP, 1f))
            addView(inkButton("Choose picture…") { pickImage(PICK_SLEEP) }, lp(WRAP, WRAP).apply { marginStart = dp(8) })
        })
        if (face == Aod.Face.AGENDA && Aod.needsCalendar(this)) body.addView(vbox().apply {
            background = outline(3)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(text("Calendar access needed", Type.L, bold = true))
            addView(text("The Agenda face shows your next 3 events. Without access it says so on the sleep screen.", Type.S, color = Ink.MUTED))
            addView(inkButton("Allow") { askCalendar(fromSettings = true) }, lp(WRAP, WRAP).apply { topMargin = dp(8) })
        }, lp(MATCH, WRAP).apply { setMargins(g, dp(8), g, dp(4)) })

        body.addView(sectionTitle("Update"))
        val minutes = Aod.interval(this)
        val ints = Aod.INTERVALS
        body.addView(choicesBlock("", "", ints.map { if (it == 0) "Frozen" else "$it min" }, ints.indexOf(minutes).coerceAtLeast(0)) { i ->
            Aod.setInterval(this, ints[i]); renderAod()
        })
        val (costTitle, costDetail) = Aod.cost(minutes)
        body.addView(vbox().apply {
            background = outline(3)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(text(costTitle, Type.L, bold = true))
            addView(text(costDetail, Type.M, color = Ink.MUTED))
            if (!face.drawn) addView(text("Updates apply to the drawn faces, not to Default or Picture.", Type.S, bold = true).apply { setPadding(0, dp(6), 0, 0) })
            if (minutes > 0) addView(text("Live faces show a small \"Updated 10:15\" stamp. Below ${Aod.LOW_BATTERY}% the face shows a battery box and updates stop. They carry on while charging.", Type.S, color = Ink.MUTED).apply { setPadding(0, dp(6), 0, 0) })
        }, lp(MATCH, WRAP).apply { setMargins(g, dp(4), g, dp(8)) })
        body.addView(divider())
        body.addView(infoRow("Show seconds", "Never. A panel refresh every second would drain the battery.", "Never", filled = false))
        body.addView(divider())
        body.addView(infoRow("Frontlight", "Always off during an update, so nothing glows in your pocket.", "Off", filled = true))
        body.addView(divider())
        body.addView(note("In landscape the face is drawn sideways to match. The face is redrawn every minute while the screen is on, so it's ready when you press the power button."))
    }

    /** A fixed answer on the right (mockup 21: Show seconds, Frontlight). */
    private fun infoRow(title: String, detail: String, value: String, filled: Boolean): View = hbox().apply {
        setPadding(g, dp(10), g, dp(10))
        addView(vbox().apply {
            addView(text(title, Type.L, bold = true))
            addView(text(detail, Type.S, color = Ink.MUTED))
        }, lp(0, WRAP, 1f).apply { marginEnd = dp(12) })
        addView(text(value, Type.M, bold = true, color = if (filled) Ink.PAPER else Ink.MUTED).apply {
            background = if (filled) outline(2, Radius.SMALL, Ink.INK) else outline(2, Radius.SMALL)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        })
    }

    private fun faceTile(f: Aod.Face, selected: Boolean, thumb: Bitmap?): View = vbox().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        val frame = FrameLayout(this@MainActivity).apply {
            background = outline(if (selected) 4 else 2, Radius.SMALL)
            val pad = dp(if (selected) 4 else 2)
            setPadding(pad, pad, pad, pad)
            if (thumb != null) addView(ImageView(this@MainActivity).apply { setImageBitmap(thumb); scaleType = ImageView.ScaleType.FIT_XY },
                FrameLayout.LayoutParams(MATCH, MATCH))
            else addView(text(if (f == Aod.Face.DEFAULT) "Moaan" else if (f == Aod.Face.PICTURE) "Choose…" else "…", Type.M, bold = true).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(MATCH, MATCH))
            if (selected) addView(text("✓", Type.L, bold = true, color = Ink.PAPER).apply {
                background = outline(2, Radius.LARGE, Ink.INK); gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END))
        }
        addView(frame, lp(MATCH, 0).apply { height = dp(150) })
        addView(text(f.title, Type.M, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(4)) }, lp(MATCH, WRAP))
        pressable()
        contentDescription = "Face ${f.title}"
        setOnClickListener { pickFace(f) }
    }

    private fun pickFace(f: Aod.Face) {
        when {
            f == Aod.Face.PICTURE && !Aod.hasPicture(this) -> { pickImage(PICK_SLEEP); return }
            f == Aod.Face.AGENDA && Aod.needsCalendar(this) -> askCalendar(fromSettings = false)
        }
        work({ Aod.setFace(this, f) }, "Sleep screen: ${f.title}")
    }

    /** READ_CALENDAR, asked the first time Agenda is chosen. Once denied for good, Android's app settings. */
    private fun askCalendar(fromSettings: Boolean) {
        if (fromSettings && !shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR) &&
            Prefs(this).sp.getBoolean("calendar_asked", false)) {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
            return
        }
        Prefs(this).sp.edit().putBoolean("calendar_asked", true).apply()
        requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), ASK_CALENDAR)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != ASK_CALENDAR) return
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) toast("Calendar access needed for the Agenda face")
        thumbs = null
        Aod.prepare(this, force = true)
        if (section == SECTION_AOD) renderAod()
    }

    // ---- About ------------------------------------------------------------------------------------

    private fun renderAbout() {
        body.removeAllViews()
        body.addView(infoRow("Version", packageName, version(), filled = false))
        body.addView(divider())
        body.addView(note("No internet permission: nothing leaves the phone. Not affiliated with Moaan."))
        body.addView(note("The side panel's accessibility service only learns which app is in front, and that a page scrolled, text was typed or the screen changed. It doesn't read what's on screen or what you type."))
        body.addView(note("E-ink control: ${Eink.describe()}."))
        body.addView(note("Source and help: github.com/rn-os/inkpalm-plus"))
    }

    private fun pickImage(request: Int) = runCatching {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), request)
    }.onFailure { toast("No file picker") }

    /** Runs a blocking bridge step ([job] returns an error or null), then says how it went. */
    private fun work(job: () -> String?, done: String) = Eink.post {
        val err = job()
        Shell.main.post { toast(err?.let { "Didn't work: $it" } ?: done); if (section != null && section != SECTION_EINK && section != SECTION_TOUCH) { thumbs = if (section == SECTION_AOD) thumbs else null; render() } }
    }

    @Deprecated("Activity result API isn't used in this app (no AndroidX)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            PICK_SLEEP -> work({ SleepImages.setSleep(this, uri).also { thumbs = null } }, "Sleep screen picture set")
            PICK_POWEROFF -> work({ SleepImages.setPowerOff(this, SleepImages.PowerOff.CUSTOM, uri) }, "Power-off screen set")
        }
    }

    // ---- Whole device -------------------------------------------------------------------------

    // ---- Touch button (docs/mockups/10-touch-button.png) ------------------------------------------

    private fun renderTouch() {
        touchBox.removeAllViews()
        if (Eink.isKompakt) {
            // KompaktOS: the button is Home (Moaan's settings for it are gone); InkPalm+ can add the stock hold.
            touchBox.addView(text("The button below the screen is Home. Holding it can do a full refresh, as on the stock firmware.", Type.M, color = Ink.MUTED).apply {
                setPadding(g, dp(8), g, dp(4))
            })
            val prefs = Prefs(this)
            touchBox.addView(toggleRow("Hold for a full refresh", if (QuickPanelService.enabled(this)) "A short touch is still Home"
                else "Needs the side panel on (Accessibility)", prefs.holdRefresh) {
                prefs.holdRefresh = !prefs.holdRefresh
                sendBroadcast(Intent(QuickPanelService.ACTION_STRIP).setPackage(packageName))
                renderTouch()
            })
            return
        }
        touchBox.addView(text("The button below the screen. These are the actions built into the device; changes apply on the next touch.", Type.M, color = Ink.MUTED).apply {
            setPadding(g, dp(8), g, dp(4))
        })
        if (!TouchButton.canChange(this)) {
            touchBox.addView(text(if (Shell.allowed()) "Allow it first under Keeping the panel on → Turns itself back on."
                else "Needs the same permission as Keeping the panel on → Turns itself back on. Start Shizuku, or run: ${Survival.grantAdbCommand(this)}", Type.M).apply {
                setPadding(g, dp(4), g, dp(8))
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
                setPadding(g, dp(8), g, dp(2))
                addView(text(title, Type.L, medium = true))
                addView(text(sub, Type.S, color = Ink.MUTED))
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
        touchBox.addView(text("For anything else (open an app, switch the panel), pick Page up or Volume up and map that key in Key Mapper.", Type.S, color = Ink.MUTED).apply {
            setPadding(g, dp(8), g, dp(8))
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
            setPadding(g, dp(10), g, dp(10))
            pressable()
            addView(vbox().apply {
                addView(text("Font", Type.L, medium = true))
                addView(text("Every app's text. Heavier fonts hold up better on e-ink.", Type.S, color = Ink.MUTED))
            }, lp(0, WRAP, 1f))
            addView(text(nowTitle + if (fontsOpen) "  ▴" else "  ▾", Type.M, bold = true))
            setOnClickListener { fontsOpen = !fontsOpen; renderDevice() }
        })
        if (fontsOpen) for (f in fonts) {
            val on = f.path == now
            deviceBox.addView(hbox().apply {
                setPadding(dp(24), dp(6), dp(16), dp(6))
                pressable()
                addView(text(if (on) "●" else "○", Type.L, bold = true), lp(dp(28), WRAP))
                addView(text(f.title, Type.S, bold = on, medium = !on), lp(dp(120), WRAP))
                addView(text("The quick brown fox jumps", Type.L).apply {
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
        if (shown.isEmpty()) appsBox.addView(text("No apps match", Type.M, color = Ink.MUTED).apply { setPadding(g, dp(12), g, dp(12)) })
    }

    private fun appRow(app: AppEntry, p: EinkDb.Profile?): View = hbox().apply {
        setPadding(g, dp(10), g, dp(10))
        pressable()
        addView(icon(app.icon, 40, tint = false))
        addView(vbox().apply {
            addView(text(app.label, Type.L, medium = true))
            val summary = p?.takeIf { !it.isDefault }?.summary()
            addView(text(summary ?: "Default", Type.S, bold = summary != null, color = if (summary != null) Ink.INK else Ink.MUTED))
        }, lp(0, WRAP, 1f).apply { marginStart = dp(14) })
        addView(icon(getDrawable(android.R.drawable.ic_media_play), 18))
        setOnClickListener {
            startActivity(Intent(this@MainActivity, AppActivity::class.java).putExtra(AppActivity.EXTRA_PKG, app.pkg))
        }
    }

    // ---- Rows ---------------------------------------------------------------------------------

    private fun toggleRow(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit): View = hbox().apply {
        setPadding(g, dp(10), g, dp(10))
        pressable()
        addView(vbox().apply {
            addView(text(title, Type.L, medium = true))
            addView(text(detail, Type.S, color = Ink.MUTED))
        }, lp(0, WRAP, 1f).apply { marginEnd = dp(12) })
        addView(chip(if (on) "On" else "Off", on) { onChange(!on) })
        setOnClickListener { onChange(!on) }
    }

    private fun stepperRow(title: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit): View = hbox().apply {
        setPadding(g, dp(10), g, dp(10))
        addView(text(title, Type.L, medium = true), lp(0, WRAP, 1f))
        addView(inkButton("−", Type.XL) { onMinus() }, lp(dp(52), WRAP))
        addView(text(value, Type.L, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(72)
        })
        addView(inkButton("+", Type.XL) { onPlus() }, lp(dp(52), WRAP))
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_SECTION = "section"
        const val SECTION_EINK = "eink"
        const val SECTION_TOUCH = "touch"
        const val SECTION_KEEP_ON = "keep_on"
        const val SECTION_SCREEN = "screen"
        const val SECTION_PANEL = "panel"
        const val SECTION_LIGHT = "light"
        const val SECTION_SLEEP = "sleep"
        const val SECTION_AOD = "aod"
        const val SECTION_READING = "reading"
        const val SECTION_ABOUT = "about"
        private const val PICK_SLEEP = 51
        private const val PICK_POWEROFF = 52
        private const val ASK_CALENDAR = 53

        val ANIMATION_KEYS = listOf(
            Settings.Global.WINDOW_ANIMATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            Settings.Global.ANIMATOR_DURATION_SCALE,
        )
    }
}

/** Outlined choice button; filled black when selected. */
fun android.content.Context.chip(label: String, selected: Boolean, onClick: () -> Unit): TextView =
    inkButton(label, Type.M, onClick).apply {
        if (selected) {
            background = outline(2, Radius.SMALL, Ink.INK)
            setTextColor(Ink.PAPER)
        }
        minWidth = dp(56)
    }

/**
 * The home screen's sub-screens (E-ink settings, Touch button, Keeping the panel on): the same screen code,
 * as its own activity because the home screen is single-task and would swallow the intent.
 */
class SectionActivity : MainActivity()
