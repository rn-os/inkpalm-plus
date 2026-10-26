package com.inkpalmplus

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Auto power-off like the stock Moaan firmware (from InkControl): after the screen has been off for N hours on
 * battery, shut down. A wake-up alarm is armed at screen-off (or unplugging while asleep) and cancelled at screen-on
 * or plugging in. Apps may not shut down on KompaktOS, so the bridge does it (power.req), and checks again itself
 * that the screen is off and nothing is plugged in.
 */
object AutoOff {
    private const val TAG = "InkPalmPlus"
    private const val K_HOURS = "auto_off_hours"
    val HOURS = listOf(0, 4, 8, 12, 24)

    /** Needs a bridge that knows power.req. */
    fun supported(): Boolean = Eink.bridgeKnowsPower

    fun hours(c: Context): Int = Prefs(c).sp.getInt(K_HOURS, 0)
    fun setHours(c: Context, h: Int) {
        Prefs(c).sp.edit().putInt(K_HOURS, h).apply()
        cancel(c)   // armed again at the next screen-off
    }

    /** Screen off or charger unplugged: arm the timer if asleep on battery. */
    fun arm(c: Context) {
        val h = hours(c)
        if (h <= 0 || !supported() || interactive(c) || plugged(c)) return
        val at = SystemClock.elapsedRealtime() + h * 3_600_000L
        val am = alarms(c)
        // Exact and allowed while idle, so Doze can't push it back by hours (USE_EXACT_ALARM, Android 13+).
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(c))
            else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(c))
        }.onSuccess { Log.i(TAG, "auto power-off armed: $h h${if (exact) "" else " (inexact)"}") }
            .onFailure { Log.w(TAG, "auto power-off: couldn't arm: $it") }
    }

    /** Screen on or charger plugged in. */
    fun cancel(c: Context) = alarms(c).cancel(pending(c))

    private fun interactive(c: Context) = c.getSystemService(PowerManager::class.java).isInteractive

    private fun plugged(c: Context): Boolean =
        (c.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

    private fun alarms(c: Context) = c.getSystemService(AlarmManager::class.java)

    private fun pending(c: Context): PendingIntent = PendingIntent.getBroadcast(c.applicationContext, 0,
        Intent(c.applicationContext, Alarm::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /**
     * The power-off picture over everything ([WakeCover]) before the bridge's predraw wakes the screen to draw it (with
     * a picture installed; Moaan's own draws without a wake). True when it's up. Blocking (on [Eink.worker]).
     */
    fun showCover(c: Context): Boolean {
        val f = File(c.filesDir, "poweroff.png")
        if (!f.isFile) return false
        val bmp = BitmapFactory.decodeFile(f.path) ?: return false
        return WakeCover.show(bmp, "power-off")
    }

    /** Timer ran out: shut down if still asleep on battery (the bridge checks again). The bridge first wakes and
     *  sleeps the screen to draw the power-off picture: like the always-on display's redraw, that wake is InkPalm+'s
     *  (no light, no Live update armed, see [Aod.poweringOff]), and covered by the picture itself. */
    class Alarm : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (hours(c) <= 0 || interactive(c) || plugged(c)) return
            Log.i(TAG, "auto power-off: shutting down")
            val done = goAsync()
            val app = c.applicationContext
            Eink.init(app)
            Eink.post {
                try {
                    Aod.poweringOff(app)
                    showCover(app)
                    Eink.bridgeWrite("power.req", "off=1\n")
                } finally { done.finish() }
            }
        }
    }
}
