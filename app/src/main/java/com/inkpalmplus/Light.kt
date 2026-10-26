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
 * then, so it's ignored. With Auto on, the light sensor sets both Brightness (0..100) and Warmth from lux curves ([autoLevel],
 * [autoWarmth]), and the two sliders become offsets in points (-50..+50, "brighter / dimmer than auto") on top; only
 * the Auto pill turns it off. The LEDs go off when the screen does ([screenOff]; the driver drops
 * its levels across suspend) and come back with it ([apply]).
 */
object Light {
    const val MAX = 2047
    private const val K_LEVEL = "light_level"
    private const val K_WARMTH = "light_warmth"
    private const val K_ON = "light_on"
    private const val K_FOLLOW = "light_follow_system"
    private const val K_SENS = "light_sensitivity"
    private const val K_LAST_LEVEL = "light_auto_level"
    private const val K_LAST_WARM = "light_auto_warm"
    private const val K_OFF_B = "light_auto_off_b"
    private const val K_OFF_W = "light_auto_off_w"
    private const val K_MODEL = "light_auto_model"
    private const val K_LAST_LUX = "light_auto_lux"
    private const val K_IMPORTED = "light_imported"
    private const val INKCONTROL = "org.neoport.inkcontrol"
    private val INKCONTROL_STATE: Uri by lazy { Uri.parse("content://org.neoport.inkcontrol.light/state") }

    class State(val level: Int, val warmth: Int, val on: Boolean, val follows: Boolean = false, val auto: Boolean = false,
                val offB: Int = 0, val offW: Int = 0)

    /** The bridge can drive the LEDs: KompaktOS on the InkPalm (the bridge is only in InkPalm images; /proc/aw99703
     *  itself may not be visible to apps). */
    fun supported(): Boolean = Eink.bridgeAvailable()

    private fun sp(c: Context) = Prefs(c).sp

    /** The light now, or null when there's no light to control. */
    fun read(context: Context): State? {
        if (!supported()) return null
        migrate(context)
        val s = sp(context)
        return State(s.getInt(K_LEVEL, 50), s.getInt(K_WARMTH, 50), s.getBoolean(K_ON, true), followSystem(context), auto(context),
            autoOffset(context, true), autoOffset(context, false))
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
        autoCurve.force(sensitivity(context))?.let { cancelRamp(); setAutoValues(context, it.level, it.warmth) }
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

    const val OFFSET_MAX = 50

    /** First run of the lux-curve model: the old slider was a ceiling, so a stale level must not become an offset. */
    @Synchronized
    private fun migrate(context: Context) {
        val s = sp(context)
        if (s.getInt(K_MODEL, 1) >= 2) return
        s.edit().putInt(K_MODEL, 2).putInt(K_OFF_B, 0).putInt(K_OFF_W, 0).remove("light_auto_scale").apply()
    }

    /** The Auto offset in points, -50..+50: [bright] for brightness, else warmth. */
    fun autoOffset(context: Context, bright: Boolean): Int {
        migrate(context)
        return sp(context).getInt(if (bright) K_OFF_B else K_OFF_W, 0).coerceIn(-OFFSET_MAX, OFFSET_MAX)
    }

    fun setAutoOffset(context: Context, bright: Boolean, v: Int) {
        migrate(context)
        sp(context).edit().putInt(if (bright) K_OFF_B else K_OFF_W, v.coerceIn(-OFFSET_MAX, OFFSET_MAX)).apply()
        listener?.invoke()
        apply(context)
    }

    /** Piecewise-linear in log10(lux) through [knots] (lux to value), flat outside them. */
    private fun curve(lux: Float, knots: Array<FloatArray>): Float {
        val x = log10(maxOf(lux, 1f).toDouble())
        val first = knots.first(); val last = knots.last()
        if (x <= log10(first[0].toDouble())) return first[1]
        for (k in 1 until knots.size) {
            val x1 = log10(knots[k][0].toDouble())
            if (x <= x1) {
                val x0 = log10(knots[k - 1][0].toDouble())
                return (knots[k - 1][1] + (knots[k][1] - knots[k - 1][1]) * ((x - x0) / (x1 - x0))).toFloat()
            }
        }
        return last[1]
    }

    private val LEVEL_KNOTS = arrayOf(floatArrayOf(1f, 2f), floatArrayOf(20f, 15f), floatArrayOf(100f, 35f),
        floatArrayOf(500f, 60f), floatArrayOf(2000f, 90f), floatArrayOf(10000f, 100f))
    private val WARM_KNOTS = arrayOf(floatArrayOf(1f, 90f), floatArrayOf(10f, 85f), floatArrayOf(100f, 55f),
        floatArrayOf(300f, 45f), floatArrayOf(1000f, 25f), floatArrayOf(3000f, 15f))

    /** Ambient light → front-light brightness 0..100 under Auto: ~2% at 1 lux, 15% at 20, 35% at 100, 60% at 500, 90% at
     *  2000, 100% from 10000. [bias] (-2..2) doubles the lux per step. (Stock Moaan uses only a 6-step table; see
     *  docs/firmware.md.) */
    fun autoLevel(lux: Float, bias: Int = 0): Float = curve(lux.coerceAtLeast(0f) * 2f.pow(bias), LEVEL_KNOTS)

    /** Ambient light → warmth 0..100 under Auto: 85-90 in the dark, ~50 indoors (100-300 lux), 15 from 3000 lux. */
    fun autoWarmth(lux: Float, bias: Int = 0): Float = curve(lux.coerceAtLeast(0f) * 2f.pow(bias), WARM_KNOTS)

    /** The Auto result with the offsets: brightness and warmth, 0..100 each. */
    fun autoResult(level: Float, warmth: Float, offB: Int, offW: Int): Pair<Int, Int> =
        (level + offB).roundToInt().coerceIn(0, 100) to (warmth + offW).roundToInt().coerceIn(0, 100)

    private val autoCurve = AutoLux()

    /** The curve's brightness and warmth on the LEDs now while adaptive brightness is on (they move in steps during a
     *  ramp), or -1 before any. */
    @Volatile private var autoLvl = -1f
    @Volatile private var autoWarm = -1f

    /** Called after the auto scale or the lux readout changed, on the main thread (the open panel's readout). */
    @Volatile var listener: (() -> Unit)? = null

    /** "120 lux · 41% · warmth 55" for the panel (the actual result, offsets included), or null before the first reading. */
    fun readout(context: Context): String? {
        val lux = autoCurve.smoothed
        if (lux.isNaN() || autoLvl < 0) return null
        val (l, w) = autoResult(autoLvl, autoWarm, autoOffset(context, true), autoOffset(context, false))
        return "${lux.roundToInt()} lux · $l% · warmth $w"
    }

    private var lastRaw = Float.NaN
    private var lastSampleAt = 0L
    private var ticking: Context? = null
    private var rampLevel = -1f
    private var rampWarm = -1f
    private var rampLeft = 0

    private fun setAutoValues(context: Context, level: Float, warmth: Float) {
        autoLvl = level; autoWarm = warmth
        apply(context)
    }

    private fun cancelRamp() { Shell.main.removeCallbacks(rampStep); rampLeft = 0 }

    private val rampStep = object : Runnable {
        override fun run() {
            val c = ticking ?: return
            val l = autoLvl + (rampLevel - autoLvl) / rampLeft
            val w = autoWarm + (rampWarm - autoWarm) / rampLeft
            rampLeft--
            if (rampLeft == 0) setAutoValues(c, rampLevel, rampWarm) else setAutoValues(c, l, w)
            listener?.invoke()
            if (rampLeft > 0) Shell.main.postDelayed(this, RAMP_MS)
        }
    }

    private fun accept(context: Context, r: AutoLux.Result) {
        cancelRamp()
        sp(context).edit().putFloat(K_LAST_LEVEL, r.level).putFloat(K_LAST_WARM, r.warmth).putFloat(K_LAST_LUX, autoCurve.smoothed).apply()
        if (!r.ramp || autoLvl < 0) { setAutoValues(context, r.level, r.warmth); listener?.invoke(); return }
        rampLevel = r.level; rampWarm = r.warmth
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
        if (rampLeft > 0) { cancelRamp(); autoLvl = rampLevel; autoWarm = rampWarm }
        ticking = null
    }

    /** The level and warmth for the LEDs under Auto: the curve plus the offsets (the last values kept in prefs until the sensor reports). */
    private fun autoNow(context: Context): Pair<Int, Int> {
        val s = sp(context)
        val l = if (autoLvl >= 0) autoLvl else s.getFloat(K_LAST_LEVEL, autoLevel(s.getFloat(K_LAST_LUX, 100f), sensitivity(context)))
        val w = if (autoWarm >= 0) autoWarm else s.getFloat(K_LAST_WARM, autoWarmth(s.getFloat(K_LAST_LUX, 100f), sensitivity(context)))
        return autoResult(l, w, autoOffset(context, true), autoOffset(context, false))
    }

    /** Android's brightness slider as 0..1 (adaptive off, follow on), or 1 when the light doesn't follow it. */
    private fun scale(context: Context): Float =
        if (!followSystem(context)) 1f
        else runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128) / 255f

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
            // The always-on display's redraw wakes the screen for a few seconds with the light held off ([Aod]).
            if (Aod.redrawing()) return@post
            importOnce(c)
            val s = sp(c)
            migrate(c)
            val autoOn = followSystem(c) && auto(c)
            val (cold, warm) = when {
                autoOn -> if (s.getBoolean(K_ON, true)) autoNow(c).let { (l, w) -> mix(l, w) } else 0 to 0
                s.getBoolean(K_ON, true) -> mix(s.getInt(K_LEVEL, 50), s.getInt(K_WARMTH, 50))
                else -> 0 to 0
            }
            val k = if (autoOn) 1f else scale(c)
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
