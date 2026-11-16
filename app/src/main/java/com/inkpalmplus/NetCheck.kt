package com.inkpalmplus

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.util.Log

/**
 * Whether InkPalm+ can reach the internet, and if not, whether the phone is offline or Android blocks InkPalm+.
 * Seen on the InkPalm: LineageOS's "restricted networking mode" (`settings get global restricted_networking_mode` = 1)
 * blocks new apps (dumpsys connectivity: blockedReasons=8, BLOCKED_REASON_RESTRICTED_MODE) until App info → Mobile data
 * & Wi‑Fi → Network access is allowed; our `activeNetwork` is then null although Wi‑Fi is connected.
 */
object NetCheck {
    private const val TAG = "InkPalmPlus"

    enum class State { ONLINE, OFFLINE, BLOCKED }

    /**
     * [appInternet]: our default network reaches the internet. [deviceInternet]: some network of the phone's does
     * (Wi‑Fi connected). [restrictedMode]: Android's restricted networking mode is on. Blocked = the phone is online but
     * we aren't; with restricted mode on, any connected network counts ([deviceNetwork]).
     */
    fun decide(appInternet: Boolean, deviceInternet: Boolean, deviceNetwork: Boolean, restrictedMode: Boolean): State = when {
        appInternet -> State.ONLINE
        deviceInternet || (restrictedMode && deviceNetwork) -> State.BLOCKED
        else -> State.OFFLINE
    }

    /** The log line for a state ("home: weather: …"), once per change. */
    fun logLine(s: State, restrictedMode: Boolean): String = "home: weather: " + when (s) {
        State.ONLINE -> "online"
        State.OFFLINE -> "offline"
        State.BLOCKED -> if (restrictedMode) "blocked by restricted networking" else "blocked (the phone is online, InkPalm+ isn't)"
    }

    @Volatile private var last: State? = null

    /** Now; logs when it changed since the last look. Never turns anything on. */
    fun state(c: Context): State {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        fun internet(n: android.net.Network?) = n != null && runCatching {
            cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(false)
        val app = runCatching { internet(cm.activeNetwork) }.getOrDefault(false)
        @Suppress("DEPRECATION")
        val all = if (app) emptyArray() else runCatching { cm.allNetworks }.getOrDefault(emptyArray())
        val restricted = runCatching { Settings.Global.getInt(c.contentResolver, "restricted_networking_mode", 0) == 1 }.getOrDefault(false)
        val s = decide(app, all.any { internet(it) }, all.isNotEmpty(), restricted)
        if (s != last) { last = s; Log.i(TAG, logLine(s, restricted)) }
        return s
    }
}
