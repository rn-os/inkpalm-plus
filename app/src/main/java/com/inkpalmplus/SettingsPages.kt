package com.inkpalmplus

/**
 * Pure parts of the settings screens (docs/mockups/settings-reorg/): splitting a screen's groups into pages, and the
 * home menu's one-line summaries.
 */
object Paging {
    /**
     * Splits groups of [heights] (px, in order) into pages no taller than [page]. A group never splits; one taller than
     * a page gets a page of its own (that page scrolls). [breaks]: groups that always start a new page.
     * Returns each page's group indices; at least one page, even with no groups.
     */
    fun split(heights: List<Int>, page: Int, breaks: Set<Int> = emptySet()): List<IntRange> {
        if (heights.isEmpty()) return listOf(IntRange.EMPTY)
        val out = ArrayList<IntRange>()
        var start = 0
        var used = 0
        for (i in heights.indices) {
            val h = heights[i]
            if (i > start && (i in breaks || used + h > page)) {
                out += start until i
                start = i; used = 0
            }
            used += h
        }
        out += start until heights.size
        return out
    }

    /** True when everything fits on one page of [page] px and nothing forces a break (then there's no pager). */
    fun fitsOne(heights: List<Int>, page: Int, breaks: Set<Int> = emptySet()): Boolean =
        heights.sum() <= page && breaks.none { it in 1 until heights.size }

    /** The page holding [group] (0 when it's not there). */
    fun pageOf(pages: List<IntRange>, group: Int): Int = pages.indexOfFirst { group in it }.coerceAtLeast(0)

    /** "1 / 2". */
    fun label(page: Int, count: Int): String = "${page + 1} / $count"
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
