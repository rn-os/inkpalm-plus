package com.inkpalmplus

import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.util.Log
import java.io.File
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * The InkPalm Plus front light on KompaktOS (from InkControl, which InkPalm+ replaces): two LED channels, cold
 * (`aw99703`) and warm (`aw99703_sec`), 0..2047 each, written by the root bridge (light.req) because the /proc nodes
 * aren't app-writable. Stock Moaan firmware drives its own light; there's no light block there.
 *
 * The panel shows two numbers: Brightness (the overall level) and Warmth (the cold/warm balance: 0 all cold,
 * 50 both channels at the level, 100 all warm), [mix]ed into the two channels, each on a gamma-2 curve (finer steps
 * when dim). With [follows] on, both are scaled by Android's brightness slider, or, with adaptive brightness on,
 * by the light sensor ([onLux]): Android 16's auto brightness never writes SCREEN_BRIGHTNESS, so there's nothing to
 * follow there. Moving Brightness turns adaptive brightness off: the user's choice wins. The LEDs go off when the
 * screen does ([screenOff]; the driver drops its levels across suspend) and come back with it ([apply]).
 */
object Light {
    const val MAX = 2047
    private const val K_LEVEL = "light_level"
    private const val K_WARMTH = "light_warmth"
    private const val K_ON = "light_on"
    private const val K_FOLLOW = "light_follow_system"
    private const val K_IMPORTED = "light_imported"
    private const val INKCONTROL = "org.neoport.inkcontrol"
    private val INKCONTROL_STATE: Uri by lazy { Uri.parse("content://org.neoport.inkcontrol.light/state") }

    class State(val level: Int, val warmth: Int, val on: Boolean, val follows: Boolean = false, val auto: Boolean = false)

    /** The bridge can drive the LEDs: KompaktOS on the InkPalm. */
    fun supported(): Boolean = Eink.bridgeAvailable() && runCatching { File("/proc/aw99703").exists() }.getOrDefault(false)

    private fun sp(c: Context) = Prefs(c).sp

    /** The light now, or null when there's no light to control. */
    fun read(context: Context): State? {
        if (!supported()) return null
        importOnce(context)
        val s = sp(context)
        return State(s.getInt(K_LEVEL, 50), s.getInt(K_WARMTH, 50), s.getBoolean(K_ON, true), followSystem(context), auto(context))
    }

    /** [level] and [warmth] 0–100, both channels on or off with [on]. A new level turns adaptive brightness off. */
    fun set(context: Context, level: Int, warmth: Int, on: Boolean) {
        val s = sp(context)
        val l = level.coerceIn(0, 100)
        if (l != s.getInt(K_LEVEL, 50) && auto(context)) setAuto(context, false)
        s.edit().putInt(K_LEVEL, l).putInt(K_WARMTH, warmth.coerceIn(0, 100)).putBoolean(K_ON, on).apply()
        apply(context)
    }

    fun setOn(context: Context, on: Boolean) {
        sp(context).edit().putBoolean(K_ON, on).apply()
        apply(context)
    }

    fun isOn(context: Context) = sp(context).getBoolean(K_ON, true)

    /** Scale with Android's brightness slider / adaptive brightness (on by default, as in InkControl). */
    fun followSystem(context: Context) = sp(context).getBoolean(K_FOLLOW, true)
    fun setFollowSystem(context: Context, on: Boolean) { sp(context).edit().putBoolean(K_FOLLOW, on).apply(); apply(context) }

    /** Android's adaptive brightness (the InkPalm's light sensor), on or off. */
    fun auto(context: Context): Boolean = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }.getOrDefault(false)

    /** Turns adaptive brightness on or off; false when InkPalm+ may not (Modify system settings isn't allowed). */
    fun setAuto(context: Context, on: Boolean): Boolean = Settings.System.canWrite(context) && runCatching {
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
    }.getOrDefault(false)

    /** Channel levels for a brightness and warmth: cold = L·min(1, 2(1−t)), warm = L·min(1, 2t). */
    fun mix(level: Int, warmth: Int): Pair<Int, Int> {
        val t = warmth.coerceIn(0, 100) / 100f
        val l = level.coerceIn(0, 100)
        return (l * minOf(1f, 2 * (1 - t))).roundToInt() to (l * minOf(1f, 2 * t)).roundToInt()
    }

    /** One channel's 0..100 level → LED value, gamma 2, times [scale] (0..1). */
    fun raw(percent: Int, scale: Float): Int {
        val x = percent.coerceIn(0, 100) / 100.0
        return (MAX * x * x * scale.coerceIn(0f, 1f)).roundToInt()
    }

    /** Ambient light → scale: dim in the dark, full from ~300 lux (indoor daylight) up, log-shaped like the eye. */
    fun luxScale(lux: Float): Float = (0.1 + 0.9 * log10(1.0 + lux.coerceAtLeast(0f)) / log10(301.0)).coerceIn(0.1, 1.0).toFloat()

    /** Scale from the light sensor while adaptive brightness is on, or -1 before the first reading. */
    @Volatile private var autoScale = -1f

    /** A light sensor reading (the service listens while the screen is on and adaptive brightness is on). Small
     *  changes are ignored so the LEDs don't flicker. */
    fun onLux(context: Context, lux: Float) {
        val f = luxScale(lux)
        if (autoScale >= 0 && kotlin.math.abs(f - autoScale) < 0.05f) return
        autoScale = f
        apply(context)
    }

    private fun scale(context: Context): Float = when {
        !followSystem(context) -> 1f
        auto(context) && autoScale >= 0 -> autoScale
        else -> runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128) / 255f
    }

    /** Pushes the current state to the LEDs. */
    fun apply(context: Context) {
        if (!supported()) return
        val c = context.applicationContext
        Eink.post {
            val s = sp(c)
            val (cold, warm) = if (s.getBoolean(K_ON, true)) mix(s.getInt(K_LEVEL, 50), s.getInt(K_WARMTH, 50)) else 0 to 0
            val k = scale(c)
            Eink.bridgeWrite("light.req", "light.cold=${raw(cold, k)}\nlight.warm=${raw(warm, k)}\n")
        }
    }

    /** Screen off: LEDs off right away (the driver only cuts them at suspend, seconds later). Levels are kept. */
    fun screenOff() {
        if (!supported()) return
        Eink.post { Eink.bridgeWrite("light.req", "light.cold=0\nlight.warm=0\n") }
    }

    /**
     * Coming from InkControl: its levels once, read through its light API, so the light doesn't jump. (InkControl
     * should be uninstalled afterwards: both apps would drive the LEDs. The home screen says so.)
     */
    private fun importOnce(context: Context) {
        val s = sp(context)
        if (s.getBoolean(K_IMPORTED, false)) return
        s.edit().putBoolean(K_IMPORTED, true).apply()
        runCatching {
            context.contentResolver.query(INKCONTROL_STATE, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use
                fun int(name: String) = c.getColumnIndex(name).takeIf { it >= 0 }?.let { c.getInt(it) } ?: 0
                if (int("supported") != 1) return@use
                val cold = int("cold").coerceIn(0, 100)
                val warm = int("warm").coerceIn(0, 100)
                val level = maxOf(cold, warm)
                val warmth = when {
                    level == 0 -> 50
                    cold >= warm -> (50f * warm / cold).roundToInt()
                    else -> (100f - 50f * cold / warm).roundToInt()
                }
                s.edit().putInt(K_LEVEL, level).putInt(K_WARMTH, warmth)
                    .putBoolean(K_ON, int("cold_on") == 1 || int("warm_on") == 1)
                    .putBoolean(K_FOLLOW, int("follow_system") == 1).apply()
                Log.i("InkPalmPlus", "light imported from InkControl: level=$level warmth=$warmth")
            }
        }
    }

    /** InkControl is still installed (it would drive the LEDs and modes too). */
    fun inkControlInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(INKCONTROL, 0) }.isSuccess
}
