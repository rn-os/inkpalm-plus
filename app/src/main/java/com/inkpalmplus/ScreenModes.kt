package com.inkpalmplus

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * KompaktOS's six e-ink presets (KompaktService `Eink.MODES`), as the InkPalm's waveforms (`sys.eink.mode`, Rockchip
 * EPD modes). On the Kompakt they bundle waveform, gamma and dither; here only the waveform carries over, so the
 * mapping is by intent (InkControl's, kept). [key] is KompaktOS's own name, as in its per-app config.
 */
enum class ScreenMode(val key: String, val title: String, val detail: String, val code: Int, val icon: String) {
    FAST("fast", "Fast", "Black and white, quickest", 12, "FAST"),
    SOFT("soft", "Soft", "Black and white, gentler", 14, "SOFT"),
    DITHER("fastdither", "Dither", "Fast, dotted grays for pictures", 13, "DITHER"),
    TEXT("text", "Text", "Crisp text, fewer flashes", 8, "TEXT"),
    QUALITY("quality", "Quality", "All 16 grays, cleanest", 7, "QUALITY"),
    AUTO("auto", "Auto", "The screen picks for each update", 22, "AUTO");

    companion object {
        /** Quality (PART_GC16) is the firmware's own default. */
        val DEFAULT = QUALITY
        fun ofKey(key: String?): ScreenMode? = entries.firstOrNull { it.key == key }
        fun ofCode(code: Int): ScreenMode? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Which [ScreenMode] the app in front gets, and putting it on the screen (from InkControl's Actions/Prefs):
 * the app's own choice, else KompaktOS's per-app setting (when its config is synced, see [KompaktSync]), else the
 * device-wide one. On KompaktOS nothing resets the mode behind our back, so it's only written when it changes.
 *
 * A new mode only applies to pixels drawn after it, so a change made from the panel or a tile is followed by
 * [repaint]: a brief white overlay redraws the whole screen in it.
 */
object ScreenModes {
    private const val TAG = "InkPalmPlus"
    private const val K_GLOBAL = "screen_mode"
    private const val K_APP = "screen_mode_"
    private const val K_CONTRAST = "eink_contrast"
    private const val K_LAST_MASTER = "kompakt_last_master"
    private const val K_FOLLOW = "kompakt_follow"
    private const val K_PIGGYBACK = "kompakt_flash"

    enum class Source(val label: String) { APP("this app's own"), KOMPAKT("KompaktOS's setting for this app"), EVERYWHERE("everywhere") }

    /** Time for a bridge request to reach the screen (inotifyd + setprop) before repainting. */
    const val SETTLE_MS = 250L
    const val REPAINT_MS = 150L

    /** The mode last written, or null when unknown (after a restart: write again). */
    @Volatile private var current: Int? = null
    @Volatile private var lastFlash = 0L

    private fun sp(c: Context) = Prefs(c).sp

    fun global(c: Context): ScreenMode = ScreenMode.ofKey(sp(c).getString(K_GLOBAL, null)) ?: ScreenMode.DEFAULT
    fun own(c: Context, pkg: String): ScreenMode? = ScreenMode.ofKey(sp(c).getString(K_APP + pkg, null))

    /** Use KompaktOS's per-app config when the bridge has copied it (on by default). */
    fun followKompakt(c: Context) = sp(c).getBoolean(K_FOLLOW, true)
    fun setFollowKompakt(c: Context, on: Boolean) = sp(c).edit().putBoolean(K_FOLLOW, on).apply()

    /** KompaktOS's own refresh (its QS tile, the flush after its mode tiles) shows a black overlay and does nothing
     *  else here: turn it into a real full refresh (on by default). */
    fun flashOnKompaktRefresh(c: Context) = sp(c).getBoolean(K_PIGGYBACK, true)
    fun setFlashOnKompaktRefresh(c: Context, on: Boolean) = sp(c).edit().putBoolean(K_PIGGYBACK, on).apply()

    fun kompakt(c: Context): KompaktSync? = if (followKompakt(c)) KompaktSync.get(c.filesDir) else null

    /** The mode [pkg] gets now and where it comes from ([pkg] null: the device-wide one). */
    fun resolve(c: Context, pkg: String?): Pair<ScreenMode, Source> {
        pkg?.let { own(c, it) }?.let { return it to Source.APP }
        val k = kompakt(c)
        if (k != null) {
            // A KompaktOS master preset changed since we last looked (its own tiles or Settings): adopt it.
            // The first time the copy appears it's only noted, so it doesn't replace the user's own choice.
            val last = sp(c).getString(K_LAST_MASTER, null)
            if (k.master != null && k.master != last) {
                sp(c).edit().putString(K_LAST_MASTER, k.master).apply()
                if (last != null) ScreenMode.ofKey(k.master)?.let { sp(c).edit().putString(K_GLOBAL, it.key).apply() }
            }
            if (pkg != null && k.perApp) ScreenMode.ofKey(k.appMode[pkg])?.let { return it to Source.KOMPAKT }
        }
        return global(c) to Source.EVERYWHERE
    }

    /** The app in front changed: its mode, written only if it's different. Stock firmware is left to Moaan. */
    fun onForeground(c: Context, pkg: String) {
        if (!Eink.isKompakt) return
        val app = c.applicationContext
        Eink.post { write(resolve(app, pkg).first.code, force = false) }
    }

    private fun write(code: Int, force: Boolean) {
        if (!force && current == code) return
        if (Eink.setMode(code)) current = code else Log.w(TAG, "couldn't set screen mode $code")
    }

    /**
     * The panel or a tile picked [mode]: for [pkg] only (null clears that app's own), or everywhere. Applied to the
     * app in front ([foreground]) and the screen repainted in it.
     */
    fun choose(c: Context, mode: ScreenMode?, pkg: String?, foreground: String?) {
        val e = sp(c).edit()
        when {
            pkg != null && mode != null -> e.putString(K_APP + pkg, mode.key)
            pkg != null -> e.remove(K_APP + pkg)
            mode != null -> e.putString(K_GLOBAL, mode.key)
        }
        e.apply()
        val now = resolve(c, foreground).first
        Log.i(TAG, "screen mode ${mode?.title ?: "cleared"} for ${pkg ?: "everywhere"}; in front: ${now.title}")
        applyAndRepaint(now.code)
    }

    /** Quick Settings: everywhere, and on the screen now even if the app in front has its own (until the next switch). */
    fun chooseEverywhere(c: Context, mode: ScreenMode) {
        sp(c).edit().putString(K_GLOBAL, mode.key).apply()
        applyAndRepaint(mode.code)
    }

    private fun applyAndRepaint(code: Int) = Eink.post {
        write(code, force = true)
        repaintSoon()
    }

    /** After the request has reached the screen; timed on the main thread so the worker stays free (light writes). */
    private fun repaintSoon() { Shell.main.postDelayed({ QuickPanelService.instance?.repaint(REPAINT_MS) }, SETTLE_MS) }

    /** A real full refresh: the next frames are posted full, and the repaint provides them. */
    fun fullRefresh() {
        lastFlash = SystemClock.elapsedRealtime()
        Eink.post {
            Eink.requestFull()
            repaintSoon()
        }
    }

    /** KompaktService's refresh overlay appeared (it lasts 480 ms): one real refresh, at most every 2 s. */
    fun kompaktFlashSeen(c: Context) {
        if (!flashOnKompaktRefresh(c) || SystemClock.elapsedRealtime() - lastFlash < 2_000) return
        fullRefresh()
    }

    // ---- Contrast (KompaktOS: the HWC's grey256 → grey16 curve, sys.gray.gammalevel 0..80, default 30) --------

    const val CONTRAST_DEFAULT = 30
    const val CONTRAST_STEP = 10
    const val CONTRAST_MAX = 80

    fun contrast(c: Context): Int = sp(c).getInt(K_CONTRAST, CONTRAST_DEFAULT)

    fun setContrast(c: Context, level: Int) {
        val v = level.coerceIn(0, CONTRAST_MAX)
        sp(c).edit().putInt(K_CONTRAST, v).apply()
        Eink.post {
            Eink.setProperty("sys.gray.gammalevel", v.toString())
            repaintSoon()
        }
    }

    /** "Contrast normal", "Contrast +2", "Contrast −1": steps from the default. */
    fun contrastLabel(level: Int): String {
        val steps = (level - CONTRAST_DEFAULT) / CONTRAST_STEP
        return when {
            steps == 0 -> "Contrast normal"
            steps > 0 -> "Contrast +$steps"
            else -> "Contrast −${-steps}"
        }
    }

    /**
     * The service (re)started, e.g. after a reboot (`sys.*` properties don't survive one): contrast back, and
     * refresh_type 0 so the HWC uses the mode as asked (1, 2, 4+ re-pick it by content, 3 forces GLR16; see
     * docs/firmware.md). The mode itself follows at the next app switch.
     */
    fun restore(c: Context) {
        if (!Eink.isKompakt) return
        val app = c.applicationContext
        current = null
        Eink.post {
            Eink.setProperties("persist.sys.mogu.refresh_type" to "0", "sys.gray.gammalevel" to contrast(app).toString())
        }
    }
}

/**
 * Read-only view of KompaktOS's own e-ink config (from InkControl). KompaktService keeps it in its private
 * SharedPreferences `kompakt`; the bridge can copy it to `files/kompakt_prefs.xml` (needs a bridge change, see
 * InkPalm-KompaktOS tools/inkcontrol/README.md "Needs bridge change"; until then this is never there):
 * `eink_mode` = master preset, `per_app` = per-app switch, `app_mode_<pkg>` = preset, `invert_<pkg>`.
 */
class KompaktSync private constructor(val master: String?, val perApp: Boolean, val appMode: Map<String, String>) {
    companion object {
        const val FILE = "kompakt_prefs.xml"
        private var stamp = -1L
        private var cached: KompaktSync? = null

        /** Null when there's no copy or it doesn't parse. Cheap: cached by time and size. */
        @Synchronized
        fun get(filesDir: File): KompaktSync? {
            val f = File(filesDir, FILE)
            if (!f.isFile) { cached = null; stamp = -1; return null }
            val s = f.lastModified() xor f.length()
            if (s == stamp) return cached
            stamp = s
            cached = parse(f)
            return cached
        }

        private fun parse(f: File): KompaktSync? = runCatching {
            f.inputStream().use { input ->
                val p = Xml.newPullParser()
                p.setInput(input, "UTF-8")
                var master: String? = null
                var perApp = true
                val modes = HashMap<String, String>()
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        val name = p.getAttributeValue(null, "name")
                        if (name != null) when (p.name) {
                            "string" -> p.nextText().let { v ->
                                if (name == "eink_mode") master = v else if (name.startsWith("app_mode_")) modes[name.removePrefix("app_mode_")] = v
                            }
                            "boolean" -> if (name == "per_app") perApp = p.getAttributeValue(null, "value") == "true"
                        }
                    }
                    ev = p.next()
                }
                KompaktSync(master, perApp, modes)
            }
        }.onFailure { Log.w("InkPalmPlus", "kompakt prefs parse failed: $it") }.getOrNull()
    }
}
