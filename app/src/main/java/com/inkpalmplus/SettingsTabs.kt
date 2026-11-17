package com.inkpalmplus

/**
 * Pure parts of the settings screens (docs/mockups/settings-tabs/): the screens' names and where an older or deep-link
 * name opens ([Sections]), their named tabs ([Tabs]), landscape's two columns ([Columns]), and the home menu's one-line
 * summaries ([Summaries]).
 */
object Sections {
    const val SLEEP = "sleep"
    const val WEATHER = "weather"
    const val COUNTDOWNS = "countdowns"
    const val TRASH = "trash"
    /** KompaktOS's Screen & light; stock firmware opens [EINK] instead. */
    const val SCREEN = "screen"
    const val SCREEN_ADVANCED = "screen_advanced"
    const val EINK = "eink"
    const val PANEL = "panel"
    const val PANEL_ADVANCED = "panel_advanced"
    const val POWER = "power"
    const val BUTTONS = "buttons"
    const val ABOUT = "about"
    // Older names, kept for links: each opens the screen (and tab) it moved to.
    const val AOD = "aod"
    const val LIGHT = "light"
    const val KEEP_ON = "keep_on"
    const val TOUCH = "touch"
    const val READING = "reading"
    // Tabs by name.
    const val UPDATES = "updates"
    const val HOME_FACE = "home_face"

    /** Where a link opens: a screen, a tab of it (null: the one used last this session, else the first), and a group
     *  to scroll to ([anchor]). */
    data class Target(val section: String?, val tab: String? = null, val anchor: String? = null)

    /** Where [asked] (a section name, old or new; null = home) opens. [tab]: an explicit `tab` extra, which wins. */
    fun resolve(asked: String?, kompakt: Boolean, tab: String? = null): Target {
        val t = when (asked) {
            AOD -> Target(SLEEP, Tabs.FACE)
            UPDATES -> Target(SLEEP, Tabs.UPDATES)
            HOME_FACE -> Target(SLEEP, Tabs.HOME)
            LIGHT -> Target(SCREEN, Tabs.LIGHT)
            KEEP_ON -> Target(PANEL_ADVANCED, anchor = KEEP_ON)
            TOUCH -> Target(BUTTONS)
            READING -> Target(BUTTONS, anchor = READING)
            else -> Target(asked)
        }
        // Stock firmware has no Screen & light of its own: Moaan's per-app e-ink screen is it.
        val s = if (!kompakt && (t.section == SCREEN || t.section == SCREEN_ADVANCED)) Target(EINK) else t
        return if (tab != null) s.copy(tab = tab) else s
    }
}

/** A screen's tab: [key] (remembered, and named by links) and [title] (on the tab). */
data class Tab(val key: String, val title: String)

/** The screens' named tabs (docs/mockups/settings-tabs/tabs-sheet.png). A screen with fewer than two shows no tab row. */
object Tabs {
    const val FACE = "face"
    const val UPDATES = "updates"
    const val HOME = "home"
    const val SCREEN = "screen"
    const val LIGHT = "light"

    /** Sleep screen: Face · Updates, and Home face while that face is in use. */
    fun sleep(homeFace: Boolean): List<Tab> =
        listOfNotNull(Tab(FACE, "Face"), Tab(UPDATES, "Updates"), if (homeFace) Tab(HOME, "Home face") else null)

    /** Screen & light: Screen · Light, or just the screen when there's no front light control. */
    fun screen(light: Boolean): List<Tab> = if (light) listOf(Tab(SCREEN, "Screen"), Tab(LIGHT, "Light")) else listOf(Tab(SCREEN, "Screen"))

    /** The tab to show: [wanted] (this visit's or a link's), else [remembered] (the session's last), else the first;
     *  only ones [tabs] has (a Home face tab goes away with the Home face). Null when there are no tabs. */
    fun pick(tabs: List<Tab>, wanted: String?, remembered: String?): String? =
        listOf(wanted, remembered).firstOrNull { k -> k != null && tabs.any { it.key == k } } ?: tabs.firstOrNull()?.key
}

/** Landscape: a tab's groups in two columns, side by side like a book; groups never split. */
object Columns {
    /** How many of the groups ([heights], in order) go in the left column so the taller column is as short as can be
     *  (on a tie, the left one is the taller). */
    fun split(heights: List<Int>): Int {
        if (heights.size < 2) return heights.size
        val total = heights.sum()
        var best = heights.size; var bestMax = Int.MAX_VALUE; var bestLeftTaller = false; var left = 0
        for (k in 0..heights.size) {
            if (k > 0) left += heights[k - 1]
            val m = maxOf(left, total - left)
            val leftTaller = left >= total - left
            if (m < bestMax || (m == bestMax && leftTaller && !bestLeftTaller)) { best = k; bestMax = m; bestLeftTaller = leftTaller }
        }
        return best
    }

    /** True when [content] px doesn't fit in [room] px (the tab then scrolls, and says so in the log). */
    fun overflows(content: Int, room: Int): Boolean = room > 0 && content > room
}

/** The home menu's summaries, and labels shared by its screens. */
object Summaries {
    /** "Frozen", "every 5 min". */
    fun update(minutes: Int): String = if (minutes <= 0) "Frozen" else "every $minutes min"

    /** "Agenda · Classic · every 5 min", "Picture", "Moaan's picture". */
    fun sleep(faceTitle: String, isDefault: Boolean, drawn: Boolean, minutes: Int): String = when {
        isDefault -> "Moaan's picture"
        drawn -> "$faceTitle · ${update(minutes)}"
        else -> faceTitle
    }

    /** "Text everywhere · light follows Android". [light] null: no front light control. */
    fun screen(modeTitle: String, light: Boolean?): String =
        "$modeTitle everywhere" + when (light) { null -> ""; true -> " · light follows Android"; false -> " · light set in the screen panel" }

    /** "Left edge · 9 apps", or how to turn it on. */
    fun sidePanel(on: Boolean, edgeRight: Boolean, kept: Int): String =
        if (!on) "Off · turn it on in Accessibility" else "${if (edgeRight) "Right" else "Left"} edge · $kept apps"

    /** "Picture: Panda · auto off after 24 h". [picture] / [autoHours] null: not on this firmware. */
    fun powerOff(picture: String?, autoHours: Int?): String = listOfNotNull(
        picture?.let { "Picture: $it" },
        autoHours?.let { if (it <= 0) "auto off never" else "auto off after $it h" },
    ).joinToString(" · ").replaceFirstChar { it.uppercase() }

    /** "Touch: Home / hold refresh · Reader: KOReader". */
    fun buttons(touch: String, reader: String): String = "Touch: $touch · Reader: $reader"

    /** KompaktOS's touch button: a short touch is Home; hold is a full refresh or nothing. */
    fun kompaktTouch(holdRefresh: Boolean): String = if (holdRefresh) "Home / hold refresh" else "Home"

    /** "Contrast +2" → "+2", "Contrast normal" → "Normal". */
    fun contrast(label: String): String = label.removePrefix("Contrast ").replaceFirstChar { it.uppercase() }
}
