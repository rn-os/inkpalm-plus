package com.inkpalmplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sleep-screen picker's pages ([FacePicker]): order, the page in use, what "Use this" sets, setup lines. */
class FacePickerTest {
    private val list = FacePicker.entries()
    private fun now(face: Aod.Face, agenda: Agenda.Style = Agenda.Style.CLASSIC, home: Home.Style = Home.Style.DASHBOARD, photo: Dither.Style = Dither.Style.CRISP) =
        FacePicker.Current(face, agenda, home, photo)

    @Test fun pagesInTheApprovedOrder() {
        assertEquals(listOf("Clock", "Clock + date", "Analog", "Home · Dashboard", "Home · Calm",
            "Agenda · Classic", "Agenda · Big clock", "Agenda · Day rail", "Agenda · Next up", "Agenda · Two days", "Agenda · Week",
            "Reading", "Photo · Crisp", "Photo · Smooth", "Photo · Plain", "Picture", "Minimal", "Default"), list.map { it.title })
    }

    @Test fun everyFaceAndStyleHasAPage() {
        val styled = setOf(Aod.Face.AGENDA, Aod.Face.HOME, Aod.Face.PHOTO)
        for (f in Aod.Face.entries) if (f !in styled) assertEquals(1, list.count { it.face == f })
        assertEquals(Agenda.Style.entries, list.mapNotNull { it.agenda })
        assertEquals(Home.Style.entries, list.mapNotNull { it.home })
        assertEquals(Dither.Style.entries, list.mapNotNull { it.photo })
        assertEquals(list.size, list.map { it.key }.toSet().size)
        // A face's pages sit together.
        assertEquals(list.map { it.face }.distinct(), Aod.Face.entries)
    }

    @Test fun opensOnThePageInUse() {
        assertEquals("Agenda · Week", list[FacePicker.indexOf(list, now(Aod.Face.AGENDA, agenda = Agenda.Style.WEEK))].title)
        assertEquals("Home · Calm", list[FacePicker.indexOf(list, now(Aod.Face.HOME, home = Home.Style.CALM, agenda = Agenda.Style.WEEK))].title)
        assertEquals("Photo · Smooth", list[FacePicker.indexOf(list, now(Aod.Face.PHOTO, photo = Dither.Style.SMOOTH))].title)
        // Picture is one page whatever the Photo style.
        assertEquals("Picture", list[FacePicker.indexOf(list, now(Aod.Face.PICTURE, photo = Dither.Style.PLAIN))].title)
        assertEquals("Default", list[FacePicker.indexOf(list, now(Aod.Face.DEFAULT))].title)
        assertEquals("Clock", list[FacePicker.indexOf(list, now(Aod.Face.CLOCK, agenda = Agenda.Style.WEEK))].title)
    }

    @Test fun inUseOnlyForTheFaceAndItsStyle() {
        val week = now(Aod.Face.AGENDA, agenda = Agenda.Style.WEEK)
        assertEquals(listOf("Agenda · Week"), list.filter { FacePicker.inUse(it, week) }.map { it.title })
        val smooth = now(Aod.Face.PHOTO, photo = Dither.Style.SMOOTH)
        assertEquals(listOf("Photo · Smooth"), list.filter { FacePicker.inUse(it, smooth) }.map { it.title })
        assertEquals(listOf("Picture"), list.filter { FacePicker.inUse(it, now(Aod.Face.PICTURE, photo = Dither.Style.SMOOTH)) }.map { it.title })
    }

    @Test fun usingAPageSetsItsFaceAndStyle() {
        val crisp = list.first { it.title == "Photo · Plain" }
        assertEquals(Aod.Face.PHOTO, crisp.face); assertEquals(Dither.Style.PLAIN, crisp.photo); assertNull(crisp.agenda); assertNull(crisp.home)
        val week = list.first { it.title == "Agenda · Week" }
        assertEquals(Aod.Face.AGENDA, week.face); assertEquals(Agenda.Style.WEEK, week.agenda); assertNull(week.photo)
        val picture = list.first { it.face == Aod.Face.PICTURE }
        assertNull(picture.photo)   // keeps the Photo style it has
        // After "Use this", that page (and only it) is in use.
        for (e in list) {
            val after = FacePicker.Current(e.face, e.agenda ?: Agenda.Style.CLASSIC, e.home ?: Home.Style.DASHBOARD, e.photo ?: Dither.Style.SMOOTH)
            assertTrue(e.title, FacePicker.inUse(e, after))
            assertEquals(e.title, 1, list.count { FacePicker.inUse(it, after) })
        }
    }

    @Test fun allGridGroups() {
        val g = FacePicker.groups(list)
        assertEquals(listOf("Clock", "Home", "Agenda", "Photo", "Other"), g.map { it.first.title })
        assertEquals(listOf("Crisp", "Smooth", "Plain", "Picture"), g.first { it.first == Aod.Group.PHOTO }.second.map { it.value.label })
        assertEquals(listOf("Reading", "Minimal", "Default"), g.first { it.first == Aod.Group.OTHER }.second.map { it.value.label })
        // Indexes point back at the pages.
        for ((_, items) in g) for ((i, e) in items) assertEquals(list[i], e)
        assertEquals(list.size, g.sumOf { it.second.size })
    }

    @Test fun setupLines() {
        val ready = FacePicker.State(calendar = true, city = "Lisbon", picture = true, reader = "NeoReader")
        val none = FacePicker.State(calendar = false, city = null, picture = false, reader = null)
        fun e(t: String) = list.first { it.title == t }
        assertEquals(FacePicker.Need("Uses your calendar"), FacePicker.need(e("Agenda · Week"), ready))
        assertEquals(FacePicker.Setup.CALENDAR, FacePicker.need(e("Agenda · Week"), none).setup)
        assertEquals(FacePicker.Setup.CALENDAR, FacePicker.need(e("Home · Calm"), none).setup)
        assertEquals(FacePicker.Need("Weather: set location", FacePicker.Setup.WEATHER), FacePicker.need(e("Home · Dashboard"), ready.copy(city = null)))
        assertNull(FacePicker.need(e("Home · Dashboard"), ready).setup)
        assertEquals(FacePicker.Setup.PHOTO, FacePicker.need(e("Photo · Crisp"), none).setup)
        assertEquals(FacePicker.Setup.PHOTO, FacePicker.need(e("Picture"), none).setup)
        assertEquals(FacePicker.Need("Shows your current book"), FacePicker.need(e("Reading"), ready))
        assertEquals(FacePicker.Setup.READER, FacePicker.need(e("Reading"), none).setup)
        for (t in listOf("Clock", "Clock + date", "Analog", "Minimal", "Default")) assertNull(t, FacePicker.need(e(t), none).setup)
    }

    @Test fun detailLines() {
        val cur = now(Aod.Face.CLOCK, photo = Dither.Style.SMOOTH)
        assertEquals(Agenda.Style.WEEK.detail, FacePicker.detail(list.first { it.agenda == Agenda.Style.WEEK }, cur))
        assertTrue(FacePicker.detail(list.first { it.face == Aod.Face.PICTURE }, cur).endsWith("(Smooth)"))
        assertFalse(list.any { FacePicker.detail(it, cur).isBlank() })
    }
}
