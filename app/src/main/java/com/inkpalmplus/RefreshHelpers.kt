package com.inkpalmplus

import android.content.Context
import android.util.Log

/**
 * Two automatic refresh helpers (mockup: docs/mockups/05-scrolling.png), on by default and
 * switchable for the whole device or per app:
 *
 * - **Fast while scrolling:** the first scroll event switches the panel to Speed (A2); 0.5 s after
 *   the last one, the app's own refresh mode comes back and one full refresh clears the ghosting.
 *   Scroll events come from the accessibility service (no screen content is read).
 * - **Clean screen on open:** one full refresh when an app comes to the front, clearing what the
 *   previous app left behind.
 */
class RefreshHelpers(private val context: Context) {
    private val prefs = Prefs(context)
    private var profile: EinkDb.Profile? = null
    private var boosting = false
    private val endBoost = Runnable { stopBoost() }

    /** The app in front changed: remember its settings (to restore after a boost) and clean the screen. */
    fun onForeground(pkg: String) {
        Shell.main.removeCallbacks(endBoost)
        boosting = false
        profile = null
        Shell.io.execute {
            val p = EinkDb.read(context, pkg)
            Shell.main.post { if (profile == null) profile = p }
        }
        if (prefs.helperOn(Prefs.CLEAN_ON_OPEN, pkg)) {
            // After SystemUI has applied the app's own settings and the first frame is drawn.
            Shell.main.postDelayed({ Shell.io.execute { Live.fullRefresh() } }, 400)
        }
    }

    /** The panel changed the app's settings: restore those after a boost. */
    fun onProfileChanged(p: EinkDb.Profile) {
        profile = p
    }

    fun onScroll(pkg: String, panelOpen: Boolean) {
        if (panelOpen || !prefs.helperOn(Prefs.SCROLL_BOOST, pkg)) return
        if (!boosting) {
            boosting = true
            Shell.io.execute { Live.setProperty("persist.sys.mogu.refresh_type", SPEED.toString()) }
            Log.i(TAG, "scroll boost on ($pkg)")
        }
        Shell.main.removeCallbacks(endBoost)
        Shell.main.postDelayed(endBoost, SETTLE_MS)
    }

    private fun stopBoost() {
        if (!boosting) return
        boosting = false
        val p = profile
        Shell.io.execute {
            if (p != null) Live.applyDisplay(context, p)
            Live.fullRefresh()
        }
        Log.i(TAG, "scroll boost off")
    }

    fun release() {
        Shell.main.removeCallbacks(endBoost)
        if (boosting) stopBoost()
    }

    companion object {
        private const val TAG = "InkPalmPlus"
        /** Rockchip A2: the fastest waveform, black and white. */
        const val SPEED = 12
        const val SETTLE_MS = 500L
    }
}
