package com.inkpalmplus

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Keeps the edge strip on screen and opens [QuickPanel] over the app in front.
 * Also tracks which app that is (window-state events), so the panel opens on it.
 */
class QuickPanelService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private var strip: View? = null
    private var panel: QuickPanel? = null
    private var side: SidePanel? = null
    lateinit var recents: RecentApps
        private set
    private lateinit var rotation: Rotation
    lateinit var helpers: RefreshHelpers
        private set
    /** The last real app in front (not InkPalm+, SystemUI or a keyboard). */
    var foreground: String? = null
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_OPEN -> openPanel()
                ACTION_CLOSE -> closePanel()
                ACTION_STRIP -> showStrip(preview = i.getBooleanExtra(EXTRA_PREVIEW, false))
                ACTION_CLEAR_RECENTS -> recents.clear()
                ACTION_TEST -> test(i)
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
        Prefs(this).panelWanted = true
        wm = getSystemService(WindowManager::class.java)
        rotation = Rotation(this, wm)
        recents = RecentApps(this)
        helpers = RefreshHelpers(this)
        val filter = IntentFilter().apply {
            addAction(ACTION_OPEN); addAction(ACTION_CLOSE); addAction(ACTION_STRIP); addAction(ACTION_CLEAR_RECENTS)
            if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) addAction(ACTION_TEST)
        }
        registerReceiver(receiver, filter, RECEIVER_EXPORTED_COMPAT)
        showStrip()
        // The app in front isn't known until the next window change: the service doesn't read window
        // content (so rootInActiveWindow is null), only window-state events.
        Log.i(TAG, "quick panel service connected")
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        removeStrip()
        closePanel()
        closeSide()
        if (::rotation.isInitialized) rotation.release()
        if (::helpers.isInitialized) helpers.release()
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    /** Only when the user switches the service off (a force-stop or crash never gets here). */
    override fun onUnbind(intent: Intent?): Boolean {
        Prefs(this).panelWanted = false
        Log.i(TAG, "quick panel switched off by the user")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (pkg != foreground) return
                when (event.eventType) {
                    AccessibilityEvent.TYPE_VIEW_SCROLLED -> helpers.onScroll(pkg, panel != null)
                    // Only that text changed; event.text (what was typed) is never read.
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> helpers.onTyping(pkg, panel != null)
                    else -> helpers.onContentChanged()
                }
                return
            }
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android" || pkg.contains("inputmethod") || isKeyboard(pkg)) return
        if (pkg != foreground) {
            // An app leaving the screen can report one more window change just after the next app's
            // (seen ~125 ms later, e.g. as it rebuilds for a rotation). Taking that as a switch back would
            // turn the screen the wrong way and clean it twice, so it's ignored.
            val now = android.os.SystemClock.uptimeMillis()
            if (pkg == left && now - leftAt < BOUNCE_MS) {
                Log.i(TAG, "ignored late window change from $pkg")
                return
            }
            left = foreground
            leftAt = now
            foreground = pkg
            Log.i(TAG, "foreground $pkg")
            recents.record(pkg)
            rotation.apply(Prefs(this).rotation(pkg))
            helpers.onForeground(pkg)
            // Another app came to the front under the panel: it belongs to the old one.
            if (panel != null && panel?.pkg != pkg) closePanel()
            if (side != null) closeSide()
        }
    }

    /** The app that was in front before [foreground], and when it left. */
    private var left: String? = null
    private var leftAt = 0L

    private val keyboards by lazy {
        runCatching { getSystemService(android.view.inputmethod.InputMethodManager::class.java).inputMethodList.map { it.packageName }.toSet() }.getOrDefault(emptySet())
    }
    private fun isKeyboard(pkg: String) = pkg in keyboards

    /** The screen turned: the strip and panel are laid out for the old shape. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::wm.isInitialized) return
        showStrip()
        panel?.let { old ->
            old.dismiss(wm)
            panel = QuickPanel(this, old.pkg) { closePanel() }.also { it.show(wm) }
        }
        side?.let { old ->
            old.dismiss(wm)
            side = SidePanel(this) { closeSide() }.also { it.show(wm) }
        }
    }

    // ---- Rotation -----------------------------------------------------------------------------

    fun currentRotation(): Rotation.Mode = rotation.mode

    /** Turns the screen for [pkg] now and remembers it for next time it comes to the front. */
    fun setRotation(pkg: String, mode: Rotation.Mode) {
        Prefs(this).setRotation(pkg, mode)
        if (pkg == foreground) rotation.apply(mode)
    }

    // ---- Strip --------------------------------------------------------------------------------

    private val endPreview = Runnable { showStrip() }

    /** [preview]: drawn solid black for a few seconds, while Settings changes its size or side. */
    fun showStrip(preview: Boolean = false) {
        removeStrip()
        Shell.main.removeCallbacks(endPreview)
        val prefs = Prefs(this)
        val v = EdgeHandleView(this, prefs.edgeRight, prefs.showStrip, solid = preview) { toggleSide() }
        if (preview) Shell.main.postDelayed(endPreview, 3_000)
        val h = resources.displayMetrics.heightPixels
        val lp = WindowManager.LayoutParams(
            dp(prefs.stripWidthDp), (h * 0.35f).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or if (prefs.edgeRight) Gravity.END else Gravity.START
            y = (h * 0.3f).toInt()
        }
        runCatching { wm.addView(v, lp) }.onSuccess { strip = v }.onFailure { Log.w(TAG, "strip: $it") }
    }

    private fun removeStrip() {
        strip?.let { runCatching { wm.removeView(it) } }
        strip = null
    }

    /**
     * Test hooks for tools/device-test.sh (debuggable builds only):
     * `am broadcast -a com.inkpalmplus.TEST -p com.inkpalmplus --es cmd dpi --es value 240`
     * Answers in logcat: "InkPalmPlus: test <cmd> <value> ok|fail <state>".
     */
    private fun test(i: Intent) {
        val cmd = i.getStringExtra("cmd").orEmpty()
        val value = i.getStringExtra("value").orEmpty()
        val ok = when (cmd) {
            "state" -> true
            "open" -> { if (panel == null) openPanel(); panel != null }
            "side" -> { if (side == null) toggleSide(); side != null }
            "side_close" -> { closeSide(); true }
            "side_tap" -> side?.tap(value.toInt()) ?: false
            "side_close_app" -> side?.testClose(value.toInt()) ?: false
            "side_clear_all" -> side?.testClearAll() ?: false
            "side_page" -> side?.testPage(value == "next") ?: false
            "side_state" -> { Log.i(TAG, "side: ${side?.describe() ?: "closed"}"); true }
            "remember" -> { recents.record(value); true }
            "recents_clear" -> { recents.clear(); true }
            // "scroll_boost:off", "idle_clean:on", "typing_boost:default": the whole-device switch for a helper.
            "helper" -> { val (k, v) = value.split(":"); Prefs(this).apply { if (v == "default") sp.edit().remove(k).apply() else setHelperDefault(k, v == "on") }; true }
            "idle_ms" -> { helpers.idleMs = value.toLong(); true }
            // Puts the app's data folder back to owner-only and removes test files (sleep-image experiments).
            "fixdata" -> runCatching {
                java.io.File(filesDir, "sleep.png").delete()
                android.system.Os.chmod(dataDir.absolutePath, "700".toInt(8))
                true
            }.getOrDefault(false)
            // A system font by path ("system/fonts/Roboto-Medium.ttf"); "original" for the device's own.
            "font" -> {
                val target = if (value == "original") Fonts.original(this) else value
                Fonts.list(this).firstOrNull { it.path == target }?.let { f -> Shell.io.execute { Log.i(TAG, "font ${f.path}: ${Fonts.apply(this, f)}") }; true } ?: false
            }
            // "tap:0", "hold:4", "on:1": the touch button's settings (Moaan's Settings.Global keys).
            "touch" -> {
                val (k, v) = value.split(":")
                when (k) { "tap" -> TouchButton.setTap(this, v.toInt()); "hold" -> TouchButton.setHold(this, v.toInt()); else -> TouchButton.setOn(this, v == "1") }
            }
            "strip" -> { Prefs(this).stripWidthDp = value.toInt(); showStrip(preview = true); true }
            "strip_side" -> { Prefs(this).edgeRight = value == "right"; showStrip(preview = true); true }
            "rotate" -> { val f = foreground; if (f != null) setRotation(f, Rotation.Mode.of(value)); f != null }
            else -> panel?.test(cmd, value) ?: false
        }
        Log.i(TAG, "test $cmd $value ${if (ok) "ok" else "fail"} foreground=$foreground panel=${panel?.pkg} rotation=${rotation.mode.name}")
    }

    // ---- Side panel (recent apps) -------------------------------------------------------------

    /** The strip opens the side panel; a second swipe, ✕ or a tap outside closes it. */
    fun toggleSide() {
        if (side != null) return closeSide()
        closePanel()
        side = SidePanel(this) { closeSide() }.also { it.show(wm) }
    }

    fun closeSide() {
        val s = side ?: return
        side = null
        s.dismiss(wm)
        Shell.io.execute { Thread.sleep(150); Live.fullRefresh() }
    }

    // ---- Panel --------------------------------------------------------------------------------

    fun openPanel() {
        if (panel != null) return closePanel()
        val pkg = foreground ?: return
        panel = QuickPanel(this, pkg) { closePanel() }.also { it.show(wm) }
    }

    fun closePanel() {
        val p = panel ?: return
        panel = null
        p.dismiss(wm)
        // The panel leaves a ghost on e-ink.
        Shell.io.execute { Thread.sleep(150); Live.fullRefresh() }
    }

    companion object {
        const val TAG = "InkPalmPlus"
        const val ACTION_OPEN = "com.inkpalmplus.OPEN_PANEL"
        const val ACTION_CLOSE = "com.inkpalmplus.CLOSE_PANEL"
        const val ACTION_STRIP = "com.inkpalmplus.REFRESH_STRIP"
        const val ACTION_TEST = "com.inkpalmplus.TEST"
        const val ACTION_CLEAR_RECENTS = "com.inkpalmplus.CLEAR_RECENTS"
        const val EXTRA_PREVIEW = "preview"
        /** Window changes from the app that just left, within this long, are its last gasp, not a switch back. */
        const val BOUNCE_MS = 500L
        private const val RECEIVER_EXPORTED_COMPAT = 0x2

        @Volatile var instance: QuickPanelService? = null
            private set

        fun enabled(context: Context): Boolean =
            android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.contains("${context.packageName}/") == true
    }
}
