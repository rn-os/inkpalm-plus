package com.inkpalmplus

import org.junit.Assert.assertEquals
import org.junit.Test

class RotationLockTest {
    @Test fun lockingPinsTheRotationFirst() {
        assertEquals(listOf("user_rotation" to 1, "accelerometer_rotation" to 0), RotationLock.writes(true, 1))
        assertEquals(listOf("user_rotation" to 3, "accelerometer_rotation" to 0), RotationLock.writes(true, 7))
        assertEquals(listOf("accelerometer_rotation" to 1), RotationLock.writes(false, 1))
    }

    @Test fun iconAndDescriptionFollowTheLock() {
        assertEquals(R.drawable.ic_screen_lock_rotation, RotationLock.icon(true))
        assertEquals(R.drawable.ic_screen_rotation, RotationLock.icon(false))
        assertEquals("Rotation, locked", RotationLock.describe(true))
        assertEquals("Rotation, auto", RotationLock.describe(false))
    }
}
