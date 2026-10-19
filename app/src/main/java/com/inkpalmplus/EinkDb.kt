package com.inkpalmplus

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Moaan's own per-app e-ink settings, which the firmware already applies
 * whenever an app comes to the front (see docs/firmware.md):
 *
 * - SystemUI keeps one row per package in `content://com.android.systemui.eink/einksettings`.
 * - When an app opens, SystemUI reads its row and sets the refresh waveform,
 *   full-refresh interval and contrast, and passes the DPI to PackageManager.
 * - `Activity.performCreate` asks PackageManager for the app's DPI and draws
 *   the app at it. No global density change, no restart of other apps.
 *
 * Moaan hides the screen that edits these rows; this is the replacement.
 */
object EinkDb {
    val URI: Uri = Uri.parse("content://com.android.systemui.eink/einksettings")
    private const val URI_TEXT = "content://com.android.systemui.eink/einksettings"

    /** The panel's own density (ro.sf.lcd_density). */
    const val NATIVE_DPI = 276

    /** Moaan's contrast steps: the gamma SurfaceFlinger applies, lightest to darkest. */
    val CONTRAST_STEPS = floatArrayOf(1.5f, 1.4f, 1.3f, 1.2f, 1.1f, 1.0f, 0.9f, 0.8f, 0.7f, 0.6f, 0.5f, 0.4f, 0.3f, 0.2f, 0.1f)
    /** Moaan's system default (persist.sys.gamma.level). */
    const val DEFAULT_CONTRAST = 0.8f

    /** Per-app refresh waveforms (Rockchip EPD modes), as Moaan's own refresh dialog offers them. */
    enum class Refresh(val code: Int, val title: String, val detail: String) {
        SYSTEM(-1, "System", "Follows the refresh mode in Quick Settings"),
        NORMAL(7, "Normal", "Full grays, least ghosting. Best for reading"),
        AUTO(0, "Auto", "The panel picks per update"),
        FAST(14, "Fast", "Black and white updates, quicker page turns"),
        FAST_GRAY(15, "Fast + 4 grays", "Quick, keeps some gray"),
        SPEED(12, "Speed", "For scrolling and video. Ghosts more"),
        SPEED_DITHER(13, "Speed + dither", "Speed, with dithered grays for images");

        companion object {
            fun of(code: Int): Refresh = entries.firstOrNull { it.code == code } ?: SYSTEM
        }
    }

    data class Profile(
        val pkg: String,
        /** Density the app is drawn at; -1 for the system's. */
        val dpi: Int = -1,
        val refresh: Refresh = Refresh.SYSTEM,
        /** Full refresh after this many partial updates; -1 for the system's. */
        val fullEvery: Int = -1,
        /** SurfaceFlinger gamma while the app is open; null for the system's. */
        val contrast: Float? = null,
        val animFilter: Boolean = false,
        val bleach: Boolean = false,
        val textPlus: Boolean = false,
        /** Row exists in Moaan's table (every installed app gets one on first launch). */
        val exists: Boolean = false,
    ) {
        val isDefault: Boolean
            get() = dpi < 0 && refresh == Refresh.SYSTEM && fullEvery < 0 && contrast == null && !animFilter && !bleach && !textPlus

        /** One line for the app list, e.g. "300 dpi · Fast · Darker +2". */
        fun summary(): String = buildList {
            if (dpi > 0) add("$dpi dpi")
            if (refresh != Refresh.SYSTEM) add(refresh.title)
            if (fullEvery > 0) add("Full refresh every $fullEvery")
            contrast?.let { add(contrastLabel(it)) }
            if (bleach) add("Bleach")
            if (textPlus) add("Bold text")
            if (animFilter) add("Animation filter")
        }.joinToString(" · ")

        fun values(): ContentValues = ContentValues().apply {
            put("app_dpi", if (dpi > 0) dpi else -1)
            put("is_dpi_setting", if (dpi > 0) 1 else 0)
            put("refresh_mode", refresh.code)
            put("refresh_frequency", if (fullEvery > 0) fullEvery else -1)
            put("is_refresh_setting", if (refresh != Refresh.SYSTEM || fullEvery > 0) 1 else 0)
            put("app_contrast", contrast ?: 0f)
            put("is_contrast_setting", if (contrast != null) 1 else 0)
            put("app_anim_filter", if (animFilter) 1 else 0)
            put("app_bleach_mode", if (bleach) 1 else 0)
            put("app_bleach_text_plus", if (textPlus) 1 else 0)
        }
    }

    /** "Default", or how many steps lighter/darker than Moaan's default the gamma is. */
    fun contrastLabel(gamma: Float): String {
        val step = CONTRAST_STEPS.indexOfFirst { kotlin.math.abs(it - gamma) < 0.01f }
        val base = CONTRAST_STEPS.indexOfFirst { kotlin.math.abs(it - DEFAULT_CONTRAST) < 0.01f }
        if (step < 0) return "Contrast $gamma"
        val d = step - base
        return when {
            d == 0 -> "Contrast default"
            d > 0 -> "Darker +$d"
            else -> "Lighter ${-d}"
        }
    }

    private fun Map<String, String>.toProfile(pkg: String): Profile {
        fun int(k: String) = this[k]?.toIntOrNull() ?: -1
        val dpiOn = int("is_dpi_setting") == 1
        val refreshOn = int("is_refresh_setting") == 1
        val contrastOn = int("is_contrast_setting") == 1
        return Profile(
            pkg = pkg,
            dpi = if (dpiOn) int("app_dpi") else -1,
            refresh = if (refreshOn) Refresh.of(int("refresh_mode")) else Refresh.SYSTEM,
            fullEvery = if (refreshOn) int("refresh_frequency").takeIf { it > 0 } ?: -1 else -1,
            contrast = if (contrastOn) this["app_contrast"]?.toFloatOrNull() else null,
            animFilter = int("app_anim_filter") == 1,
            bleach = int("app_bleach_mode") == 1,
            textPlus = int("app_bleach_text_plus") == 1,
            exists = true,
        )
    }

    // ---- Reading ------------------------------------------------------------------------------

    /**
     * All rows, keyed by package, and how they were read (null: Moaan's table
     * can't be reached). Call off the main thread.
     */
    fun load(context: Context): Pair<Via?, Map<String, Profile>> {
        val (via, rows) = readDirect(context)?.let { Via.APP to it }
            ?: readShell()?.let { Via.SHIZUKU to it }
            ?: return null to emptyMap()
        return via to rows.mapNotNull { r -> r["package_name"]?.let { it to r.toProfile(it) } }.toMap()
    }

    fun read(context: Context, pkg: String): Profile = load(context).second[pkg] ?: Profile(pkg)

    private fun readDirect(context: Context): List<Map<String, String>>? = runCatching {
        context.contentResolver.query(URI, null, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add((0 until c.columnCount).associate { c.getColumnName(it) to (c.getString(it) ?: "") })
                }
            }
        }
    }.getOrNull()

    /** `content query` prints rows as "Row: 0 id=1, package_name=x, app_dpi=276, ...". */
    private fun readShell(): List<Map<String, String>>? {
        val (code, out) = Shell.run("content query --uri $URI_TEXT")
        if (code != 0) return null
        return out.lines().filter { it.startsWith("Row:") }.map { line ->
            line.substringAfter(' ').substringAfter(' ').split(", ").mapNotNull { kv ->
                val i = kv.indexOf('=')
                if (i <= 0) null else kv.substring(0, i) to kv.substring(i + 1)
            }.toMap()
        }
    }

    // ---- Writing ------------------------------------------------------------------------------

    enum class Via { APP, SHIZUKU }

    /**
     * Saves [p] to Moaan's table and hands the DPI to PackageManager, so the app
     * opens at it next time. Call off the main thread. Returns how it got written, or null.
     */
    fun write(context: Context, p: Profile): Via? {
        val via = writeDirect(context, p) ?: writeShell(p) ?: return null
        setPackageDpi(context, p.pkg, p.dpi)
        return via
    }

    private fun writeDirect(context: Context, p: Profile): Via? = runCatching {
        val cr = context.contentResolver
        val values = p.values()
        val updated = cr.update(URI, values, "package_name = ?", arrayOf(p.pkg))
        if (updated <= 0) cr.insert(URI, values.apply { put("package_name", p.pkg) }) ?: return null
        Via.APP
    }.getOrNull()

    private fun writeShell(p: Profile): Via? {
        val v = p.values()
        val binds = v.keySet().joinToString(" ") { k ->
            when (val x = v.get(k)) {
                is Float -> "--bind $k:f:$x"
                is Int -> "--bind $k:i:$x"
                else -> "--bind $k:s:$x"
            }
        }
        val where = "--where \"package_name='${p.pkg}'\""
        val (code, out) = Shell.run("content update --uri $URI_TEXT $binds $where")
        if (code != 0) return null
        // `content update` prints nothing either way; insert when the app has no row yet.
        if (p.exists) return Via.SHIZUKU
        val (qc, qOut) = Shell.run("content query --uri $URI_TEXT --projection id $where")
        if (qc == 0 && qOut.contains("Row:")) return Via.SHIZUKU
        val (ic, _) = Shell.run("content insert --uri $URI_TEXT --bind package_name:s:${p.pkg} $binds")
        return if (ic == 0) Via.SHIZUKU else null
    }

    /**
     * Moaan's addition to PackageManager (binder call 201 on this firmware). SystemUI
     * also does this from the row when the app comes to the front, but only after
     * the first window is drawn, so without it the first launch uses the old DPI.
     */
    private fun setPackageDpi(context: Context, pkg: String, dpi: Int) {
        val value = if (dpi > 0) dpi else -1
        val ok = runCatching {
            HiddenApiBypass.invoke(android.content.pm.PackageManager::class.java, context.packageManager, "setAppDpiToMap", pkg, value)
            true
        }.getOrDefault(false)
        if (!ok) Shell.run("service call package 201 s16 $pkg i32 $value")
    }

    /**
     * Closes the app so its next launch picks up a new DPI. It's in the background
     * while InkPalm+ is open, so Android lets any app end it; Shizuku is the fallback.
     */
    fun closeApp(context: Context, pkg: String): Boolean {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        runCatching { am.killBackgroundProcesses(pkg) }
        val stillRunning = runCatching { am.runningAppProcesses.orEmpty().any { pkg in it.pkgList.orEmpty() } }.getOrDefault(false)
        return !stillRunning || Shell.run("am force-stop $pkg").first == 0
    }
}
