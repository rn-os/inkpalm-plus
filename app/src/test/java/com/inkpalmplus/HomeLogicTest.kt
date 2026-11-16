package com.inkpalmplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** The pure parts of the Home face ([Home]): weather parsing and summary, countdowns, trash day. */
class HomeLogicTest {
    private val h = Agenda.HOUR

    // ---- JSON and Open-Meteo ----------------------------------------------------------------------------

    @Test fun jsonReadsWhatOpenMeteoSends() {
        val v = Home.Json.parse("""{"a": [1, -2.5, 3e2, null, true], "b": {"c": "x\"yé\n"}, "d": []}""") as Map<*, *>
        assertEquals(listOf(1L, -2.5, 300.0, null, true), v["a"])
        assertEquals("x\"yé\n", (v["b"] as Map<*, *>)["c"])
        assertEquals(emptyList<Any>(), v["d"])
        assertNull(Home.parseForecast("not json"))
        assertNull(Home.parseForecast("""{"current": {}}"""))
        assertTrue(runCatching { Home.Json.parse("""{"a": 1""") }.isFailure)
    }

    /** A trimmed Open-Meteo answer (timeformat=unixtime, timezone=auto) for Lisbon, UTC+0, Monday 16 Nov 2026. */
    private val day0 = 1_794_787_200L   // 2026-11-16T00:00Z
    private fun forecastJson(probs: List<Int?>, offset: Long = 0) = """
        {"latitude":38.72,"longitude":-9.13,"generationtime_ms":0.1,"utc_offset_seconds":$offset,"timezone":"Europe/Lisbon",
         "current_units":{"time":"unixtime","temperature_2m":"°C"},
         "current":{"time":${day0 + 7 * 3600 + 45 * 60},"interval":900,"temperature_2m":9.3,"weather_code":61},
         "hourly":{"time":[${(0 until 48).joinToString(",") { "${day0 - offset + it * 3600}" }}],
                   "precipitation_probability":[${(0 until 48).joinToString(",") { probs.getOrNull(it)?.toString() ?: "null" }}]},
         "daily":{"time":[${day0 - offset},${day0 - offset + 86400}],"weather_code":[61,3],"temperature_2m_max":[14.2,15.0],
                  "temperature_2m_min":[5.8,7.1],"sunrise":[${day0 + 26000},${day0 + 112000}],"sunset":[${day0 + 62000},${day0 + 148000}]}}
    """.trimIndent()

    private val rainy = listOf(0, 0, 0, 0, 0, 0, 5, 5, 5, 5, 5, 5, 5, 10, 20, 60, 85, 85, 30, 20, 10, 5, 0, 0)

    @Test fun forecastIsParsed() {
        val f = Home.parseForecast(forecastJson(rainy))!!
        assertEquals(9.3, f.temp, 0.0)
        assertEquals(61, f.code)
        assertEquals(48, f.hours.size)
        assertEquals(0, f.today(day0 + 10 * 3600))
        assertEquals(1, f.today(day0 + 30 * 3600))
        assertEquals(-1, f.today(day0 + 50 * 3600))
        val hourly = f.hourly(day0 + 10 * 3600)
        assertEquals(60, hourly[15]); assertEquals(0, hourly[23])
        assertEquals(14.2, f.max[0]!!, 0.0)
        // The place's own offset decides the local hour.
        val g = Home.parseForecast(forecastJson(rainy, offset = 3600))!!
        assertEquals(15, g.hour(day0 - 3600 + 15 * 3600))
        assertEquals(60, g.hourly(day0 + 10 * 3600)[15])
    }

    @Test fun placesAreParsed() {
        val json = """{"results":[{"id":2267057,"name":"Lisbon","latitude":38.71667,"longitude":-9.13333,"country":"Portugal","admin1":"Lisbon"},
            {"id":1,"name":"Lisbon","latitude":44.03,"longitude":-70.1,"country":"United States","admin1":"Maine"},
            {"id":2,"name":"Broken"}],"generationtime_ms":0.5}"""
        val p = Home.parsePlaces(json)
        assertEquals(2, p.size)
        assertEquals(Home.Place("Lisbon", "Portugal", 38.71667, -9.13333), p[0])
        assertEquals("Maine, United States", p[1].detail)
        assertEquals(emptyList<Home.Place>(), Home.parsePlaces("""{"generationtime_ms":0.5}"""))
        assertEquals(emptyList<Home.Place>(), Home.parsePlaces("<html>"))
    }

    // ---- Rain summary ---------------------------------------------------------------------------------

    private fun hours(vararg rain: IntRange): Array<Int?> = Array(24) { hr -> if (rain.any { hr in it }) 70 else 10 }

    @Test fun rainSpans() {
        assertEquals(Home.Span(15, 18, false), Home.rainSpan(hours(15..17), 7))
        // The first span from now on; an earlier one is past.
        assertEquals(Home.Span(15, 18, false), Home.rainSpan(hours(8..9, 15..17), 11))
        assertEquals(Home.Span(8, 10, false), Home.rainSpan(hours(8..9, 15..17), 7))
        // Raining already: ongoing.
        assertEquals(Home.Span(16, 18, true), Home.rainSpan(hours(15..17), 16))
        // To the end of the day.
        assertEquals(Home.Span(21, 24, false), Home.rainSpan(hours(21..23), 7))
        assertNull(Home.rainSpan(hours(), 7))
        // Exactly the threshold counts; nulls don't.
        val edge = arrayOfNulls<Int>(24).also { it[12] = 50; it[13] = 49 }
        assertEquals(Home.Span(12, 13, false), Home.rainSpan(edge, 0))
    }

    @Test fun rainSummaries() {
        assertEquals("Rain 3–6 PM", Home.rainSummary(hours(15..17), 7, false))
        assertEquals("Rain 15–18", Home.rainSummary(hours(15..17), 7, true))
        assertEquals("Rain 11 AM–2 PM", Home.rainSummary(hours(11..13), 7, false))
        assertEquals("Rain until 6 PM", Home.rainSummary(hours(15..17), 16, false))
        assertEquals("Rain 9 PM–12 AM", Home.rainSummary(hours(21..23), 7, false))
        assertEquals("No rain today", Home.rainSummary(hours(), 7, false))
        assertEquals("No more rain today", Home.rainSummary(hours(8..9), 12, false))
        assertNull(Home.rainSummary(arrayOfNulls(24), 7, false))
        assertEquals("12 PM", Home.hourLabel(12, false)); assertEquals("12 AM", Home.hourLabel(0, false)); assertEquals("09", Home.hourLabel(9, true))
    }

    // ---- Units, staleness, the view -------------------------------------------------------------------

    @Test fun unitsFollowTheLocale() {
        assertTrue(Home.fahrenheit(Home.Units.AUTO, Locale.US))
        assertFalse(Home.fahrenheit(Home.Units.AUTO, Locale.UK))
        assertFalse(Home.fahrenheit(Home.Units.AUTO, Locale.GERMANY))
        assertTrue(Home.fahrenheit(Home.Units.F, Locale.UK))
        assertFalse(Home.fahrenheit(Home.Units.C, Locale.US))
        assertEquals(9, Home.degrees(9.3, false)); assertEquals(49, Home.degrees(9.3, true)); assertEquals(-3, Home.degrees(-2.6, false))
    }

    @Test fun fetchesAreThrottledAndOldDataShowsItsAge() {
        val m = 60_000L
        assertTrue(Home.due(1_000_000, 0))
        assertFalse(Home.due(100 * m + 29 * m, 100 * m))
        assertTrue(Home.due(100 * m + 30 * m, 100 * m))
        assertTrue(Home.due(50 * m, 100 * m))   // the clock went back
        assertFalse(Home.showAge(200 * m, 200 * m - 90 * m, true))
        assertTrue(Home.showAge(200 * m, 200 * m - 91 * m, true))
        assertTrue(Home.showAge(200 * m, 200 * m - 5 * m, false))
    }

    @Test fun weatherView() {
        val f = Home.parseForecast(forecastJson(rainy))!!
        val now = (day0 + 7 * 3600 + 50 * 60) * 1000
        val v = Home.weatherView(f, now, now - 10 * 60_000, true, false, false) { "6:40" }
        assertEquals("9°", v.temp)
        assertEquals("H 14 · L 6", v.hiLo)
        assertEquals("9° · Rain 3–6 PM · H 14 L 6", v.line())
        assertNull(v.age)
        assertEquals(12, v.strip!!.size); assertEquals(60, v.strip!![15 - 8])
        assertTrue(v.rainy)
        // Offline: the age shows; °F converts.
        val off = Home.weatherView(f, now, now - 10 * 60_000, false, true, false) { "6:40" }
        assertEquals("as of 6:40", off.age); assertEquals("49°", off.temp); assertEquals("H 58 · L 42", off.hiLo)
        // Two days later the forecast has no today: nothing but that it's old.
        assertNotNull(Home.weatherView(f, now + 3 * Agenda.DAY, now, false, false, false) { "" }.message)
        assertNotNull(Home.weatherView(null, now, 0, false, false, false) { "" }.message)
    }

    // ---- Countdowns ------------------------------------------------------------------------------------

    private fun cand(key: String, fromH: Double, toH: Double, allDay: Boolean = false, title: String = key) =
        Home.Candidate(key, title, (fromH * h).toLong(), (toH * h).toLong(), allDay)

    @Test fun countdownsPickLongAndAllDayEventsNearestFirst() {
        val today0 = 0L; val horizon = 60 * Agenda.DAY
        val all = listOf(
            cand("standup", 9.0, 9.5),                          // ordinary: never
            cand("trip", 24 * 12.0, 24 * 15.0, true),            // in 12 days, multi-day
            cand("offsite", 24 * 3 + 9.0, 24 * 3 + 17.0),        // 8 h: counts
            cand("birthday", 24 * 1.0, 24 * 2.0, true),          // tomorrow
            cand("far", 24 * 70.0, 24 * 71.0, true),             // beyond 60 days
            cand("today", 0.0, 24.0, true),                      // today's all-day
        )
        assertEquals(listOf("today", "birthday"), Home.countdowns(all, emptySet(), emptySet(), today0, horizon, 2).map { it.key })
        assertEquals(listOf("today", "birthday", "offsite", "trip"), Home.countdowns(all, emptySet(), emptySet(), today0, horizon, 9).map { it.key })
        // Pinned first (even beyond the horizon); hidden never.
        assertEquals(listOf("trip", "far", "today"), Home.countdowns(all, setOf("far", "trip"), setOf("birthday"), today0, horizon, 3).map { it.key })
        // The same event in two calendars shows once.
        val dup = listOf(cand("a", 24.0, 48.0, true, "Trip"), cand("b", 24.0, 48.0, true, "trip"))
        assertEquals(1, Home.countdowns(dup, emptySet(), emptySet(), today0, horizon, 2).size)
        // Started before today: not a countdown.
        assertTrue(Home.countdowns(listOf(cand("old", -24.0, 48.0, true)), emptySet(), emptySet(), today0, horizon, 2).isEmpty())
        assertFalse(Home.isCountdown(cand("3h", 24 * 2.0, 24 * 2 + 3.0), today0, horizon))
        assertTrue(Home.isCountdown(cand("4h", 24 * 2.0, 24 * 2 + 4.0), today0, horizon))
    }

    @Test fun countdownTexts() {
        assertEquals("12 days to Lisbon trip", Home.countdownText("Lisbon trip", 12).toString())
        assertEquals("12", Home.countdownText("Lisbon trip", 12).number)
        assertEquals("Tomorrow: Lisbon trip", Home.countdownText("Lisbon trip", 1).toString())
        assertNull(Home.countdownText("Lisbon trip", 1).number)
        assertEquals("Today: Lisbon trip", Home.countdownText("Lisbon trip", 0).toString())
        // Days count local midnights across a DST change.
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Lisbon"), Locale.UK)
        fun at(m: Int, d: Int, hr: Int) = cal.apply { clear(); set(2026, m, d, hr, 0) }.timeInMillis
        assertEquals(2, Home.daysUntil(at(Calendar.OCTOBER, 24, 23), at(Calendar.OCTOBER, 26, 1), cal))
        assertEquals(1, Home.daysUntil(at(Calendar.NOVEMBER, 16, 23), at(Calendar.NOVEMBER, 17, 0), cal))
    }

    // ---- Trash day -------------------------------------------------------------------------------------

    @Test fun trashDay() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Lisbon"), Locale.UK)
        val monday = cal.apply { clear(); set(2026, Calendar.NOVEMBER, 16, 7, 42) }.timeInMillis
        val tue = setOf(Calendar.TUESDAY); val fri = setOf(Calendar.FRIDAY)
        assertEquals("Recycling tomorrow", Home.trashDay(tue, fri, monday, cal)?.text)
        assertFalse(Home.trashDay(tue, fri, monday, cal)!!.today)
        val tuesday = monday + Agenda.DAY
        assertEquals(Home.TrashDay("Recycling today", true, Agenda.midnight(tuesday, 0, cal)), Home.trashDay(tue, fri, tuesday, cal))
        assertNull(Home.trashDay(tue, fri, monday + 2 * Agenda.DAY, cal))   // Wednesday
        assertEquals("Trash tomorrow", Home.trashDay(tue, fri, monday + 3 * Agenda.DAY, cal)?.text)
        assertEquals("Trash & recycling today", Home.trashDay(fri, fri, monday + 4 * Agenda.DAY, cal)?.text)
        assertNull(Home.trashDay(emptySet(), emptySet(), monday, cal))
        assertEquals(setOf(2, 6), Home.daysOf("2,6,9,x")); assertEquals("2,6", Home.daysString(setOf(6, 2))); assertEquals(emptySet<Int>(), Home.daysOf(null))
    }
}
