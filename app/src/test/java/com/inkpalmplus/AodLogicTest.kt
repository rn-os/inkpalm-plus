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

    @Test fun liveUpdatesAreNeverArmedWithinAMinute() {
        val min = 60_000L
        val mark = (10 * 60 + 15) * min
        // On the mark (the alarm that just fired), just before it, just after it: never the mark itself.
        assertEquals(5 * min, Aod.delayToNext(mark, 0, 5))
        assertEquals(5 * min + 1, Aod.delayToNext(mark - 1, 0, 5))
        assertEquals(5 * min - 1, Aod.delayToNext(mark + 1, 0, 5))
        // Exactly a minute ahead is allowed; a millisecond less is not.
        assertEquals(min, Aod.delayToNext(mark - min, 0, 5))
        assertEquals(6 * min - 1, Aod.delayToNext(mark - min + 1, 0, 5))
        // Negative offsets (west of UTC) and every interval: always within [1 min, interval + 1 min], on a boundary.
        for (tz in listOf(-7 * 60 * min, -3 * 60 * min - 30 * min, 0L, 5 * 60 * min + 45 * min))
            for (m in Aod.INTERVALS.filter { it > 0 })
                for (t in 0L until 2 * m * min step 7_919L) {
                    val now = 1_790_000_000_000L + t
                    val d = Aod.delayToNext(now, tz, m)
                    assertTrue("tz=$tz m=$m t=$t d=$d", d >= Aod.MIN_DELAY_MS && d <= m * min + Aod.MIN_DELAY_MS)
                    assertEquals(0L, Math.floorMod(now + tz + d, m * min))
                }
    }

    /** The display turns window content clockwise by degreesFor(rotation) onto the panel; the cover must undo it. */
    @Test fun coverShowsThePanelBufferAtEveryRotation() {
        assertEquals(listOf(0, 270, 180, 90), (0..3).map { WakeCover.turnFor(it) })
        val w = 720f; val h = 1440f
        fun apply(t: FloatArray, x: Float, y: Float) = (t[0] * x + t[1] * y + t[4]) to (t[2] * x + t[3] * y + t[5])
        for (r in 0..3) {
            val cover = WakeCover.turnFor(r)
            // The window's frame at this rotation, and the picture turned into it.
            val (fw, fh) = if (cover % 180 == 0) w to h else h to w
            val toWindow = WakeCover.turn(cover, w, h)
            val toPanel = WakeCover.turn(Aod.degreesFor(r), fw, fh)
            for ((x, y) in listOf(0f to 0f, w to 0f, 0f to h, w to h, 100f to 300f, 719f to 1f)) {
                val (wx, wy) = apply(toWindow, x, y)
                assertTrue("r=$r in frame", wx in 0f..fw && wy in 0f..fh)
                val (px, py) = apply(toPanel, wx, wy)
                assertEquals("r=$r x", x, px, 0.001f)
                assertEquals("r=$r y", y, py, 0.001f)
            }
        }
        // A landscape face (ROTATION_90) is turned +90° into the buffer; at ROTATION_90 the cover shows it upright:
        // the buffer's top-left (the face's bottom-left) lands at the window's bottom-left.
        assertEquals(0f to w, apply(WakeCover.turn(WakeCover.turnFor(1), w, h), 0f, 0f))
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
