package com.inkpalmplus

import android.content.Context
import android.util.Log

/**
 * Automatic refresh helpers (mockup: docs/mockups/05-scrolling.png), each one switch for the
 * whole device with per-app overrides on the app's own page:
 *
 * - **Fast while scrolling** (on by default): the first scroll event switches the panel to Speed (A2);
 *   0.5 s after the last one, the app's own refresh mode comes back and one full refresh clears the ghosting.
 * - **Fast while typing** (on by default): the same while text is being typed, ending 1 s after the
 *   last keystroke. The service is told *that* text changed; what was typed isn't read.
 * - **Clean screen on open** (on by default): one full refresh when an app comes to the front,
 *   clearing what the previous app left behind.
 * - **Clean after idle** (off by default): one full refresh once the screen has been still for
 *   [IDLE_MS], to clear ghosting that built up unnoticed. Off by default because it can flash
 *   while you're reading a long page.
 *
 * Events come from the accessibility service; no screen content is read.
 */
class RefreshHelpers(private val context: Context) {
    private val prefs = Prefs(context)
    private var profile: EinkDb.Profile? = null
    private var foreground: String? = null
    private var boosting = false
    private val endBoost = Runnable { stopBoost() }
    private val idleClean = Runnable { cleanAfterIdle() }
    /** [IDLE_MS], shortened by the test hooks (which also turn on [debugIdle]). */
    var idleMs = IDLE_MS
        set(v) { field = v; debugIdle = v != IDLE_MS }
    private var debugIdle = false

    /** Something changed on screen since the last idle clean (no point cleaning a screen that's already clean). */
    private var dirty = false

    /** The app in front changed: remember its settings (to restore after a boost) and clean the screen. */
    fun onForeground(pkg: String) {
        foreground = pkg
        Shell.main.removeCallbacks(endBoost)
        boosting = false
        profile = null
        Shell.io.execute {
            val p = EinkDb.read(context, pkg)
            Shell.main.post { if (profile == null) profile = p }
        }
        if (prefs.helperOn(Prefs.CLEAN_ON_OPEN, pkg)) {
            // After SystemUI has applied the app's own settings and the first frame is drawn.
            Shell.main.postDelayed({ fullRefresh(); Log.i(TAG, "clean on open ($pkg)") }, 400)
            dirty = false
        } else {
            dirty = true
        }
        armIdle()
    }

    /** The panel changed the app's settings: restore those after a boost. */
    fun onProfileChanged(p: EinkDb.Profile) {
        profile = p
    }

    fun onScroll(pkg: String, panelOpen: Boolean) = boost(pkg, panelOpen, Prefs.SCROLL_BOOST, SCROLL_SETTLE_MS, "scroll")

    fun onTyping(pkg: String, panelOpen: Boolean) = boost(pkg, panelOpen, Prefs.TYPING_BOOST, TYPING_SETTLE_MS, "typing")

    /**
     * Anything drew in the app in front: restarts the idle timer. Ignored right after a full refresh
     * InkPalm+ triggered, which itself reports a redraw; otherwise an idle app would flash every minute.
     */
    fun onContentChanged() {
        if (android.os.SystemClock.uptimeMillis() - lastRefresh < REFRESH_ECHO_MS) return
        if (debugIdle && !dirty) Log.i(TAG, "idle: screen changed ${android.os.SystemClock.uptimeMillis() - lastRefresh} ms after the last clean")
        dirty = true
        armIdle()
    }

    private var lastRefresh = 0L

    private fun fullRefresh() {
        lastRefresh = android.os.SystemClock.uptimeMillis()
        Shell.io.execute { Live.fullRefresh() }
    }

    private fun boost(pkg: String, panelOpen: Boolean, key: String, settleMs: Long, why: String) {
        dirty = true
        armIdle()
        if (panelOpen || !prefs.helperOn(key, pkg)) return
        if (!boosting) {
            boosting = true
            Shell.io.execute { Live.setProperty("persist.sys.mogu.refresh_type", SPEED.toString()) }
            Log.i(TAG, "$why boost on ($pkg)")
        }
        Shell.main.removeCallbacks(endBoost)
        Shell.main.postDelayed(endBoost, settleMs)
    }

    private fun stopBoost() {
        if (!boosting) return
        boosting = false
        val p = profile
        lastRefresh = android.os.SystemClock.uptimeMillis()
        dirty = false   // this refresh cleans the screen; idle clean only follows later changes
        Shell.io.execute {
            if (p != null) Live.applyDisplay(context, p)
            Live.fullRefresh()
        }
        Log.i(TAG, "boost off")
    }

    private fun armIdle() {
        Shell.main.removeCallbacks(idleClean)
        val pkg = foreground ?: return
        if (prefs.helperOn(Prefs.IDLE_CLEAN, pkg)) Shell.main.postDelayed(idleClean, idleMs)
        else if (debugIdle) Log.i(TAG, "idle: off for $pkg")
    }

    private fun cleanAfterIdle() {
        if (!dirty || boosting) {
            Log.i(TAG, "idle: nothing to clean (dirty=$dirty boosting=$boosting)")
            return
        }
        dirty = false
        fullRefresh()
        Log.i(TAG, "idle clean ($foreground)")
    }

    fun release() {
        Shell.main.removeCallbacks(endBoost)
        Shell.main.removeCallbacks(idleClean)
        if (boosting) stopBoost()
    }

    companion object {
        private const val TAG = "InkPalmPlus"
        /** Rockchip A2: the fastest waveform, black and white. */
        const val SPEED = 12
        const val SCROLL_SETTLE_MS = 500L
        const val TYPING_SETTLE_MS = 1_000L
        const val IDLE_MS = 60_000L
        /** A full refresh shows up as a redraw for about this long afterwards. */
        const val REFRESH_ECHO_MS = 1_500L
    }
}
