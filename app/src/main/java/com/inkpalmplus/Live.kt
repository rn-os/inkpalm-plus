package com.inkpalmplus

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Applies e-ink settings to the app that's open right now, without reopening it
 * (see docs/firmware.md, "Live changes"):
 *
 * - Refresh, full-refresh interval and contrast are system properties. Moaan's
 *   `eink` service sets any property for any caller, which is how this works
 *   without root.
 * - DPI is only read when an activity is created. A tiny font-scale change is a
 *   configuration change, so Android recreates the open activities in place
 *   (keeping their state), and they come back at the new DPI.
 */
object Live {
    private const val TAG = "InkPalmPlus"

    // ---- System properties through Moaan's eink service ---------------------------------------

    private val einkBinder: IBinder? by lazy {
        runCatching {
            HiddenApiBypass.invoke(Class.forName("android.os.ServiceManager"), null, "getService", "eink") as IBinder?
        }.onFailure { Log.w(TAG, "no eink service: $it") }.getOrNull()
    }

    /** IEinkManager.setProperty (binder call 5). Falls back to `setprop` through Shizuku. */
    fun setProperty(key: String, value: String): Boolean {
        val direct = einkBinder?.let { b ->
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("android.os.IEinkManager")
                data.writeString(key)
                data.writeString(value)
                b.transact(5, data, reply, 0)
                reply.readException()
                true
            } catch (e: Exception) {
                Log.w(TAG, "eink setProperty $key failed: $e")
                false
            } finally {
                data.recycle()
                reply.recycle()
            }
        } ?: false
        return direct || Shell.run("setprop $key $value").first == 0
    }

    private val propGet by lazy {
        runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java) }
            .onFailure { Log.w(TAG, "no SystemProperties.get: $it") }.getOrNull()
    }

    fun getProperty(key: String, default: String): String =
        runCatching { propGet?.invoke(null, key, default) as String? }.getOrNull() ?: default

    /** One full-screen refresh, as Moaan's EinkManager.sendOneFullFrame does. */
    fun fullRefresh() {
        // The driver refreshes when the value changes. Apps can't read it, so use one that's always new.
        setProperty("sys.eink.one_full_mode_timeline", "${(android.os.SystemClock.uptimeMillis() / 100) % 1_000_000}")
    }

    // ---- Per-app settings, now ----------------------------------------------------------------

    /**
     * Applies [p]'s refresh and contrast to the screen now, the way SystemUI does when the
     * app comes to the front (EinkSettingsProvider.query), so a live change looks the same
     * as reopening the app: waveform base 7, the refresh choice as Moaan's refresh type,
     * the full-refresh count (40 for the system), and the contrast gamma.
     * Apps can't read these properties back (SELinux), so they're always written.
     */
    fun applyDisplay(context: Context, p: EinkDb.Profile) {
        val cr = context.contentResolver
        val gamma = p.contrast ?: systemContrast(context)
        val refreshType = if (p.refresh != EinkDb.Refresh.SYSTEM) p.refresh.code
            else runCatching { Settings.Global.getInt(cr, "moan_eink_mode") }.getOrDefault(1)
        val full = if (p.fullEvery > 0) p.fullEvery else SYSTEM_FULL_EVERY
        setProperty("debug.sf.gamma.gamma", gamma.toString())
        setProperty("sys.eink.mode", "7")
        setProperty("persist.sys.mogu.refresh_type", refreshType.toString())
        setProperty("persist.vendor.fullmode_cnt", full.toString())
        Log.i(TAG, "live ${p.pkg}: gamma=$gamma refreshType=$refreshType full=$full")
    }

    /** SystemUI's full-refresh count for apps without their own. */
    private const val SYSTEM_FULL_EVERY = 40

    /** The contrast set with Moaan's own slider (index into the gamma steps). */
    private fun systemContrast(context: Context): Float {
        val i = runCatching { Settings.Global.getInt(context.contentResolver, "mogu_contrast_value") }.getOrNull()
        return i?.let { EinkDb.CONTRAST_STEPS.getOrNull(it) } ?: EinkDb.DEFAULT_CONTRAST
    }

    // ---- DPI, now -----------------------------------------------------------------------------

    /** Live DPI needs "Modify system settings" (or Shizuku). */
    fun canNudge(context: Context): Boolean = Settings.System.canWrite(context) || Shell.allowed()

    /**
     * Recreates the open activities so they pick up a new DPI: moves font_scale by
     * 0.000001 (or back), which nobody can see but Android treats as a configuration
     * change. Returns false if it couldn't.
     */
    fun nudgeConfiguration(context: Context): Boolean {
        val cr = context.contentResolver
        val now = Settings.System.getFloat(cr, Settings.System.FONT_SCALE, 1f)
        val base = Prefs(context).fontBase.takeIf { kotlin.math.abs(it - now) < 0.00001f } ?: now.also { Prefs(context).fontBase = it }
        val next = if (now == base) base + 0.000001f else base
        val ok = runCatching { Settings.System.canWrite(context) && Settings.System.putFloat(cr, Settings.System.FONT_SCALE, next) }
            .getOrDefault(false) || Shell.run("settings put system font_scale $next").first == 0
        Log.i(TAG, "nudge font_scale $now -> $next: $ok")
        return ok
    }
}
