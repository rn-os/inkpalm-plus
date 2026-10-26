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
        assertEquals(0.08f, Light.luxScale(0f), 0.001f)
        assertEquals(1f, Light.luxScale(1999f), 0.001f)
        assertEquals(1f, Light.luxScale(100_000f), 0.001f)
        assertTrue(Light.luxScale(10f) < Light.luxScale(100f))
        assertEquals(Light.luxScale(200f), Light.luxScale(50f, 2), 0.001f)    // +2 = lux × 4
        assertEquals(Light.luxScale(25f), Light.luxScale(50f, -1), 0.001f)
    }

    @Test fun autoHoldsThenAppliesFirstValueDirectly() {
        val a = AutoLux()
        a.restart(0)
        assertEquals(null, a.sample(400, 100f, 0))
        val r = a.sample(500, 100f, 0)!!
        assertEquals(Light.luxScale(100f), r.scale, 0.001f)
        assertEquals(false, r.ramp)
    }

    @Test fun autoWaitsTwoSecondsUpAndFourDown() {
        val a = AutoLux()
        a.restart(0); a.sample(500, 100f, 0)
        var t = 500L
        var up: AutoLux.Result? = null
        while (up == null && t < 20_000) { t += 1000; up = a.sample(t, 1000f, 0) }
        assertEquals(true, up!!.ramp)
        assertTrue(t - 500 >= 2000)
        val b = AutoLux()
        b.restart(0); b.sample(500, 1000f, 0)
        t = 500
        var down: AutoLux.Result? = null
        while (down == null && t < 20_000) { t += 1000; down = b.sample(t, 100f, 0) }
        assertTrue(t - 500 >= 4000)
        assertTrue(down!!.scale < Light.luxScale(1000f))
    }

    @Test fun autoIgnoresSmallDriftAndSteadyLight() {
        val a = AutoLux()
        a.restart(0); a.sample(500, 100f, 0)
        for (i in 1..20) assertEquals(null, a.sample(500L + i * 1000, if (i % 2 == 0) 108f else 94f, 0))
    }

    @Test fun sensitivityRescalesAtOnce() {
        val a = AutoLux()
        assertEquals(null, a.force(1))
        a.restart(0); a.sample(500, 50f, 0)
        assertEquals(Light.luxScale(100f), a.force(1)!!, 0.001f)
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
