package com.inkpalmplus

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlin.math.roundToInt

/**
 * The front light, through InkControl's light API (KompaktOS; package org.neoport.inkcontrol). InkControl drives
 * the two LED channels (cold, warm) with its root helper; InkPalm+ only reads its state and asks for changes.
 * Without InkControl, or with `supported` = 0, there's no light block in the side panel.
 *
 * The panel shows two numbers: Brightness (the overall level) and Warmth (the cold/warm balance:
 * 0 all cold, 50 both channels at the level, 100 all warm).
 */
object Light {
    const val PACKAGE = "org.neoport.inkcontrol"
    const val ACTION_CHANGED = "org.neoport.inkcontrol.action.LIGHT_CHANGED"
    private const val ACTION_SET = "org.neoport.inkcontrol.action.SET_LIGHT"
    private val STATE: Uri = Uri.parse("content://org.neoport.inkcontrol.light/state")

    class State(val level: Int, val warmth: Int, val on: Boolean)

    /** The light now, or null when there's no light to control. A cross-app query: keep it off the swipe. */
    fun read(context: Context): State? = runCatching {
        context.contentResolver.query(STATE, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            fun int(name: String) = c.getColumnIndex(name).takeIf { it >= 0 }?.let { c.getInt(it) } ?: 0
            if (int("supported") != 1) return@use null
            val cold = int("cold").coerceIn(0, 100)
            val warm = int("warm").coerceIn(0, 100)
            val level = maxOf(cold, warm)
            val warmth = when {
                level == 0 -> 50
                cold >= warm -> (50f * warm / cold).roundToInt()
                else -> (100f - 50f * cold / warm).roundToInt()
            }
            State(level, warmth, int("cold_on") == 1 || int("warm_on") == 1)
        }
    }.onFailure { Log.w("InkPalmPlus", "InkControl's light isn't readable: $it") }.getOrNull()

    /** Asks InkControl for [level] and [warmth] (0–100), both channels on or off with [on]. */
    fun set(context: Context, level: Int, warmth: Int, on: Boolean) {
        val (cold, warm) = mix(level, warmth)
        runCatching {
            context.sendBroadcast(Intent(ACTION_SET).setPackage(PACKAGE)
                .putExtra("cold", cold).putExtra("warm", warm)
                .putExtra("cold_on", on).putExtra("warm_on", on))
        }.onFailure { Log.w("InkPalmPlus", "couldn't set the light: $it") }
    }

    /** Channel levels for a brightness and warmth: cold = L·min(1, 2(1−t)), warm = L·min(1, 2t). */
    fun mix(level: Int, warmth: Int): Pair<Int, Int> {
        val t = warmth.coerceIn(0, 100) / 100f
        val l = level.coerceIn(0, 100)
        return (l * minOf(1f, 2 * (1 - t))).roundToInt() to (l * minOf(1f, 2 * t)).roundToInt()
    }
}
