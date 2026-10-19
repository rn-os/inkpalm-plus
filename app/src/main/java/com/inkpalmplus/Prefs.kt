package com.inkpalmplus

import android.content.Context
import android.content.SharedPreferences

/** InkPalm+'s own settings (the per-app e-ink settings live in Moaan's table, see [EinkDb]). */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.applicationContext.getSharedPreferences("inkpalmplus", Context.MODE_PRIVATE)

    /** Quick panel strip on the right edge (else left). */
    var edgeRight: Boolean
        get() = sp.getBoolean("edge_right", true)
        set(v) = sp.edit().putBoolean("edge_right", v).apply()

    /** Show a thin black pill where the strip is. */
    var showStrip: Boolean
        get() = sp.getBoolean("show_strip", true)
        set(v) = sp.edit().putBoolean("show_strip", v).apply()

    /** The user's own font scale, before [Live.nudgeConfiguration] moved it by a hair. */
    var fontBase: Float
        get() = sp.getFloat("font_base", -1f)
        set(v) = sp.edit().putFloat("font_base", v).apply()
}
