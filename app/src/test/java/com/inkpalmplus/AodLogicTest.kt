package com.inkpalmplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the always-on sleep screen ([Aod]). */
class AodLogicTest {
    @Test fun neoReaderProgress() {
        assertEquals(417f / 1367f, Aod.progressOf("417/1367")!!, 0.0001f)
        assertEquals(1f, Aod.progressOf("20/10")!!, 0f)
        assertNull(Aod.progressOf("1/0"))
        assertNull(Aod.progressOf(null))
        assertNull(Aod.progressOf("chapter 3"))
    }

    @Test fun liveUpdatesLandOnTheWallClock() {
        val min = 60_000L
        // 10:12:30 local → 10:15 at 5 min.
        val t = (10 * 60 + 12) * min + 30_000
        assertEquals(2 * min + 30_000, Aod.delayToNext(t, 0, 5))
        // Less than a minute to the boundary: the one after.
        assertEquals(5 * min + 40_000, Aod.delayToNext((10 * 60 + 14) * min + 20_000, 0, 5))
        // The local offset counts: UTC 09:59:30 at +1 h is 10:59:30 local → 11:00 is too close, so 12:00 for 60 min.
        assertEquals(60 * min + 30_000, Aod.delayToNext(9 * 60 * min + 59 * min + 30_000, 60 * min, 60))
        assertEquals(15 * min, Aod.delayToNext(0, 0, 15))
    }

    @Test fun landscapeTurnsMatchAndroidRotation() {
        assertEquals(listOf(0, 90, 180, 270), (0..3).map { Aod.degreesFor(it) })
    }

    @Test fun lowBatteryOnlyOnBattery() {
        assertTrue(Aod.isLow(9, false))
        assertFalse(Aod.isLow(10, false))
        assertFalse(Aod.isLow(5, true))
        assertFalse(Aod.isLow(-1, false))
    }

    @Test fun batteryCostIsHonest() {
        assertEquals("Free.", Aod.cost(0).first)
        assertEquals("About 4% of battery a day.", Aod.cost(5).first)
        assertEquals("About 1.3% of battery a day.", Aod.cost(15).first)
        assertEquals("About 0.7% of battery a day.", Aod.cost(30).first)
        assertEquals("About 0.3% of battery a day.", Aod.cost(60).first)
        assertTrue(Aod.cost(5).second.startsWith("288 wakes a day"))
        assertEquals(5, Aod.DEFAULT_INTERVAL)
        assertEquals(listOf(0, 5, 15, 30, 60), Aod.INTERVALS)
    }

    @Test fun sleepFreezesTheHeldOrientation() {
        val o = Aod.Orientation()
        // Awake in landscape (ROTATION_90): drawn as the display says.
        assertEquals(1, o.current { 1 })
        o.sleep()
        // Asleep and in the redraw's wake the display reports 0: ignored.
        assertEquals(1, o.current { 0 })
        o.awake(0)
        assertEquals(1, o.current { 3 })
        assertTrue(o.asleep)
        // The user's wake clears it.
        o.wake()
        assertFalse(o.asleep)
        assertEquals(0, o.current { 0 })
        o.awake(3); o.sleep()
        assertEquals(3, o.current { 0 })
    }
}
