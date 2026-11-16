package com.inkpalmplus

import com.inkpalmplus.Home.MicrogStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Fix microG location": when it's offered and the order it tries (bridge, Shizuku, microG's permission page). */
class MicrogFixTest {
    @Test fun offeredOnlyWhenMicrogLacksALocationPermission() {
        assertFalse(Home.microgFixNeeded(installed = false, fine = false, background = false))
        assertFalse(Home.microgFixNeeded(installed = true, fine = true, background = true))
        assertTrue(Home.microgFixNeeded(installed = true, fine = false, background = true))
        assertTrue(Home.microgFixNeeded(installed = true, fine = true, background = false))
    }

    @Test fun triesBridgeThenShizukuThenSettings() {
        assertEquals(MicrogStep.BRIDGE, Home.microgNextStep(false, bridge = true, triedBridge = false, shizuku = true, triedShizuku = false))
        assertEquals(MicrogStep.SHIZUKU, Home.microgNextStep(false, bridge = true, triedBridge = true, shizuku = true, triedShizuku = false))
        assertEquals(MicrogStep.SETTINGS, Home.microgNextStep(false, bridge = true, triedBridge = true, shizuku = true, triedShizuku = true))
        assertEquals(MicrogStep.SHIZUKU, Home.microgNextStep(false, bridge = false, triedBridge = false, shizuku = true, triedShizuku = false))
        assertEquals(MicrogStep.SETTINGS, Home.microgNextStep(false, bridge = false, triedBridge = false, shizuku = false, triedShizuku = false))
    }

    @Test fun stopsAsSoonAsGranted() {
        assertEquals(MicrogStep.DONE, Home.microgNextStep(true, bridge = true, triedBridge = false, shizuku = true, triedShizuku = false))
        assertEquals(MicrogStep.DONE, Home.microgNextStep(true, bridge = true, triedBridge = true, shizuku = false, triedShizuku = false))
    }

    @Test fun grantsOnlyMicrogsLocation() {
        assertTrue(Home.MICROG_COMMANDS.all { Home.MICROG in it && "LOCATION" in it })
        assertEquals("microg_location=1\n", Home.MICROG_REQUEST)
    }
}
