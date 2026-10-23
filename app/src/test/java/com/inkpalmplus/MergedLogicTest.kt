package com.inkpalmplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of what came over from InkControl. */
class MergedLogicTest {
    @Test fun modesMatchKompaktOsAndInkControl() {
        assertEquals(listOf(12, 14, 13, 8, 7, 22), ScreenMode.entries.map { it.code })
        assertEquals(listOf("fast", "soft", "fastdither", "text", "quality", "auto"), ScreenMode.entries.map { it.key })
        assertEquals(ScreenMode.TEXT, ScreenMode.ofKey("text"))
        assertEquals(null, ScreenMode.ofKey("nope"))
        assertEquals(ScreenMode.QUALITY, ScreenMode.DEFAULT)
    }

    @Test fun warmthMixesTheTwoChannels() {
        assertEquals(60 to 60, Light.mix(60, 50))
        assertEquals(60 to 0, Light.mix(60, 0))
        assertEquals(0 to 60, Light.mix(60, 100))
        assertEquals(100 to 50, Light.mix(100, 25))
    }

    @Test fun ledLevelsUseGammaTwo() {
        assertEquals(0, Light.raw(0, 1f))
        assertEquals(Light.MAX, Light.raw(100, 1f))
        assertEquals(512, Light.raw(50, 1f))          // 2047 · 0.25
        assertEquals(256, Light.raw(50, 0.5f))
        assertEquals(Light.MAX, Light.raw(150, 2f))   // clamped
    }

    @Test fun luxCurveIsBoundedAndRising() {
        assertEquals(0.1f, Light.luxScale(0f), 0.001f)
        assertEquals(1f, Light.luxScale(300f), 0.001f)
        assertEquals(1f, Light.luxScale(100_000f), 0.001f)
        assertTrue(Light.luxScale(10f) < Light.luxScale(100f))
    }

    @Test fun contrastLabelsCountStepsFromNormal() {
        assertEquals("Contrast normal", ScreenModes.contrastLabel(30))
        assertEquals("Contrast +1", ScreenModes.contrastLabel(40))
        assertEquals("Contrast −3", ScreenModes.contrastLabel(0))
        assertEquals("Contrast +5", ScreenModes.contrastLabel(80))
    }

    @Test fun pandaBandIsMoaansTextBand() {
        assertEquals(1040..1240, SleepImages.band(1440))
        assertEquals(520..620, SleepImages.band(720))
    }
}
