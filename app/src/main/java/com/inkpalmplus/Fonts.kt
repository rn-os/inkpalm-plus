package com.inkpalmplus

import android.content.Context
import android.graphics.Typeface
import java.io.File

/**
 * The system font (mockup: docs/mockups/09-font.png). Moaan's framework draws every app's default
 * font from `persist.sys.mogufont.path` and reloads it on the next font-scale change, so a switch
 * applies live: set the property (Moaan's eink service sets any property) and nudge the font scale
 * ([Live.nudgeConfiguration]). The fonts offered are Android's own (always present, readable by
 * every app), plus the font the device had before InkPalm+ changed it.
 */
object Fonts {
    private const val PROP = "persist.sys.mogufont.path"

    class Font(val title: String, /** Relative to "/", as Moaan stores it. */ val path: String)

    private val SYSTEM = listOf(
        Font("Roboto", "system/fonts/Roboto-Regular.ttf"),
        Font("Roboto Medium", "system/fonts/Roboto-Medium.ttf"),
        Font("Roboto Bold", "system/fonts/Roboto-Bold.ttf"),
        Font("Roboto Black", "system/fonts/Roboto-Black.ttf"),
        Font("Noto Serif", "system/fonts/NotoSerif-Regular.ttf"),
        Font("Noto Serif Bold", "system/fonts/NotoSerif-Bold.ttf"),
    )

    fun current(): String = Live.getProperty(PROP, "")

    /** The device's own font (Moaan's or the user's), remembered the first time it's seen. */
    fun original(context: Context): String? {
        val prefs = Prefs(context)
        val now = current()
        if (now.isNotEmpty() && SYSTEM.none { it.path == now } && now != prefs.originalFont) prefs.originalFont = now
        return prefs.originalFont
    }

    fun list(context: Context): List<Font> =
        listOfNotNull(original(context)?.let { Font("Moaan font", it) }) + SYSTEM.filter { File("/" + it.path).canRead() }

    fun typeface(f: Font): Typeface? = runCatching { Typeface.createFromFile("/" + f.path) }.getOrNull()

    /** Switches the system font and redraws open apps. Call off the main thread. */
    fun apply(context: Context, f: Font): Boolean {
        original(context)
        val set = Live.setProperty(PROP, f.path)
        return set && current() == f.path && Live.nudgeConfiguration(context)
    }
}
