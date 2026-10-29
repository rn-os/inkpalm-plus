package com.inkpalmplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** The pure parts of the Agenda face's styles ([Agenda]). */
class AgendaLogicTest {
    private val h = Agenda.HOUR
    /** Day 0 starts at 0; times in hours from there. */
    private fun ev(title: String, from: Double, to: Double, allDay: Boolean = false) =
        AodFaces.Event(title, (from * h).toLong(), (to * h).toLong(), allDay, null)

    @Test fun upcomingSkipsWhatsOverAndKeepsAWeek() {
        val list = listOf(ev("past", 8.0, 9.0), ev("now", 10.0, 11.0), ev("later", 14.0, 15.0), ev("all day", 0.0, 24.0, true),
            ev("next week", 24 * 8.0, 24 * 8 + 1.0), ev("tomorrow", 33.0, 34.0))
        assertEquals(listOf("all day", "now", "later"), Agenda.upcoming(list, (10.5 * h).toLong()).map { it.title })
        assertEquals(listOf("all day", "now", "later", "tomorrow"), Agenda.upcoming(list, (10.5 * h).toLong(), n = 10).map { it.title })
        // All-day first on a tie.
        assertEquals("all day", Agenda.upcoming(listOf(ev("timed", 0.0, 1.0), ev("all day", 0.0, 24.0, true)), 0).first().title)
    }

    @Test fun eventsOnADay() {
        val list = listOf(ev("yesterday", -3.0, -2.0), ev("overnight", 22.0 - 24, 2.0), ev("morning", 9.0, 10.0),
            ev("all day", 0.0, 24.0, true), ev("ends at midnight", 23.0, 24.0), ev("tomorrow", 24.0, 25.0), ev("zero", 12.0, 12.0))
        assertEquals(listOf("all day", "overnight", "morning", "zero", "ends at midnight"), Agenda.onDay(list, 0, 24 * h).map { it.title })
        // With now: only those not over yet (all-day still on).
        assertEquals(listOf("all day", "ends at midnight"), Agenda.onDay(list, 0, 24 * h, now = (12.5 * h).toLong()).map { it.title })
        // Per day: a multi-day all-day event counts on each of its days.
        val trip = listOf(ev("trip", 24.0, 72.0, true), ev("call", 30.0, 31.0))
        assertEquals(listOf(0, 2, 1, 0), Agenda.perDay(trip, listOf(0L, 24 * h, 48 * h, 72 * h, 96 * h)))
    }

    @Test fun weekStartsOnTheLocalesFirstDay() {
        val tz = TimeZone.getTimeZone("Europe/Berlin")
        fun cal(locale: Locale) = Calendar.getInstance(tz, locale)
        val now = cal(Locale.UK).apply { clear(); set(2026, Calendar.OCTOBER, 29, 10, 34) }.timeInMillis   // a Thursday
        val uk = Agenda.weekBounds(now, cal(Locale.UK))
        val us = Agenda.weekBounds(now, cal(Locale.US))
        assertEquals(8, uk.size)
        assertEquals(Calendar.MONDAY, cal(Locale.UK).apply { timeInMillis = uk[0] }.get(Calendar.DAY_OF_WEEK))
        assertEquals(26, cal(Locale.UK).apply { timeInMillis = uk[0] }.get(Calendar.DAY_OF_MONTH))
        assertEquals(25, cal(Locale.US).apply { timeInMillis = us[0] }.get(Calendar.DAY_OF_MONTH))
        // The week holds the DST change (25 Oct in Berlin): days are local midnights, one 25 h long.
        assertEquals(25 * h, us[1] - us[0])
        assertTrue(uk.zipWithNext().all { (a, b) -> b - a == 24 * h })
        assertEquals(cal(Locale.UK).apply { clear(); set(2026, Calendar.OCTOBER, 30) }.timeInMillis, Agenda.midnight(now, 1, cal(Locale.UK)))
    }

    @Test fun durations() {
        assertEquals("30 min", Agenda.duration(30 * 60_000L))
        assertEquals("1 h", Agenda.duration(h))
        assertEquals("1 h 30", Agenda.duration(90 * 60_000L))
        assertEquals("2 h 05", Agenda.duration(125 * 60_000L))
        assertEquals("1 d", Agenda.duration(24 * h))
        assertEquals("0 min", Agenda.duration(-5))
        assertEquals("in 56 min", Agenda.until(56 * 60_000L, 0))
        assertEquals("in 1 min", Agenda.until(1_000, 0))
        assertEquals("now", Agenda.until(0, 10))
    }

    @Test fun railHoursFollowTheDay() {
        // Nothing on: 8 to 19 (now inside).
        assertEquals(8 to 19, Agenda.railHours(emptyList(), 10.5f))
        // An early event and a late one widen it.
        assertEquals(6 to 23, Agenda.railHours(listOf(6.5f to 7f, 21f to 22.25f), 10f))
        // Now outside: taken in, with the hour after it.
        assertEquals(8 to 23, Agenda.railHours(emptyList(), 22f))
        assertEquals(0 to 19, Agenda.railHours(emptyList(), 0.2f))
        // Until midnight at most; a short day is padded to 11 hours.
        assertEquals(13 to 24, Agenda.railHours(emptyList(), 23.5f, from = 20, to = 21))
        assertEquals(8 to 24, Agenda.railHours(listOf(23f to 24f), 10f))
    }

    @Test fun hourLabelsDontTouch() {
        assertEquals(1, Agenda.hourStep(80f, 35f))
        assertEquals(2, Agenda.hourStep(30f, 35f))
        assertEquals(3, Agenda.hourStep(12f, 35f))
    }

    @Test fun labelsAreStackedWithoutOverlap() {
        // Apart already: kept where they want to be.
        assertEquals(listOf(0f, 100f, 200f), Agenda.stack(listOf(0f, 100f, 200f), 44f, 0f, 0f, 900f))
        // Crowded: pushed down one under the other.
        assertEquals(listOf(100f, 144f, 188f), Agenda.stack(listOf(100f, 110f, 120f), 44f, 0f, 0f, 900f))
        // Near the bottom: pushed back up so the last still fits.
        assertEquals(listOf(768f, 812f, 856f), Agenda.stack(listOf(850f, 860f, 870f), 44f, 0f, 0f, 900f))
        // Above the top: brought in.
        assertEquals(listOf(10f), Agenda.stack(listOf(-20f), 44f, 0f, 10f, 900f))
        // Too many: null.
        assertNull(Agenda.stack(List(30) { it * 10f }, 44f, 0f, 0f, 900f))
        for (tops in listOf(Agenda.stack(listOf(0f, 1f, 2f, 500f, 501f, 880f), 44f, 4f, 0f, 900f)!!))
            tops.zipWithNext().forEach { (a, b) -> assertTrue("$a $b", b - a >= 48f - 0.01f) }
        assertEquals(20, Agenda.fitting(44f, 0f, 0f, 900f))
        assertEquals(0, Agenda.fitting(44f, 0f, 0f, 40f))
        assertEquals(0 until 5, Agenda.window(5, 10, 3))
        assertEquals(3 until 8, Agenda.window(10, 5, 3))
        assertEquals(5 until 10, Agenda.window(10, 5, 8))
    }

    @Test fun theLongestFreeStretchAfterNow() {
        val spans = listOf(9 * h to 10 * h, 11 * h + h / 2 to 12 * h, 14 * h to 14 * h + h / 2, 16 * h + h / 2 to 17 * h + h / 2)
        // 12:00–14:00 and 14:30–16:30 are both 2 h: the later one.
        assertEquals(14 * h + h / 2 to 16 * h + h / 2, Agenda.freeGap(spans, 10 * h + h / 2))
        // From now when it's inside a gap: 15:00–16:30 is 1.5 h.
        assertEquals(15 * h to 16 * h + h / 2, Agenda.freeGap(spans, 15 * h))
        // Overlapping events leave no gap between them; under an hour doesn't count.
        assertNull(Agenda.freeGap(listOf(9 * h to 11 * h, 10 * h to 11 * h + h / 2, 12 * h to 13 * h), 0))
        assertNull(Agenda.freeGap(emptyList(), 0))
    }

    @Test fun twoListsShareTheRows() {
        assertEquals(3 to 3, Agenda.share(3, 3, 8))
        assertEquals(3 to 3, Agenda.share(3, 3, 6))
        assertEquals(2 to 3, Agenda.share(9, 3, 5))
        assertEquals(1 to 4, Agenda.share(1, 9, 5))
        assertEquals(3 to 2, Agenda.share(9, 9, 5))
        assertEquals(0 to 0, Agenda.share(4, 4, 0))
        // A cut list keeps room for its "+2 more".
        assertEquals(3, Agenda.rowsShown(3, 100f, 0f, 300f, 40f))
        assertEquals(2, Agenda.rowsShown(4, 100f, 0f, 300f, 40f))
        assertEquals(0, Agenda.rowsShown(4, 100f, 0f, 120f, 40f))
    }

    @Test fun stylesHaveShortNamesAndClassicIsTheDefault() {
        assertEquals(Agenda.Style.CLASSIC, Agenda.Style.of(null))
        assertEquals(Agenda.Style.CLASSIC, Agenda.Style.of("GONE"))
        assertEquals(Agenda.Style.WEEK, Agenda.Style.of("WEEK"))
        assertEquals(6, Agenda.Style.entries.size)
        assertTrue(Agenda.Style.entries.all { it.title.length <= 10 })
    }
}
