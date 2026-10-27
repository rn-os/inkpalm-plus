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

    @Test fun autoBrightnessCurveCoversTheWholeRange() {
        assertEquals(2f, Light.autoLevel(1f), 0.01f)
        assertEquals(15f, Light.autoLevel(20f), 0.01f)
        assertEquals(35f, Light.autoLevel(100f), 0.01f)
        assertEquals(60f, Light.autoLevel(500f), 0.01f)
        assertEquals(100f, Light.autoLevel(100_000f), 0.01f)
        assertTrue(Light.autoLevel(2000f) >= 85f)
        var prev = -1f
        for (lux in listOf(0f, 1f, 5f, 20f, 100f, 500f, 2000f, 10000f)) { val v = Light.autoLevel(lux); assertTrue(v >= prev); prev = v }
        assertEquals(Light.autoLevel(200f), Light.autoLevel(50f, 2), 0.001f)    // +2 = lux × 4
        assertEquals(Light.autoLevel(25f), Light.autoLevel(50f, -1), 0.001f)
    }

    @Test fun autoWarmthCurveIsWarmInTheDarkAndCoolInSun() {
        assertTrue(Light.autoWarmth(1f) in 85f..90f)
        assertTrue(Light.autoWarmth(5f) >= 85f)
        assertTrue(Light.autoWarmth(120f) in 45f..60f)
        assertTrue(Light.autoWarmth(1500f) < 25f)
        assertTrue(Light.autoWarmth(5000f) in 15f..20f)
        var prev = 101f
        for (lux in listOf(0f, 1f, 10f, 100f, 300f, 1000f, 3000f, 20000f)) { val v = Light.autoWarmth(lux); assertTrue(v <= prev); prev = v }
    }

    @Test fun autoOffsetsShiftAndClamp() {
        assertEquals(41 to 55, Light.autoResult(41f, 55f, 0, 0))
        assertEquals(51 to 45, Light.autoResult(41f, 55f, 10, -10))
        assertEquals(100 to 100, Light.autoResult(90f, 90f, 50, 50))
        assertEquals(0 to 5, Light.autoResult(15f, 55f, -50, -50))
    }

    /** Prints the lux table: the cold/warm LED values Auto writes at offset 0 (mix + gamma 2). */
    @Test fun autoLuxTable() {
        for (lux in listOf(1f, 10f, 50f, 120f, 500f, 2000f)) {
            val (l, w) = Light.autoResult(Light.autoLevel(lux), Light.autoWarmth(lux), 0, 0)
            val (c, wm) = Light.mix(l, w)
            println("AUTOTABLE lux=${lux.toInt()} level=$l% warmth=$w cold=${Light.raw(c, 1f)} warm=${Light.raw(wm, 1f)}")
            assertTrue(Light.raw(c, 1f) + Light.raw(wm, 1f) > 0)
        }
    }

    @Test fun autoHoldsThenAppliesFirstValueDirectly() {
        val a = AutoLux()
        a.restart(0)
        assertEquals(null, a.sample(400, 100f, 0))
        val r = a.sample(500, 100f, 0)!!
        assertEquals(Light.autoLevel(100f), r.level, 0.001f)
        assertEquals(Light.autoWarmth(100f), r.warmth, 0.001f)
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
        assertTrue(down!!.level < Light.autoLevel(1000f))
        assertTrue(down.warmth > Light.autoWarmth(1000f))
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
        val r = a.force(1)!!
        assertEquals(Light.autoLevel(100f), r.level, 0.001f)
        assertEquals(Light.autoWarmth(100f), r.warmth, 0.001f)
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

class RadiosTest {
    @Test fun requestsAreKeyValueLines() {
        assertEquals("wifi=1\n", Radios.request(Radios.Radio.WIFI, true))
        assertEquals("bt=0\n", Radios.request(Radios.Radio.BT, false))
        assertEquals("airplane=1\n", Radios.request(Radios.Radio.AIRPLANE, true))
    }

    @Test fun labelsAreShort() {
        assertEquals("Wi-Fi\nOn", Radios.label(Radios.Radio.WIFI, Radios.State.ON))
        assertEquals("Bluetooth\nTurning on…", Radios.label(Radios.Radio.BT, Radios.State.TURNING_ON))
        assertEquals("svc bluetooth disable", Radios.shell(Radios.Radio.BT, false))
        assertEquals("cmd connectivity airplane-mode enable", Radios.shell(Radios.Radio.AIRPLANE, true))
    }
}
