package com.inkpalmplus

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The InkPalm Plus front light on KompaktOS (from InkControl, which InkPalm+ replaces): two LED channels, cold
 * (`aw99703`) and warm (`aw99703_sec`), 0..2047 each, written by the root bridge (light.req) because the /proc nodes
 * aren't app-writable. Stock Moaan firmware drives its own light; there's no light block there.
 *
 * The panel shows two numbers: Brightness (the overall level) and Warmth (the cold/warm balance: 0 all cold,
 * 50 both channels at the level, 100 all warm), [mix]ed into the two channels, each on a gamma-2 curve (finer steps
 * when dim). With [follows] on, both are scaled by Android's brightness slider, or, with adaptive brightness on,
 * by the light sensor ([onLux]), and only by that: Android's own auto brightness also rewrites SCREEN_BRIGHTNESS
 * then, so it's ignored. With Auto on, Brightness is the ceiling the lux scale multiplies (Boox-style), Warmth stays
 * manual, and only the Auto pill turns it off. The LEDs go off when the screen does ([screenOff]; the driver drops
 * its levels across suspend) and come back with it ([apply]).
 */
object Light {
    const val MAX = 2047
    private const val K_LEVEL = "light_level"
    private const val K_WARMTH = "light_warmth"
    private const val K_ON = "light_on"
    private const val K_FOLLOW = "light_follow_system"
    private const val K_SENS = "light_sensitivity"
    private const val K_LAST_SCALE = "light_auto_scale"
    private const val K_LAST_LUX = "light_auto_lux"
    private const val K_IMPORTED = "light_imported"
    private const val INKCONTROL = "org.neoport.inkcontrol"
    private val INKCONTROL_STATE: Uri by lazy { Uri.parse("content://org.neoport.inkcontrol.light/state") }

    class State(val level: Int, val warmth: Int, val on: Boolean, val follows: Boolean = false, val auto: Boolean = false)

    /** The bridge can drive the LEDs: KompaktOS on the InkPalm (the bridge is only in InkPalm images; /proc/aw99703
     *  itself may not be visible to apps). */
    fun supported(): Boolean = Eink.bridgeAvailable()

    private fun sp(c: Context) = Prefs(c).sp

    /** The light now, or null when there's no light to control. */
    fun read(context: Context): State? {
        if (!supported()) return null
        val s = sp(context)
        return State(s.getInt(K_LEVEL, 50), s.getInt(K_WARMTH, 50), s.getBoolean(K_ON, true), followSystem(context), auto(context))
    }

    /** [level] and [warmth] 0–100, both channels on or off with [on]. With adaptive brightness on, [level] is the ceiling. */
    fun set(context: Context, level: Int, warmth: Int, on: Boolean) {
        val s = sp(context)
        val l = level.coerceIn(0, 100)
        if (on && !s.getBoolean(K_ON, true)) autoCurve.restart(SystemClock.elapsedRealtime())
        s.edit().putInt(K_LEVEL, l).putInt(K_WARMTH, warmth.coerceIn(0, 100)).putBoolean(K_ON, on).apply()
        apply(context)
    }

    fun setOn(context: Context, on: Boolean) {
        if (on && !isOn(context)) autoCurve.restart(SystemClock.elapsedRealtime())
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
        autoCurve.restart(SystemClock.elapsedRealtime())
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
    }.getOrDefault(false)

    const val SENS_MIN = -2
    const val SENS_MAX = 2

    /** Auto sensitivity -2..2: each step doubles (or halves) the lux before the curve, so + is brighter at the same room. */
    fun sensitivity(context: Context): Int = sp(context).getInt(K_SENS, 0).coerceIn(SENS_MIN, SENS_MAX)

    fun setSensitivity(context: Context, v: Int) {
        sp(context).edit().putInt(K_SENS, v.coerceIn(SENS_MIN, SENS_MAX)).apply()
        autoCurve.force(sensitivity(context))?.let { cancelRamp(); setScale(context, it) }
        listener?.invoke()
        apply(context)
    }

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

    /** Ambient light → scale: 0.08 in the dark, full from ~1500 lux, log-shaped like the eye. [bias] (-2..2) doubles the lux per step. */
    fun luxScale(lux: Float, bias: Int = 0): Float {
        val l = lux.coerceAtLeast(0f) * 2f.pow(bias)
        return (0.08 + 0.92 * log10(1.0 + l) / log10(2000.0)).coerceIn(0.08, 1.0).toFloat()
    }

    private val autoCurve = AutoLux()

    /** The scale on the LEDs now while adaptive brightness is on (it moves in steps during a ramp), or -1 before any. */
    @Volatile private var autoScale = -1f

    /** Called after the auto scale or the lux readout changed, on the main thread (the open panel's readout). */
    @Volatile var listener: (() -> Unit)? = null

    /** "120 lux · 63%" for the panel, or null before the first reading. */
    fun readout(): String? {
        val lux = autoCurve.smoothed
        return if (lux.isNaN() || autoScale < 0) null else "${lux.roundToInt()} lux · ${(autoScale * 100).roundToInt()}%"
    }

    private var lastRaw = Float.NaN
    private var lastSampleAt = 0L
    private var ticking: Context? = null
    private var rampTarget = -1f
    private var rampLeft = 0

    private fun setScale(context: Context, s: Float) {
        autoScale = s
        apply(context)
    }

    private fun cancelRamp() { Shell.main.removeCallbacks(rampStep); rampLeft = 0 }

    private val rampStep = object : Runnable {
        override fun run() {
            val c = ticking ?: return
            val s = autoScale + (rampTarget - autoScale) / rampLeft
            rampLeft--
            setScale(c, if (rampLeft == 0) rampTarget else s)
            listener?.invoke()
            if (rampLeft > 0) Shell.main.postDelayed(this, RAMP_MS)
        }
    }

    private fun accept(context: Context, r: AutoLux.Result) {
        cancelRamp()
        sp(context).edit().putFloat(K_LAST_SCALE, r.scale).putFloat(K_LAST_LUX, autoCurve.smoothed).apply()
        if (!r.ramp || autoScale < 0) { setScale(context, r.scale); listener?.invoke(); return }
        rampTarget = r.scale
        rampLeft = RAMP_STEPS
        rampStep.run()
    }

    /** One sample a second at most (the sensor's own limit is 200 ms, whatever period is asked), the first one at once. */
    private fun due(now: Long) = autoCurve.smoothed.isNaN() || now - lastSampleAt >= SAMPLE_MS - 50

    private fun process(context: Context, now: Long) {
        lastSampleAt = now
        autoCurve.sample(now, lastRaw, sensitivity(context))?.let { accept(context, it) }
    }

    /** A light sensor reading (the service listens while the screen is on and adaptive brightness is on). */
    fun onLux(context: Context, lux: Float) {
        lastRaw = lux
        val now = SystemClock.elapsedRealtime()
        if (due(now)) process(context, now)
    }

    /** The sensor only reports changes, so a steady room sends nothing: this repeats the last reading, once a second,
     *  so the smoothing and the 2 s / 4 s hysteresis still run (and the first value comes after the 500 ms hold). */
    private val tick = object : Runnable {
        override fun run() {
            val c = ticking ?: return
            val now = SystemClock.elapsedRealtime()
            if (!lastRaw.isNaN() && due(now)) process(c, now)
            Shell.main.postDelayed(this, TICK_MS)
        }
    }

    /** The service started listening (screen on, adaptive on): sensor events are ignored for 500 ms, then the first one
     *  counts. The sensor reports changes only and sends nothing at registration, so until it does, the last reading
     *  (kept in prefs across restarts) stands in; a changed room sends its own event. */
    fun sensorStarted(context: Context) {
        ticking = context.applicationContext
        if (lastRaw.isNaN()) lastRaw = sp(context).getFloat(K_LAST_LUX, Float.NaN)
        autoCurve.restart(SystemClock.elapsedRealtime())
        Shell.main.removeCallbacks(tick)
        Shell.main.postDelayed(tick, TICK_MS)
    }

    /** The sensor stopped (screen off, adaptive off). The last scale stays until new data arrives (no reset to the system level). */
    fun sensorStopped() {
        Shell.main.removeCallbacks(tick)
        if (rampLeft > 0) { cancelRamp(); autoScale = rampTarget }
        ticking = null
    }

    private fun scale(context: Context): Float = when {
        !followSystem(context) -> 1f
        auto(context) -> if (autoScale >= 0) autoScale else sp(context).getFloat(K_LAST_SCALE, 0.5f)
        else -> runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128) / 255f
    }

    private const val RAMP_STEPS = 3
    private const val RAMP_MS = 300L
    private const val SAMPLE_MS = 1_000L
    private const val TICK_MS = 250L
    /** Sensor sampling period in microseconds. */
    const val SENSOR_PERIOD_US = 1_000_000

    /** A write is queued: it reads the state when it runs, so more changes meanwhile (a slider drag) add nothing. */
    private val queued = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Pushes the current state to the LEDs. */
    fun apply(context: Context) {
        if (!supported() || !queued.compareAndSet(false, true)) return
        val c = context.applicationContext
        Eink.post {
            queued.set(false)
            // Asleep: the LEDs stay off (a brightness change can arrive then); the screen-on apply restores them.
            if (!c.getSystemService(android.os.PowerManager::class.java).isInteractive) return@post
            importOnce(c)
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
    @Synchronized
    private fun importOnce(context: Context) {   // on Eink.worker only (a cross-app query)
        val s = sp(context)
        if (s.getBoolean(K_IMPORTED, false)) return
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
        s.edit().putBoolean(K_IMPORTED, true).apply()
    }

    /** InkControl is still installed (it would drive the LEDs and modes too). */
    fun inkControlInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(INKCONTROL, 0) }.isSuccess
}
