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
class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var accessBox: LinearLayout
    private lateinit var deviceBox: LinearLayout
    private lateinit var appsBox: LinearLayout
    private lateinit var panelBox: LinearLayout
    private var filter = ""
    private var apps: List<AppEntry> = emptyList()
    private var profiles: Map<String, EinkDb.Profile> = emptyMap()

    /** Icon loaded with the list, off the main thread: ~100 icons are slow to decode. */
    data class AppEntry(val pkg: String, val label: String, val icon: android.graphics.drawable.Drawable?)

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> reload() }
    private val binderListener = Shizuku.OnBinderReceivedListener { Shell.main.post { reload() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = vbox()
        root.addView(text(getString(R.string.app_name), 26f, bold = true).apply {
            setPadding(dp(16), dp(16), dp(16), dp(4))
        })
        root.addView(text("Per-app DPI, refresh and contrast for the InkPalm Plus", 14f, color = Ink.MUTED).apply {
            setPadding(dp(16), 0, dp(16), dp(8))
        })
        accessBox = vbox().also { root.addView(it) }
        root.addView(sectionTitle("Quick panel"))
        panelBox = vbox().also { root.addView(it) }
        root.addView(sectionTitle("Whole device"))
        deviceBox = vbox().also { root.addView(it) }
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
        appsBox = vbox().also { root.addView(it) }
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
        Shell.background({
            val (via, rows) = EinkDb.load(this)
            Triple(via, launchableApps(), rows)
        }) { (via, list, rows) ->
            apps = list
            profiles = rows
            renderAccess(via)
            renderPanel()
            renderDevice()
            renderApps()
        }
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
        panelBox.addView(text("Swipe up or down along the screen edge in any app to change its size, refresh, contrast and rotation while you look at it.", 14f, color = Ink.MUTED).apply {
            setPadding(dp(16), dp(8), dp(16), dp(4))
        })
        val on = QuickPanelService.enabled(this)
        panelBox.addView(toggleRow("Quick panel", if (on) "On" else "Turn on InkPalm+ in Accessibility", on) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        val live = Live.canNudge(this)
        panelBox.addView(toggleRow("Live size changes", if (live) "Apps redraw at the new DPI in place" else "Allow \"Modify system settings\" so apps redraw at the new DPI without reopening", live) {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:$packageName")))
        })
        val prefs = Prefs(this)
        if (on || Prefs(this).panelWanted) {
            val listed = Survival.inWhitelist(this)
            panelBox.addView(toggleRow("Survives Clear Memory",
                if (listed) "InkPalm+ is on Moaan's Clear Memory whitelist"
                else if (Shell.allowed()) "Tap to add InkPalm+ to Moaan's whitelist; Clear Memory would switch the panel off"
                else "Clear Memory would switch the panel off. Start Shizuku, or run: ${Survival.whitelistAdbCommand(this)}", listed) {
                if (!listed) Shell.background({ Survival.addToWhitelist(this) }) { ok -> if (!ok) toast("Needs Shizuku"); renderPanel() }
            })
            val repair = Survival.canRepair(this)
            panelBox.addView(toggleRow("Turns itself back on",
                if (repair) "If the panel is ever switched off by mistake, InkPalm+ turns it back on"
                else if (Shell.allowed()) "Tap to let InkPalm+ turn the panel back on if it's ever switched off"
                else "Start Shizuku, or run: ${Survival.grantAdbCommand(this)}", repair) {
                if (!repair) Shell.background({ Survival.grantRepair(this) }) { ok -> if (!ok) toast("Needs Shizuku"); renderPanel() }
            })
        }
        fun previewStrip() = sendBroadcast(Intent(QuickPanelService.ACTION_STRIP).setPackage(packageName).putExtra(QuickPanelService.EXTRA_PREVIEW, true))
        panelBox.addView(hbox().apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(vbox().apply {
                addView(text("Strip side", 17f, medium = true))
                addView(text("Swipe up or down along this edge", 13f, color = Ink.MUTED))
            }, lp(0, WRAP, 1f))
            addView(chip("Left", !prefs.edgeRight) { prefs.edgeRight = false; previewStrip(); renderPanel() }, lp(WRAP, WRAP).apply { marginEnd = dp(6) })
            addView(chip("Right", prefs.edgeRight) { prefs.edgeRight = true; previewStrip(); renderPanel() })
        })
        val w = prefs.stripWidthDp
        panelBox.addView(stepperRow("Strip width", "$w dp",
            onMinus = { prefs.stripWidthDp = w - 2; previewStrip(); renderPanel() },
            onPlus = { prefs.stripWidthDp = w + 2; previewStrip(); renderPanel() }))
        panelBox.addView(toggleRow("Show strip", "A thin black line where you swipe", prefs.showStrip) {
            prefs.showStrip = !prefs.showStrip
            sendBroadcast(Intent(QuickPanelService.ACTION_STRIP).setPackage(packageName))
            renderPanel()
        })
    }

    // ---- Whole device -------------------------------------------------------------------------

    private fun renderDevice() {
        deviceBox.removeAllViews()
        val animOff = ANIMATION_KEYS.all { Settings.Global.getFloat(contentResolver, it, 1f) == 0f }
        deviceBox.addView(toggleRow("Animations off", "Window, transition and animator scales at 0. Less flashing on e-ink.", animOff) { on ->
            val v = if (on) "0" else "1"
            Shell.background({ ANIMATION_KEYS.all { k -> putGlobal(k, v) } }) { ok ->
                if (!ok) toast("Needs Shizuku")
                renderDevice()
            }
        })
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
