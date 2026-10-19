package com.inkpalmplus

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import org.json.JSONArray

/**
 * Keeps the quick panel running (see docs/firmware.md, "Keeping the panel on"):
 *
 * - A reboot is fine: Android binds the service again by itself and Moaan doesn't kill it.
 * - Moaan's **Clear Memory** tile force-stops every running app not in its whitelist
 *   (`Settings.System.APPS_WHITE_LIST_KEY`, a JSON list). A force-stop makes Android switch the
 *   service off as crashed, and leaves the app in the stopped state, so nothing of it runs until
 *   it's opened. [addToWhitelist] puts InkPalm+ on that list (needs Shizuku or adb: apps can't
 *   write Moaan's own System keys).
 * - If the service is off anyway while the user wants it ([Prefs.panelWanted]), [repair] turns it
 *   back on, as Android's own switch does. Needs WRITE_SECURE_SETTINGS, granted once through
 *   Shizuku or adb; it survives restarts and updates. Runs at boot ([BootReceiver]) and whenever
 *   InkPalm+ opens.
 */
object Survival {
    private const val TAG = "InkPalmPlus"
    private const val WHITELIST = "APPS_WHITE_LIST_KEY"

    private fun component(context: Context) = ComponentName(context, QuickPanelService::class.java)

    // ---- Clear Memory whitelist -----------------------------------------------------------------

    private fun whitelist(context: Context): List<String> = runCatching {
        val a = JSONArray(Settings.System.getString(context.contentResolver, WHITELIST) ?: "[]")
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    fun inWhitelist(context: Context): Boolean = context.packageName in whitelist(context)

    /** Adds InkPalm+ to Moaan's Clear Memory whitelist through Shizuku. Call off the main thread. */
    fun addToWhitelist(context: Context): Boolean {
        if (inWhitelist(context)) return true
        val json = JSONArray(whitelist(context) + context.packageName).toString()
        // JSON holds only double quotes, so single quotes keep it one shell word.
        val (code, out) = Shell.run("settings put system $WHITELIST '$json'")
        Log.i(TAG, "whitelist add -> $code $out")
        return inWhitelist(context)
    }

    fun whitelistAdbCommand(context: Context): String {
        val json = JSONArray(whitelist(context) + context.packageName).toString()
        return "adb shell \"settings put system $WHITELIST '$json'\""
    }

    // ---- Turning the service back on --------------------------------------------------------------

    fun canRepair(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Grants WRITE_SECURE_SETTINGS through Shizuku, once. Call off the main thread. */
    fun grantRepair(context: Context): Boolean {
        Shell.run("pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}")
        return canRepair(context)
    }

    fun grantAdbCommand(context: Context) = "adb shell pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}"

    private fun enabledList(context: Context): List<String> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() }

    /**
     * If the user wants the panel but Android has it off, switches it back on (keeping every other
     * accessibility service as it is). Returns true if it wrote the setting.
     */
    fun repair(context: Context, why: String): Boolean {
        if (!Prefs(context).panelWanted || QuickPanelService.enabled(context) || !canRepair(context)) return false
        val list = (enabledList(context) + component(context).flattenToString()).distinct().joinToString(":")
        return runCatching {
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list)
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "panel was off ($why): switched it back on")
            true
        }.onFailure { Log.w(TAG, "couldn't switch the panel back on: $it") }.getOrDefault(false)
    }

    /** After a restart: Android usually brings the service back itself; this covers when it doesn't. */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
            val pending = goAsync()
            // Give Android a moment to bind it on its own first.
            Shell.main.postDelayed({
                repair(context.applicationContext, "after restart")
                pending.finish()
            }, 8_000)
        }
    }
}
