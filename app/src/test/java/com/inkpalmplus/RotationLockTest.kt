package com.inkpalmplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RotationLockTest {
    @Test fun lockingPinsTheRotationFirst() {
        assertEquals(listOf("user_rotation" to 1, "accelerometer_rotation" to 0), RotationLock.writes(true, 1))
        assertEquals(listOf("user_rotation" to 3, "accelerometer_rotation" to 0), RotationLock.writes(true, 7))
        assertEquals(listOf("accelerometer_rotation" to 1), RotationLock.writes(false, 1))
    }

    @Test fun labelsAreNameOverState() {
        assertEquals("Rotate\nLocked", RotationLock.label(true))
        assertEquals("Rotate\nAuto", RotationLock.label(false))
        assertEquals("BT\nOn", Radios.label(Radios.Radio.BT, Radios.State.ON, short = true))
        assertEquals("Bluetooth\nOff", Radios.label(Radios.Radio.BT, Radios.State.OFF))
        assertEquals("Wi-Fi\nOff", Radios.label(Radios.Radio.WIFI, Radios.State.OFF, short = true))
    }

    /** A line's width: 6 px per character per sp-step above 0 (so 10 chars at 10 sp = 60 px). */
    private val w = { s: String, sp: Int -> s.length * sp * 0.6f }

    @Test fun oneSizeFitsTheWidestLine() {
        // "Bluetooth" (9) is the widest: 9·0.6·sp ≤ 60 → sp ≤ 11.
        assertEquals(11, ToggleFit.sharedSp(listOf("Wi-Fi\nOff", "Bluetooth\nOff", "Rotate\nAuto"), 60f, 12, 10, w))
        assertEquals(12, ToggleFit.sharedSp(listOf("BT\nOff", "Rotate\nAuto"), 60f, 12, 10, w))
    }

    @Test fun nullWhenNothingFits() {
        assertNull(ToggleFit.sharedSp(listOf("Bluetooth\nOff"), 50f, 12, 10, w))
        assertEquals(9, ToggleFit.sharedSp(listOf("Bluetooth\nOff"), 50f, 12, 9, w))
    }
}
