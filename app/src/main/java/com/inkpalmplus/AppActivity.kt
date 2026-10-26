package com.inkpalmplus

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * One app's e-ink settings: DPI, refresh waveform, full-refresh interval,
 * contrast and Moaan's bleach options. Saved to Moaan's own table, which the
 * firmware applies whenever the app opens.
 */
class AppActivity : Activity() {
    private lateinit var pkg: String
    private lateinit var label: String
    private lateinit var body: LinearLayout
    private var saved: EinkDb.Profile? = null
    private var edit = EinkDb.Profile("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent.getStringExtra(EXTRA_PKG) ?: return finish()
        label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        edit = EinkDb.Profile(pkg)

        val root = vbox()
        root.addView(hbox().apply {
            setPadding(dp(8), dp(8), dp(16), dp(8))
            addView(inkIconButton(R.drawable.ic_back, "Back", 48) { finish() })
            addView(icon(runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull(), 40, tint = false).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(12)
            })
            addView(vbox().apply {
                addView(text(label, 20f, bold = true))
                addView(text(pkg, 12f, color = Ink.MUTED))
            }, lp(0, WRAP, 1f).apply { marginStart = dp(12) })
        })
        root.addView(divider(2))
        body = vbox()
        root.addView(ScrollView(this).apply { addView(body) }, lp(MATCH, 0, 1f))
        root.addView(divider(2))
        root.addView(hbox().apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(inkButton("Reset") { edit = EinkDb.Profile(pkg, exists = edit.exists); render() }, lp(0, WRAP, 1f).apply { marginEnd = dp(6) })
            addView(inkButton("Save") { save(restart = false) }, lp(0, WRAP, 1f).apply { marginEnd = dp(6) })
            addView(inkButton("Save + reopen") { save(restart = true) }, lp(0, WRAP, 1.3f))
        })
        setContentView(root)

        Shell.background({ EinkDb.read(this, pkg) }) { p ->
            saved = p
            edit = p
            render()
        }
    }

    private fun render() {
        body.removeAllViews()

        // DPI
        body.addView(sectionTitle("Size (DPI)"))
        body.addView(note("Lower fits more on screen; higher makes everything bigger. The screen's own is ${EinkDb.NATIVE_DPI}. Takes effect when the app reopens."))
        val dpi = edit.dpi
        body.addView(hbox().apply {
            setPadding(dp(16), dp(6), dp(16), dp(6))
            addView(inkButton("−10", 18f) { setDpi((if (dpi > 0) dpi else EinkDb.NATIVE_DPI) - 10) }, lp(dp(64), WRAP))
            addView(text(if (dpi > 0) "$dpi dpi" else "System", 22f, bold = true).apply { gravity = Gravity.CENTER }, lp(0, WRAP, 1f))
            addView(inkButton("+10", 18f) { setDpi((if (dpi > 0) dpi else EinkDb.NATIVE_DPI) + 10) }, lp(dp(64), WRAP))
        })
        body.addView(chips(listOf(-1, 200, 240, 276, 300, 330, 360, 400), { if (it < 0) "System" else "$it" }, dpi.takeIf { it > 0 } ?: -1) { setDpi(it) })

        // Refresh
        body.addView(sectionTitle("Refresh mode"))
        for (r in EinkDb.Refresh.entries) body.addView(choiceRow(r.title, r.detail, edit.refresh == r) {
            edit = edit.copy(refresh = r); render()
        })

        body.addView(sectionTitle("Full refresh"))
        body.addView(note("Flash the whole screen after this many partial updates, to clear ghosting."))
        body.addView(chips(listOf(-1, 5, 10, 15, 20, 30, 50), { if (it < 0) "System" else "Every $it" }, edit.fullEvery) {
            edit = edit.copy(fullEvery = it); render()
        })

        // Contrast
        body.addView(sectionTitle("Contrast"))
        body.addView(note("Darkens or lightens everything while this app is open."))
        val steps = EinkDb.CONTRAST_STEPS
        val idx = edit.contrast?.let { g -> steps.indexOfFirst { kotlin.math.abs(it - g) < 0.01f } } ?: -1
        val base = steps.indexOfFirst { kotlin.math.abs(it - EinkDb.DEFAULT_CONTRAST) < 0.01f }
        body.addView(hbox().apply {
            setPadding(dp(16), dp(6), dp(16), dp(6))
            addView(inkButton("Lighter", 15f) {
                val i = if (idx < 0) base else idx
                edit = edit.copy(contrast = steps[(i - 1).coerceAtLeast(0)]); render()
            }, lp(0, WRAP, 1f))
            addView(text(edit.contrast?.let { EinkDb.contrastLabel(it) } ?: "System", 17f, bold = true).apply { gravity = Gravity.CENTER }, lp(0, WRAP, 1.3f))
            addView(inkButton("Darker", 15f) {
                val i = if (idx < 0) base else idx
                edit = edit.copy(contrast = steps[(i + 1).coerceAtMost(steps.size - 1)]); render()
            }, lp(0, WRAP, 1f))
        })
        body.addView(chips(listOf(0), { "Use system contrast" }, if (edit.contrast == null) 0 else 1) {
            edit = edit.copy(contrast = null); render()
        })

        // Refresh helpers: follow the whole-device switches unless set here. Applied right away (no Save needed).
        body.addView(sectionTitle("Refresh helpers"))
        body.addView(note("Normally these follow the switches on InkPalm+'s home screen. Change them here only if this app misbehaves. Applies right away."))
        val prefs = Prefs(this)
        for ((key, title, _) in Prefs.HELPERS) {
            val own = prefs.helperOverride(key, pkg)
            val def = if (prefs.helperDefault(key)) "on" else "off"
            body.addView(text(title, 16f, medium = true).apply { setPadding(dp(16), dp(8), dp(16), 0) })
            body.addView(chips(listOf<Boolean?>(null, true, false), { if (it == null) "Default ($def)" else if (it) "On" else "Off" }, own) {
                prefs.setHelper(key, pkg, it); render()
            })
        }

        // Moaan extras: what they do is inferred from the firmware; see docs/firmware.md.
        body.addView(sectionTitle("Moaan extras"))
        body.addView(note("Moaan's own per-app options. Effects depend on the app."))
        body.addView(choiceRow("Bleach", "Whiten backgrounds and covers", edit.bleach, check = true) {
            edit = edit.copy(bleach = !edit.bleach); render()
        })
        body.addView(choiceRow("Bold text", "Android's high-contrast text while the app is open", edit.textPlus, check = true) {
            edit = edit.copy(textPlus = !edit.textPlus); render()
        })
        body.addView(choiceRow("Animation filter", "Skip in-app animations", edit.animFilter, check = true) {
            edit = edit.copy(animFilter = !edit.animFilter); render()
        })
        body.addView(View(this), lp(MATCH, dp(24)))
    }

    private fun setDpi(v: Int) {
        edit = edit.copy(dpi = if (v < 0) -1 else v.coerceIn(120, 560))
        render()
    }

    private fun save(restart: Boolean) {
        val p = edit
        Shell.background({
            val via = EinkDb.write(this, p)
            val stopped = via != null && restart && EinkDb.closeApp(this, pkg)
            via to stopped
        }) { (via, stopped) ->
            if (via == null) {
                toast("Couldn't save. Is Shizuku running?")
                return@background
            }
            saved = p.copy(exists = true)
            edit = saved!!
            when {
                stopped -> packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                restart -> toast("Saved. Close $label from Recents and open it again")
                else -> toast("Saved. Applies next time $label opens")
            }
        }
    }

    // ---- Views --------------------------------------------------------------------------------

    private fun note(s: String) = text(s, 13f, color = Ink.MUTED).apply { setPadding(dp(16), dp(4), dp(16), dp(4)) }

    private fun <T> chips(options: List<T>, title: (T) -> String, selected: T, onPick: (T) -> Unit): View =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(hbox().apply {
                setPadding(dp(12), dp(6), dp(12), dp(6))
                for (o in options) addView(chip(title(o), o == selected) { onPick(o) }, lp(WRAP, WRAP).apply { marginStart = dp(4); marginEnd = dp(4) })
            })
        }

    private fun choiceRow(title: String, detail: String, selected: Boolean, check: Boolean = false, onClick: () -> Unit): View = hbox().apply {
        setPadding(dp(16), dp(10), dp(16), dp(10))
        pressable()
        addView(text(if (selected) (if (check) "☑" else "●") else (if (check) "☐" else "○"), 20f, bold = true), lp(dp(32), WRAP))
        addView(vbox().apply {
            addView(text(title, 16f, bold = selected, medium = !selected))
            addView(text(detail, 13f, color = Ink.MUTED))
        }, lp(0, WRAP, 1f))
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_PKG = "pkg"
    }
}
