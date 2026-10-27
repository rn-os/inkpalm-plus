package com.inkpalmplus

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.provider.Settings

/**
 * Wi-Fi and Bluetooth for the screen and light panel. Apps targeting API 29+ can't switch either radio themselves
 * (setWifiEnabled and BluetoothAdapter.enable do nothing), so a switch goes through, in order: the KompaktOS root
 * bridge (`radio.req`: wifi=, bt=, airplane=), Shizuku (`svc wifi|bluetooth enable|disable`), and last Android's own panel for it.
 * Reading the state needs only ACCESS_WIFI_STATE (Bluetooth's on/off state needs no permission).
 */
object Radios {
    enum class Radio(val title: String, val key: String) { WIFI("Wi-Fi", "wifi"), BT("Bluetooth", "bt"), AIRPLANE("Airplane", "airplane") }
    enum class State { OFF, ON, TURNING_ON, TURNING_OFF, NONE }

    /** On, or on its way up. A tap on this switches it off. */
    fun State.wantsOn() = this == State.ON || this == State.TURNING_ON

    fun state(ctx: Context, r: Radio): State = runCatching {
        when (r) {
            Radio.AIRPLANE -> if (Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0) State.ON else State.OFF
            Radio.WIFI -> when ((ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).wifiState) {
                WifiManager.WIFI_STATE_ENABLED -> State.ON
                WifiManager.WIFI_STATE_ENABLING -> State.TURNING_ON
                WifiManager.WIFI_STATE_DISABLING -> State.TURNING_OFF
                else -> State.OFF
            }
            Radio.BT -> (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.let {
                when (it.state) {
                    BluetoothAdapter.STATE_ON -> State.ON
                    BluetoothAdapter.STATE_TURNING_ON -> State.TURNING_ON
                    BluetoothAdapter.STATE_TURNING_OFF -> State.TURNING_OFF
                    else -> State.OFF
                }
            } ?: State.NONE
        }
    }.getOrDefault(State.NONE)

    /** The button's two short lines: the name, then On / Off / Turning on… */
    fun label(r: Radio, s: State): String = "${r.title}\n" + when (s) {
        State.ON -> "On"
        State.OFF -> "Off"
        State.TURNING_ON -> "Turning on…"
        State.TURNING_OFF -> "Turning off…"
        State.NONE -> "None"
    }

    /** The request file's line, e.g. `wifi=1`. */
    fun request(r: Radio, on: Boolean) = "${r.key}=${if (on) 1 else 0}\n"

    /** Shell command that does the same through Shizuku. */
    fun shell(r: Radio, on: Boolean) = if (r == Radio.AIRPLANE) "cmd connectivity airplane-mode ${if (on) "enable" else "disable"}" else "svc ${if (r == Radio.WIFI) "wifi" else "bluetooth"} ${if (on) "enable" else "disable"}"

    /**
     * Asks for [r] to go [on]. Returns true when something was asked to do it (the broadcast shows the result),
     * false when only Android's settings screen could be opened. Blocking: not on the main thread.
     */
    fun set(ctx: Context, r: Radio, on: Boolean): Boolean {
        if (Eink.bridgeKnowsRadio && Eink.bridgeWrite("radio.req", request(r, on))) return true
        if (Shell.allowed() && Shell.run(shell(r, on)).first == 0) return true
        runCatching {
            ctx.startActivity(Intent(when (r) { Radio.WIFI -> Settings.Panel.ACTION_WIFI; Radio.BT -> Settings.ACTION_BLUETOOTH_SETTINGS; Radio.AIRPLANE -> Settings.ACTION_AIRPLANE_MODE_SETTINGS })
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return false
    }
}
