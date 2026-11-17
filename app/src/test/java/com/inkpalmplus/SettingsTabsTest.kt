package com.inkpalmplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings screens' links and tabs ([Sections], [Tabs]), landscape columns ([Columns]) and the home menu's
 *  summaries ([Summaries]). */
class SettingsTabsTest {
    @Test fun newNamesOpenThemselves() {
        for (s in listOf(Sections.SLEEP, Sections.WEATHER, Sections.COUNTDOWNS, Sections.TRASH, Sections.PANEL, Sections.PANEL_ADVANCED,
            Sections.POWER, Sections.BUTTONS, Sections.ABOUT, Sections.SCREEN, Sections.SCREEN_ADVANCED))
            assertEquals(Sections.Target(s), Sections.resolve(s, kompakt = true))
        assertEquals(Sections.Target(null), Sections.resolve(null, kompakt = true))
    }

    @Test fun olderNamesOpenTheirTabOrAdvanced() {
        assertEquals(Sections.Target(Sections.SLEEP, Tabs.FACE), Sections.resolve("aod", true))
        assertEquals(Sections.Target(Sections.SCREEN, Tabs.LIGHT), Sections.resolve("light", true))
        assertEquals(Sections.Target(Sections.PANEL_ADVANCED, anchor = "keep_on"), Sections.resolve("keep_on", true))
        assertEquals(Sections.Target(Sections.BUTTONS), Sections.resolve("touch", true))
        assertEquals(Sections.Target(Sections.BUTTONS, anchor = "reading"), Sections.resolve("reading", true))
        assertEquals(Sections.Target(Sections.SLEEP, Tabs.UPDATES), Sections.resolve("updates", true))
        assertEquals(Sections.Target(Sections.SLEEP, Tabs.HOME), Sections.resolve("home_face", true))
    }

    @Test fun stockFirmwareScreenIsMoaansEinkScreen() {
        assertEquals(Sections.Target(Sections.EINK), Sections.resolve("screen", kompakt = false))
        assertEquals(Sections.Target(Sections.EINK), Sections.resolve("screen_advanced", kompakt = false))
        assertEquals(Sections.Target(Sections.EINK), Sections.resolve("light", kompakt = false))
        assertEquals(Sections.Target(Sections.SLEEP, Tabs.FACE), Sections.resolve("aod", kompakt = false))
    }

    @Test fun aTabExtraWins() {
        assertEquals(Sections.Target(Sections.SLEEP, Tabs.HOME), Sections.resolve("sleep", true, tab = "home"))
        assertEquals(Sections.Target(Sections.SCREEN, Tabs.SCREEN), Sections.resolve("light", true, tab = "screen"))
    }

    @Test fun sleepTabs() {
        assertEquals(listOf("Face", "Updates"), Tabs.sleep(homeFace = false).map { it.title })
        assertEquals(listOf("Face", "Updates", "Home face"), Tabs.sleep(homeFace = true).map { it.title })
        assertEquals(listOf("Screen", "Light"), Tabs.screen(light = true).map { it.title })
        assertEquals(listOf("Screen"), Tabs.screen(light = false).map { it.title })
    }

    @Test fun pickingATab() {
        val home = Tabs.sleep(true); val plain = Tabs.sleep(false)
        assertEquals(Tabs.FACE, Tabs.pick(home, null, null))
        // The session's last tab, unless this visit (or a link) wants another.
        assertEquals(Tabs.UPDATES, Tabs.pick(home, null, Tabs.UPDATES))
        assertEquals(Tabs.HOME, Tabs.pick(home, Tabs.HOME, Tabs.UPDATES))
        // The Home face tab goes away with the Home face: the remembered one, else the first.
        assertEquals(Tabs.UPDATES, Tabs.pick(plain, Tabs.HOME, Tabs.UPDATES))
        assertEquals(Tabs.FACE, Tabs.pick(plain, Tabs.HOME, Tabs.HOME))
        assertEquals(Tabs.FACE, Tabs.pick(plain, "nonsense", null))
        assertEquals(Tabs.LIGHT, Tabs.pick(Tabs.screen(true), Tabs.LIGHT, null))
        assertEquals(Tabs.SCREEN, Tabs.pick(Tabs.screen(false), Tabs.LIGHT, Tabs.LIGHT))
        assertNull(Tabs.pick(emptyList(), Tabs.FACE, null))
    }

    @Test fun twoColumns() {
        assertEquals(0, Columns.split(emptyList()))
        assertEquals(1, Columns.split(listOf(500)))
        assertEquals(1, Columns.split(listOf(100, 100)))
        // 200 | 100 beats 100 | 200 (left taller on a tie).
        assertEquals(2, Columns.split(listOf(100, 100, 100)))
        assertEquals(1, Columns.split(listOf(400, 100, 100, 100)))
        assertEquals(3, Columns.split(listOf(50, 50, 50, 400)))
        assertTrue(Columns.overflows(901, 900))
        assertFalse(Columns.overflows(900, 900))
        assertFalse(Columns.overflows(900, 0))
    }

    @Test fun summaries() {
        assertEquals("Agenda · Classic · every 5 min", Summaries.sleep("Agenda · Classic", false, true, 5))
        assertEquals("Clock · Frozen", Summaries.sleep("Clock", false, true, 0))
        assertEquals("Moaan's picture", Summaries.sleep("Default", true, false, 5))
        assertEquals("Picture", Summaries.sleep("Picture", false, false, 5))
        assertEquals("Text everywhere · light follows Android", Summaries.screen("Text", true))
        assertEquals("Text everywhere · light set in the screen panel", Summaries.screen("Text", false))
        assertEquals("Quality everywhere", Summaries.screen("Quality", null))
        assertEquals("Left edge · 9 apps", Summaries.sidePanel(true, false, 9))
        assertEquals("Off · turn it on in Accessibility", Summaries.sidePanel(false, true, 9))
        assertEquals("Picture: Panda · auto off after 24 h", Summaries.powerOff("Panda", 24))
        assertEquals("Picture: Moaan's · auto off never", Summaries.powerOff("Moaan's", 0))
        assertEquals("Auto off after 4 h", Summaries.powerOff(null, 4))
        assertEquals("Touch: Home / hold refresh · Reader: KOReader", Summaries.buttons(Summaries.kompaktTouch(true), "KOReader"))
        assertEquals("Home", Summaries.kompaktTouch(false))
        assertEquals("Normal", Summaries.contrast(ScreenModes.contrastLabel(ScreenModes.CONTRAST_DEFAULT)))
        assertEquals("+2", Summaries.contrast(ScreenModes.contrastLabel(ScreenModes.CONTRAST_DEFAULT + 2 * ScreenModes.CONTRAST_STEP)))
    }
}
