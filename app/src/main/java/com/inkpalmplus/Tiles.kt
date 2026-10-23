package com.inkpalmplus

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tiles (from InkControl): one per screen mode, a mode cycler, Full refresh and the front light.
 * KompaktOS shows tiles icon-only, so each icon is the tile's name in words ([TextIcon]). Users add them with the
 * pencil editor (KompaktOS's SystemUI ignores `cmd statusbar add-tile`).
 */
object TextIcon {
    private const val SIZE = 96

    /** Bold, condensed, fitted, up to two lines ("\n"); a white mask SystemUI tints like any icon. */
    fun of(text: String): Icon {
        val b = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xffffffff.toInt()
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val lines = text.split("\n")
        val maxW = SIZE - 4f
        var size = (SIZE - 4f) / lines.size
        for (l in lines) {
            p.textSize = 100f
            size = minOf(size, 100f * maxW / maxOf(1f, p.measureText(l)))
        }
        p.textSize = size
        val fm = p.fontMetrics
        val lineH = fm.descent - fm.ascent
        val top = (SIZE - lineH * lines.size) / 2f - fm.ascent
        lines.forEachIndexed { i, l -> c.drawText(l, SIZE / 2f, top + i * lineH, p) }
        return Icon.createWithBitmap(b)
    }
}

/** A tap makes this mode the device-wide one, shows it now, and repaints. */
abstract class ModeTile(private val mode: ScreenMode) : TileService() {
    override fun onStartListening() = render()

    override fun onClick() {
        if (!Eink.isKompakt) return
        ScreenModes.chooseEverywhere(this, mode)
        render()
    }

    private fun render() {
        val t = qsTile ?: return
        t.label = mode.title
        t.icon = TextIcon.of(mode.icon)
        t.subtitle = "E-ink"
        t.state = when {
            !Eink.isKompakt -> Tile.STATE_UNAVAILABLE
            ScreenModes.global(this) == mode -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        t.updateTile()
    }

    class Fast : ModeTile(ScreenMode.FAST)
    class Soft : ModeTile(ScreenMode.SOFT)
    class Dither : ModeTile(ScreenMode.DITHER)
    class Text : ModeTile(ScreenMode.TEXT)
    class Quality : ModeTile(ScreenMode.QUALITY)
    class Auto : ModeTile(ScreenMode.AUTO)
}

/** Steps the device-wide mode through the six, in KompaktOS's order. */
class ModeCycleTile : TileService() {
    override fun onStartListening() = render()

    override fun onClick() {
        if (!Eink.isKompakt) return
        val all = ScreenMode.entries
        ScreenModes.chooseEverywhere(this, all[(all.indexOf(ScreenModes.global(this)) + 1) % all.size])
        render()
    }

    private fun render() {
        val t = qsTile ?: return
        val m = ScreenModes.global(this)
        t.label = "E-ink mode"
        t.subtitle = m.title
        t.icon = TextIcon.of("MODE\n${m.icon}")
        t.state = if (Eink.isKompakt) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
        t.updateTile()
    }
}

class RefreshTile : TileService() {
    override fun onStartListening() {
        val t = qsTile ?: return
        t.label = "Full refresh"
        t.icon = TextIcon.of("REFRESH")
        t.state = Tile.STATE_INACTIVE
        t.updateTile()
    }

    override fun onClick() = ScreenModes.fullRefresh()
}

/** Front light on or off. */
class LightTile : TileService() {
    override fun onStartListening() = render()

    override fun onClick() {
        if (!Light.supported()) return
        Light.setOn(this, !Light.isOn(this))
        render()
    }

    private fun render() {
        val t = qsTile ?: return
        t.label = "Front light"
        t.icon = TextIcon.of("LIGHT")
        t.state = when {
            !Light.supported() -> Tile.STATE_UNAVAILABLE
            Light.isOn(this) -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        t.updateTile()
    }
}
