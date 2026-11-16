package com.inkpalmplus

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Android's own rotation lock (auto-rotate), the screen panel's fourth toggle: Settings.System.accelerometer_rotation
 * 1 = Auto, 0 = Locked. Locking first pins user_rotation to the display's rotation now, as Android's quick-settings
 * rotation tile does, so the screen stays as it is.
 *
 * It sits under [Rotation]: an app with its own Rotate mode (the overlay window asking for an orientation) turns the
 * screen whatever this says. The sleep screen ([Aod.reorient]) only reads the accelerometer while this is Auto, so a
 * locked phone keeps its face in the orientation it went to sleep in.
 */
object RotationLock {
    fun locked(ctx: Context): Boolean =
        runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 0 }.getOrDefault(false)

    /** The settings to write, in order, to lock ([lock]) at display rotation [rotation] (0–3) or to go back to Auto. */
    fun writes(lock: Boolean, rotation: Int): List<Pair<String, Int>> =
        if (lock) listOf(Settings.System.USER_ROTATION to rotation.coerceIn(0, 3), Settings.System.ACCELEROMETER_ROTATION to 0)
        else listOf(Settings.System.ACCELEROMETER_ROTATION to 1)

    /** Locks at the display's rotation now, or goes back to Auto. WRITE_SETTINGS (appop) first, then Shizuku. */
    fun set(ctx: Context, lock: Boolean) {
        val w = writes(lock, Aod.displayRotation(ctx))
        val ok = runCatching { w.forEach { (k, v) -> Settings.System.putInt(ctx.contentResolver, k, v) } }
        if (ok.isFailure) {
            Log.w(QuickPanelService.TAG, "rotation lock: ${ok.exceptionOrNull()}, trying Shizuku")
            if (Shell.allowed()) Shell.background({ w.forEach { (k, v) -> Shell.run("settings put system $k $v") } }) {}
        }
        Log.i(QuickPanelService.TAG, "rotation lock: ${w.joinToString { "${it.first}=${it.second}" }}")
    }

    /** The toggle's icon: screen_rotation while Auto, screen_lock_rotation while Locked. */
    fun icon(locked: Boolean) = if (locked) R.drawable.ic_screen_lock_rotation else R.drawable.ic_screen_rotation

    /** What TalkBack reads for the toggle. */
    fun describe(locked: Boolean) = "Rotation, " + if (locked) "locked" else "auto"
}
