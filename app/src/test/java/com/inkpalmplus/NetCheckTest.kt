package com.inkpalmplus

import com.inkpalmplus.NetCheck.State
import org.junit.Assert.assertEquals
import org.junit.Test

/** [NetCheck]: online, offline, or blocked by Android (restricted networking mode) while the phone is online. */
class NetCheckTest {
    @Test fun online() {
        assertEquals(State.ONLINE, NetCheck.decide(appInternet = true, deviceInternet = true, deviceNetwork = true, restrictedMode = true))
        assertEquals(State.ONLINE, NetCheck.decide(appInternet = true, deviceInternet = false, deviceNetwork = false, restrictedMode = false))
    }

    @Test fun blockedWhenThePhoneIsOnlineButWeAreNot() {
        assertEquals(State.BLOCKED, NetCheck.decide(appInternet = false, deviceInternet = true, deviceNetwork = true, restrictedMode = false))
        assertEquals(State.BLOCKED, NetCheck.decide(appInternet = false, deviceInternet = true, deviceNetwork = true, restrictedMode = true))
        // Restricted mode on and a network connected whose capabilities we can't see: still blocked.
        assertEquals(State.BLOCKED, NetCheck.decide(appInternet = false, deviceInternet = false, deviceNetwork = true, restrictedMode = true))
    }

    @Test fun offline() {
        assertEquals(State.OFFLINE, NetCheck.decide(appInternet = false, deviceInternet = false, deviceNetwork = false, restrictedMode = true))
        assertEquals(State.OFFLINE, NetCheck.decide(appInternet = false, deviceInternet = false, deviceNetwork = true, restrictedMode = false))
    }

    @Test fun logLines() {
        assertEquals("home: weather: blocked by restricted networking", NetCheck.logLine(State.BLOCKED, true))
        assertEquals("home: weather: online", NetCheck.logLine(State.ONLINE, false))
        assertEquals("home: weather: offline", NetCheck.logLine(State.OFFLINE, true))
    }

    @Test fun theFaceSaysWhyThereIsNoWeather() {
        assertEquals(Home.BLOCKED, Home.weatherView(null, 0, 0, false, false, false, blocked = true) { "" }.message)
        assertEquals("No weather yet: offline", Home.weatherView(null, 0, 0, false, false, false) { "" }.message)
    }
}
