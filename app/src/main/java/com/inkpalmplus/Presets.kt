package com.inkpalmplus

import android.content.Context

/**
 * One choice instead of refresh knobs (mockup: docs/mockups/08-presets.png). A preset sets an app's
 * refresh mode and full-refresh interval (Moaan's table) and its refresh helpers ([RefreshHelpers]);
 * it never touches Size, Contrast or Rotate. An app whose settings match no preset is "Custom",
 * edited on its own page. New apps start on [Preset.SYSTEM].
 */
enum class Preset(
    val title: String,
    val detail: String,
    val refresh: EinkDb.Refresh,
    val fullEvery: Int,
    /** Helper overrides this preset sets; helpers not listed follow the whole-device switches. */
    val helpers: Map<String, Boolean>,
) {
    SYSTEM("System", "Moaan's own settings, nothing changed", EinkDb.Refresh.SYSTEM, -1, emptyMap()),
    READING("Reading", "Normal refresh, full refresh every 10 pages, clean on open",
        EinkDb.Refresh.NORMAL, 10, mapOf(Prefs.CLEAN_ON_OPEN to true)),
    BROWSING("Browsing", "Normal at rest, Speed while scrolling, full refresh every 20",
        EinkDb.Refresh.NORMAL, 20, mapOf(Prefs.SCROLL_BOOST to true, Prefs.TYPING_BOOST to true)),
    COMICS("Comics & photos", "Full grays, full refresh every 5 to keep images clean",
        EinkDb.Refresh.NORMAL, 5, emptyMap()),
    VIDEO("Video", "Speed with dithered grays, no automatic cleaning",
        EinkDb.Refresh.SPEED_DITHER, -1,
        mapOf(Prefs.SCROLL_BOOST to false, Prefs.TYPING_BOOST to false, Prefs.CLEAN_ON_OPEN to false, Prefs.IDLE_CLEAN to false));

    /** The profile with this preset's refresh values (Size, Contrast and the rest kept). */
    fun applyTo(p: EinkDb.Profile): EinkDb.Profile = p.copy(refresh = refresh, fullEvery = fullEvery)

    /** Sets this preset's helper overrides for [pkg], clearing any others. */
    fun applyHelpers(prefs: Prefs, pkg: String) {
        for ((key, _, _) in Prefs.HELPERS) prefs.setHelper(key, pkg, helpers[key])
    }

    private fun matches(p: EinkDb.Profile, prefs: Prefs, pkg: String): Boolean =
        p.refresh == refresh && (if (fullEvery > 0) p.fullEvery == fullEvery else p.fullEvery <= 0) &&
            Prefs.HELPERS.all { (key, _, _) -> prefs.helperOverride(key, pkg) == helpers[key] }

    companion object {
        /** The preset [pkg]'s settings match, or null for Custom. */
        fun of(context: Context, p: EinkDb.Profile): Preset? {
            val prefs = Prefs(context)
            return entries.firstOrNull { it.matches(p, prefs, p.pkg) }
        }
    }
}
