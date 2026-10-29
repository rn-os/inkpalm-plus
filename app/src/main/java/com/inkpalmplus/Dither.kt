package com.inkpalmplus

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log

/**
 * The Photo style of the Photo and Picture faces. The panel has 16 greys, so a photo's soft gradients band into
 * blotchy steps; error diffusion trades the bands for dots. Run at the panel's own resolution and orientation, so one
 * picture pixel is one panel pixel (276 ppi: the dots read as grey). The pure parts work on greys (0–255, row by row)
 * and are unit tested; [apply] is the Bitmap wrapper.
 */
object Dither {
    /** Crisp is the default (the user's pick on the device, 2026-10-29); Plain is the picture as before. */
    enum class Style(val title: String, val detail: String) {
        CRISP("Crisp", "Black and white dots (Atkinson): sharp, no grey bands"),
        SMOOTH("Smooth", "The panel's 16 greys with fine dots between them (Floyd–Steinberg)"),
        PLAIN("Plain", "Greys as they come: gradients may show bands");

        companion object { fun of(s: String?): Style = entries.firstOrNull { it.name == s } ?: CRISP }
    }

    /** The panel's grey levels. */
    const val LEVELS = 16

    /** Rec. 601 luma of an ARGB colour, 0–255 (as Pillow's "L"). */
    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114 + 500) / 1000
    }

    /** [grey] dithered in [style] (a new array; Plain gives a copy). */
    fun greys(grey: IntArray, w: Int, h: Int, style: Style): IntArray = when (style) {
        Style.CRISP -> atkinson(grey, w, h)
        Style.SMOOTH -> floydSteinberg(grey, w, h, LEVELS)
        Style.PLAIN -> grey.copyOf()
    }

    /**
     * Atkinson to black and white: 1/8 of the error to each of six neighbours (x+1, x+2; x-1, x, x+1 below; x two
     * below), so 1/4 is dropped: highlights and shadows stay clean. Three rows of error are kept, not the picture.
     */
    fun atkinson(grey: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        // Padded by one on the left and two on the right.
        var e0 = FloatArray(w + 3); var e1 = FloatArray(w + 3); var e2 = FloatArray(w + 3)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val o = grey[row + x] + e0[x + 1]
                val n = if (o > 127f) 255 else 0
                out[row + x] = n
                val e = (o - n) / 8f
                e0[x + 2] += e; e0[x + 3] += e
                e1[x] += e; e1[x + 1] += e; e1[x + 2] += e
                e2[x + 1] += e
            }
            val t = e0; e0 = e1; e1 = e2; e2 = t; e2.fill(0f)
        }
        return out
    }

    /** Floyd–Steinberg to [levels] evenly spaced greys (7/16 right, 3/16, 5/16, 1/16 below). */
    fun floydSteinberg(grey: IntArray, w: Int, h: Int, levels: Int): IntArray {
        val out = IntArray(w * h)
        val step = 255f / (levels - 1)
        // Padded by one on each side.
        var cur = FloatArray(w + 2); var next = FloatArray(w + 2)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val o = grey[row + x] + cur[x + 1]
                val n = Math.round(o.coerceIn(0f, 255f) / step) * step
                out[row + x] = Math.round(n)
                val e = o - n
                cur[x + 2] += e * 7 / 16
                next[x] += e * 3 / 16; next[x + 1] += e * 5 / 16; next[x + 2] += e / 16
            }
            val t = cur; cur = next; next = t; next.fill(0f)
        }
        return out
    }

    /** Dithers [area] (default all) of the mutable [bmp] in place, as grey. Plain leaves it untouched. */
    fun apply(bmp: Bitmap, style: Style, area: Rect = Rect(0, 0, bmp.width, bmp.height)) {
        if (style == Style.PLAIN || area.isEmpty) return
        val t0 = SystemClock.uptimeMillis()
        val w = area.width(); val h = area.height()
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, area.left, area.top, w, h)
        val grey = IntArray(px.size) { luma(px[it]) }
        val out = greys(grey, w, h, style)
        for (i in px.indices) { val v = out[i]; px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        bmp.setPixels(px, 0, w, area.left, area.top, w, h)
        if (w * h >= 100_000) Log.i("InkPalmPlus", "dither: ${style.name} ${w}x$h in ${SystemClock.uptimeMillis() - t0} ms")
    }
}
