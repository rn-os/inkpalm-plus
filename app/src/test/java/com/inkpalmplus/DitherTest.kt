package com.inkpalmplus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Photo styles' dithers ([Dither]) on plain grey arrays. */
class DitherTest {
    private val w = 120
    private val h = 90

    /** A diagonal ramp, black to white. */
    private fun ramp() = IntArray(w * h) { i -> ((i % w + i / w) * 255) / (w + h - 2) }

    private fun flat(v: Int) = IntArray(w * h) { v }

    private val levels = (0 until Dither.LEVELS).map { it * 17 }.toSet()

    @Test fun crispIsBlackAndWhite() {
        assertTrue(Dither.greys(ramp(), w, h, Dither.Style.CRISP).all { it == 0 || it == 255 })
    }

    @Test fun smoothUsesOnlyThePanelsGreys() {
        val out = Dither.greys(ramp(), w, h, Dither.Style.SMOOTH)
        assertTrue(out.all { it in levels })
        // A ramp uses many of them, not just black and white.
        assertTrue(out.toSet().size >= 12)
    }

    @Test fun plainIsUntouched() {
        val src = ramp()
        val out = Dither.greys(src, w, h, Dither.Style.PLAIN)
        assertArrayEquals(src, out)
        assertTrue(out !== src)
    }

    @Test fun flatMidGreyIsAboutHalfBlack() {
        val out = Dither.greys(flat(128), w, h, Dither.Style.CRISP)
        val black = out.count { it == 0 }.toFloat() / out.size
        assertEquals(0.5f, black, 0.05f)
    }

    @Test fun crispKeepsTheMeanTone() {
        // Atkinson drops a quarter of the error, so light and dark greys come out a little clipped; 10 % is plenty.
        for (v in listOf(64, 96, 160, 192)) {
            val mean = Dither.greys(flat(v), w, h, Dither.Style.CRISP).average()
            assertEquals("grey $v", v.toDouble(), mean, 25.5)
        }
    }

    @Test fun smoothKeepsTheMeanTone() {
        // 125 lies between two levels (119 and 136): the dither mixes them to the same mean.
        val out = Dither.greys(flat(125), w, h, Dither.Style.SMOOTH)
        assertEquals(setOf(119, 136), out.toSet())
        assertEquals(125.0, out.average(), 1.0)
    }

    @Test fun blackAndWhiteStayClean() {
        for (s in Dither.Style.entries) {
            assertTrue(Dither.greys(flat(0), w, h, s).all { it == 0 })
            assertTrue(Dither.greys(flat(255), w, h, s).all { it == 255 })
        }
        // A panel level is kept as it is by Smooth.
        assertTrue(Dither.greys(flat(136), w, h, Dither.Style.SMOOTH).all { it == 136 })
    }

    @Test fun lumaIsRec601() {
        assertEquals(0, Dither.luma(0xFF000000.toInt()))
        assertEquals(255, Dither.luma(0xFFFFFFFF.toInt()))
        assertEquals(76, Dither.luma(0xFFFF0000.toInt()))
        assertEquals(150, Dither.luma(0xFF00FF00.toInt()))
        assertEquals(29, Dither.luma(0xFF0000FF.toInt()))
    }

    @Test fun styleDefaultsToCrisp() {
        assertEquals(Dither.Style.CRISP, Dither.Style.of(null))
        assertEquals(Dither.Style.CRISP, Dither.Style.of("nonsense"))
        assertEquals(Dither.Style.PLAIN, Dither.Style.of("PLAIN"))
    }
}
