package com.inkpalmplus

import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The Agenda face's styles (docs/mockups/aod/agenda-variants/: sheet.png and one HTML board per style) and the pure
 * parts of their layouts (unit tested). [AodFaces] draws them; [Aod] keeps the pick and reads the calendar.
 */
object Agenda {
    /** Classic is the face as first shipped; A–E are the variants of the mockup sheet. */
    enum class Style(val title: String, val detail: String) {
        CLASSIC("Classic", "The time, then your next 3 events in boxes"),
        BIG_CLOCK("Big clock", "A large time, then your next 3 events as rows"),
        DAY_RAIL("Day rail", "Today by the hour, with a line at now and your free time"),
        NEXT_UP("Next up", "Your next event large, the rest of today and tomorrow's first below"),
        TWO_DAYS("Two days", "Today and tomorrow side by side"),
        WEEK("Week", "This week's days with a dot per event, then today");

        companion object { fun of(s: String?): Style = entries.firstOrNull { it.name == s } ?: CLASSIC }
    }

    const val HOUR = 3_600_000L
    const val DAY = 24 * HOUR

    /** The next [n] events not over by [now] and starting within [days] days: all-day events first on a tie. */
    fun upcoming(events: List<AodFaces.Event>, now: Long, n: Int = 3, days: Int = 7): List<AodFaces.Event> =
        events.filter { it.end > now && it.begin < now + days * DAY }.sortedWith(compareBy({ it.begin }, { !it.allDay })).take(n)

    /** The events on the day [start] until [end] (local midnights), all-day ones first; with [now], only those not
     *  over yet. A zero-length event counts on the day it starts. */
    fun onDay(events: List<AodFaces.Event>, start: Long, end: Long, now: Long? = null): List<AodFaces.Event> =
        events.filter { it.begin < end && (it.end > start || it.begin >= start) && (now == null || it.end > now) }
            .sortedWith(compareBy({ !it.allDay }, { it.begin }))

    /** Events per day for the days between consecutive [bounds] (local midnights, one more than the days). */
    fun perDay(events: List<AodFaces.Event>, bounds: List<Long>): List<Int> =
        (0 until bounds.size - 1).map { i -> onDay(events, bounds[i], bounds[i + 1]).size }

    /** Local midnights from the start of [now]'s week ([Calendar.getFirstDayOfWeek], the locale's) for 8 days, so
     *  7 days between them; DST-safe. */
    fun weekBounds(now: Long, cal: Calendar = Calendar.getInstance()): List<Long> {
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        while (cal.get(Calendar.DAY_OF_WEEK) != cal.firstDayOfWeek) cal.add(Calendar.DAY_OF_MONTH, -1)
        return (0..7).map { i -> if (i > 0) cal.add(Calendar.DAY_OF_MONTH, 1); cal.timeInMillis }
    }

    /** Local midnight of [now]'s day plus [days] days (DST-safe). */
    fun midnight(now: Long, days: Int = 0, cal: Calendar = Calendar.getInstance()): Long {
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_MONTH, days)
        return cal.timeInMillis
    }

    /** "30 min", "1 h", "1 h 30", "2 d": how long an event lasts. */
    fun duration(ms: Long): String {
        val m = (ms / 60_000L).coerceAtLeast(0)
        return when {
            m >= 24 * 60 -> "${(m + 12 * 60) / (24 * 60)} d"
            m < 60 -> "$m min"
            m % 60 == 0L -> "${m / 60} h"
            else -> "${m / 60} h ${"%02d".format(m % 60)}"
        }
    }

    /** "in 56 min", "in 2 h 05", "now": how soon [begin] is. */
    fun until(begin: Long, now: Long): String = if (begin <= now) "now" else "in " + duration(begin - now + 59_999L)

    // ---- Day rail (style B) --------------------------------------------------------------------------

    /**
     * The rail's whole hours [first, last]: from the first timed event's hour or [from] (7), whichever is earlier, to
     * [to] (19), widened to take in [spans] (hours of the day, from midnight, e.g. 9.5 to 10.25), and at least
     * [minSpan] hours long (widened later first, then earlier). Now isn't taken in: at night an empty day keeps its
     * daytime rail, and the line at now is drawn only when it falls on the rail.
     */
    fun railHours(spans: List<Pair<Float, Float>>, from: Int = 7, to: Int = 19, minSpan: Int = 11): Pair<Int, Int> {
        var lo = from; var hi = to
        for ((b, e) in spans) { lo = min(lo, floor(b).toInt()); hi = max(hi, ceil(max(e, b)).toInt()) }
        lo = lo.coerceIn(0, 23); hi = hi.coerceIn(lo + 1, 24)
        while (hi - lo < minSpan && hi < 24) hi++
        while (hi - lo < minSpan && lo > 0) lo--
        return lo to hi
    }

    /** Every how many hours the rail is labelled so that labels [labelPx] tall don't touch, [pxPerHour] apart. */
    fun hourStep(pxPerHour: Float, labelPx: Float): Int = listOf(1, 2, 3, 4, 6).firstOrNull { it * pxPerHour >= labelPx } ?: 12

    /**
     * Tops for labels [height] tall that want to sit at [want] (sorted), so that none overlap ([gap] between) and all
     * stay within [top]..[bottom]: pushed down, then back up from the bottom. Null when they can't all fit.
     */
    fun stack(want: List<Float>, height: Float, gap: Float, top: Float, bottom: Float): List<Float>? {
        if (want.isEmpty()) return emptyList()
        if (want.size * height + (want.size - 1) * gap > bottom - top + 0.01f) return null
        val out = FloatArray(want.size)
        for (i in want.indices) out[i] = max(max(want[i], top), if (i > 0) out[i - 1] + height + gap else top)
        for (i in want.indices.reversed()) out[i] = min(out[i], if (i < want.size - 1) out[i + 1] - height - gap else bottom - height)
        return out.toList()
    }

    /** The most labels of [height] (and [gap]) that fit between [top] and [bottom]. */
    fun fitting(height: Float, gap: Float, top: Float, bottom: Float): Int =
        if (bottom - top < height) 0 else 1 + ((bottom - top - height) / (height + gap)).toInt()

    /** How many of [n] rows ([rowH] each) to show between [top] and [bottom]: all when they fit, else as many as
     *  leave room for a "+2 more" line [moreH] tall. */
    fun rowsShown(n: Int, rowH: Float, top: Float, bottom: Float, moreH: Float): Int {
        if (fitting(rowH, 0f, top, bottom) >= n) return n
        return fitting(rowH, 0f, top, bottom - moreH).coerceIn(0, n)
    }

    /** Which [k] of [n] rail labels to show when not all fit: from the first not over yet ([first]), as far as the list allows. */
    fun window(n: Int, k: Int, first: Int): IntRange {
        if (k >= n) return 0 until n
        val start = first.coerceIn(0, n - k)
        return start until start + k
    }

    /** The longest free stretch of at least [minMs] between timed events ([spans], begin to end) after [now]; on a tie
     *  the later one. Null when there's none. */
    fun freeGap(spans: List<Pair<Long, Long>>, now: Long, minMs: Long = HOUR): Pair<Long, Long>? {
        var best: Pair<Long, Long>? = null
        var busyUntil = Long.MIN_VALUE
        for ((b, e) in spans.sortedBy { it.first }) {
            if (busyUntil != Long.MIN_VALUE) {
                val from = max(busyUntil, now)
                if (b - from >= minMs && (best == null || b - from >= best.second - best.first)) best = from to b
            }
            busyUntil = max(busyUntil, e)
        }
        return best
    }

    // ---- Lists ---------------------------------------------------------------------------------------

    /** Rows for two lists of [a] and [b] items when only [room] rows fit: each gets up to half, and what one doesn't
     *  use goes to the other. */
    fun share(a: Int, b: Int, room: Int): Pair<Int, Int> {
        if (a + b <= room) return a to b
        val half = room / 2
        return when {
            a <= half -> a to room - a
            b <= room - half -> room - b to b
            else -> (room - half) to half
        }
    }
}
