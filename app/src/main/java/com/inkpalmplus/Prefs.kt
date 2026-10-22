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

    /** Width of the touch strip along the edge, in dp. */
    var stripWidthDp: Int
        get() = sp.getInt("strip_width", 16)
        set(v) = sp.edit().putInt("strip_width", v.coerceIn(STRIP_MIN, STRIP_MAX)).apply()

    /** How much of the screen width the side panel covers in portrait, in percent. */
    var panelWidthPct: Int
        get() = sp.getInt("panel_width", 78)
        set(v) = sp.edit().putInt("panel_width", v).apply()

    /** How many recent apps the side panel remembers. */
    var recentKept: Int
        get() = sp.getInt("recent_kept", 18)
        set(v) = sp.edit().putInt("recent_kept", v).apply()

    /** The reader app for the side panel's Last read card: a [Readers.App] key, "none", or null (not asked yet). */
    var readerApp: String?
        get() = sp.getString("reader_app", null)
        set(v) = sp.edit().putString("reader_app", v).apply()

    /** Show a thin black pill where the strip is. */
    var showStrip: Boolean
        get() = sp.getBoolean("show_strip", true)
        set(v) = sp.edit().putBoolean("show_strip", v).apply()

    /** The rotation chosen for [pkg] in the quick panel (applied whenever it comes to the front). */
    fun rotation(pkg: String): Rotation.Mode = Rotation.Mode.of(sp.getString("rotation_$pkg", null))

    fun setRotation(pkg: String, mode: Rotation.Mode) {
        sp.edit().apply { if (mode == Rotation.Mode.AUTO) remove("rotation_$pkg") else putString("rotation_$pkg", mode.name) }.apply()
    }

    /**
     * The quick panel service is meant to be on: set when it connects, cleared only when the user
     * switches it off (Android unbinds it then; a force-stop or crash doesn't). [Survival.repair] uses it.
     */
    var panelWanted: Boolean
        get() = sp.getBoolean("panel_wanted", false)
        set(v) = sp.edit().putBoolean("panel_wanted", v).apply()

    /**
     * A refresh helper ([RefreshHelpers]) for [pkg]: the app's own choice if it has one,
     * else the whole-device switch (on by default).
     */
    fun helperOn(key: String, pkg: String): Boolean =
        if (sp.contains("${key}_$pkg")) sp.getBoolean("${key}_$pkg", false) else helperDefault(key)

    /** All helpers start on except Clean after idle, which can flash during a long page. */
    fun helperDefault(key: String): Boolean = sp.getBoolean(key, key != IDLE_CLEAN)
    fun setHelperDefault(key: String, on: Boolean) = sp.edit().putBoolean(key, on).apply()

    /** [on] null: follow the whole-device switch. */
    fun setHelper(key: String, pkg: String, on: Boolean?) =
        sp.edit().apply { if (on == null) remove("${key}_$pkg") else putBoolean("${key}_$pkg", on) }.apply()

    fun helperOverride(key: String, pkg: String): Boolean? = if (sp.contains("${key}_$pkg")) sp.getBoolean("${key}_$pkg", true) else null

    companion object {
        const val STRIP_MIN = 6
        const val STRIP_MAX = 40
        const val SCROLL_BOOST = "scroll_boost"
        const val CLEAN_ON_OPEN = "clean_on_open"
        const val TYPING_BOOST = "typing_boost"
        const val IDLE_CLEAN = "idle_clean"

        /** The helpers, as the home screen and an app's page list them. */
        val HELPERS = listOf(
            Triple(SCROLL_BOOST, "Fast while scrolling", "Speed refresh while a list or page moves, then back to normal and a clean refresh"),
            Triple(TYPING_BOOST, "Fast while typing", "Speed refresh while you type, then back to normal and a clean refresh"),
            Triple(CLEAN_ON_OPEN, "Clean screen on open", "One full refresh when an app comes to the front, clearing the last app's ghost"),
            Triple(IDLE_CLEAN, "Clean after idle", "One full refresh after the screen has been still for a minute"),
        )
    }

    /** The system font the device had before InkPalm+ first changed it (see [Fonts]). */
    var originalFont: String?
        get() = sp.getString("original_font", null)
        set(v) = sp.edit().putString("original_font", v).apply()

    /** The user's own font scale, before [Live.nudgeConfiguration] moved it by a hair. */
    var fontBase: Float
        get() = sp.getFloat("font_base", -1f)
        set(v) = sp.edit().putFloat("font_base", v).apply()
}
