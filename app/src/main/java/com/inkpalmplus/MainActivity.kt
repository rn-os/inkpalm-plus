package com.inkpalmplus

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

/**
 * Settings (docs/mockups/settings-reorg/, tabs from docs/mockups/settings-tabs/): a home menu of six rows, each with a
 * one-line summary, opening one screen ([SectionActivity]): Sleep screen, Screen & light, Side panel, Power off,
 * Buttons & reading, About. Nothing is more than two levels deep (Sleep screen › Weather, Countdowns, Trash day;
 * Screen & light › Advanced; Side panel › Advanced).
 *
 * A screen with more than fits has named tabs under its title ([Tabs]: Sleep screen = Face · Updates · Home face,
 * Screen & light = Screen · Light) and rarely used settings behind an "Advanced ›" row pinned at the bottom. A screen is
 * built as groups (a setting with its notes); in landscape they're laid out in two columns, like a book ([Columns]).
 * A tab that still doesn't fit (large font scale, a long list) scrolls rather than clipping, and says so in the log.
 *
 * Stock firmware's "Screen & light" is the per-app e-ink screen (search and the app list), which scrolls.
 */
open class MainActivity : Activity() {
    // Stock firmware's E-ink screen (a scrolling list).
    private lateinit var accessBox: LinearLayout
    private lateinit var deviceBox: LinearLayout
    private lateinit var appsBox: LinearLayout
    private var filter = ""
    private var fontsOpen = false
    private var apps: List<AppEntry> = emptyList()
    private var profiles: Map<String, EinkDb.Profile> = emptyMap()

    // Tabbed screens.
    private lateinit var titleView: TextView
    /** Below the header: the tab row, the tab's content (scrolls only when it can't fit) and the pinned row. */
    private lateinit var area: LinearLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var contentBox: LinearLayout
    private lateinit var pinBox: LinearLayout

    /** A setting with its notes, kept in one column; [anchor]: a link's target, scrolled to. */
    private class Group(val box: LinearLayout, val anchor: String?)
    private val groups = ArrayList<Group>()
    private var tabs: List<Tab> = emptyList()
    /** The tab shown ([Tabs] key); null on a screen without tabs. */
    private var tab: String? = null
    /** The row pinned under the content ("Advanced ›"). */
    private var pinned: View? = null
    private var cols = 1
    /** The next [show] starts at the top (a new tab), instead of keeping the scroll position (a setting changed). */
    private var scrollTop = true
    /** A link's group ([Group.anchor]), scrolled to on the next [show]. */
    private var anchorPending: String? = null
    /** Logged once a visit for each tab that doesn't fit. */
    private val overflowLogged = HashSet<String>()

    /** Icon loaded with the list, off the main thread: ~100 icons are slow to decode. */
    data class AppEntry(val pkg: String, val label: String, val icon: android.graphics.drawable.Drawable?)

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> reload() }
    private val binderListener = Shizuku.OnBinderReceivedListener { Shell.main.post { reload() } }

    /** Where this screen's link opens ([Sections.resolve]: older names open the screen and tab they moved to). */
    private val target: Sections.Target by lazy {
        Sections.resolve(intent.getStringExtra(EXTRA_SECTION), Eink.isKompakt, intent.getStringExtra(EXTRA_TAB))
    }
    /** Which screen this is: null for home, else one of the SECTION_ names. */
    private val section: String? by lazy { target.section }

    /** The first-start question's pick so far: a reader, or null for No reader ([askPicked] says whether one was tapped). */
    private var askPick: Readers.App? = null
    private var askPicked = false

    private val g get() = dp(Space.GUTTER)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getString(STATE_TAB) ?: target.tab
        if (savedInstanceState == null) anchorPending = target.anchor
        val root = vbox().apply { setBackgroundColor(Ink.PAPER) }
        root.addView(hbox().apply {
            setPadding(g, dp(8), g, dp(6))
            addView(inkIconButton(R.drawable.ic_back, "Back", 48) { finish() }, lp(dp(48), dp(48)).apply { marginEnd = dp(12) })
            titleView = text(baseTitle(), Type.XL, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            addView(titleView, lp(0, WRAP, 1f))
        })
        root.addView(View(this).apply { setBackgroundColor(Ink.INK) }, lp(MATCH, dp(2)).apply { setMargins(g, 0, g, 0) })
        if (section == SECTION_EINK) {
            accessBox = vbox(); deviceBox = vbox(); appsBox = vbox()
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
            setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        } else {
            // The keyboard pans the screen instead of resizing it (a resize would lay it out again and drop the field's focus).
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
            tabBar = hbox().apply { setPadding(g, dp(8), g, dp(4)); visibility = View.GONE }
            contentBox = hbox().apply { gravity = Gravity.TOP; setPadding(0, 0, 0, dp(CONTENT_BOTTOM)) }
            scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = true; addView(contentBox, lp(MATCH, WRAP)) }
            pinBox = vbox().apply { visibility = View.GONE }
            area = vbox()
            area.addView(tabBar, lp(MATCH, WRAP))
            area.addView(scroll, lp(MATCH, 0, 1f))
            area.addView(pinBox, lp(MATCH, WRAP))
            area.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                if (r - l != or - ol || b - t != ob - ot) area.post { show() }
            }
            root.addView(area, lp(MATCH, 0, 1f))
            setContentView(root)
        }
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        tab?.let { outState.putString(STATE_TAB, it) }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (section == SECTION_COUNTDOWNS) countdownRows = null
        preview = null
        // The panel may have been switched off by a force-stop (Moaan's Clear Memory): put it back.
        Shell.background({ Survival.repair(this, "app opened") }) { reload() }
    }

    private fun reload() {
        val eink = section == SECTION_EINK
        Shell.background({
            // The per-app list (~100 icons) only loads on the E-ink screen.
            if (eink) { val (via, rows) = EinkDb.load(this); Triple(via, launchableApps(), rows) } else Triple(null, emptyList(), emptyMap())
        }) { (via, list, rows) ->
            if (isDestroyed) return@background
            apps = list
            profiles = rows
            render(via)
        }
    }

    private fun render(via: EinkDb.Via? = null) {
        when (section) {
            SECTION_EINK -> { renderAccess(via); renderDevice(); renderApps() }
            SECTION_SLEEP -> renderSleep()
            SECTION_WEATHER -> renderWeather()
            SECTION_COUNTDOWNS -> renderCountdowns()
            SECTION_TRASH -> renderTrash()
            SECTION_SCREEN -> renderScreen()
            SECTION_SCREEN_ADVANCED -> renderScreenAdvanced()
            SECTION_PANEL -> renderPanel()
            SECTION_PANEL_ADVANCED -> renderPanelAdvanced()
            SECTION_POWER -> renderPower()
            SECTION_BUTTONS -> renderButtons()
            SECTION_ABOUT -> renderAbout()
            else -> renderHome()
        }
    }

    private fun baseTitle(): String = when (section) {
        SECTION_SLEEP -> "Sleep screen"
        SECTION_WEATHER -> "Sleep screen › Weather"
        SECTION_COUNTDOWNS -> "Sleep screen › Countdowns"
        SECTION_TRASH -> "Sleep screen › Trash day"
        SECTION_SCREEN, SECTION_EINK -> "Screen & light"
        SECTION_SCREEN_ADVANCED -> "Screen & light › Advanced"
        SECTION_PANEL -> "Side panel"
        SECTION_PANEL_ADVANCED -> "Side panel › Advanced"
        SECTION_POWER -> "Power off"
        SECTION_BUTTONS -> "Buttons & reading"
        SECTION_ABOUT -> "About"
        else -> getString(R.string.app_name)
    }

    // ---- Tabs ------------------------------------------------------------------------------------------

    private fun begin() { groups.clear(); tabs = emptyList(); pinned = null }

    /** Starts a group; what's added next goes into it. */
    private fun group(anchor: String? = null): LinearLayout = vbox().also { groups += Group(it, anchor) }

    /** This screen's tabs; picks the one to show (this visit's, else the session's last, else the first) and remembers it. */
    private fun tabs(list: List<Tab>): String? {
        tabs = list
        tab = Tabs.pick(list, tab, lastTab[section])
        tab?.let { t -> section?.let { lastTab[it] = t } }
        return tab
    }

    private fun selectTab(key: String) {
        if (key == tab) return
        tab = key; scrollTop = true
        section?.let { lastTab[it] = key }
        render()
    }

    /** A row pinned under the content, always visible ("Advanced ›"). */
    private fun pin(row: View) { pinned = row }

    /** Segmented tabs: the current one filled black, the others outlined; one tap swaps the content, no animation. */
    private fun renderTabBar(width: Int) {
        tabBar.removeAllViews()
        if (tabs.size < 2) { tabBar.visibility = View.GONE; return }
        tabBar.visibility = View.VISIBLE
        val bar = hbox().apply { background = outline(2, Radius.SMALL); setPadding(dp(2), dp(2), dp(2), dp(2)) }
        tabs.forEachIndexed { i, t ->
            val on = t.key == tab
            if (i > 0) bar.addView(View(this).apply { setBackgroundColor(Ink.INK) }, lp(dp(2), MATCH))
            bar.addView(text(t.title, Type.L, bold = true, color = if (on) Ink.PAPER else Ink.INK).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                minHeight = dp(TAB_HEIGHT)
                setPadding(dp(6), 0, dp(6), 0)
                if (on) setBackgroundColor(Ink.INK) else pressable()
                isSelected = on
                contentDescription = "${t.title} tab" + if (on) ", selected" else ""
                setOnClickListener { selectTab(t.key) }
            }, lp(0, MATCH, 1f))
        }
        // Landscape: the tabs span the left column, as in the mockup.
        tabBar.addView(bar, lp(if (width > 0 && cols == 2) width / 2 - g * 2 else MATCH, dp(TAB_HEIGHT + 4)))
    }

    /** Lays out the tab's groups (two columns side by side in landscape), the tab row and the pinned row. The content
     *  scrolls only when it can't fit; that's logged. */
    private fun show() {
        if (section == SECTION_EINK || isDestroyed) return
        val w = area.width
        if (w == 0) return   // the layout listener calls again once there's a size
        val y = if (scrollTop) 0 else scroll.scrollY
        scrollTop = false
        for (gr in groups) (gr.box.parent as? ViewGroup)?.removeView(gr.box)
        cols = if (w > area.height && w >= dp(TWO_COLUMNS)) 2 else 1
        val split = if (cols == 2) {
            val ws = View.MeasureSpec.makeMeasureSpec(w / 2, View.MeasureSpec.EXACTLY)
            val hs = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            Columns.split(groups.map { it.box.measure(ws, hs); it.box.measuredHeight })
        } else groups.size
        contentBox.removeAllViews()
        val columns = if (cols == 2) listOf(0 until split, split until groups.size) else listOf(groups.indices)
        columns.forEachIndexed { c, range ->
            if (c > 0) contentBox.addView(View(this).apply { setBackgroundColor(Ink.INK) }, lp(dp(1), MATCH).apply { topMargin = dp(8) })
            contentBox.addView(vbox().apply { for (i in range) addView(groups[i].box, lp(MATCH, WRAP)) }, lp(0, WRAP, 1f))
        }
        renderTabBar(w)
        pinBox.removeAllViews()
        pinned?.let { pinBox.addView(divider()); pinBox.addView(it) }
        pinBox.visibility = if (pinned != null) View.VISIBLE else View.GONE
        titleView.text = baseTitle()
        val anchor = anchorPending?.let { a -> groups.firstOrNull { it.anchor == a }?.box }
        anchorPending = null
        // Once laid out: back to where it was (or the link's group), and log a tab that doesn't fit.
        scroll.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (scroll.viewTreeObserver.isAlive) scroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (isDestroyed) return
                if (anchor != null && anchor.parent != null) {
                    val r = android.graphics.Rect(); anchor.getDrawingRect(r); contentBox.offsetDescendantRectToMyCoords(anchor, r)
                    scroll.scrollTo(0, r.top)
                } else scroll.scrollTo(0, y)
                val key = "$section/${tab ?: "-"}/${if (cols == 2) "landscape" else "portrait"}"
                if (Columns.overflows(contentBox.height, scroll.height) && overflowLogged.add(key))
                    android.util.Log.i(TAG, "settings: $key doesn't fit (${contentBox.height} > ${scroll.height} px at font scale " +
                        "${resources.configuration.fontScale}), it scrolls")
            }
        })
    }

    private fun openSection(name: String) = startActivity(Intent(this, SectionActivity::class.java).putExtra(EXTRA_SECTION, name))

    private fun chevron(): View = icon(getDrawable(R.drawable.ic_back), 24).apply { scaleX = -1f }

    /** A row that opens a screen: title, what's set there, and a ›. The whole row is the button. */
    private fun navRow(title: String, detail: String, open: () -> Unit): View = hbox().apply {
        setPadding(g, dp(10), g, dp(10))
        minimumHeight = dp(64)
        pressable()
        addView(vbox().apply {
            addView(text(title, Type.XL, medium = true))
            if (detail.isNotEmpty()) addView(text(detail, Type.S, color = Ink.MUTED))
        }, lp(0, WRAP, 1f).apply { marginEnd = dp(12) })
        addView(chevron())
        contentDescription = "$title. $detail"
        setOnClickListener { open() }
    }

    private fun navRow(title: String, detail: String, name: String): View = navRow(title, detail) { openSection(name) }

    /** The "Advanced ›" row pinned at the bottom: a [navRow], a little shorter (56 dp). */
    private fun advancedRow(detail: String, name: String): View = hbox().apply {
        setPadding(g, dp(6), g, dp(6))
        minimumHeight = dp(56)
        pressable()
        addView(vbox().apply {
            addView(text("Advanced", Type.L, medium = true))
            addView(text(detail, Type.S, color = Ink.MUTED))
        }, lp(0, WRAP, 1f).apply { marginEnd = dp(12) })
        addView(chevron())
        contentDescription = "Advanced. $detail"
        setOnClickListener { openSection(name) }
    }

    // ---- Home -------------------------------------------------------------------------------------

    private fun renderHome() {
        begin()
        renderAsk()
        val prefs = Prefs(this)
        fun row(title: String, detail: String, name: String) = group().apply { addView(navRow(title, detail, name)); addView(divider()) }
        if (Aod.supported()) {
            val f = Aod.face(this)
            row("Sleep screen", Summaries.sleep(faceLabel().title, f == Aod.Face.DEFAULT, f.drawn, Aod.interval(this)), SECTION_SLEEP)
        }
        if (Eink.isKompakt) row("Screen & light", Summaries.screen(ScreenModes.global(this).title, if (Light.supported()) Light.followSystem(this) else null), SECTION_SCREEN)
        else row("Screen & light", "Per-app DPI, refresh, contrast, presets, font", SECTION_EINK)
        row("Side panel", Summaries.sidePanel(QuickPanelService.enabled(this), prefs.edgeRight, prefs.recentKept), SECTION_PANEL)
        if (SleepImages.supported() || AutoOff.supported()) row("Power off", Summaries.powerOff(
            if (SleepImages.supported()) SleepImages.powerOff(this).title else null, if (AutoOff.supported()) AutoOff.hours(this) else null), SECTION_POWER)
        row("Buttons & reading", Summaries.buttons(touchSummary(), readerSummary()), SECTION_BUTTONS)
        row("About", "Version ${version()} · internet only for weather", SECTION_ABOUT)
        show()
    }

    private fun touchSummary(): String = when {
        Eink.isKompakt -> Summaries.kompaktTouch(Prefs(this).holdRefresh)
        !TouchButton.canChange(this) -> "needs a permission"
        !TouchButton.on(this) -> "off"
        else -> "${TouchButton.TAP_ACTIONS[TouchButton.tap(this)]} / hold ${TouchButton.HOLD_ACTIONS[TouchButton.hold(this)].lowercase()}"
    }

    private fun readerSummary(): String = Readers.chosen(this)?.label ?: if (Readers.installedApps(this).isEmpty()) "none installed" else "none"

    private fun version() = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

    /** First start: which reader's last book the side panel shows. Only installed readers; skipped with none. */
    private fun renderAsk() {
        val apps = Readers.installedApps(this)
        if (Prefs(this).readerApp != null || apps.isEmpty()) return
        group().addView(vbox().apply {
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
                    minimumHeight = dp(56)
                    addView(vbox().apply {
                        addView(text(app?.label ?: "No reader", Type.L, bold = true))
                        addView(text(sub, Type.S, color = Ink.MUTED))
                    }, lp(0, WRAP, 1f))
                    addView(text(if (on) "●" else "○", Type.XL, bold = true))
                    setOnClickListener { askPick = app; askPicked = true; renderHome() }
                }, lp(MATCH, WRAP).apply { bottomMargin = dp(7) })
            }
            for (app in apps) option(app, if (Readers.hasAccess(app)) "Installed · works as is" else "Installed · asks once for All files access")
            option(null, "No book card in the panel")
            addView(inkButton("Continue", Type.L) {
                if (!askPicked) return@inkButton
                Prefs(this@MainActivity).readerApp = askPick?.key ?: "none"
                askPick?.let { if (!Readers.hasAccess(it)) runCatching { startActivity(Readers.accessIntent(this@MainActivity)) } }
                renderHome()
            }.apply { isEnabled = askPicked }, lp(MATCH, WRAP))
        }, lp(MATCH, WRAP).apply { setMargins(g, dp(8), g, dp(8)) })
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

    // ---- Rows shared by the screens -----------------------------------------------------------------

    /** Label above, chips sharing the width below (a longer label gets a wider chip, so "Each update" isn't cut). */
    private fun choicesBlock(title: String, detail: String, labels: List<String>, current: Int, pick: (Int) -> Unit): View = vbox().apply {
        setPadding(g, dp(6), g, dp(4))
        if (title.isNotEmpty()) addView(text(title, Type.L, medium = true))
        if (detail.isNotEmpty()) addView(text(detail, Type.S, color = Ink.MUTED))
        addView(hbox().apply {
            setPadding(0, dp(4), 0, 0)
            labels.forEachIndexed { i, l -> addView(chip(l, i == current) { pick(i) }.apply { maxLines = 1; minWidth = 0; setPadding(dp(4), dp(8), dp(4), dp(8)) },
                lp(0, WRAP, maxOf(1f, l.length / 7f)).apply { if (i > 0) marginStart = dp(6) }) }
        })
    }

    /** One choice of a list (screen modes, power-off pictures): filled black with ✓ when it's the one in use. */
    private fun optionRow(title: String, detail: String, on: Boolean, more: Boolean = false, compact: Boolean = false, click: () -> Unit): View = hbox().apply {
        // Compact (the six screen modes, so the Screen tab fits): 48 dp, still a full tap target.
        setPadding(dp(12), dp(if (compact) 2 else 6), dp(12), dp(if (compact) 2 else 6))
        minimumHeight = dp(if (compact) 48 else 56)
        if (on) background = outline(2, Radius.SMALL, Ink.INK) else pressable()
        val fg = if (on) Ink.PAPER else Ink.INK
        addView(vbox().apply {
            addView(text(title, Type.L, bold = true, color = fg))
            if (detail.isNotEmpty()) addView(text(detail, Type.S, color = if (on) Ink.PAPER else Ink.MUTED))
        }, lp(0, WRAP, 1f))
        if (on) addView(text("✓", Type.XL, bold = true, color = Ink.PAPER)) else if (more) addView(chevron())
        isClickable = true
        contentDescription = title + if (on) ", in use" else ""
        setOnClickListener { click() }
    }

    private fun optionLp(compact: Boolean = false) = lp(MATCH, WRAP).apply { setMargins(g, dp(if (compact) 1 else 2), g, dp(if (compact) 1 else 2)) }

    /** Small grey text under the setting it explains. */
    private fun note(s: String): View = text(s, Type.S, color = Ink.MUTED).apply { setPadding(g, dp(2), g, dp(6)) }

    private fun boxLp() = lp(MATCH, WRAP).apply { setMargins(g, dp(8), g, dp(8)) }

    private fun toggleRow(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit): View = hbox().apply {
        setPadding(g, dp(4), g, dp(4))
        minimumHeight = dp(56)
        pressable()
        addView(vbox().apply {
            addView(text(title, Type.L, medium = true))
            if (detail.isNotEmpty()) addView(text(detail, Type.S, color = Ink.MUTED))
        }, lp(0, WRAP, 1f).apply { marginEnd = dp(12) })
        addView(chip(if (on) "On" else "Off", on) { onChange(!on) })
        contentDescription = "$title, ${if (on) "on" else "off"}"
        setOnClickListener { onChange(!on) }
    }

    private fun stepperRow(title: String, detail: String, value: String, minus: Boolean, plus: Boolean, onMinus: () -> Unit, onPlus: () -> Unit): View = hbox().apply {
        setPadding(g, dp(4), g, dp(4))
        minimumHeight = dp(56)
        addView(vbox().apply {
            addView(text(title, Type.L, medium = true))
            if (detail.isNotEmpty()) addView(text(detail, Type.S, color = Ink.MUTED))
        }, lp(0, WRAP, 1f))
        addView(inkButton("−", Type.XL) { onMinus() }.apply { isEnabled = minus; contentDescription = "Less" }, lp(dp(52), WRAP))
        addView(text(value, Type.L, bold = true).apply { gravity = Gravity.CENTER; minWidth = dp(72) })
        addView(inkButton("+", Type.XL) { onPlus() }.apply { isEnabled = plus; contentDescription = "More" }, lp(dp(52), WRAP))
    }

    /** A title with a button on the right (Clear recent apps, Full refresh). */
    private fun buttonRow(title: String, detail: String, button: String, click: () -> Unit): View = hbox().apply {
        setPadding(g, dp(4), g, dp(4))
        minimumHeight = dp(56)
        addView(vbox().apply {
            addView(text(title, Type.L, medium = true))
            if (detail.isNotEmpty()) addView(text(detail, Type.S, color = Ink.MUTED))
        }, lp(0, WRAP, 1f).apply { marginEnd = dp(12) })
        addView(inkButton(button) { click() })
    }

    private fun otherEdge() = if (Prefs(this).edgeRight) "left" else "right"

    // ---- Sleep screen (the face is picked in [FacePickerActivity]) -------------------------------------

    /** The face in use with its style, as the picker names it: "Agenda · Week". */
    private fun faceLabel(): FacePicker.Entry {
        val now = Aod.current(this)
        return FacePicker.entries().let { it[FacePicker.indexOf(it, now)] }
    }

    /** The face row's small preview, drawn once per visit off the main thread. */
    private var preview: Pair<String, Bitmap?>? = null

    private fun faceRow(entry: FacePicker.Entry): View = hbox().apply {
        setPadding(g, dp(10), g, dp(10))
        minimumHeight = dp(64)
        pressable()
        val dm = resources.displayMetrics
        val ratio = minOf(dm.widthPixels, dm.heightPixels).toFloat() / maxOf(dm.widthPixels, dm.heightPixels)
        val p = FacePreview(this@MainActivity, ratio, if (entry.face == Aod.Face.DEFAULT) "Moaan" else "…").apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val cached = preview
        if (cached != null && cached.first == entry.key) p.bitmap = cached.second
        else {
            preview = entry.key to null
            Shell.background({ runCatching { Aod.thumbnail(this@MainActivity, entry.face, entry.agenda, entry.photo, entry.home, false, divisor = 6) }.getOrNull() }) { b ->
                if (preview?.first == entry.key) { preview = entry.key to b; p.bitmap = b }
            }
        }
        addView(p, lp(dp(60), dp(108)).apply { marginEnd = dp(14) })
        addView(vbox().apply {
            addView(text("Sleep screen:", Type.S, color = Ink.MUTED))
            addView(text(entry.title, Type.XL, bold = true))
            addView(text("Tap to flip through every screen, with your own day in it", Type.S, color = Ink.MUTED))
        }, lp(0, WRAP, 1f).apply { marginEnd = dp(12) })
        addView(chevron())
        contentDescription = "Sleep screen: ${entry.title}. Choose another"
        setOnClickListener { FacePickerActivity.open(this@MainActivity) }
    }

    private fun calendarBox(face: Aod.Face): View = vbox().apply {
        background = outline(3)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(text("Calendar access needed", Type.L, bold = true))
        addView(text("The ${face.title} face shows your events${if (face == Aod.Face.HOME) " and countdowns" else ""}. Without access it says so on the sleep screen.", Type.S, color = Ink.MUTED))
        addView(inkButton("Allow") { askCalendar(fromSettings = true, ASK_CALENDAR) }, lp(WRAP, WRAP).apply { topMargin = dp(8) })
    }

    /** Sleep screen: Face · Updates · Home face (only while the Home face is in use). */
    private fun renderSleep() {
        begin()
        if (!Aod.supported()) { group().addView(note("Needs KompaktOS's bridge.")); show(); return }
        val face = Aod.face(this)
        when (tabs(Tabs.sleep(face == Aod.Face.HOME))) {
            Tabs.UPDATES -> renderSleepUpdates(face)
            Tabs.HOME -> renderHomeRows()
            else -> renderSleepFace(face)
        }
        show()
    }

    /** The face in use (opens the picker), its picture and photo style, calendar access. */
    private fun renderSleepFace(face: Aod.Face) {
        group().apply { addView(faceRow(faceLabel())); addView(divider()) }
        if ((face == Aod.Face.AGENDA || face == Aod.Face.HOME) && Aod.needsCalendar(this)) group().addView(calendarBox(face), boxLp())
        if (face == Aod.Face.PHOTO || face == Aod.Face.PICTURE) group().apply {
            val has = Aod.hasPicture(this@MainActivity)
            addView(buttonRow(if (has) "Your picture" else "No picture yet", when {
                !has -> "The Photo face shows the clock and date until you pick one"
                face == Aod.Face.PHOTO && !Aod.hasPictureSource(this@MainActivity) -> "Pick it again for a better landscape crop"
                else -> "Shown in gray"
            }, "Choose picture…") { pickImage(PICK_SLEEP) })
            // Photo's style is picked with the face; Picture is one page there, drawn in the same style, set here.
            if (face == Aod.Face.PICTURE && has) {
                val styles = Dither.Style.entries
                val style = Aod.photoStyle(this@MainActivity)
                addView(choicesBlock("Photo style", style.detail, styles.map { it.title }, styles.indexOf(style)) { i ->
                    work({ Aod.setPhotoStyle(this@MainActivity, styles[i]) }, "Photo style: ${styles[i].title}")
                })
            }
            addView(divider())
        }
        group().apply {
            if (!face.drawn) addView(note("Updates apply to the drawn faces, not to Default or Picture."))
            addView(note("In landscape the face is drawn sideways to match. It's redrawn every minute while the screen is on, so it's ready when you press the power button."))
        }
    }

    /** How often the face is redrawn asleep, what it costs, and the "Updated 10:34" line. */
    private fun renderSleepUpdates(face: Aod.Face) {
        val minutes = Aod.interval(this)
        group().apply {
            val ints = Aod.INTERVALS
            addView(choicesBlock("", "", ints.map { if (it == 0) "Frozen" else "$it min" }, ints.indexOf(minutes).coerceAtLeast(0)) { i ->
                Aod.setInterval(this@MainActivity, ints[i]); renderSleep()
            })
            val (costTitle, costDetail) = Aod.cost(minutes)
            addView(note("$costTitle $costDetail"))
            if (!face.drawn) addView(note("Updates apply to the drawn faces, not to Default or Picture."))
            if (minutes > 0) addView(note("Below ${Aod.LOW_BATTERY}% the face shows a battery box and updates stop. They carry on while charging."))
        }
        group().apply {
            addView(divider())
            val stamp = Aod.showStamp(this@MainActivity)
            addView(toggleRow("Show update time", "The small \"Updated 10:34\" line at the bottom", stamp) {
                Aod.setShowStamp(this@MainActivity, !stamp); renderSleep()
            })
            addView(divider())
            addView(note("Seconds are never shown: a panel refresh every second would drain the battery."))
            addView(note("The front light stays off during updates, so nothing glows in your pocket."))
        }
    }

    /** The Home face's options (its tab): weather location and updates, units, countdowns, trash day. */
    private fun renderHomeRows() {
        val city = Weather.city(this)
        if (city != null && Weather.blocked(this)) group().addView(netBlockedBox(), boxLp())
        group().apply {
            addView(navRow("Weather location", city?.name ?: "Not set · search for your city", SECTION_WEATHER))
            addView(divider())
        }
        // Finding the city by location needs microG's Wi-Fi location: offer the fix here too while there's no city.
        if (city == null) microgGroup()
        group().apply {
            val every = Weather.every(this@MainActivity)
            addView(choicesBlock("Weather updates", "How often the forecast is fetched", Home.WEATHER_EVERY.map { Home.weatherEveryLabel(it) },
                Home.WEATHER_EVERY.indexOf(every)) { i -> Weather.setEvery(this@MainActivity, Home.WEATHER_EVERY[i]); renderSleep() })
            addView(divider())
        }
        group().apply {
            val units = Home.Units.entries
            val auto = if (Home.fahrenheit(Home.Units.AUTO)) "°F" else "°C"
            addView(choicesBlock("Units", "Auto follows your region (now $auto)", units.map { it.title }, units.indexOf(Weather.units(this@MainActivity))) { i ->
                Weather.setUnits(this@MainActivity, units[i]); Aod.homeChanged(this@MainActivity); renderSleep()
            })
            addView(divider())
        }
        group().apply {
            val pins = Aod.pinned(this@MainActivity).size; val hides = Aod.hidden(this@MainActivity).size
            addView(navRow("Countdowns", "From your calendar" + (if (pins > 0) " · $pins pinned" else "") + (if (hides > 0) " · $hides hidden" else ""), SECTION_COUNTDOWNS))
            addView(divider())
            addView(navRow("Trash day", trashLabel(), SECTION_TRASH))
            addView(divider())
        }
        group().apply {
            val every = Weather.every(this@MainActivity)
            val minutes = Aod.interval(this@MainActivity)
            addView(note("Each fetch is one small request (about ${Home.WEATHER_KB} KB), only when Wi\u2011Fi is already on; Open-Meteo updates about every 15 min. " +
                (Home.weatherPerDay(every, minutes)?.let { n -> "With updates every $minutes min: about $n a day (~${n * Home.WEATHER_KB / 100 / 10.0} MB), each 1–2 s more awake." }
                    ?: "Frozen: one fetch when the screen goes off (if the last was ${if (every > 0) "$every" else "4"}+ min ago).")))
        }
    }

    private val weekdays = listOf(java.util.Calendar.MONDAY, java.util.Calendar.TUESDAY, java.util.Calendar.WEDNESDAY, java.util.Calendar.THURSDAY,
        java.util.Calendar.FRIDAY, java.util.Calendar.SATURDAY, java.util.Calendar.SUNDAY)

    private fun dayName(d: Int) = java.text.DateFormatSymbols.getInstance().shortWeekdays[d]

    private fun trashLabel(): String {
        if (!Aod.trashOn(this)) return "Off"
        fun days(s: Set<Int>) = weekdays.filter { it in s }.joinToString(", ") { dayName(it) }.ifEmpty { "no day" }
        return "Recycling ${days(Aod.recyclingDays(this))} · Trash ${days(Aod.trashDays(this))}"
    }

    // ---- Sleep screen › Weather (docs/mockups/aod/home-face/) ----------------------------------------

    private var cityQuery = ""
    private var places: List<Home.Place>? = null
    private var placesNote: String? = null

    /** The weather's city: searched with Open-Meteo's geocoding (a typed name goes to it), picked from the results. */
    private fun renderWeather() {
        begin()
        val city = Weather.city(this)
        group().apply {
            addView(hbox().apply {
                setPadding(g, dp(10), g, dp(10))
                minimumHeight = dp(56)
                addView(vbox().apply {
                    addView(text(city?.name ?: "No city set", Type.L, bold = true))
                    val at = Weather.fetchedAt(this@MainActivity)
                    addView(text(when {
                        city == null -> "The Home face says \"Set a city\" until you pick one"
                        at > 0 -> "Forecast from " + android.text.format.DateFormat.getTimeFormat(this@MainActivity).format(java.util.Date(at)) +
                            " · Open-Meteo" + if (!Weather.online(this@MainActivity)) " · offline now" else ""
                        else -> if (Weather.online(this@MainActivity)) "Fetching the forecast…" else "Offline: the forecast comes on the next update with Wi-Fi on"
                    }, Type.S, color = Ink.MUTED))
                }, lp(0, WRAP, 1f))
                if (city != null) addView(inkButton("Remove") {
                    Weather.setCity(this@MainActivity, null); Aod.homeChanged(this@MainActivity); renderWeather()
                })
            })
        }
        if (city != null && Weather.blocked(this)) group().addView(netBlockedBox(), boxLp())
        group().apply {
            addView(sectionTitle("Search"))
            val field = EditText(this@MainActivity).apply {
                hint = "City name"
                setText(cityQuery)
                textSize = Type.L
                setTextColor(Ink.INK)
                setSingleLine()
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                background = outline(2, Radius.SMALL)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            fun search() {
                cityQuery = field.text.toString().trim()
                if (cityQuery.length < 2) return
                if (!Weather.online(this@MainActivity)) { places = null; placesNote = "Offline: turn on Wi-Fi to search. InkPalm+ never turns it on."; renderWeather(); return }
                placesNote = "Searching…"; places = null; renderWeather()
                val q = cityQuery
                Shell.background({ Weather.search(q) }) { r ->
                    places = r; placesNote = when { r == null -> "Couldn't reach Open-Meteo"; r.isEmpty() -> "No city called \"$q\""; else -> null }
                    anchorPending = ANCHOR_RESULTS
                    if (!isDestroyed) renderWeather()
                }
            }
            field.setOnEditorActionListener { _, _, _ -> search(); true }
            addView(hbox().apply {
                setPadding(g, dp(8), g, dp(4))
                addView(field, lp(0, WRAP, 1f))
                addView(inkButton("Search") { search() }, lp(WRAP, WRAP).apply { marginStart = dp(8) })
            })
        }
        group(anchor = ANCHOR_RESULTS).apply {
            placesNote?.let { addView(note(it)) }
            places?.firstOrNull()?.let { addView(placeRow(it)); addView(divider()) }
        }
        places?.drop(1)?.forEach { p -> group().apply { addView(placeRow(p)); addView(divider()) } }
        microgGroup()
        group().apply {
            addView(inkButton(if (locating) "Finding your location… (up to ${Weather.LOCATE_MS / 1000} s)" else "Use my location") { if (!locating) useLocation() },
                lp(MATCH, WRAP).apply { setMargins(g, dp(8), g, dp(2)) })
            addView(note("Finds your location once, when you tap it, to about 1 km. It's never tracked."))
            locateFail?.let { addView(locateFailBox(it), boxLp()) }
        }
        group().apply {
            if (ipConfirm) addView(ipConfirmBox(), boxLp())
            else if (locateFail == null && !locating) {
                addView(note("No location? Guess the city instead."))
                addView(inkButton("Estimate from internet connection") { ipConfirm = true; renderWeather() }, lp(MATCH, WRAP).apply { setMargins(g, dp(2), g, dp(2)) })
            }
        }
        group().addView(note("Weather by Open-Meteo (open-meteo.com): free, no account. Only the city's coordinates are sent, as often as Sleep screen › Home face › Weather updates says, and only when Wi-Fi is already on; the face shows the forecast's age when it's old or the phone is offline. \"Estimate from internet connection\" sends your public IP address to ${Home.IP_SERVICE}, only when you tap it. Nothing else leaves the phone."))
        show()
    }

    /** Android blocks InkPalm+'s network (LineageOS's restricted networking mode, see [NetCheck]): where to allow it. */
    private fun netBlockedBox(): View = vbox().apply {
        background = outline(3)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(text("InkPalm+ has no network access. Your phone limits internet for new apps.", Type.M, bold = true))
        addView(text("In App info → Mobile data & Wi\u2011Fi, turn on Network access.", Type.S, color = Ink.MUTED).apply { setPadding(0, dp(4), 0, 0) })
        addView(inkButton("Allow network access") {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
        }, lp(WRAP, WRAP).apply { topMargin = dp(8) })
    }

    private fun placeRow(p: Home.Place): View = hbox().apply {
        setPadding(g, dp(10), g, dp(10))
        minimumHeight = dp(56)
        pressable()
        addView(vbox().apply {
            addView(text(p.name, Type.L, bold = true))
            if (p.detail.isNotEmpty()) addView(text(p.detail, Type.S, color = Ink.MUTED))
        }, lp(0, WRAP, 1f))
        setOnClickListener {
            Weather.setCity(this@MainActivity, p)
            places = null; placesNote = null; cityQuery = ""; scrollTop = true
            toast("Weather for ${p.name}")
            renderWeather()
            Shell.background({ Weather.fetchIfDue(this@MainActivity) }) { Aod.homeChanged(this@MainActivity); if (!isDestroyed) renderWeather() }
        }
    }

    private var locating = false
    /** The last "Use my location" that found nothing; cleared by the next try or a city. */
    private var locateFail: Weather.Located.Failed? = null
    /** The IP estimate's explanation is showing, waiting for "Send and estimate". */
    private var ipConfirm = false

    /** What went wrong, microG's Wi-Fi location setting, precise location (when only approximate was given), and the
     *  internet-connection estimate. */
    private fun locateFailBox(f: Weather.Located.Failed): View = vbox().apply {
        background = outline(3)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        val microg = Weather.microg(this@MainActivity)
        val microgPrecise = !microg || Weather.microgPrecise(this@MainActivity)
        addView(text(Home.locateMessage(f.fail, microg, Weather.LOCATE_MS / 1000, microgPrecise), Type.M, bold = true))
        if (Weather.microgNeedsFix(this@MainActivity) && !microgConfirm) addView(inkButton("Fix microG location") { microgConfirm = true; renderWeather() },
            lp(WRAP, WRAP).apply { topMargin = dp(8) })
        val noFix = f.fail == Home.LocateFail.NO_FIX || f.fail == Home.LocateFail.NO_PROVIDER || f.fail == Home.LocateFail.LOCATION_OFF
        if (noFix) addView(inkButton(if (microg && f.fail != Home.LocateFail.LOCATION_OFF) "microG location settings" else "Location settings") {
            val intents = Weather.locationSettingsIntents().let { if (microg && f.fail != Home.LocateFail.LOCATION_OFF) it else it.takeLast(1) }
            if (intents.none { runCatching { startActivity(it) }.isSuccess }) toast("Couldn't open the location settings")
        }, lp(WRAP, WRAP).apply { topMargin = dp(8) })
        if (f.fail == Home.LocateFail.NO_FIX && !f.precise) {
            addView(text(Home.APPROXIMATE_NOTE, Type.S, color = Ink.MUTED).apply { setPadding(0, dp(10), 0, 0) })
            addView(inkButton("Allow precise (used once, rounded to ~1 km)") { askPrecise() }, lp(WRAP, WRAP).apply { topMargin = dp(8) })
        }
        if (f.fail != Home.LocateFail.NO_PERMISSION && !ipConfirm) {
            addView(text("Or guess the city from your internet connection.", Type.S, color = Ink.MUTED).apply { setPadding(0, dp(10), 0, 0) })
            addView(inkButton("Estimate from internet connection") { ipConfirm = true; renderWeather() }, lp(WRAP, WRAP).apply { topMargin = dp(8) })
        }
    }

    /** "Fix microG location" asked, waiting for Grant. */
    private var microgConfirm = false
    private var microgBusy = false
    /** How the last fix went. */
    private var microgNote: String? = null

    /** microG lacks precise or background location (it then can't read Wi-Fi scans): a row offering the fix, its
     *  confirmation, and how it went. Hidden when microG has both or isn't installed. */
    private fun microgGroup() {
        val needed = Weather.microgNeedsFix(this)
        if (!needed && microgNote == null) return
        group().apply {
            if (needed && !microgConfirm) addView(buttonRow("microG can't use Wi\u2011Fi location", "It needs precise location, allowed all the time", "Fix\u2026") {
                microgConfirm = true; microgNote = null; render()
            })
            if (microgConfirm) addView(vbox().apply {
                background = outline(3)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(text("Fix microG location", Type.L, bold = true))
                addView(text(Home.MICROG_CONFIRM, Type.S, color = Ink.MUTED).apply { setPadding(0, dp(4), 0, 0) })
                addView(hbox().apply {
                    addView(inkButton(if (microgBusy) "Granting\u2026" else "Grant") { if (!microgBusy) fixMicrog() })
                    addView(inkButton("Cancel") { microgConfirm = false; render() }, lp(WRAP, WRAP).apply { marginStart = dp(8) })
                }, lp(WRAP, WRAP).apply { topMargin = dp(8) })
            }, boxLp())
            microgNote?.let { addView(text(it, Type.S, bold = true).apply { setPadding(g, dp(2), g, dp(6)) }) }
        }
    }

    private fun fixMicrog() {
        microgBusy = true; render()
        Shell.background({ Weather.fixMicrog(this) }) { ok ->
            microgBusy = false; microgConfirm = false
            microgNote = if (ok) Home.MICROG_DONE else Home.MICROG_BY_HAND
            if (ok) locateFail = null
            else if (runCatching { startActivity(Weather.microgAppSettings()) }.isFailure) toast("Couldn't open microG's app settings")
            if (!isDestroyed) render()
        }
    }

    /** Explains what the IP estimate sends before anything is sent. */
    private fun ipConfirmBox(): View = vbox().apply {
        background = outline(3)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(text("Estimate from internet connection", Type.L, bold = true))
        addView(text(Home.IP_NOTE, Type.S, color = Ink.MUTED).apply { setPadding(0, dp(4), 0, 0) })
        addView(hbox().apply {
            addView(inkButton("Send and estimate") { estimateFromIp() })
            addView(inkButton("Cancel") { ipConfirm = false; renderWeather() }, lp(WRAP, WRAP).apply { marginStart = dp(8) })
        }, lp(WRAP, WRAP).apply { topMargin = dp(8) })
    }

    private fun estimateFromIp() {
        ipConfirm = false
        if (!Weather.online(this)) { placesNote = "Offline: turn on Wi-Fi first. InkPalm+ never turns it on."; renderWeather(); return }
        places = null; placesNote = "Asking ${Home.IP_SERVICE}…"; renderWeather()
        Shell.background({ Weather.estimateFromIp() }) { (p, why) ->
            if (p != null) {
                Weather.setCity(this, p)
                locateFail = null; placesNote = "Estimated from your internet connection: ${p.name}. Search if it's wrong."
                toast("Weather for ${p.name}")
                Shell.background({ Weather.fetchIfDue(this) }) { Aod.homeChanged(this); if (!isDestroyed) renderWeather() }
            } else placesNote = why
            anchorPending = ANCHOR_RESULTS
            if (!isDestroyed) renderWeather()
        }
    }

    /** Only approximate location was given: asks again with precise (Android's upgrade dialog). */
    private fun askPrecise() {
        if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) && Prefs(this).sp.getBoolean("location_precise_asked", false)) {
            placesNote = "Precise location is off for InkPalm+: turn it on under Permissions → Location in Android's app settings."
            renderWeather()
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
            return
        }
        Prefs(this).sp.edit().putBoolean("location_precise_asked", true).apply()
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), ASK_LOCATION)
    }

    /** "Use my location": precise and approximate location asked together when neither is given yet (the user picks
     *  one), then one fix ([Weather.locate]). */
    private fun useLocation() {
        if (!Weather.approximate(this)) {
            if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION) && Prefs(this).sp.getBoolean("location_asked", false)) {
                placesNote = "Location permission is off for InkPalm+: allow it in Android's app settings, or search for your city."
                renderWeather()
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
                return
            }
            Prefs(this).sp.edit().putBoolean("location_asked", true).putBoolean("location_precise_asked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), ASK_LOCATION)
            return
        }
        // Approximate given earlier (before precise was asked for): Android's upgrade dialog once; either answer goes on.
        if (!Weather.precise(this) && !Prefs(this).sp.getBoolean("location_precise_asked", false)) {
            Prefs(this).sp.edit().putBoolean("location_precise_asked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), ASK_LOCATION)
            return
        }
        places = null; placesNote = null; locateFail = null; ipConfirm = false; locating = true; renderWeather()
        Shell.background({ Weather.locate(this) }) { r ->
            locating = false
            when (r) {
                is Weather.Located.Found -> {
                    Weather.setCity(this, r.place)
                    toast("Weather for ${r.place.name}")
                    scrollTop = true
                    Shell.background({ Weather.fetchIfDue(this) }) { Aod.homeChanged(this); if (!isDestroyed) renderWeather() }
                }
                is Weather.Located.Failed -> locateFail = r
            }
            if (!isDestroyed) renderWeather()
        }
    }

    // ---- Sleep screen › Countdowns, Trash day ---------------------------------------------------------

    /** Countdown candidates and how many days to them, loaded off the main thread. */
    private var countdownRows: List<Home.Candidate>? = null

    /** The calendar's countdowns (all-day, multi-day and 4 h+ events in the next 60 days, and pinned ones), each
     *  with Pin and Hide. */
    private fun renderCountdowns() {
        begin()
        if (Aod.needsCalendar(this)) {
            group().apply {
                addView(note("Countdowns come from your calendar: allow calendar access first."))
                addView(inkButton("Allow") { askCalendar(fromSettings = true, ASK_CALENDAR) }, lp(WRAP, WRAP).apply { setMargins(g, dp(8), g, dp(8)) })
            }
            show(); return
        }
        group().addView(note("Shown automatically: all-day, multi-day and 4 h+ events in the next ${Home.COUNTDOWN_DAYS} days, nearest first. Pinned ones always come first; hidden ones never show."))
        val rows = countdownRows
        if (rows == null) {
            group().addView(note("Reading your calendar…"))
            show()
            Shell.background({ Aod.refreshCountdowns(); Aod.countdownCandidates(this) }) { countdownRows = it; if (!isDestroyed) renderCountdowns() }
            return
        }
        val now = System.currentTimeMillis(); val today0 = Agenda.midnight(now)
        val pins = Aod.pinned(this); val hides = Aod.hidden(this)
        val shown = Home.countdowns(rows, pins, hides, today0, Agenda.midnight(now, Home.COUNTDOWN_DAYS), 2).map { it.key }
        val list = rows.filter { it.key in pins || Home.isCountdown(it, today0, Agenda.midnight(now, Home.COUNTDOWN_DAYS)) }.distinctBy { it.key }
        group().apply {
            addView(sectionTitle("On the face now"))
            addView(text(if (shown.isEmpty()) "None" else rows.filter { it.key in shown }.distinctBy { it.key }
                .joinToString("\n") { Home.countdownText(it.title, Home.daysUntil(today0, it.begin)).toString() }, Type.M, bold = true).apply { setPadding(g, dp(4), g, dp(6)) })
        }
        group().apply {
            addView(sectionTitle("From your calendar"))
            if (list.isEmpty()) addView(note("Nothing in the next ${Home.COUNTDOWN_DAYS} days."))
        }
        val df = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault())
        for (e in list) group().apply {
            val pinned = e.key in pins; val hidden = e.key in hides
            addView(hbox().apply {
                setPadding(g, dp(8), g, dp(8))
                minimumHeight = dp(56)
                addView(vbox().apply {
                    addView(text(e.title, Type.L, bold = !hidden, color = if (hidden) Ink.MUTED else Ink.INK).apply { maxLines = 2 })
                    val days = Home.daysUntil(today0, e.begin)
                    addView(text(df.format(java.util.Date(e.begin)) + " · " + when (days) { 0 -> "today"; 1 -> "tomorrow"; else -> "in $days days" } +
                        (if (e.key in shown) " · shown" else ""), Type.S, color = Ink.MUTED))
                }, lp(0, WRAP, 1f).apply { marginEnd = dp(8) })
                addView(chip("Pin", pinned) { Aod.setCountdown(this@MainActivity, e.key, pin = !pinned, hide = false); renderCountdowns() }, lp(WRAP, WRAP))
                addView(chip("Hide", hidden) { Aod.setCountdown(this@MainActivity, e.key, pin = false, hide = !hidden); renderCountdowns() }, lp(WRAP, WRAP).apply { marginStart = dp(6) })
            })
            addView(divider())
        }
        show()
    }

    /** Off, or the weekdays for recycling and for trash (M T W T F S S). */
    private fun renderTrash() {
        begin()
        val on = Aod.trashOn(this)
        val rec = Aod.recyclingDays(this); val trash = Aod.trashDays(this)
        group().addView(toggleRow("Trash day", if (on) "A card the day before and on the day" else "Off: no trash card", on) {
            Aod.setTrash(this, !on, rec, trash); renderTrash()
        })
        if (on) {
            fun days(title: String, set: Set<Int>, change: (Set<Int>) -> Unit) = group().apply {
                addView(text(title, Type.L, medium = true).apply { setPadding(g, dp(10), g, dp(2)) })
                addView(hbox().apply {
                    setPadding(g, dp(4), g, dp(4))
                    weekdays.forEachIndexed { i, d ->
                        addView(chip(dayName(d).take(1), d in set) { change(if (d in set) set - d else set + d); renderTrash() }.apply {
                            minWidth = 0; setPadding(0, dp(8), 0, dp(8)); contentDescription = "$title ${dayName(d)}"
                        }, lp(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(4) })
                    }
                })
            }
            days("Recycling", rec) { Aod.setTrash(this, true, it, trash) }
            days("Trash", trash) { Aod.setTrash(this, true, rec, it) }
            group().addView(note("The Home face shows \"Recycling tomorrow\" the day before and \"Trash today\" on the day; nothing on other days."))
        }
        show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ASK_LOCATION) {
            // Either is enough: precise, or only approximate (then the failure note offers precise).
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) useLocation()
            else { placesNote = "No location permission: search for your city instead."; if (section == SECTION_WEATHER) renderWeather() }
            return
        }
        if (requestCode != ASK_CALENDAR) return
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) toast("Calendar access needed for the Agenda face")
        Aod.prepare(this, force = true)
        if (section != SECTION_EINK) render()
    }

    // ---- Screen & light (KompaktOS; from InkControl) ----------------------------------------------------

    /** Screen & light: Screen · Light (when there's front light control), and Advanced pinned at the bottom. */
    private fun renderScreen() {
        begin()
        if (tabs(Tabs.screen(Light.supported())) == Tabs.LIGHT) group().apply {
            addView(toggleRow("Follows Android's brightness", "The brightness slider and adaptive brightness dim the light too", Light.followSystem(this@MainActivity)) {
                Light.setFollowSystem(this@MainActivity, !Light.followSystem(this@MainActivity)); renderScreen()
            })
            addView(divider())
            addView(note("Brightness, warmth and Auto live in the screen panel: swipe along the ${otherEdge()} edge. The light goes off with the screen and comes back with it, and stays off while the sleep screen updates."))
        } else renderScreenModes()
        pin(advancedRow(if (KompaktSync.get(filesDir) != null) "Per-app modes · Real refresh" else "Real refresh on Refresh", SECTION_SCREEN_ADVANCED))
        show()
    }

    /** The Screen tab: the mode everywhere, a full refresh, contrast. */
    private fun renderScreenModes() {
        // The e-ink status only when something's wrong (no bridge, or InkControl still installed).
        val bridge = Eink.bridgeAvailable()
        val inkControl = Light.inkControlInstalled(this)
        if (!bridge || inkControl) group().addView(vbox().apply {
            background = outline(3)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(text("E-ink control: ${Eink.describe()}", Type.L, bold = true))
            if (!bridge) addView(text("No KompaktOS bridge: front light, sleep and power-off screens aren't available.", Type.S, color = Ink.MUTED))
            if (inkControl) {
                addView(text("InkControl is still installed. InkPalm+ does everything it did now; uninstall InkControl so the two don't both drive the screen and light.", Type.S, bold = true)
                    .apply { setPadding(0, dp(6), 0, 0) })
                addView(inkButton("Uninstall InkControl") {
                    runCatching { startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:org.neoport.inkcontrol"))) }
                }, lp(WRAP, WRAP).apply { topMargin = dp(8) })
            }
        }, boxLp())
        group().apply {
            addView(sectionTitle("Screen mode, everywhere", 0.8f))
            val now = ScreenModes.global(this@MainActivity)
            for (m in ScreenMode.entries) addView(optionRow(m.title, m.detail, m == now, compact = true) {
                ScreenModes.choose(this@MainActivity, m, null, packageName); renderScreen()
            }, optionLp(compact = true))
            addView(note("One app's own mode: the screen panel (swipe along the ${otherEdge()} edge)."))
        }
        group().apply {
            addView(divider())
            addView(buttonRow("Full refresh", "Clears ghosting from the whole screen", "Refresh now") { ScreenModes.fullRefresh() })
            addView(divider())
        }
        group().apply {
            val level = ScreenModes.contrast(this@MainActivity)
            addView(stepperRow("Contrast", "Text and lines; normal is the firmware's", Summaries.contrast(ScreenModes.contrastLabel(level)), level > 0, level < ScreenModes.CONTRAST_MAX,
                onMinus = { ScreenModes.setContrast(this@MainActivity, level - ScreenModes.CONTRAST_STEP); renderScreen() },
                onPlus = { ScreenModes.setContrast(this@MainActivity, level + ScreenModes.CONTRAST_STEP); renderScreen() }))
            addView(divider())
        }
    }

    /** Screen & light › Advanced: how InkPalm+ goes along with KompaktOS's own screen settings. */
    private fun renderScreenAdvanced() {
        begin()
        group().apply {
            addView(sectionTitle("KompaktOS"))
            if (KompaktSync.get(filesDir) != null) addView(toggleRow("KompaktOS per-app modes", "Apps you set in KompaktOS's settings use that mode, unless you pick one here", ScreenModes.followKompakt(this@MainActivity)) {
                ScreenModes.setFollowKompakt(this@MainActivity, !ScreenModes.followKompakt(this@MainActivity)); renderScreenAdvanced()
            })
            addView(toggleRow("Real refresh on Refresh", "Its own refresh tile only flashes black here; this adds a full refresh", ScreenModes.flashOnKompaktRefresh(this@MainActivity)) {
                ScreenModes.setFlashOnKompaktRefresh(this@MainActivity, !ScreenModes.flashOnKompaktRefresh(this@MainActivity)); renderScreenAdvanced()
            })
            addView(divider())
        }
        show()
    }

    // ---- Side panel -------------------------------------------------------------------------------------

    private fun renderPanel() {
        begin()
        val prefs = Prefs(this)
        fun previewStrip() = sendBroadcast(Intent(QuickPanelService.ACTION_STRIP).setPackage(packageName).putExtra(QuickPanelService.EXTRA_PREVIEW, true))
        group().apply {
            val on = QuickPanelService.enabled(this@MainActivity)
            addView(toggleRow("Side panel", if (on) "Swipe up or down along the edge in any app for your recent apps" else "Turn on InkPalm+ in Accessibility", on) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        }
        group().addView(choicesBlock("Edge", "Where you swipe for recent apps", listOf("Left", "Right"), if (prefs.edgeRight) 1 else 0) {
            prefs.edgeRight = it == 1; previewStrip(); renderPanel()
        })
        val widths = listOf(66, 78, 90)
        group().addView(choicesBlock("Panel width", "How much of the screen it covers", widths.map { "$it%" }, widths.indexOf(prefs.panelWidthPct)) {
            prefs.panelWidthPct = widths[it]; renderPanel()
        })
        val kept = listOf(9, 18, 27)
        group().addView(choicesBlock("Apps kept", "Recent apps the panel remembers", kept.map { "$it" }, kept.indexOf(prefs.recentKept)) {
            prefs.recentKept = kept[it]; renderPanel()
        })
        group().addView(choicesBlock("Newest app", "Where the app you're in sits in the list", listOf("Top", "Bottom"), if (prefs.newestAtBottom) 1 else 0) {
            prefs.newestAtBottom = it == 1; renderPanel()
        })
        group().addView(buttonRow("Clear recent apps", "Start the list again", "Clear") {
            getSharedPreferences("recent_apps", MODE_PRIVATE).edit().clear().apply()
            sendBroadcast(Intent(QuickPanelService.ACTION_CLEAR_RECENTS).setPackage(packageName))
            toast("Recent apps cleared")
        })
        pin(advancedRow("Strip, Edge Back, keeping it on", SECTION_PANEL_ADVANCED))
        show()
    }

    /** Side panel › Advanced: the strip, Edge Back, and keeping the panel on. */
    private fun renderPanelAdvanced() {
        begin()
        val prefs = Prefs(this)
        fun previewStrip() = sendBroadcast(Intent(QuickPanelService.ACTION_STRIP).setPackage(packageName).putExtra(QuickPanelService.EXTRA_PREVIEW, true))
        group().apply {
            addView(sectionTitle("Strip"))
            val w = prefs.stripWidthDp
            addView(stepperRow("Strip width", "How wide the swipe area is", "$w dp", w > Prefs.STRIP_MIN, w < Prefs.STRIP_MAX,
                onMinus = { prefs.stripWidthDp = w - 2; previewStrip(); renderPanelAdvanced() },
                onPlus = { prefs.stripWidthDp = w + 2; previewStrip(); renderPanelAdvanced() }))
            addView(toggleRow("Show strip", "A thin black line where you swipe", prefs.showStrip) {
                prefs.showStrip = !prefs.showStrip
                sendBroadcast(Intent(QuickPanelService.ACTION_STRIP).setPackage(packageName))
                renderPanelAdvanced()
            })
        }
        group().apply {
            if (Eink.isKompakt) addView(toggleRow("Screen & light strip", "Swipe along the ${otherEdge()} edge for screen modes and the front light", prefs.screenPanel) {
                prefs.screenPanel = !prefs.screenPanel; previewStrip(); renderPanelAdvanced()
            })
            addView(toggleRow("Edge Back", if (Eink.isKompakt) "Swipe in from either edge to go back (KompaktOS has no back gesture)"
                else "Swipe in from either edge to go back. Android's own back gesture already does this here", prefs.edgeBack) {
                prefs.edgeBack = !prefs.edgeBack; previewStrip(); renderPanelAdvanced()
            })
        }
        // Keeping the panel on: Clear Memory whitelist and turning the service back on.
        group(anchor = ANCHOR_KEEP_ON).apply {
            addView(sectionTitle("Keeping the panel on"))
            addView(note("So the panel survives Clear Memory and restarts. Both are optional."))
            val listed = Survival.inWhitelist(this@MainActivity)
            addView(toggleRow("Survives Clear Memory",
                if (listed) "InkPalm+ is on Moaan's Clear Memory whitelist"
                else if (Shell.allowed()) "Tap to add InkPalm+ to Moaan's whitelist; Clear Memory would switch the panel off"
                else "Clear Memory would switch the panel off. Start Shizuku, or run: ${Survival.whitelistAdbCommand(this@MainActivity)}", listed) {
                if (!listed) Shell.background({ Survival.addToWhitelist(this@MainActivity) }) { ok -> if (!ok) toast("Needs Shizuku"); renderPanelAdvanced() }
            })
        }
        group().apply {
            val repair = Survival.canRepair(this@MainActivity)
            addView(toggleRow("Turns itself back on",
                if (repair) "If the panel is ever switched off by mistake, InkPalm+ turns it back on"
                else if (Shell.allowed()) "Tap to let InkPalm+ turn the panel back on if it's ever switched off"
                else "Start Shizuku, or run: ${Survival.grantAdbCommand(this@MainActivity)}", repair) {
                if (!repair) Shell.background({ Survival.grantRepair(this@MainActivity) }) { ok -> if (!ok) toast("Needs Shizuku"); renderPanelAdvanced() }
            })
        }
        show()
    }

    // ---- Power off ----------------------------------------------------------------------------------------

    private fun renderPower() {
        begin()
        if (SleepImages.supported()) group().apply {
            addView(sectionTitle("Picture when off"))
            val now = SleepImages.powerOff(this@MainActivity)
            for (p in SleepImages.PowerOff.entries) {
                val detail = when (p) {
                    SleepImages.PowerOff.OFF -> "Moaan's own power-off picture"
                    SleepImages.PowerOff.PANDA -> "Moaan's panda, in English"
                    SleepImages.PowerOff.CUSTOM -> "Pick one from your photos"
                }
                addView(optionRow(p.title + if (p == SleepImages.PowerOff.CUSTOM) "…" else "", detail, p == now, more = p == SleepImages.PowerOff.CUSTOM) {
                    if (p == SleepImages.PowerOff.CUSTOM) pickImage(PICK_POWEROFF)
                    else work({ SleepImages.setPowerOff(this@MainActivity, p) }, "Power-off picture: ${p.title}")
                }, optionLp())
            }
            addView(note("What stays on the screen when the phone is off. It costs no battery."))
        }
        if (AutoOff.supported()) group().apply {
            addView(divider())
            val hours = AutoOff.HOURS
            addView(choicesBlock("Auto power-off", "After this long asleep on battery, like the stock firmware", hours.map { if (it == 0) "Never" else "$it h" },
                hours.indexOf(AutoOff.hours(this@MainActivity)).coerceAtLeast(0)) { i -> AutoOff.setHours(this@MainActivity, hours[i]); renderPower() })
            addView(note("Auto power-off only counts time on battery; while charging the phone stays asleep, not off."))
        }
        if (groups.isEmpty()) group().addView(note("Needs KompaktOS's bridge."))
        show()
    }

    // ---- Buttons & reading (docs/mockups/10-touch-button.png, 14-reader-app.png) ------------------------------

    private fun renderButtons() {
        begin()
        renderTouch()
        // Reader app: NeoReader · KOReader · None (installed readers only).
        group(anchor = ANCHOR_READING).apply {
            addView(sectionTitle("Reader app"))
            val prefs = Prefs(this@MainActivity)
            val apps = Readers.installedApps(this@MainActivity)
            if (apps.isEmpty()) { addView(note("No reader app is installed. NeoReader and KOReader are supported.")); return@apply }
            val chosen = Readers.chosen(this@MainActivity)
            val needsAccess = chosen != null && !Readers.hasAccess(chosen)
            val current = if (prefs.readerApp == "none") apps.size else apps.indexOf(chosen)
            addView(choicesBlock("Reader", "Its last book sits in the side panel, one tap away, and on the Reading sleep screen", apps.map { it.label } + "None", current) { i ->
                val app = apps.getOrNull(i)
                prefs.readerApp = app?.key ?: "none"
                if (app != null && !Readers.hasAccess(app)) runCatching { startActivity(Readers.accessIntent(this@MainActivity)) }
                renderButtons()
            })
            addView(note("Covers on the Reading sleep screen come from the book file (EPUB)." +
                if (needsAccess) " ${chosen!!.label} keeps its history in shared storage, so InkPalm+ needs All files access to read it." else ""))
            if (needsAccess) addView(inkButton("Allow All files access") { runCatching { startActivity(Readers.accessIntent(this@MainActivity)) } },
                lp(MATCH, WRAP).apply { setMargins(g, dp(4), g, dp(8)) })
        }
        show()
    }

    /** The touch button below the screen. KompaktOS: a short touch is Home, hold can be a full refresh; stock: the
     *  firmware's own tap and hold actions. */
    private fun renderTouch() {
        val prefs = Prefs(this)
        if (Eink.isKompakt) {
            group().apply {
                addView(sectionTitle("Touch button"))
                addView(note("The button below the screen. On KompaktOS a short touch is always Home."))
                addView(choicesBlock("Hold", if (QuickPanelService.enabled(this@MainActivity)) "Touch for about a second" else "Touch for about a second. Needs the side panel on (Accessibility)",
                    listOf("Nothing", "Full refresh"), if (prefs.holdRefresh) 1 else 0) { i ->
                    prefs.holdRefresh = i == 1
                    sendBroadcast(Intent(QuickPanelService.ACTION_STRIP).setPackage(packageName))
                    renderButtons()
                })
                addView(note("Volume keys always turn pages the same way in every rotation."))
            }
            return
        }
        group().apply {
            addView(sectionTitle("Touch button"))
            addView(note("The button below the screen. These are the actions built into the device; changes apply on the next touch."))
            if (!TouchButton.canChange(this@MainActivity)) {
                addView(note(if (Shell.allowed()) "Allow it first: Side panel › Advanced › Turns itself back on."
                    else "Needs the same permission as Side panel › Advanced › Turns itself back on. Start Shizuku, or run: ${Survival.grantAdbCommand(this@MainActivity)}"))
                return
            }
            val on = TouchButton.on(this@MainActivity)
            addView(toggleRow("Touch button", if (on) "On" else "Off: touching it does nothing", on) {
                TouchButton.setOn(this@MainActivity, !on); renderButtons()
            })
        }
        if (!TouchButton.on(this)) return
        fun options(title: String, sub: String, labels: List<String>, current: Int, perRow: Int, pick: (Int) -> Unit) = group().apply {
            addView(vbox().apply {
                setPadding(g, dp(8), g, dp(2))
                addView(text(title, Type.L, medium = true))
                addView(text(sub, Type.S, color = Ink.MUTED))
            })
            labels.indices.chunked(perRow).forEach { row ->
                addView(hbox().apply {
                    setPadding(dp(12), dp(4), dp(12), dp(4))
                    for (i in row) addView(chip(labels[i], i == current) { pick(i); renderButtons() }.apply { maxLines = 2 },
                        lp(0, WRAP, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                    repeat(perRow - row.size) { addView(View(this@MainActivity), lp(0, 1, 1f)) }
                })
            }
        }
        options("Tap", "A short touch", TouchButton.TAP_ACTIONS, TouchButton.tap(this), 4) { TouchButton.setTap(this, it) }
        options("Hold", "Touch for about a second", TouchButton.HOLD_ACTIONS, TouchButton.hold(this), 3) { TouchButton.setHold(this, it) }
            .addView(note("For anything else (open an app, switch the panel), pick Page up or Volume up and map that key in Key Mapper."))
    }

    // ---- About ------------------------------------------------------------------------------------------

    private fun renderAbout() {
        begin()
        group().apply {
            addView(hbox().apply {
                setPadding(g, dp(10), g, dp(10))
                minimumHeight = dp(56)
                addView(text("Version", Type.L, bold = true), lp(0, WRAP, 1f))
                addView(text(version(), Type.L, bold = true))
            })
            addView(divider())
        }
        group().addView(note("Internet is used only for the Home sleep screen's weather: when you set a city, its name is searched and its coordinates are sent to Open-Meteo (open-meteo.com, no account), as often as the sleep screen updates (or at most every 15, 30 or 60 min, as chosen) and only on a network that's already on. \"Estimate from internet connection\" (only when you tap it) sends your public IP address to ipinfo.io once. Nothing else leaves the phone. Not affiliated with Moaan."))
        group().addView(note("The side panel's accessibility service only learns which app is in front, and that a page scrolled, text was typed or the screen changed. It doesn't read what's on screen or what you type."))
        group().addView(note("E-ink control: ${Eink.describe()}."))
        group().addView(note("Source and help: github.com/rn-os/inkpalm-plus · $packageName"))
        show()
    }

    private fun pickImage(request: Int) = runCatching {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), request)
    }.onFailure { toast("No file picker") }

    /** Runs a blocking bridge step ([job] returns an error or null), then says how it went. */
    private fun work(job: () -> String?, done: String) = Eink.post {
        val err = job()
        Shell.main.post { toast(err?.let { "Didn't work: $it" } ?: done); if (!isDestroyed && section != SECTION_EINK) render() }
    }

    @Deprecated("Activity result API isn't used in this app (no AndroidX)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            PICK_SLEEP -> work({ SleepImages.setSleep(this, uri) }, "Sleep screen picture set")
            PICK_POWEROFF -> work({ SleepImages.setPowerOff(this, SleepImages.PowerOff.CUSTOM, uri) }, "Power-off picture set")
        }
    }

    // ---- Stock firmware: Screen & light = Moaan's per-app e-ink settings (scrolls) ------------------------------

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
            minimumHeight = dp(56)
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
                minimumHeight = dp(56)
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
        deviceBox.addView(stepperRow("Text size", "", "%.2f×".format(scale), scale > 0.7f, scale < 1.6f,
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
        minimumHeight = dp(56)
        pressable()
        addView(icon(app.icon, 40, tint = false))
        addView(vbox().apply {
            addView(text(app.label, Type.L, medium = true))
            val summary = p?.takeIf { !it.isDefault }?.summary()
            addView(text(summary ?: "Default", Type.S, bold = summary != null, color = if (summary != null) Ink.INK else Ink.MUTED))
        }, lp(0, WRAP, 1f).apply { marginStart = dp(14) })
        addView(chevron())
        setOnClickListener {
            startActivity(Intent(this@MainActivity, AppActivity::class.java).putExtra(AppActivity.EXTRA_PKG, app.pkg))
        }
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_SECTION = "section"
        /** Optional: the tab to open ([Tabs] key: face, updates, home, screen, light). */
        const val EXTRA_TAB = "tab"
        // The screens ([Sections]).
        const val SECTION_SLEEP = Sections.SLEEP
        const val SECTION_WEATHER = Sections.WEATHER
        const val SECTION_COUNTDOWNS = Sections.COUNTDOWNS
        const val SECTION_TRASH = Sections.TRASH
        /** KompaktOS's Screen & light; stock firmware opens [SECTION_EINK] instead. */
        const val SECTION_SCREEN = Sections.SCREEN
        const val SECTION_SCREEN_ADVANCED = Sections.SCREEN_ADVANCED
        const val SECTION_EINK = Sections.EINK
        const val SECTION_PANEL = Sections.PANEL
        const val SECTION_PANEL_ADVANCED = Sections.PANEL_ADVANCED
        const val SECTION_POWER = Sections.POWER
        const val SECTION_BUTTONS = Sections.BUTTONS
        const val SECTION_ABOUT = Sections.ABOUT
        // Older names, kept for links: each opens the screen (and tab) it moved to.
        const val SECTION_AOD = Sections.AOD
        const val SECTION_LIGHT = Sections.LIGHT
        const val SECTION_KEEP_ON = Sections.KEEP_ON
        const val SECTION_TOUCH = Sections.TOUCH
        const val SECTION_READING = Sections.READING

        private const val ANCHOR_KEEP_ON = Sections.KEEP_ON
        private const val ANCHOR_READING = Sections.READING
        private const val ANCHOR_RESULTS = "results"
        private const val STATE_TAB = "tab"
        private const val TAG = "InkPalmPlus"
        /** Tab height (dp; at least 48 to tap). */
        private const val TAB_HEIGHT = 48
        /** Landscape at least this wide (dp) shows two columns. */
        private const val TWO_COLUMNS = 560
        /** Room under a tab's last group (dp). */
        private const val CONTENT_BOTTOM = 4

        /** Each screen's last tab, for this session (the app's process). */
        private val lastTab = HashMap<String, String>()

        private const val PICK_SLEEP = 51
        private const val PICK_POWEROFF = 52
        private const val ASK_CALENDAR = 53
        private const val ASK_LOCATION = 54

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
 * The home menu's screens: the same screen code, as its own activity because the home screen is single-task and
 * would swallow the intent.
 */
class SectionActivity : MainActivity()
