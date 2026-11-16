package com.inkpalmplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings screens' paging ([Paging]) and the home menu's summaries ([Summaries]). */
class SettingsPagesTest {
    @Test fun everythingOnOnePageWhenItFits() {
        assertEquals(listOf(0..2), Paging.split(listOf(100, 200, 300), 600))
        assertTrue(Paging.fitsOne(listOf(100, 200, 300), 600))
        assertFalse(Paging.fitsOne(listOf(100, 200, 301), 600))
    }

    @Test fun groupsNeverSplit() {
        // 250 + 250 = 500 fits; the third would overflow, so it starts page 2.
        assertEquals(listOf(0..1, 2..3), Paging.split(listOf(250, 250, 250, 100), 500))
    }

    @Test fun aGroupTallerThanAPageGetsItsOwn() {
        assertEquals(listOf(0..0, 1..1, 2..2), Paging.split(listOf(100, 900, 100), 500))
        assertEquals(listOf(0..0), Paging.split(listOf(900), 500))
    }

    @Test fun forcedBreaks() {
        assertEquals(listOf(0..1, 2..3), Paging.split(listOf(10, 10, 10, 10), 500, setOf(2)))
        // A break on the first group, or one that would start a page anyway, adds no empty page.
        assertEquals(listOf(0..1), Paging.split(listOf(10, 10), 500, setOf(0)))
        assertEquals(listOf(0..0, 1..1), Paging.split(listOf(400, 400), 500, setOf(1)))
        assertFalse(Paging.fitsOne(listOf(10, 10), 500, setOf(1)))
        assertTrue(Paging.fitsOne(listOf(10, 10), 500, setOf(0)))
    }

    @Test fun noGroups() {
        assertEquals(1, Paging.split(emptyList(), 500).size)
        assertEquals(0, Paging.pageOf(Paging.split(emptyList(), 500), 3))
    }

    @Test fun pageOfAndLabel() {
        val pages = Paging.split(listOf(300, 300, 300), 500)
        assertEquals(listOf(0..0, 1..1, 2..2), pages)
        assertEquals(2, Paging.pageOf(pages, 2))
        assertEquals(0, Paging.pageOf(pages, 7))
        assertEquals("2 / 3", Paging.label(1, 3))
        // Landscape: two columns a page.
        assertEquals(2, Paging.screens(3, 2))
        assertEquals(1, Paging.screens(2, 2))
        assertEquals(1, Paging.screens(0, 2))
        assertEquals(3, Paging.screens(3, 1))
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
