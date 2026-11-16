package com.inkpalmplus

import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Home face (docs/mockups/aod/home-face/: home-sheet.png A Dashboard, B Calm): its styles and the pure parts behind
 * its cards (unit tested in HomeLogicTest): the weather (Open-Meteo's answer, read by [Json]; the rain summary, the
 * hourly strip, staleness), countdowns picked from the calendar, and trash day. [Weather] fetches and caches;
 * [Aod] collects; [AodFaces] draws.
 */
object Home {
    enum class Style(val title: String, val detail: String) {
        DASHBOARD("Dashboard", "Clock, your next 3 events, then weather, countdown and trash cards"),
        CALM("Calm", "A big clock, the weather in one line, your next event and a large countdown");

        companion object { fun of(s: String?): Style = entries.firstOrNull { it.name == s } ?: DASHBOARD }
    }

    /** Temperature units: [AUTO] follows the locale (°F in the US and a few others, else °C). */
    enum class Units(val title: String) {
        AUTO("Auto"), C("°C"), F("°F");

        companion object { fun of(s: String?): Units = entries.firstOrNull { it.name == s } ?: AUTO }
    }

    /** Countries that use °F. */
    private val FAHRENHEIT = setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH", "PR", "GU", "VI", "AS", "MP")

    fun fahrenheit(units: Units, locale: Locale = Locale.getDefault()): Boolean = when (units) {
        Units.C -> false
        Units.F -> true
        Units.AUTO -> locale.country.uppercase() in FAHRENHEIT
    }

    /** A Celsius temperature in the units shown, rounded: "9°". */
    fun degrees(c: Double, f: Boolean): Int = (if (f) c * 9 / 5 + 32 else c).roundToInt()

    // ---- Weather -----------------------------------------------------------------------------------

    /** The forecast as Open-Meteo gives it (Celsius; times in Unix seconds; [offset] the place's UTC offset, s). */
    class Forecast(
        val offset: Long,
        val currentTime: Long, val temp: Double, val code: Int,
        val hours: List<Long>, val precip: List<Int?>,
        val days: List<Long>, val max: List<Double?>, val min: List<Double?>, val dayCodes: List<Int?>,
        val sunrise: List<Long?> = emptyList(), val sunset: List<Long?> = emptyList(),
    ) {
        /** The place's local day number (days since 1970) of [t] (Unix s). */
        fun day(t: Long): Long = Math.floorDiv(t + offset, 86_400L)
        /** The place's local hour (0–23) of [t] (Unix s). */
        fun hour(t: Long): Int = (Math.floorMod(t + offset, 86_400L) / 3600).toInt()
        /** The index into [days] for [nowS]'s day, or -1. */
        fun today(nowS: Long): Int = days.indexOfFirst { day(it) == day(nowS) }
        /** Precipitation probability by local hour (0–23) on [nowS]'s day; null where unknown. */
        fun hourly(nowS: Long): Array<Int?> {
            val out = arrayOfNulls<Int>(24)
            val d = day(nowS)
            hours.forEachIndexed { i, t -> if (day(t) == d) out[hour(t)] = precip.getOrNull(i) }
            return out
        }
    }

    /** Reads Open-Meteo's /v1/forecast answer (with `timeformat=unixtime`); null when it isn't one. */
    fun parseForecast(json: String): Forecast? = runCatching {
        val o = Json.parse(json) as? Map<*, *> ?: return null
        val cur = o["current"] as? Map<*, *> ?: return null
        val hourly = o["hourly"] as? Map<*, *>
        val daily = o["daily"] as? Map<*, *>
        fun longs(m: Map<*, *>?, k: String) = (m?.get(k) as? List<*>).orEmpty().map { (it as? Number)?.toLong() }
        fun doubles(m: Map<*, *>?, k: String) = (m?.get(k) as? List<*>).orEmpty().map { (it as? Number)?.toDouble() }
        Forecast(
            offset = (o["utc_offset_seconds"] as? Number)?.toLong() ?: 0L,
            currentTime = (cur["time"] as? Number)?.toLong() ?: return null,
            temp = (cur["temperature_2m"] as? Number)?.toDouble() ?: return null,
            code = (cur["weather_code"] as? Number)?.toInt() ?: -1,
            hours = longs(hourly, "time").map { it ?: 0L },
            precip = longs(hourly, "precipitation_probability").map { it?.toInt() },
            days = longs(daily, "time").map { it ?: 0L },
            max = doubles(daily, "temperature_2m_max"), min = doubles(daily, "temperature_2m_min"),
            dayCodes = longs(daily, "weather_code").map { it?.toInt() },
            sunrise = longs(daily, "sunrise"), sunset = longs(daily, "sunset"),
        )
    }.getOrNull()

    /** A place from Open-Meteo's geocoding search. */
    data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

    /** Reads Open-Meteo's geocoding answer: "Lisbon" / "Lisbon, Portugal". Empty when nothing was found. */
    fun parsePlaces(json: String): List<Place> = runCatching {
        val o = Json.parse(json) as? Map<*, *> ?: return emptyList()
        (o["results"] as? List<*>).orEmpty().mapNotNull { r ->
            val m = r as? Map<*, *> ?: return@mapNotNull null
            val name = m["name"] as? String ?: return@mapNotNull null
            val lat = (m["latitude"] as? Number)?.toDouble() ?: return@mapNotNull null
            val lon = (m["longitude"] as? Number)?.toDouble() ?: return@mapNotNull null
            val detail = listOfNotNull(m["admin1"] as? String, m["country"] as? String).filter { it.isNotBlank() && it != name }.distinct().joinToString(", ")
            Place(name, detail, lat, lon)
        }
    }.getOrDefault(emptyList())

    // ---- "Use my location" ------------------------------------------------------------------------------

    /** A one-shot location is kept to ~0.01° (about 1 km): enough for the weather, no more. */
    fun roundCoord(v: Double): Double = Math.round(v * 100) / 100.0

    /** The geocoder's place name, else "Current location (38.72, -9.14)". */
    fun locationLabel(name: String?, lat: Double, lon: Double): String =
        name?.trim()?.takeIf { it.isNotEmpty() } ?: "Current location (%.2f, %.2f)".format(Locale.US, lat, lon)

    /** A last known fix is used only when it is this recent. */
    const val LAST_FIX_MAX_AGE_MS = 60 * 60_000L

    fun freshFix(ageMs: Long): Boolean = ageMs in 0..LAST_FIX_MAX_AGE_MS

    /** Why "Use my location" found nothing. */
    enum class LocateFail { NO_PERMISSION, NO_SERVICE, LOCATION_OFF, NO_PROVIDER, NO_FIX }

    /** What the weather page says when "Use my location" found nothing. [microg]: microG is the location provider
     *  (its Wi-Fi location is the only source on the InkPalm, which has no GPS). */
    fun locateMessage(fail: LocateFail, microg: Boolean, seconds: Long, microgPrecise: Boolean = true): String = when {
        // microG reads Wi-Fi scans only with precise location (Android's rule for scan results); with approximate it
        // scans and finds nothing (seen on the InkPalm: COARSE granted, FINE not).
        microg && !microgPrecise && (fail == LocateFail.NO_FIX || fail == LocateFail.NO_PROVIDER) -> MICROG_PRECISE
        else -> when (fail) {
        LocateFail.NO_PERMISSION -> "No location permission: search for your city instead."
        LocateFail.NO_SERVICE -> "No location service on this phone. Search for your city instead."
        LocateFail.LOCATION_OFF -> "Location is off in Android's settings."
        LocateFail.NO_PROVIDER -> if (microg) "No location provider is on. Turn on Wi\u2011Fi location in microG Settings \u2192 Location."
            else "No location provider is on."
        LocateFail.NO_FIX -> if (microg) "No location from microG. Turn on Wi\u2011Fi location in microG Settings \u2192 Location."
            else "No location found within $seconds s."
        }
    }

    /** microG Services itself has only approximate location: it can't read Wi-Fi networks. */
    const val MICROG_PRECISE = "No location from microG: microG Services has only approximate location, so it can't read nearby Wi\u2011Fi networks. In its app settings \u2192 Permissions \u2192 Location, choose \"Allow all the time\" and turn on \"Use precise location\". Wi\u2011Fi location must also be on in microG Settings \u2192 Location."

    /** With only approximate location granted, Android lets an app ask about once every 10 minutes, so microG
     *  postpones the Wi-Fi scan past the wait. */
    const val APPROXIMATE_NOTE = "InkPalm+ may use only your approximate location. Android limits how often approximate lookups run, so the answer can come too late. Precise location is used once and rounded to about 1 km all the same."

    /** The service "Estimate from internet connection" asks (HTTPS, no key, no account). */
    const val IP_SERVICE = "ipinfo.io"

    /** What "Estimate from internet connection" tells before it runs. */
    const val IP_NOTE = "This sends your phone's public IP address (the one every website sees) to $IP_SERVICE, which guesses the city your internet connection comes from. It's often the nearest big town. Nothing else is sent, and only now."

    /** ipinfo.io's answer ({"city":"Lisbon","region":"Lisbon","country":"PT","loc":"38.7167,-9.1333",…}) as a
     *  place rounded like a fix ([roundCoord]); null for an error, a private ("bogon") address or no coordinates. */
    fun parseIpPlace(json: String): Place? = runCatching {
        val o = Json.parse(json) as? Map<*, *> ?: return null
        if (o["error"] != null || o["bogon"] == true) return null
        val loc = (o["loc"] as? String)?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() } ?: return null
        if (loc.size != 2 || loc[0] !in -90.0..90.0 || loc[1] !in -180.0..180.0) return null
        val city = (o["city"] as? String)?.trim().orEmpty()
        val region = (o["region"] as? String)?.trim().orEmpty()
        val country = (o["country"] as? String)?.trim().orEmpty()
        val lat = roundCoord(loc[0]); val lon = roundCoord(loc[1])
        val name = city.ifEmpty { region }.ifEmpty { locationLabel(null, lat, lon) }
        Place(name, listOf(region, country).filter { it.isNotEmpty() && it != name }.distinct().joinToString(", "), lat, lon)
    }.getOrNull()

    /** The providers to ask for one fix, in order: network, fused, GPS (those that exist and are on). */
    fun providerOrder(available: Collection<String>): List<String> = listOf("network", "fused", "gps").filter { it in available }

    /** The probability (%) at which an hour counts as rainy. */
    const val RAINY = 50

    /** The first run of rainy hours ([RAINY] or more) today from [fromHour] on: start hour and end hour (exclusive,
     *  up to 24), or null. [ongoing]: it had already begun before [fromHour]. */
    data class Span(val start: Int, val end: Int, val ongoing: Boolean)

    fun rainSpan(hourly: Array<Int?>, fromHour: Int): Span? {
        val from = fromHour.coerceIn(0, 23)
        val first = (from until 24).firstOrNull { (hourly[it] ?: 0) >= RAINY } ?: return null
        var end = first
        while (end < 24 && (hourly[end] ?: 0) >= RAINY) end++
        return Span(first, end, first == from && from > 0 && (hourly[from - 1] ?: 0) >= RAINY)
    }

    /** "3 PM", "12 AM" (24: midnight) or "15". */
    fun hourLabel(h: Int, h24: Boolean): String {
        if (h24) return "%02d".format(Locale.US, h % 24).let { if (h == 24) "24" else it }
        val hh = h % 12
        return "${if (hh == 0) 12 else hh} ${if (h % 24 < 12) "AM" else "PM"}"
    }

    /** "3–6 PM", "11 AM–2 PM", "15–18". */
    fun hourRange(a: Int, b: Int, h24: Boolean): String {
        if (h24) return "${hourLabel(a, true)}–${hourLabel(b, true)}"
        val sameHalf = (a % 24 < 12) == (b % 24 < 12) && b != 24
        return if (sameHalf) "${hourLabel(a, false).substringBefore(' ')}–${hourLabel(b, false)}" else "${hourLabel(a, false)}–${hourLabel(b, false)}"
    }

    /** "Rain 3–6 PM", "Rain until 6 PM", "No rain today", "No more rain today"; null without hourly data. */
    fun rainSummary(hourly: Array<Int?>, nowHour: Int, h24: Boolean): String? {
        if (hourly.all { it == null }) return null
        val s = rainSpan(hourly, nowHour)
        if (s == null) return if (rainSpan(hourly, 0) != null && nowHour > 0) "No more rain today" else "No rain today"
        return if (s.ongoing) "Rain until ${hourLabel(s.end, h24)}" else "Rain ${hourRange(s.start, s.end, h24)}"
    }

    /** The hours of the Dashboard's strip (8 to 19). */
    val STRIP = 8..19

    /** A cached forecast is stale after this. */
    const val STALE_MS = 90 * 60_000L
    /** A forecast is fetched again after this. */
    const val REFRESH_MS = 30 * 60_000L

    /** Whether a fetch is due: [lastAttempt] (ms, 0: never) this long ago. Never more than once per [REFRESH_MS]. */
    fun due(now: Long, lastAttempt: Long): Boolean = lastAttempt <= 0 || now - lastAttempt >= REFRESH_MS || now < lastAttempt

    /** Show the forecast's age ("as of 6:40") when it is stale or the phone is offline. */
    fun showAge(now: Long, fetchedAt: Long, online: Boolean): Boolean = !online || now - fetchedAt > STALE_MS

    /** What the face shows for the weather. [message] instead of the rest when there's nothing to show. */
    class WeatherView(
        val temp: String, val hiLo: String?, val hiLoShort: String?, val rain: String?, val code: Int,
        /** Probability (%) per hour of [STRIP]; null when unknown. */
        val strip: List<Int?>?,
        /** "as of 6:40" when stale or offline. */
        val age: String?,
        val message: String? = null,
    ) {
        /** The Calm style's line: "9° · Rain 3–6 PM · H 14 L 6". */
        fun line(): String = message ?: listOfNotNull(temp, rain, hiLoShort).joinToString(" · ")
        val rainy: Boolean get() = code in RAIN_CODES || rain?.startsWith("Rain") == true

        companion object { fun message(s: String) = WeatherView("", null, null, null, -1, null, null, s) }
    }

    /** WMO weather codes with rain, drizzle, showers or thunder. */
    val RAIN_CODES = setOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99)
    val SNOW_CODES = setOf(71, 73, 75, 77, 85, 86)

    /**
     * The weather as shown at [now] (ms) from [f] fetched at [fetchedAt] (ms); [online]: the phone has a network now.
     * A forecast without today in it (days old) shows no temperature, only that it's old. [hm] formats a time.
     */
    fun weatherView(f: Forecast?, now: Long, fetchedAt: Long, online: Boolean, fahrenheit: Boolean, h24: Boolean, hm: (Long) -> String): WeatherView {
        if (f == null) return WeatherView.message(if (online) "Weather on the next update" else "No weather yet: offline")
        val nowS = now / 1000
        val i = f.today(nowS)
        if (i < 0) return WeatherView.message("No recent weather (offline)")
        val hourly = f.hourly(nowS)
        val hi = f.max.getOrNull(i); val lo = f.min.getOrNull(i)
        val hiLo = if (hi != null && lo != null) "H ${degrees(hi, fahrenheit)} · L ${degrees(lo, fahrenheit)}" else null
        val hiLoShort = if (hi != null && lo != null) "H ${degrees(hi, fahrenheit)} L ${degrees(lo, fahrenheit)}" else null
        // The temperature is the reading at the fetch; an old one is shown with its age.
        val strip = STRIP.map { hourly[it] }.takeIf { l -> l.any { it != null } }
        return WeatherView(
            temp = "${degrees(f.temp, fahrenheit)}°", hiLo = hiLo, hiLoShort = hiLoShort,
            rain = rainSummary(hourly, f.hour(nowS), h24),
            code = f.code,
            strip = strip,
            age = if (showAge(now, fetchedAt, online)) "as of ${hm(fetchedAt)}" else null,
        )
    }

    // ---- Countdowns ---------------------------------------------------------------------------------

    /** A countdown candidate: an event (its [key] persists pins and hides). */
    class Candidate(val key: String, val title: String, val begin: Long, val end: Long, val allDay: Boolean)

    fun keyOf(id: Long, title: String) = "$id|$title"

    /** Lasts at least this long to count without being all-day. */
    const val LONG_EVENT = 4 * Agenda.HOUR
    const val COUNTDOWN_DAYS = 60

    /** Whether an event counts as a countdown: all-day, multi-day or ≥ 4 h, starting today or later (by [today0], the
     *  local midnight) and within [COUNTDOWN_DAYS] days. Ordinary (short, timed) events never do. */
    fun isCountdown(c: Candidate, today0: Long, horizonEnd: Long): Boolean =
        c.begin >= today0 && c.begin < horizonEnd && (c.allDay || c.end - c.begin >= LONG_EVENT)

    /**
     * The countdowns to show, [n] at most: [pinned] first (by date), then the rest nearest first; [hidden] never.
     * Pinned ones count from [today0] on whatever the horizon ([horizonEnd] for the rest). Repeats (the same title
     * and day, e.g. one event in two calendars) are shown once.
     */
    fun countdowns(all: List<Candidate>, pinned: Set<String>, hidden: Set<String>, today0: Long, horizonEnd: Long, n: Int): List<Candidate> {
        val ok = all.filter { it.key !in hidden && it.begin >= today0 && (it.key in pinned || isCountdown(it, today0, horizonEnd)) }
            .distinctBy { it.title.lowercase() + "@" + it.begin / 60_000 }
        val (p, rest) = ok.partition { it.key in pinned }
        return (p.sortedBy { it.begin } + rest.sortedBy { it.begin }).distinctBy { it.key }.take(n)
    }

    /** Whole local days from [today0]'s day to [t]'s (DST-safe). */
    fun daysUntil(today0: Long, t: Long, cal: Calendar = Calendar.getInstance()): Int {
        val a = Agenda.midnight(today0, 0, cal); val b = Agenda.midnight(t, 0, cal)
        return Math.round((b - a) / Agenda.DAY.toDouble()).toInt()
    }

    /** "12" over "days to Lisbon trip"; "Tomorrow: Lisbon trip" and "Today: Lisbon trip" have no number. [date]: the
     *  event's start. */
    data class CountdownText(val number: String?, val text: String, val date: Long = 0L) {
        override fun toString() = if (number != null) "$number $text" else text
    }

    fun countdownText(title: String, days: Int, date: Long = 0L): CountdownText = when {
        days <= 0 -> CountdownText(null, "Today: $title", date)
        days == 1 -> CountdownText(null, "Tomorrow: $title", date)
        else -> CountdownText("$days", "${if (days == 1) "day" else "days"} to $title", date)
    }

    // ---- Trash day ----------------------------------------------------------------------------------

    /** What the trash card says: "Recycling tomorrow", "Trash today", "Trash & recycling today"; [today]: it's today. */
    data class TrashDay(val text: String, val today: Boolean, val day: Long)

    /**
     * [recycling] and [trash] are weekdays ([Calendar.DAY_OF_WEEK], 1 Sunday … 7 Saturday). Today's pickup is shown
     * all day; else tomorrow's; else null (no card).
     */
    fun trashDay(recycling: Set<Int>, trash: Set<Int>, now: Long, cal: Calendar = Calendar.getInstance()): TrashDay? {
        fun on(days: Int): String? {
            val t = Agenda.midnight(now, days, cal)
            cal.timeInMillis = t
            val wd = cal.get(Calendar.DAY_OF_WEEK)
            val r = wd in recycling; val g = wd in trash
            return when {
                r && g -> "Trash & recycling"
                r -> "Recycling"
                g -> "Trash"
                else -> null
            }
        }
        on(0)?.let { return TrashDay("$it today", true, Agenda.midnight(now, 0, cal)) }
        on(1)?.let { return TrashDay("$it tomorrow", false, Agenda.midnight(now, 1, cal)) }
        return null
    }

    /** Weekdays as stored ("2,6") and back. */
    fun daysOf(s: String?): Set<Int> = s.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull()?.takeIf { d -> d in 1..7 } }.toSet()
    fun daysString(days: Set<Int>): String = days.sorted().joinToString(",")

    // ---- JSON ---------------------------------------------------------------------------------------

    /** A small JSON reader (objects → Map, arrays → List, numbers → Double/Long, null), enough for Open-Meteo's
     *  answers; Android's org.json isn't usable in plain unit tests. Throws on malformed input. */
    object Json {
        fun parse(s: String): Any? { val p = P(s); val v = p.value(); p.ws(); require(p.i == s.length) { "trailing data" }; return v }

        private class P(val s: String) {
            var i = 0
            fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
            fun value(): Any? {
                ws()
                require(i < s.length) { "end of input" }
                return when (val ch = s[i]) {
                    '{' -> obj()
                    '[' -> arr()
                    '"' -> str()
                    't' -> lit("true", true)
                    'f' -> lit("false", false)
                    'n' -> lit("null", null)
                    else -> if (ch == '-' || ch.isDigit()) num() else throw IllegalArgumentException("unexpected '$ch' at $i")
                }
            }
            fun lit(w: String, v: Any?): Any? { require(s.startsWith(w, i)) { "bad literal at $i" }; i += w.length; return v }
            fun obj(): Map<String, Any?> {
                val m = LinkedHashMap<String, Any?>()
                i++; ws()
                if (s[i] == '}') { i++; return m }
                while (true) {
                    ws(); val k = str(); ws()
                    require(s[i] == ':') { "':' expected at $i" }; i++
                    m[k] = value(); ws()
                    when (s[i]) { ',' -> i++; '}' -> { i++; return m }; else -> throw IllegalArgumentException("',' or '}' expected at $i") }
                }
            }
            fun arr(): List<Any?> {
                val l = ArrayList<Any?>()
                i++; ws()
                if (s[i] == ']') { i++; return l }
                while (true) {
                    l += value(); ws()
                    when (s[i]) { ',' -> i++; ']' -> { i++; return l }; else -> throw IllegalArgumentException("',' or ']' expected at $i") }
                }
            }
            fun str(): String {
                require(s[i] == '"') { "string expected at $i" }
                i++
                val b = StringBuilder()
                while (true) {
                    val ch = s[i++]
                    when (ch) {
                        '"' -> return b.toString()
                        '\\' -> when (val e = s[i++]) {
                            'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                            'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> b.append(e)
                        }
                        else -> b.append(ch)
                    }
                }
            }
            fun num(): Number {
                val start = i
                if (s[i] == '-') i++
                while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
                val t = s.substring(start, i)
                return if (t.any { it in ".eE" }) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
            }
        }
    }
}
