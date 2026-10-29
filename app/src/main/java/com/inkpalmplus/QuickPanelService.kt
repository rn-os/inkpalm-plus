package com.inkpalmplus

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.os.Build
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Keeps the edge strips on screen: one opens [SidePanel] (recent apps), the other, on KompaktOS, [ScreenPanel]
 * (screen modes and the front light); [QuickPanel] is stock firmware's e-ink panel for the app in front.
 * Also tracks which app that is (window-state events), so the panels open on it, and puts the app's screen mode
 * on ([ScreenModes]). On KompaktOS it also does what InkControl did: the front light ([Light]) follows the screen
 * and the light sensor, auto power-off ([AutoOff]) is armed at sleep, an inward swipe from the edge is Back, and
 * holding the touch button is a full refresh.
 */
class QuickPanelService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private val strips = mutableListOf<View>()
    private var panel: QuickPanel? = null
    private var side: SidePanel? = null
    private var screen: ScreenPanel? = null
    lateinit var recents: RecentApps
        private set
    private lateinit var rotation: Rotation
    lateinit var helpers: RefreshHelpers
        private set
    /** The last real app in front (not InkPalm+, SystemUI or a keyboard). */
    var foreground: String? = null
        private set

    private val packageGone = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pkg = i.data?.schemeSpecificPart ?: return
            recents.remove(pkg)
            RecentApps.Info.forget(pkg)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_OPEN -> openPanel()
                ACTION_CLOSE -> closePanel()
                ACTION_STRIP -> { showStrip(preview = i.getBooleanExtra(EXTRA_PREVIEW, false)); syncKeyFilter(); syncLightSensor() }
                ACTION_CLEAR_RECENTS -> recents.clear()
                ACTION_TEST -> test(i)
            }
        }
    }

    /** The light follows the screen (off at sleep, back at wake); auto power-off runs only asleep on battery. */
    private val power = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                // The always-on display's redraw wakes and sleeps the screen: not the user's, so the light stays
                // off and auto power-off keeps counting.
                // Its cover ([WakeCover]) goes 1 s after that sleep, once the HWC has drawn; at once on the user's wake.
                Intent.ACTION_SCREEN_OFF -> {
                    Shell.main.removeCallbacks(redrawStuck)
                    if (Aod.redrawSleep()) { WakeCover.hideAfter(1_000); return }
                    WakeCover.hide("user sleep")
                    Light.screenOff(); syncLightSensor(); AutoOff.arm(c); Aod.onScreenOff(c)
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (Aod.redrawWake()) { Shell.main.removeCallbacks(redrawStuck); Shell.main.postDelayed(redrawStuck, REDRAW_STUCK_MS); return }
                    userWake(c)
                }
                Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> Aod.tick(c)
                // Plugged in while asleep: Live updates start again if the battery had stopped them.
                Intent.ACTION_POWER_CONNECTED -> { AutoOff.cancel(c); if (!c.getSystemService(android.os.PowerManager::class.java).isInteractive) Aod.schedule(c) }
                Intent.ACTION_POWER_DISCONNECTED -> AutoOff.arm(c)
            }
        }
    }

    /** The user's wake: the cover off, the light back, auto power-off and Live updates stopped. */
    private fun userWake(c: Context) {
        WakeCover.hide("user wake")
        Light.apply(c); syncLightSensor(); AutoOff.cancel(c); Aod.onScreenOn(c)
    }

    /** The bridge puts the screen back to sleep ~5 s after its redraw wake. Still awake well after that: the wake was
     *  the user's (pressed while the redraw was in flight, so the bridge didn't wake it itself). */
    private val redrawStuck = Runnable {
        if (!getSystemService(android.os.PowerManager::class.java).isInteractive) return@Runnable
        Log.i(TAG, "redraw wake didn't end: taken as the user's")
        Aod.redrawWakeOver()
        userWake(this)
    }

    /** Android's brightness slider or adaptive brightness changed: the light follows. */
    private val brightness = object : android.database.ContentObserver(Shell.main) {
        override fun onChange(self: Boolean) { syncLightSensor(); Light.apply(this@QuickPanelService) }
    }

    override fun onServiceConnected() {
        instance = this
        Eink.init(this)
        Prefs(this).panelWanted = true
        wm = getSystemService(WindowManager::class.java)
        rotation = Rotation(this, wm)
        recents = RecentApps(this)
        helpers = RefreshHelpers(this)
        // Only apps holding InkPalm+'s signature permission (InkPalm+ itself) can send these.
        val filter = IntentFilter().apply {
            addAction(ACTION_OPEN); addAction(ACTION_CLOSE); addAction(ACTION_STRIP); addAction(ACTION_CLEAR_RECENTS)
        }
        // Android 13+ wants each receiver marked exported or not (Android 14+ throws without it; KompaktOS is 16).
        val notExported = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0
        val exported = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0
        registerReceiver(receiver, filter, PERMISSION, null, notExported)
        // The test hooks (tools/device-test.sh, sent from adb) exist only in debuggable builds.
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            registerReceiver(receiver, IntentFilter(ACTION_TEST), exported)
            testHooks = true
        }
        // Uninstalled apps leave the recent list (so the panel never has to check while drawing).
        registerReceiver(packageGone, IntentFilter(Intent.ACTION_PACKAGE_FULLY_REMOVED).apply { addDataScheme("package") })
        registerReceiver(power, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, notExported)
        if (Light.supported()) {
            for (k in listOf(android.provider.Settings.System.SCREEN_BRIGHTNESS, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE))
                contentResolver.registerContentObserver(android.provider.Settings.System.getUriFor(k), false, brightness)
        }
        // KompaktOS: contrast and refresh type back after a restart, the light on, the key filter if wanted.
        ScreenModes.restore(this)
        Light.apply(this)
        syncLightSensor()
        syncKeyFilter()
        Aod.prepare(this)
        recents.warm()
        showStrip()
        // The app in front isn't known until the next window change: the service doesn't read window
        // content (so rootInActiveWindow is null), only window-state events.
        Log.i(TAG, "quick panel service connected")
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        runCatching { unregisterReceiver(packageGone) }
        runCatching { unregisterReceiver(power) }
        runCatching { contentResolver.unregisterContentObserver(brightness) }
        runCatching { getSystemService(android.hardware.SensorManager::class.java).unregisterListener(lux) }
        Shell.main.removeCallbacks(endPreview); Shell.main.removeCallbacks(prepare); Shell.main.removeCallbacks(homeHold)
        Shell.main.removeCallbacks(redrawStuck)
        WakeCover.hide("service gone")
        removeStrip()
        closePanel()
        closeSide()
        closeScreen()
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
        if (pkg == KOMPAKT_SERVICE) {
            // KompaktOS's refresh (its QS tile, after its mode tiles) shows a bare black View and does nothing else
            // here: make it a real refresh. Its windows are never the app in front.
            if (event.className?.toString() == View::class.java.name) ScreenModes.kompaktFlashSeen(this)
            return
        }
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
            if (pkg == switchTo) {
                Log.i(TAG, "timing: switch to $pkg took ${android.os.SystemClock.uptimeMillis() - switchAt} ms (tap to window)")
                switchTo = null
            }
            recents.record(pkg)
            recents.warm()
            prepareSoon()
            rotation.apply(Prefs(this).rotation(pkg))
            ScreenModes.onForeground(this, pkg)
            helpers.onForeground(pkg)
            // Another app came to the front under the panel: it belongs to the old one.
            if (panel != null && panel?.pkg != pkg) closePanel()
            if (side != null) closeSide()
            if (screen != null) closeScreen()
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
        Aod.onConfigChanged(this)   // a landscape face if the user turned it; not asleep or in the redraw's wake
        showStrip()
        panel?.let { old ->
            old.dismiss(wm)
            panel = QuickPanel(this, old.pkg) { closePanel() }.also { it.show(wm) }
        }
        prepared = null
        side?.let { old ->
            old.dismiss(wm)
            side = SidePanel(this) { closeSide() }.also { it.show(wm) }
        }
        screen?.let { old ->
            old.dismiss(wm)
            screen = ScreenPanel(this) { closeScreen() }.also { it.show(wm) }
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

    /**
     * The strips: recent apps on the chosen edge, the screen panel on the other (KompaktOS), both mid-edge (30–65% of
     * the height) for a swipe along them. With edge Back on, an inward swipe on them is Back, and thinner Back-only
     * strips cover the rest of each edge (12–88%), clear of the status bar and the keyboard's bottom row.
     * [preview]: drawn solid black for a few seconds, while Settings changes their size or side.
     */
    fun showStrip(preview: Boolean = false) {
        removeStrip()
        Shell.main.removeCallbacks(endPreview)
        val prefs = Prefs(this)
        if (preview) Shell.main.postDelayed(endPreview, 3_000)
        val h = resources.displayMetrics.heightPixels
        val back: (() -> Unit)? = if (prefs.edgeBack) ({ back() }) else null
        val midTop = (h * 0.3f).toInt()
        val midBottom = (h * 0.65f).toInt()
        val top = (h * 0.12f).toInt()
        val bottom = (h * 0.88f).toInt()
        for (right in listOf(true, false)) {
            val trigger: (() -> Unit)? = when {
                right == prefs.edgeRight -> ({ toggleSide() })
                prefs.screenPanelOn -> ({ toggleScreen() })
                else -> null
            }
            if (trigger != null) addStrip(EdgeHandleView(this, right, prefs.showStrip, solid = preview, onBack = back, onTrigger = trigger),
                right, dp(prefs.stripWidthDp), midTop, midBottom - midTop)
            if (back == null) continue
            val w = dp(BACK_STRIP_DP)
            fun backOnly(y: Int, height: Int) = addStrip(EdgeHandleView(this, right, false, solid = preview, onBack = back, onTrigger = null), right, w, y, height)
            if (trigger != null) { backOnly(top, midTop - top); backOnly(midBottom, bottom - midBottom) } else backOnly(top, bottom - top)
        }
    }

    private fun addStrip(v: View, right: Boolean, width: Int, y: Int, height: Int) {
        val lp = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or if (right) Gravity.END else Gravity.START
            this.y = y
        }
        runCatching { wm.addView(v, lp) }.onSuccess { strips += v }.onFailure { Log.w(TAG, "strip: $it") }
    }

    private fun removeStrip() {
        strips.forEach { runCatching { wm.removeView(it) } }
        strips.clear()
    }

    /** Edge Back (KompaktOS has no navigation bar, so Android's own back gesture never works there). */
    fun back() {
        Log.i(TAG, "edge back")
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // ---- Repaint, light sensor, touch button ----------------------------------------------------

    /**
     * The panel only redraws pixels that change, in the mode current at that moment, so a new mode (or a full
     * refresh request) doesn't show on a still screen. A brief full-screen white overlay makes every non-white
     * pixel change twice, redrawing the whole screen. Any thread.
     */
    fun repaint(holdMs: Long) = Shell.main.post {
        val v = View(this).apply { setBackgroundColor(Ink.PAPER) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        )
        if (runCatching { wm.addView(v, lp) }.isFailure) return@post
        Shell.main.postDelayed({ runCatching { wm.removeView(v) } }, holdMs)
    }

    private var luxListening = false
    private val lux = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) = Light.onLux(this@QuickPanelService, e.values[0])
        override fun onAccuracyChanged(s: android.hardware.Sensor?, accuracy: Int) {}
    }

    /** The light sensor, only while the screen is on and adaptive brightness is on (the light follows it, see [Light]).
     *  Sampled once a second; the last scale stays on the LEDs while it's off. */
    private fun syncLightSensor() {
        val sm = getSystemService(android.hardware.SensorManager::class.java) ?: return
        val want = Light.supported() && Light.followSystem(this) && Light.auto(this) &&
            getSystemService(android.os.PowerManager::class.java).isInteractive && !Aod.redrawing()
        if (want == luxListening) return
        if (want) sm.getDefaultSensor(android.hardware.Sensor.TYPE_LIGHT)?.let {
            Light.sensorStarted(this)
            luxListening = sm.registerListener(lux, it, Light.SENSOR_PERIOD_US)
        } else {
            sm.unregisterListener(lux)
            luxListening = false
            Light.sensorStopped()
        }
    }

    /** Holding the touch button (KEY_HOMEPAGE → Home on KompaktOS) for [HOLD_MS] is a full refresh, as on stock. */
    private var filteringKeys = false
    private var homeHeld = false
    private val homeHold = Runnable { homeHeld = true; Log.i(TAG, "touch button held: full refresh"); ScreenModes.fullRefresh() }

    /** Key events reach the service only while the option is on (each one passes through it first). */
    private fun syncKeyFilter() {
        val want = Prefs(this).holdRefresh
        val info = serviceInfo ?: return
        val flag = android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        if (want == (info.flags and flag != 0)) { filteringKeys = want; return }
        info.flags = if (want) info.flags or flag else info.flags and flag.inv()
        serviceInfo = info
        filteringKeys = want
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!filteringKeys || event.keyCode != KeyEvent.KEYCODE_HOME) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                homeHeld = false
                Shell.main.postDelayed(homeHold, HOLD_MS)
            }
            KeyEvent.ACTION_UP -> {
                Shell.main.removeCallbacks(homeHold)
                // A short touch is still Home.
                if (!homeHeld && !event.isCanceled) performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
        return true
    }

    /**
     * Test hooks for tools/device-test.sh (debuggable builds only):
     * `am broadcast -a com.inkpalmplus.TEST -p com.inkpalmplus --es cmd dpi --es value 240`
     * Answers in logcat: "InkPalmPlus: test <cmd> <value> ok|fail <state>".
     */
    private var testHooks = false

    private fun test(i: Intent) {
        if (!testHooks) return
        val cmd = i.getStringExtra("cmd").orEmpty()
        val value = i.getStringExtra("value").orEmpty()
        val ok = when (cmd) {
            "state" -> true
            "open" -> { if (panel == null) openPanel(); panel != null }
            "side" -> { if (side == null) toggleSide(); side != null }
            "side_close" -> { closeSide(); true }
            "panel_close" -> { closePanel(); true }
            "side_tap" -> side?.tap(value.toInt()) ?: false
            "side_close_app" -> side?.testClose(value.toInt()) ?: false
            "side_clear_all" -> side?.testClearAll() ?: false
            "side_page" -> side?.testPage(value == "next") ?: false
            "side_card" -> side?.testCard(hold = value == "hold") ?: false
            "side_book" -> side?.testBook(value.toInt()) ?: false
            "side_browse" -> side?.testBrowse() ?: false
            "light_state" -> { Log.i(TAG, screen?.describeLight() ?: "light: panel closed"); true }
            "screen" -> { if (screen == null) toggleScreen(); screen != null }
            "screen_close" -> { closeScreen(); true }
            "screen_mode" -> screen?.testMode(value) ?: false
            "screen_scope" -> screen?.testScope(value == "app") ?: false
            "screen_state" -> { Log.i(TAG, "screen: ${screen?.describe() ?: "closed"} backend=${Eink.backend}"); true }
            "screen_refresh" -> { ScreenModes.fullRefresh(); true }
            "contrast" -> { ScreenModes.setContrast(this, value.toInt()); true }
            "edge_back" -> { Prefs(this).edgeBack = value == "on"; showStrip(); true }
            "back" -> { back(); true }
            // "none", "neoreader", "koreader": the side panel's reader app (screenshots without a book title).
            "reader" -> { Prefs(this).readerApp = value; prepared = null; true }
            "hold_refresh" -> { Prefs(this).holdRefresh = value == "on"; syncKeyFilter(); true }
            // "off", "panda": the power-off screen (blocking work on Eink's worker; the result is logged).
            "poweroff" -> { val c = SleepImages.PowerOff.valueOf(value.uppercase()); Eink.post { Log.i(TAG, "poweroff ${c.name}: ${SleepImages.setPowerOff(this, c) ?: "ok"}") }; true }
            // Always-on display: "aod_face CLOCK", "aod_interval 5", "aod_render" (draw and install now),
            // "aod_soon 20" (the Live alarm in 20 s), "aod_fire" (one Live update now), "aod_arrow" / "aod_arrow 1".
            "aod_face" -> { val f = Aod.Face.valueOf(value.uppercase()); Eink.post { Log.i(TAG, "aod_face ${f.name}: ${Aod.setFace(this, f) ?: "ok"}") }; true }
            // The Agenda face's style ("aod_agenda WEEK"), and every style in both orientations to files/ ("aod_agenda_dump").
            "aod_agenda" -> { val st = Agenda.Style.valueOf(value.uppercase()); Eink.post { Aod.setAgendaStyle(this, st) }; true }
            "aod_agenda_dump" -> { Eink.post { Aod.dumpAgenda(this) }; true }
            "aod_interval" -> { Aod.setInterval(this, value.toInt()); true }
            "aod_render" -> { Aod.prepare(this, force = true); true }
            "aod_soon" -> { Aod.schedule(this, value.toLong() * 1000); true }
            "aod_fire" -> { Eink.post { Aod.update(this) }; true }
            // The auto power-off cover alone (no power-off), for WakeCover.TIMEOUT_MS: "cover_poweroff"; "cover_hide".
            "cover_poweroff" -> { Eink.post { Aod.poweringOff(this, holdMs = 30_000L); Log.i(TAG, "cover_poweroff: ${AutoOff.showCover(this)}") }; true }
            "cover_hide" -> { WakeCover.hide("test"); true }
            "aod_arrow" -> { val r = value.toIntOrNull(); Eink.post { Aod.installArrow(this, r) }; true }
            // A picture file as the sleep-screen picture, as if picked: "aod_pick /sdcard/Download/x.png".
            "aod_pick" -> { Eink.post { Log.i(TAG, "aod_pick: ${SleepImages.setSleep(this, android.net.Uri.fromFile(java.io.File(value))) ?: "ok"}") }; true }
            "aod_state" -> { Log.i(TAG, "aod: face=${Aod.face(this)} interval=${Aod.interval(this)} rotation=${Aod.displayRotation(this)} redrawing=${Aod.redrawing()}"); true }
            "books_state" -> { Log.i(TAG, "books: ${side?.describeBooks() ?: "closed"}"); true }
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
        closeScreen(refresh = false)
        switchTo = null
        val t0 = android.os.SystemClock.uptimeMillis()
        val ready = prepared?.takeIf { it.fresh() }
        prepared = null
        side = (ready ?: SidePanel(this) { closeSide() }).also { it.show(wm, t0) }
    }

    /** A side panel built ahead of the next swipe ([SidePanel.prepare]), so opening only adds the window. */
    private var prepared: SidePanel? = null
    private val prepare = Runnable {
        if (side == null && ::wm.isInitialized) prepared = SidePanel(this) { closeSide() }.prepare()
    }

    /** Builds the next panel once things are quiet (not during an app switch, which it would slow). */
    private fun prepareSoon() {
        Shell.main.removeCallbacks(prepare)
        Shell.main.postDelayed(prepare, 1_500)
    }

    /** A switch started from the side panel: [switchTo] and when, to log how long it took to arrive. */
    var switchTo: String? = null
    var switchAt = 0L

    fun closeSide() {
        val s = side ?: return
        side = null
        s.dismiss(wm)
        prepareSoon()
        // Switching to an app that gets a clean refresh on open: that one refresh is enough, so the
        // panel doesn't flash the screen as well (two full refreshes made switching slower).
        val target = switchTo
        if (target != null && Prefs(this).helperOn(Prefs.CLEAN_ON_OPEN, target)) return
        Shell.io.execute { Thread.sleep(150); Live.fullRefresh() }
    }

    // ---- Screen panel (KompaktOS) ---------------------------------------------------------------

    /** The other strip opens the screen panel; a second swipe, ✕ or a tap outside closes it. */
    fun toggleScreen() {
        if (screen != null) return closeScreen()
        closePanel()
        side?.let { side = null; it.dismiss(wm); prepareSoon() }
        screen = ScreenPanel(this) { closeScreen() }.also { it.show(wm) }
    }

    fun closeScreen(refresh: Boolean = true) {
        val s = screen ?: return
        screen = null
        s.dismiss(wm)
        // The panel leaves a ghost on e-ink.
        if (refresh) Shell.io.execute { Thread.sleep(150); Live.fullRefresh() }
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
        const val PERMISSION = "com.inkpalmplus.permission.PANEL"
        const val KOMPAKT_SERVICE = "com.kompakt.service"
        /** Back-only strips are thinner than the panel strips: they eat taps on the edge's last few dp. */
        const val BACK_STRIP_DP = 10
        /** The firmware's own long press (PhoneWindowManager$18). */
        const val HOLD_MS = 800L
        /** A redraw wake still on after this long wasn't the bridge's (it sleeps again 2.5-3.5 s after waking). */
        const val REDRAW_STUCK_MS = 6_000L

        @Volatile var instance: QuickPanelService? = null
            private set

        fun enabled(context: Context): Boolean =
            android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.contains("${context.packageName}/") == true
    }
}
