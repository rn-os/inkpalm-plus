package com.inkpalmplus

import android.content.Context
import android.provider.Settings

/**
 * The touch button below the screen (mockup: docs/mockups/10-touch-button.png). Android's key handling
 * (PhoneWindowManager) reads Moaan's three Settings.Global keys on every touch, so changes apply on the
 * next one. Only Moaan's launcher had a screen for them; writing them needs WRITE_SECURE_SETTINGS,
 * the same permission [Survival] uses. The options are the firmware's own (docs/firmware.md).
 */
object TouchButton {
    private const val ON = "moan_logo_key_onoff"
    private const val FIRST = "moan_logo_key_first"
    private const val TAP = "moan_logo_key_value"
    private const val HOLD = "moan_logo_long_key_value"

    /** Index = the firmware's value. */
    val TAP_ACTIONS = listOf("Back", "Home", "Page down", "Volume down")
    val HOLD_ACTIONS = listOf("Full refresh", "Screenshot", "Page up", "Volume up", "Front light on/off", "Home")

    private fun get(context: Context, key: String, default: Int) =
        Settings.Global.getInt(context.contentResolver, key, default)

    fun on(context: Context) = get(context, ON, 1) > 0
    fun tap(context: Context) = get(context, TAP, 0).let { if (it in TAP_ACTIONS.indices) it else 1 }
    fun hold(context: Context) = get(context, HOLD, 0).coerceIn(0, HOLD_ACTIONS.size - 1)

    fun canChange(context: Context) = Survival.canRepair(context)

    private fun put(context: Context, key: String, value: Int): Boolean = runCatching {
        val cr = context.contentResolver
        // Without this the first touch shows Moaan's intro dialog instead of doing anything.
        if (get(context, FIRST, 0) != 1) Settings.Global.putInt(cr, FIRST, 1)
        Settings.Global.putInt(cr, key, value)
    }.getOrDefault(false)

    fun setOn(context: Context, on: Boolean) = put(context, ON, if (on) 1 else 0)
    fun setTap(context: Context, action: Int) = put(context, TAP, action)
    fun setHold(context: Context, action: Int) = put(context, HOLD, action)
}
