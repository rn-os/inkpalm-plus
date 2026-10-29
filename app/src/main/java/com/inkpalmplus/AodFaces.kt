package com.inkpalmplus

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Draws the sleep-screen faces (docs/mockups/20-aod-faces.png, aod/face-*.html; 21 for the stamp and low battery).
 * The mockups are 360×720 CSS px at 2×, so [u] turns their numbers into panel pixels. In landscape the face is laid
 * out at 1440×720 and turned into the panel's 720×1440 portrait buffer ([Aod.degreesFor]); the HWC itself doesn't
 * turn it (persist.sys.mogu.rotation is unset).
 */
object AodFaces {
    const val W = 720
    const val H = 1440
    private const val S = 2f
    private fun u(v: Float) = v * S
    private fun u(v: Int) = v * S

    class Event(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val where: String?)
    class Book(val title: String, val author: String?, val progress: Float?, val page: String?, val cover: Bitmap?)

    class Data(
        val now: Long, val h24: Boolean, val battery: Int, val low: Boolean, val live: Boolean,
        /** Null: no calendar access. Otherwise this week's and the next 7 days' events ([Aod]'s calendar read). */
        val events: List<Event>?, val book: Book?, val photo: Bitmap?, val rotation: Int,
        val agenda: Agenda.Style = Agenda.Style.CLASSIC,
    ) {
        fun copy(rotation: Int, agenda: Agenda.Style? = null) = Data(now, h24, battery, low, live, events, book, photo, rotation, agenda ?: this.agenda)

        /** Everything the face shows: the minute, battery, events, book, photo, orientation and Agenda style. */
        fun key(face: Aod.Face): String = listOf(face.name, agenda.name, minute(now), battery, low, live, rotation,
            events?.joinToString("|") { "${it.title}${it.begin}${it.end}" } ?: "no-access",
            book?.let { "${it.title}${it.progress}${it.cover != null}" }, photo?.generationId).joinToString(";")

        private fun minute(t: Long) = t / 60_000L
    }

    private val HEAVY: Typeface = Typeface.create(Typeface.DEFAULT, 800, false)
    private val BOLD: Typeface = Typeface.create(Typeface.DEFAULT, 700, false)
    private val SEMI: Typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    private val SERIF: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)

    private fun pen(sizePx: Float, face: Typeface = BOLD, spacing: Float = 0f, color: Int = Color.BLACK, align: Paint.Align = Paint.Align.CENTER) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sizePx; typeface = face; letterSpacing = spacing; this.color = color; textAlign = align }

    private fun stroke(widthPx: Float, cap: Paint.Cap = Paint.Cap.BUTT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = widthPx; color = Color.BLACK; strokeCap = cap }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    /** Shrinks [p] until [s] fits [maxW]. */
    private fun fit(p: Paint, s: String, maxW: Float): Paint { while (p.measureText(s) > maxW && p.textSize > 8) p.textSize *= 0.95f; return p }

    private fun ell(p: Paint, s: String, maxW: Float) = TextUtils.ellipsize(s, android.text.TextPaint(p), maxW, TextUtils.TruncateAt.END).toString()

    /** Draws [s] in a line box of height [p].textSize starting at [top], glyphs centred in it. Returns the box bottom. */
    private fun Canvas.line(s: String, x: Float, top: Float, p: Paint): Float {
        val fm = p.fontMetrics
        drawText(s, x, top + p.textSize / 2 - (fm.ascent + fm.descent) / 2, p)
        return top + p.textSize
    }

    private fun time(d: Data): String = SimpleDateFormat(if (d.h24) "HH:mm" else "h:mm", Locale.getDefault()).format(Date(d.now))
    private fun fmt(pattern: String, t: Long): String = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(t))
    private fun dayLong(d: Data) = fmt("EEEE d MMMM", d.now)
    private fun dayShort(d: Data) = fmt("EEE d MMM", d.now)

    // ---- Entry --------------------------------------------------------------------------------------

    /** The face as the panel's 720×1440 portrait picture. */
    fun render(face: Aod.Face, d: Data): Bitmap {
        val deg = Aod.degreesFor(d.rotation)
        val land = deg == 90 || deg == 270
        val w = if (land) H else W; val h = if (land) W else H
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        if (d.low) lowBattery(c, w, h, d)
        else when (face) {
            Aod.Face.CLOCK -> clock(c, w, h, d)
            Aod.Face.CLOCK_DATE -> clockDate(c, w, h, d)
            Aod.Face.ANALOG -> analog(c, w, h, d)
            Aod.Face.AGENDA -> when (d.agenda) {
                Agenda.Style.CLASSIC -> agenda(c, w, h, d)
                Agenda.Style.BIG_CLOCK -> bigClock(c, w, h, d)
                Agenda.Style.DAY_RAIL -> dayRail(c, w, h, d)
                Agenda.Style.NEXT_UP -> nextUp(c, w, h, d)
                Agenda.Style.TWO_DAYS -> twoDays(c, w, h, d)
                Agenda.Style.WEEK -> week(c, w, h, d)
            }
            Aod.Face.READING -> reading(c, w, h, d)
            Aod.Face.PHOTO -> if (d.photo != null) photo(c, w, h, d, d.photo) else clockDate(c, w, h, d)
            Aod.Face.MINIMAL -> minimal(c, w, h, d)
            else -> clockDate(c, w, h, d)
        }
        if (d.live && !d.low && !(face == Aod.Face.PHOTO && d.photo != null))
            c.line("Updated ${time(d)}", w / 2f, h - u(22) - u(13), pen(u(13)))
        return turn(bmp, deg)
    }

    /** Turns a face drawn for the screen as held into the portrait buffer (clockwise [deg]). */
    fun turn(src: Bitmap, deg: Int): Bitmap {
        if (deg == 0) return src
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            when (deg) {
                90 -> { translate(W.toFloat(), 0f); rotate(90f) }
                180 -> { translate(W.toFloat(), H.toFloat()); rotate(180f) }
                else -> { translate(0f, H.toFloat()); rotate(-90f) }
            }
            drawBitmap(src, 0f, 0f, null)
        }
        src.recycle()
        return out
    }

    /** Undoes [turn] (for pictures checked upright). */
    fun turnBack(src: Bitmap, deg: Int): Bitmap {
        if (deg == 0) return src
        val m = android.graphics.Matrix().apply { postRotate(-deg.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also { src.recycle() }
    }

    /** Test face: a big arrow to the top of the screen as held, with the rotation written under it. */
    fun arrow(rotation: Int): Bitmap {
        val deg = Aod.degreesFor(rotation)
        val land = deg == 90 || deg == 270
        val w = if (land) H else W; val h = if (land) W else H
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val cx = w / 2f
        c.drawPath(Path().apply {
            moveTo(cx, u(20)); lineTo(cx + u(90), u(130)); lineTo(cx + u(35), u(130)); lineTo(cx + u(35), h / 2f)
            lineTo(cx - u(35), h / 2f); lineTo(cx - u(35), u(130)); lineTo(cx - u(90), u(130)); close()
        }, fill)
        c.line("TOP", cx, h / 2f + u(20), pen(u(60), HEAVY))
        c.line("rotation $rotation · ${if (land) "landscape" else "portrait"}", cx, h / 2f + u(100), pen(u(20)))
        return turn(bmp, deg)
    }

    // ---- Parts --------------------------------------------------------------------------------------

    /** Battery icon and "72%", centred on [cx], in a box of height [u](20) from [top]. */
    private fun battery(c: Canvas, cx: Float, top: Float, level: Int, scale: Float = 1f) {
        if (level < 0) return
        val p = pen(u(16) * scale, BOLD, align = Paint.Align.LEFT)
        val label = "$level%"
        val iw = u(34) * scale; val ih = u(16) * scale; val gap = u(6) * scale
        val total = iw + gap + p.measureText(label)
        val x0 = cx - total / 2
        val y0 = top + (u(20) * scale - ih) / 2
        val sw = u(2.5f) * scale
        c.drawRoundRect(RectF(x0 + sw / 2, y0 + sw / 2, x0 + iw - sw / 2, y0 + ih - sw / 2), u(3) * scale, u(3) * scale, stroke(sw))
        val inner = RectF(x0 + sw + u(2) * scale, y0 + sw + u(2) * scale, x0 + iw - sw - u(2) * scale, y0 + ih - sw - u(2) * scale)
        inner.right = inner.left + inner.width() * (level.coerceIn(0, 100) / 100f)
        c.drawRect(inner, fill)
        c.line(label, x0 + iw + gap, top + (u(20) * scale - p.textSize) / 2, p)
    }

    private fun rule(c: Canvas, cx: Float, top: Float, width: Float) =
        c.drawRect(cx - width / 2, top, cx + width / 2, top + u(3), fill)

    private fun bigPen(sizeCss: Float, spacing: Float = -4f / 112f) = pen(u(sizeCss), HEAVY, spacing)

    // ---- Faces --------------------------------------------------------------------------------------

    private fun clock(c: Canvas, w: Int, h: Int, d: Data) {
        val land = w > h
        val p = fit(bigPen(if (land) 170f else 118f, -0.05f), time(d), w - u(20))
        c.line(time(d), w / 2f, (h - p.textSize) / 2, p)
    }

    private fun clockDate(c: Canvas, w: Int, h: Int, d: Data) {
        val big = fit(bigPen(112f), time(d), w - u(30))
        val day = fit(pen(u(26), BOLD), dayLong(d), w - u(30))
        val height = big.textSize + u(18 + 3 + 18) + day.textSize * 1.2f + u(16 + 20)
        var y = if (w > h) (h - height) / 2 else u(150)
        y = c.line(time(d), w / 2f, y, big) + u(18)
        rule(c, w / 2f, y, minOf(w * 0.7f, u(286))); y += u(3 + 18)
        y = c.line(dayLong(d), w / 2f, y, day) + day.textSize * 0.2f + u(16)
        battery(c, w / 2f, y, d.battery)
    }

    private fun dial(c: Canvas, cx: Float, cy: Float, r: Float, now: Long) {
        val k = r / 130f
        c.drawCircle(cx, cy, r, stroke(7 * k))
        for (i in 0 until 12) {
            val major = i % 3 == 0
            c.save(); c.rotate(i * 30f, cx, cy)
            c.drawLine(cx, cy - 118 * k, cx, cy - (if (major) 98 else 108) * k, stroke((if (major) 8 else 3) * k))
            c.restore()
        }
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val m = cal.get(Calendar.MINUTE); val hr = cal.get(Calendar.HOUR) + m / 60f
        c.save(); c.rotate(hr * 30f, cx, cy); c.drawLine(cx, cy, cx, cy - 62 * k, stroke(12 * k, Paint.Cap.ROUND)); c.restore()
        c.save(); c.rotate(m * 6f, cx, cy); c.drawLine(cx, cy, cx, cy - 96 * k, stroke(8 * k, Paint.Cap.ROUND)); c.restore()
        c.drawCircle(cx, cy, 9 * k, fill)
    }

    private fun analog(c: Canvas, w: Int, h: Int, d: Data) {
        val day = pen(u(26), BOLD)
        if (w > h) {
            val r = h * 0.38f
            dial(c, w * 0.3f, h / 2f, r, d.now)
            val big = fit(pen(u(34), BOLD), dayShort(d), w * 0.4f)
            val top = (h - big.textSize - u(10 + 20)) / 2
            val y = c.line(dayShort(d), w * 0.7f, top, big) + u(10)
            battery(c, w * 0.7f, y, d.battery, 1.3f)
            return
        }
        val k = u(300) / 280f
        dial(c, w / 2f, u(140) + u(150), 130 * k, d.now)
        var y = u(140 + 300 + 24)
        y = c.line(dayShort(d), w / 2f, y, day) + day.textSize * 0.2f + u(10)
        battery(c, w / 2f, y, d.battery)
    }

    /** One event card: "11:30  Dentist" / "until 12:00". Returns the bottom. */
    private fun event(c: Canvas, e: Event, left: Float, width: Float, top: Float, d: Data): Float {
        val today = fmt("yyyyMMdd", e.begin) == fmt("yyyyMMdd", d.now)
        val started = e.begin <= d.now
        val whenText = when {
            e.allDay -> if (today || started) "All day" else fmt("EEE", e.begin) + " · all day"
            today || started -> fmt(if (d.h24) "HH:mm" else "h:mm", e.begin)
            else -> fmt(if (d.h24) "EEE HH:mm" else "EEE h:mm", e.begin)
        }
        val sub = when {
            e.allDay -> e.where ?: ""
            else -> "until " + fmt(if (d.h24) "HH:mm" else "h:mm", e.end) + (e.where?.let { " · $it" } ?: "")
        }
        val pad = u(14); val sw = u(2.5f)
        val b = pen(u(18), HEAVY, align = Paint.Align.LEFT)
        val s = pen(u(13), Typeface.DEFAULT, align = Paint.Align.LEFT)
        val hgt = u(10) + b.textSize * 1.2f + (if (sub.isNotEmpty()) s.textSize * 1.25f else 0f) + u(10)
        c.drawRoundRect(RectF(left + sw / 2, top + sw / 2, left + width - sw / 2, top + hgt - sw / 2), u(12), u(12), stroke(sw))
        var y = c.line(ell(b, "$whenText  ${e.title}", width - 2 * pad), left + pad, top + u(10), b) + b.textSize * 0.2f
        if (sub.isNotEmpty()) c.line(ell(s, sub, width - 2 * pad), left + pad, y, s)
        return top + hgt
    }

    private fun agendaList(c: Canvas, d: Data, cx: Float, width: Float, top: Float): Float {
        var y = c.line("NEXT 3", cx, top, pen(u(16), HEAVY, 0.08f)) + u(4)
        val ev = d.events?.let { Agenda.upcoming(it, d.now) }
        val msg = when {
            ev == null -> "Calendar access needed" to "Open InkPalm+ → Sleep & power → Sleep screen"
            ev.isEmpty() -> "Nothing in the next 7 days" to ""
            else -> null
        }
        if (msg != null) {
            y += u(12)
            y = c.line(msg.first, cx, y, fit(pen(u(20), HEAVY), msg.first, width)) + u(8)
            if (msg.second.isNotEmpty()) c.line(msg.second, cx, y, fit(pen(u(13), SEMI), msg.second, width))
            return y
        }
        for (e in ev!!) y = event(c, e, cx - width / 2, width, y + u(6), d) + u(6)
        return y
    }

    private fun agenda(c: Canvas, w: Int, h: Int, d: Data) {
        val big = fit(bigPen(88f), time(d), if (w > h) w * 0.42f else w - u(30))
        val day = fit(pen(u(26), BOLD), dayLong(d), if (w > h) w * 0.42f else w - u(30))
        if (w > h) {
            val cx = w * 0.25f
            var y = (h - big.textSize - u(6) - day.textSize) / 2
            y = c.line(time(d), cx, y, big) + u(6)
            c.line(dayLong(d), cx, y, day)
            val count = d.events?.let { Agenda.upcoming(it, d.now).size }?.coerceAtLeast(1) ?: 1
            val listH = u(20) + count * u(72)
            agendaList(c, d, w * 0.72f, w * 0.48f, ((h - listH) / 2).coerceAtLeast(u(10)))
            return
        }
        var y = u(60)
        y = c.line(time(d), w / 2f, y, big) + u(6)
        y = c.line(dayLong(d), w / 2f, y, day) + day.textSize * 0.2f + u(14)
        rule(c, w / 2f, y, minOf(w * 0.7f, u(250))); y += u(3 + 14)
        agendaList(c, d, w / 2f, w * 0.9f, y)
    }

    // ---- Agenda styles (docs/mockups/aod/agenda-variants/; their CSS px are panel px, no [u]) -------------

    private val THIN: Typeface = Typeface.create(Typeface.DEFAULT, 400, false)
    private val LEFT = Paint.Align.LEFT
    private val RIGHT = Paint.Align.RIGHT

    /** Draws [s] as CSS does in a line box [lh] × its size from [top] (glyphs centred). Returns the box bottom. */
    private fun Canvas.css(s: String, x: Float, top: Float, p: Paint, lh: Float = 1.2f): Float {
        line(s, x, top + p.textSize * (lh - 1) / 2, p)
        return top + p.textSize * lh
    }

    /** The baseline [Canvas.css] draws [p]'s glyphs on, in a box from [top]. */
    private fun baseline(p: Paint, top: Float, lh: Float = 1.2f): Float {
        val fm = p.fontMetrics
        return top + p.textSize * lh / 2 - (fm.ascent + fm.descent) / 2
    }

    /** The mockups' small capitals ("NEXT 3", "TODAY"). */
    private fun cap(size: Float, align: Paint.Align = LEFT, color: Int = Color.BLACK, spacing: Float = 0.08f) = pen(size, HEAVY, spacing, color, align)

    private fun thin(size: Float, align: Paint.Align = LEFT, color: Int = Color.BLACK) = pen(size, THIN, color = color, align = align)

    /** The big time of the styles (800, tight), shrunk to [maxW]. */
    private fun clockPen(size: Float, d: Data, maxW: Float, align: Paint.Align = Paint.Align.CENTER, spacing: Float = -0.045f) =
        fit(pen(size, HEAVY, spacing, align = align), time(d), maxW)

    private fun hm(d: Data, t: Long) = fmt(if (d.h24) "HH:mm" else "h:mm", t)

    /** Where a style's content must end: above the "Updated" stamp when Live. */
    /** The bottom of a face's content: with the "Updated 10:15" footer (top at h - 70), a clear 40 px above it. */
    private fun limit(d: Data, h: Int) = h - if (d.live) 110f else 44f

    private fun today(d: Data, t: Long) = t >= Agenda.midnight(d.now) && t < Agenda.midnight(d.now, 1)

    /** "until 12:00 · Room 4" (or "until Sat 09:00"); an all-day event's place. */
    private fun subOf(d: Data, e: Event): String = if (e.allDay) e.where.orEmpty() else
        "until " + (if (today(d, e.end - 1) || e.end - e.begin < 12 * Agenda.HOUR) hm(d, e.end) else fmt("EEE ", e.end) + hm(d, e.end)) +
            (e.where?.let { " · $it" } ?: "")

    /** The mockups' battery: a 38×18 icon and "82%" (22 px), [align]ed on [x], centred on [cy]. */
    private fun bat(c: Canvas, x: Float, cy: Float, level: Int, align: Paint.Align = Paint.Align.CENTER) {
        if (level < 0) return
        val p = pen(22f, BOLD, align = LEFT)
        val label = "$level%"
        val total = 38f + 8f + p.measureText(label)
        val x0 = when (align) { LEFT -> x; RIGHT -> x - total; else -> x - total / 2 }
        c.drawRoundRect(RectF(x0 + 1.5f, cy - 7.5f, x0 + 36.5f, cy + 7.5f), 4f, 4f, stroke(3f))
        c.drawRect(x0 + 5, cy - 4, x0 + 5 + 28 * level.coerceIn(0, 100) / 100f, cy + 4, fill)
        c.line(label, x0 + 46, cy - 11, p)
    }

    /** No calendar access, else [empty]: one or two lines from [top]. Returns the bottom. */
    private fun notice(c: Canvas, d: Data, empty: String, x: Float, top: Float, width: Float, align: Paint.Align = LEFT, size: Float = 32f): Float {
        val (a, b) = if (d.events == null) "Calendar access needed" to "Open InkPalm+ → Sleep & power → Sleep screen" else empty to ""
        var y = c.css(a, x, top, fit(pen(size, HEAVY, align = align), a, width))
        if (b.isNotEmpty()) y = c.css(b, x, y + 4, fit(pen(size * 0.7f, SEMI, align = align), b, width))
        return y
    }

    /** "+2 more" under a cut list. */
    private fun more(c: Canvas, n: Int, x: Float, top: Float, size: Float) = c.css("+$n more", x, top, thin(size))

    /** "ALL DAY Parcel · Pick up" (or "+1" for more) in a rounded box at [x], [top], [fs] px. Returns the bottom. */
    private fun allDayChip(c: Canvas, events: List<Event>, x: Float, top: Float, maxW: Float, fs: Float, border: Float = 4f, padV: Float = 10f, padH: Float = 18f, radius: Float = 16f): Float {
        val e = events.first()
        val head = pen(fs, HEAVY, align = LEFT); val rest = pen(fs, BOLD, align = LEFT)
        val more = if (events.size > 1) " +${events.size - 1}" else ""
        val label = "ALL DAY "
        val textW = maxW - 2 * (border + padH) - head.measureText(label)
        val body = ell(rest, e.title + (e.where?.let { " · $it" } ?: ""), textW - rest.measureText(more)) + more
        val bw = 2 * (border + padH) + head.measureText(label) + rest.measureText(body)
        val bh = 2 * (border + padV) + fs * 1.2f
        c.drawRoundRect(RectF(x + border / 2, top + border / 2, x + bw - border / 2, top + bh - border / 2), radius, radius, stroke(border))
        val ty = top + border + padV
        c.css(label, x + border + padH, ty, head)
        c.css(body, x + border + padH + head.measureText(label), ty, rest)
        return top + bh
    }

    // A: big clock, compact next-3 rows --------------------------------------------------------------

    private fun bigClock(c: Canvas, w: Int, h: Int, d: Data) {
        val ev = Agenda.upcoming(d.events.orEmpty(), d.now)
        if (w > h) {
            val half = w * 0.48f
            c.drawRect(half - 4, 48f, half, minOf(h - 48f, limit(d, h)), fill)
            val big = clockPen(230f, d, half - 60)
            val day = fit(pen(40f, BOLD), dayLong(d), half - 60)
            var y = (h - (big.textSize + 6 + day.textSize * 1.2f + 16 + 26.4f)) / 2
            y = c.css(time(d), half / 2, y, big, 1f) + 6
            y = c.css(dayLong(d), half / 2, y, day) + 16
            bat(c, half / 2, y + 13.2f, d.battery)
            val left = half + 50; val width = w - 50 - left
            val listH = 28.8f + 14 + (if (ev.isEmpty()) 4 + 34 + 70f else ev.size * 152f + 4)
            nextRows(c, d, ev, left, width, maxOf((h - listH) / 2, 30f))
            return
        }
        var y = c.css(time(d), w / 2f, 64f, clockPen(230f, d, w - 60f), 1f) + 6
        y = c.css(dayLong(d), w / 2f, y, fit(pen(44f, BOLD), dayLong(d), w - 60f)) + 18
        bat(c, w / 2f, y + 13.2f, d.battery)
        nextRows(c, d, ev, 40f, w - 80f, y + 26.4f + 50)
    }

    /** "NEXT 3" and the rows: time (or ALL DAY, with the weekday if not today) | title over "until 12:00". */
    private fun nextRows(c: Canvas, d: Data, ev: List<Event>, left: Float, width: Float, top: Float) {
        var y = c.css("NEXT ${ev.size.coerceAtLeast(3)}", left, top, cap(24f)) + 14
        c.drawRect(left, y, left + width, y + 4, fill)
        if (ev.isEmpty()) { notice(c, d, "Nothing in the next 7 days", left, y + 38, width); return }
        val name = pen(40f, BOLD, align = LEFT); val sub = thin(25f)
        val tw = width - 170
        for (e in ev) {
            y += 4 + 34
            val mid = y + 40
            val day = if (today(d, e.begin) || e.begin <= d.now) null else fmt("EEE", e.begin).uppercase()
            val t = if (e.allDay) "ALL DAY" else hm(d, e.begin)
            val tp = if (e.allDay) cap(22f, spacing = 0.04f) else pen(34f, HEAVY, align = LEFT)
            if (day != null) {
                val dp = cap(20f)
                c.css(day, left, mid - (dp.textSize * 1.2f + tp.textSize * 1.1f) / 2, dp)
                c.css(t, left, mid - (dp.textSize * 1.2f + tp.textSize * 1.1f) / 2 + dp.textSize * 1.2f, fit(tp, t, 160f), 1.1f)
            } else c.css(t, left, mid - tp.textSize * 0.6f, fit(tp, t, 160f))
            val s = subOf(d, e)
            if (s.isEmpty()) c.css(ell(name, e.title, tw), left + 170, mid - 22, name, 1.1f)
            else c.css(ell(sub, s, tw), left + 170, c.css(ell(name, e.title, tw), left + 170, y, name, 1.1f) + 6, sub)
            y += 80 + 34
            c.drawRect(left, y, left + width, y + 4, fill)
        }
    }

    // B: day rail by hour ----------------------------------------------------------------------------

    private fun dayRail(c: Canvas, w: Int, h: Int, d: Data) {
        val t0 = Agenda.midnight(d.now)
        val todays = Agenda.onDay(d.events.orEmpty(), t0, Agenda.midnight(d.now, 1))
        val allDay = todays.filter { it.allDay }
        val rail: RectF
        if (w > h) {
            val lw = w * 0.36f
            var y = c.css(time(d), 38f, 44f, clockPen(150f, d, lw - 60, LEFT), 1f) + 6
            y = c.css(dayLong(d), 44f, y, fit(pen(34f, BOLD, align = LEFT), dayLong(d), lw - 60)) + 16
            bat(c, 44f, y + 13.2f, d.battery, LEFT)
            if (allDay.isNotEmpty()) allDayChip(c, allDay, 44f, limit(d, h) - 54.4f, lw - 64, 22f)
            rail = RectF(lw + 20, 36f, w - 44f, limit(d, h) - RAIL_SPILL)
        } else {
            val big = clockPen(150f, d, w * 0.62f, LEFT)
            var y = c.css(time(d), 40f, 50f, big, 1f) + 6
            val day = fit(pen(36f, BOLD, align = LEFT), dayLong(d), w - 80f - 130f)
            y = c.css(dayLong(d), 40f, y, day)
            bat(c, w - 40f, y - 12 - 13.2f, d.battery, RIGHT)
            y += 22
            if (allDay.isNotEmpty()) y = allDayChip(c, allDay, 40f, y, w - 80f, 26f)
            rail = RectF(30f, y + 34, w - 36f, minOf(h - 176f, limit(d, h) - RAIL_SPILL))
        }
        if (d.events == null) { notice(c, d, "", rail.left + 140, rail.top, rail.width() - 140); return }
        rail(c, d, rail, todays.filter { !it.allDay }, t0, if (todays.isEmpty()) "Nothing on today" else "No timed events today")
    }

    /** How far the rail's blocks and labels may reach below its last hour. */
    private const val RAIL_SPILL = 17f

    /** The hours down [r]: labels and ticks, a block per event (≥ 34 px), its label beside it (pushed apart so none
     *  overlap, [Agenda.stack]), the longest free stretch after now, and a line at now. */
    private fun rail(c: Canvas, d: Data, r: RectF, timed: List<Event>, t0: Long, empty: String) {
        val hour = Agenda.HOUR.toFloat()
        fun hr(t: Long) = ((t - t0).coerceIn(0L, Agenda.DAY) / hour)
        val nowH = hr(d.now)
        val (h0, h1) = Agenda.railHours(timed.map { hr(it.begin) to hr(it.end) })
        val px = r.height() / (h1 - h0)
        fun y(v: Float) = r.top + (v - h0) * px
        val x = r.left
        val hp = pen(22f, BOLD, align = RIGHT)
        fun hourLabel(v: Int) = if (d.h24) "%02d".format(v % 24) else fmt("h a", t0 + v * Agenda.HOUR)
        fit(hp, if (d.h24) "00" else hourLabel(12), 70f)
        val step = Agenda.hourStep(px, hp.textSize * 1.6f)
        for (v in h0..h1) {
            c.drawRect(x + 84, y(v.toFloat()) - 1.5f, x + 128, y(v.toFloat()) + 1.5f, fill)
            if ((v - h0) % step == 0) c.line(hourLabel(v), x + 74, y(v.toFloat()) - hp.textSize / 2, hp)
        }
        for (e in timed) {
            val top = y(hr(e.begin))
            c.drawRoundRect(RectF(x + 92, top, x + 122, minOf(top + maxOf((hr(e.end) - hr(e.begin)) * px, 34f), r.bottom + 17)), 6f, 6f, fill)
        }
        // Labels: one 44 px slot each, from the first not over yet when they can't all fit.
        val slot = 44f
        val lx = x + 140; val lw = r.right - lx
        val shown = Agenda.window(timed.size, Agenda.fitting(slot, 0f, r.top - 6, r.bottom + 17), timed.indexOfFirst { it.end > d.now }.coerceAtLeast(0)).map { timed[it] }
        val gap = Agenda.freeGap(timed.map { it.begin to it.end }, d.now)
        val free = gap?.let { (a, b) -> (y(hr(a)) + y(hr(b))) / 2 - slot / 2 }
        val wants = shown.map { y(hr(it.begin)) - 6 }
        // The free stretch takes its place among the labels when they still fit with it.
        val withFree = free?.let { f -> (wants + f).sorted().let { all -> Agenda.stack(all, slot, 0f, r.top - 6, r.bottom + 17)?.let { all to it } } }
        val tops = withFree?.second ?: Agenda.stack(wants, slot, 0f, r.top - 6, r.bottom + 17) ?: wants
        val freeIndex = withFree?.first?.indexOf(free) ?: -1
        val bold = pen(30f, HEAVY, align = LEFT); val sub = thin(22f)
        var i = 0
        tops.forEachIndexed { k, top ->
            if (k == freeIndex) {
                val (a, b) = gap!!
                val label = "FREE ${hm(d, a)} – ${hm(d, b)}"
                val p = cap(22f)
                val bw = p.measureText(label) + 28 + 6
                val box = RectF(lx + 1.5f, top + 1.5f, lx + bw - 1.5f, top + 44 - 1.5f)
                c.drawRoundRect(box, 10f, 10f, Paint().apply { color = Color.WHITE })
                c.drawRoundRect(box, 10f, 10f, stroke(3f).apply { pathEffect = android.graphics.DashPathEffect(floatArrayOf(7f, 5f), 0f) })
                c.css(label, lx + 17, top + 3 + 6, p)
                return@forEachIndexed
            }
            val e = shown[i++]
            val head = ell(bold, "${hm(d, e.begin)} ${e.title}", lw - 10)
            val s = e.where ?: "until ${hm(d, e.end)}"
            val room = lw - 10 - bold.measureText(head) - 10
            val tail = if (room > 60) ell(sub, s, room) else ""
            val end = lx + bold.measureText(head) + (if (tail.isNotEmpty()) 10 + sub.measureText(tail) else 0f) + 10
            c.drawRect(lx, top + 2, minOf(end, r.right), top + 2 + 34.5f, Paint().apply { color = Color.WHITE })
            val base = baseline(bold, top + 2, 1.15f)
            c.drawText(head, lx, base, bold)
            if (tail.isNotEmpty()) c.drawText(tail, lx + bold.measureText(head) + 10, base, sub)
        }
        val nowOn = nowH >= h0 && nowH <= h1
        if (timed.isEmpty()) {
            val top = if (!nowOn) r.top else (y(nowH) + 16).let { if (it + 40 > r.bottom) y(nowH) - 56 else it }
            c.drawRect(lx, top, lx + bold.measureText(empty) + 10, top + 38, Paint().apply { color = Color.WHITE })
            c.css(empty, lx, top, fit(bold, empty, lw), 1.15f)
        }
        if (!nowOn) return
        val ny = y(nowH)
        c.drawRect(x + 74, ny - 3, r.right, ny + 3, fill)
        c.drawCircle(x + 73, ny, 9f, fill)
    }

    // C: next-up hero card ---------------------------------------------------------------------------

    private fun nextUp(c: Canvas, w: Int, h: Int, d: Data) {
        val land = w > h
        val all = Agenda.upcoming(d.events.orEmpty(), d.now, n = Int.MAX_VALUE)
        val hero = all.firstOrNull { !it.allDay } ?: all.firstOrNull()
        val t1 = Agenda.midnight(d.now, 1); val t2 = Agenda.midnight(d.now, 2)
        val later = Agenda.onDay(d.events.orEmpty(), Agenda.midnight(d.now), t1, d.now).filter { it !== hero }.sortedBy { it.allDay }
        val tomorrow = Agenda.onDay(d.events.orEmpty(), t1, t2).let { l -> l.firstOrNull { !it.allDay } ?: l.firstOrNull() }?.takeIf { it !== hero }
        val left: Float; val width: Float; var y: Float
        if (land) {
            val lw = (w - 88) * 0.42f
            val big = clockPen(150f, d, lw, LEFT)
            val day = fit(pen(36f, BOLD, align = LEFT), dayLong(d), lw)
            var ly = (h - (big.textSize + 4 + day.textSize * 1.2f + 16 + 26.4f)) / 2
            ly = c.css(time(d), 38f, ly, big, 1f) + 4
            ly = c.css(dayLong(d), 44f, ly, day) + 16
            bat(c, 44f, ly + 13.2f, d.battery, LEFT)
            left = 44 + lw + 44; width = w - 44 - left
            val rowsH = minOf(later.size, 4) * 63f
            y = if (hero == null) (h - 180f) / 2 else maxOf((h - (280f + 14 + rowsH)) / 2, 30f)
        } else {
            val big = clockPen(96f, d, w - 80f - 140, LEFT)
            var ty = c.css(time(d), 40f, 56f, big, 1f) + 4
            ty = c.css(dayLong(d), 40f, ty, fit(pen(32f, BOLD, align = LEFT), dayLong(d), w - 80f - 140))
            bat(c, w - 40f, (56f + ty) / 2, d.battery, RIGHT)
            left = 40f; width = w - 80f; y = ty + 44
        }
        if (hero == null) {
            // An outlined card in the hero's place.
            val bh = 34 + 28.8f + 14 + 38.4f + (if (d.events == null) 31f else 0f) + 34
            c.drawRoundRect(RectF(left + 2, y + 2, left + width - 2, y + bh - 2), 22f, 22f, stroke(4f))
            val ty = c.css("NEXT UP", left + 36, y + 34, cap(24f)) + 14
            notice(c, d, "Nothing in the next 7 days", left + 36, ty, width - 72)
            return
        }
        // The card.
        val pad = if (land) 32f else 36f; val padV = if (land) 26f else 34f
        val head = when {
            hero.begin <= d.now -> if (hero.allDay) "TODAY · ALL DAY" else "NOW · ENDS ${Agenda.until(hero.end, d.now).uppercase()}"
            today(d, hero.begin) -> if (hero.allDay) "NEXT UP · TODAY" else "NEXT UP · ${Agenda.until(hero.begin, d.now).uppercase()}"
            hero.begin < t2 -> "NEXT UP · TOMORROW"
            else -> "NEXT UP · " + fmt("EEEE d MMM", hero.begin).uppercase()
        }
        val inner = width - 2 * pad
        val hp = cap(24f, color = Color.WHITE)
        val tp = fit(pen(if (land) 84f else 130f, HEAVY, -0.03f, Color.WHITE, LEFT), "00:00", inner)
        val np = pen(if (land) 48f else 72f, BOLD, color = Color.WHITE, align = LEFT)
        while (np.measureText(hero.title) > inner && np.textSize > (if (land) 38f else 54f)) np.textSize *= 0.95f
        val sp = thin(28f, color = Color.WHITE)
        val sub = subOf(d, hero)
        val cardH = 2 * padV + hp.textSize * 1.2f + 14 + tp.textSize + 6 + np.textSize * 1.05f + (if (sub.isNotEmpty()) 10 + sp.textSize * 1.2f else 0f)
        c.drawRoundRect(RectF(left, y, left + width, y + cardH), 22f, 22f, fill)
        var cy = c.css(ell(hp, head, inner), left + pad, y + padV, hp) + 14
        cy = c.css(if (hero.allDay) "All day" else hm(d, hero.begin), left + pad, cy, tp, 1f) + 6
        cy = c.css(ell(np, hero.title, inner), left + pad, cy, np, 1.05f)
        if (sub.isNotEmpty()) c.css(ell(sp, sub, inner), left + pad, cy + 10, sp)
        y += cardH
        // The rest of today, then tomorrow's first.
        val fs = if (land) 30f else 34f; val rowPad = if (land) 12f else 24f
        val rowH = 2 * rowPad + fs * 1.2f + 3
        val boxH = 3 + 18 + 36f + 18 + 3
        if (!land && later.isNotEmpty()) {
            y = c.css("LATER TODAY", left, y + 34, cap(22f)) + 6
            c.drawRect(left, y, left + width, y + 3, fill); y += 3
        } else y += 14
        val bottom = limit(d, h) - if (!land && tomorrow != null) boxH + 40 else 0f
        val rows = later.take(Agenda.rowsShown(later.size, rowH, y, bottom, 44f))
        val tb = pen(fs, HEAVY, align = LEFT); val nb = pen(fs, BOLD, align = LEFT); val rp = thin(24f, RIGHT)
        for (e in rows) {
            val base = baseline(tb, y + rowPad)
            val t = if (e.allDay) "ALL DAY" else hm(d, e.begin)
            val right = if (e.allDay) e.where.orEmpty() else Agenda.duration(e.end - e.begin)
            val rw = if (right.isEmpty()) 0f else minOf(rp.measureText(right), width * 0.3f)
            c.drawText(t, left + 4, base, tb)
            val nx = left + 4 + tb.measureText("$t  ")
            c.drawText(ell(nb, e.title, left + width - 4 - rw - 16 - nx), nx, base, nb)
            if (right.isNotEmpty()) c.drawText(ell(rp, right, rw), left + width - 4, base, rp)
            y += rowH
            c.drawRect(left, y - 3, left + width, y, fill)
        }
        if (rows.size < later.size) y = more(c, later.size - rows.size, left + 4, y + 8, 26f)
        if (!land && tomorrow != null) {
            y += 40
            c.drawRoundRect(RectF(left + 1.5f, y + 1.5f, left + width - 1.5f, y + boxH - 1.5f), 14f, 14f, stroke(3f))
            val cp = cap(22f); val tp2 = pen(30f, BOLD, align = RIGHT)
            val base = baseline(tp2, y + 21, 1.2f)
            c.drawText("TOMORROW", left + 29, base, cp)
            val txt = (if (tomorrow.allDay) "All day " else hm(d, tomorrow.begin) + " ") + tomorrow.title
            c.drawText(ell(tp2, txt, width - 58 - cp.measureText("TOMORROW") - 24), left + width - 29, base, tp2)
        }
    }

    // D: today / tomorrow split ----------------------------------------------------------------------

    private fun twoDays(c: Canvas, w: Int, h: Int, d: Data) {
        val t0 = Agenda.midnight(d.now); val t1 = Agenda.midnight(d.now, 1); val t2 = Agenda.midnight(d.now, 2)
        val ev = d.events.orEmpty()
        val today = Agenda.onDay(ev, t0, t1, d.now); val tomorrow = Agenda.onDay(ev, t1, t2)
        val tA = today.filter { it.allDay }; val tT = today.filter { !it.allDay }
        val mA = tomorrow.filter { it.allDay }; val mT = tomorrow.filter { !it.allDay }
        val bottom = limit(d, h)
        if (w > h) {
            val big = clockPen(120f, d, w * 0.4f, LEFT)
            c.css(time(d), 50f, 36f, big, 1f)
            val bb = baseline(big, 36f, 1f)
            val dp = fit(pen(34f, BOLD, align = LEFT), dayLong(d), w - 56 - 200 - big.measureText(time(d)) - 30 - 56)
            c.drawText(dayLong(d), 56 + big.measureText(time(d)) + 30, bb, dp)
            bat(c, w - 56f, 36f + 60, d.battery, RIGHT)
            c.drawRect(56f, 174f, w - 56f, 178f, fill)
            val colW = (w - 112) / 2f
            if (d.events == null) { notice(c, d, "", 56f, 204f, w - 112f); return }
            c.drawRect(56 + colW - 4, 204f, 56 + colW, bottom, fill)
            dayColumn(c, d, "TODAY", fmt("EEE d MMM", t0), tT, tA, 56f, 204f, colW - 44, 28f, 8f, bottom, null, "Nothing else today")
            dayColumn(c, d, "TOMORROW", fmt("EEE d MMM", t1), mT, mA, 56 + colW + 44, 204f, colW - 44, 28f, 8f, bottom, null, "Nothing yet")
            return
        }
        val big = clockPen(120f, d, w - 80f - 140, LEFT)
        c.css(time(d), 40f, 56f, big, 1f)
        bat(c, w - 40f, 56f + big.textSize / 2, d.battery, RIGHT)
        var y = 56f + big.textSize + 30
        c.drawRect(40f, y, w - 40f, y + 4, fill); y += 4 + 34
        if (d.events == null) { notice(c, d, "", 40f, y, w - 80f); return }
        // Rows shared between the days by the room left (each gets half, and what one doesn't use goes to the other).
        val fs = 38f; val rowH = 3 + 32 + 2 * fs * 1.15f
        fun headH(allDay: List<Event>) = 30 * 1.2f + 2 + 22 * 1.2f + 14 + (if (allDay.isNotEmpty()) 3 + 8 + (fs - 8) * 1.2f + 8 + 3 + 12 else 0f)
        val fixed = headH(tA) + 34 + 3 + 30 + headH(mA)
        val room = ((bottom - y - fixed) / rowH).toInt().coerceAtLeast(0)
        // A cut list gives a row to its "+2 more".
        val (a, b) = Agenda.share(tT.size, mT.size, room).let { (a, b) -> if (a + b < tT.size + mT.size) Agenda.share(tT.size, mT.size, (room - 1).coerceAtLeast(0)) else a to b }
        y = dayColumn(c, d, "TODAY", fmt("EEE d MMM", t0), tT, tA, 40f, y, w - 80f, fs, 16f, bottom, a, "Nothing else today") + 34
        c.drawRect(40f, y, w - 40f, y + 3, fill); y += 3 + 30
        dayColumn(c, d, "TOMORROW", fmt("EEE d MMM", t1), mT, mA, 40f, y, w - 80f, fs, 16f, bottom, b, "Nothing yet")
    }

    /** "TODAY" / "Fri 24 Oct", the all-day box, then a row per event (time over title), as many as [rows] (null: as
     *  fit above [bottom]); a cut list ends with "+2 more". Returns the bottom. */
    private fun dayColumn(c: Canvas, d: Data, title: String, sub: String, timed: List<Event>, allDay: List<Event>, x: Float, top: Float, width: Float,
                          fs: Float, rowPad: Float, bottom: Float, rows: Int?, empty: String): Float {
        var y = c.css(title, x, top, cap(30f)) + 2
        y = c.css(sub, x, y, thin(22f)) + 14
        if (allDay.isNotEmpty()) y = allDayChip(c, allDay, x, y, width, fs - 8, 3f, 8f, 14f, 12f).let { b ->
            // The box spans the column, as in the mockup.
            b + 12
        }
        if (timed.isEmpty()) return if (allDay.isEmpty()) c.css(empty, x, y, thin(fs * 0.8f)) else y
        val rowH = 3 + 2 * rowPad + 2 * fs * 1.15f
        val shown = timed.take(rows ?: Agenda.rowsShown(timed.size, rowH, y, bottom, fs * 0.7f * 1.2f + 13))
        val tp = pen(fs, HEAVY, align = LEFT); val np = pen(fs, BOLD, align = LEFT)
        for (e in shown) {
            c.drawRect(x, y, x + width, y + 3, fill)
            var ry = c.css(hm(d, e.begin), x, y + 3 + rowPad, tp, 1.15f)
            ry = c.css(ell(np, e.title, width), x, ry, np, 1.15f)
            y = ry + rowPad
        }
        if (shown.size < timed.size) { c.drawRect(x, y, x + width, y + 3, fill); y = more(c, timed.size - shown.size, x, y + 10, fs * 0.7f) }
        return y
    }

    // E: week strip + today --------------------------------------------------------------------------

    private fun week(c: Canvas, w: Int, h: Int, d: Data) {
        val land = w > h
        val bounds = Agenda.weekBounds(d.now)
        val counts = Agenda.perDay(d.events.orEmpty(), bounds)
        val sx = if (land) 44f else 40f; val sy = if (land) 30f else 44f
        val cellW = (w - 2 * sx - 6 * 8) / 7
        val cellH = 128.4f
        for (i in 0 until 7) {
            val cx = sx + i * (cellW + 8)
            val now = d.now >= bounds[i] && d.now < bounds[i + 1]
            val r = RectF(cx, sy, cx + cellW, sy + cellH)
            if (now) c.drawRoundRect(r, 14f, 14f, fill)
            else c.drawRoundRect(RectF(r.left + 1.5f, r.top + 1.5f, r.right - 1.5f, r.bottom - 1.5f), 14f, 14f, stroke(3f))
            val ink = if (now) Color.WHITE else Color.BLACK
            val mid = cx + cellW / 2
            var y = c.css(fmt("EEEEE", bounds[i]), mid, sy + 3 + 14, pen(22f, BOLD, color = ink))
            y = c.css(fmt("d", bounds[i]), mid, y, pen(40f, HEAVY, color = ink), 1.15f) + 4
            val n = if (d.events == null) 0 else minOf(counts[i], 4)
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
            for (k in 0 until n) c.drawCircle(mid - (n - 1) * 9f + k * 18f, y + 8, 6f, dot)
        }
        val t0 = Agenda.midnight(d.now)
        val list = Agenda.onDay(d.events.orEmpty(), t0, Agenda.midnight(d.now, 1), d.now)
        val bottom = limit(d, h)
        if (land) {
            val top = sy + cellH
            val lw = (w - 88) * 0.46f
            val big = clockPen(190f, d, lw)
            val day = fit(pen(36f, BOLD), dayLong(d), lw)
            var y = top + (h - top - (big.textSize + 4 + day.textSize * 1.2f + 14 + 26.4f)) / 2
            y = c.css(time(d), 44 + lw / 2, y, big, 1f) + 4
            y = c.css(dayLong(d), 44 + lw / 2, y, day) + 14
            bat(c, 44 + lw / 2, y + 13.2f, d.battery)
            val left = 44 + lw + 44
            // Centred in the room under the strip, with as many rows as fit.
            val head = 22 * 1.2f + 10 + 3
            val n = Agenda.rowsShown(list.size, ROW_E, top + 20 + head, bottom, 40f)
            val listH = head + maxOf(n, 1) * ROW_E + (if (n < list.size) 40f else 0f)
            todayRows(c, d, list, left, w - 44 - left, (top + (h - top - listH) / 2).coerceIn(top + 20, maxOf(top + 20, bottom - listH)), bottom, 22f)
            return
        }
        var y = c.css(time(d), w / 2f, sy + cellH + 50, clockPen(200f, d, w - 60f), 1f) + 6
        y = c.css(dayLong(d), w / 2f, y, fit(pen(40f, BOLD), dayLong(d), w - 60f)) + 16
        bat(c, w / 2f, y + 13.2f, d.battery)
        todayRows(c, d, list, 40f, w - 80f, y + 26.4f + 44, bottom, 24f)
    }

    private const val ROW_E = 22 + 38 * 1.2f + 22 + 3

    /** "TODAY" and its rows: time (or ALL DAY) | title | how long (or where). */
    private fun todayRows(c: Canvas, d: Data, list: List<Event>, left: Float, width: Float, top: Float, bottom: Float, capSize: Float) {
        var y = c.css("TODAY", left, top, cap(capSize)) + 10
        c.drawRect(left, y, left + width, y + 3, fill); y += 3
        if (d.events == null || list.isEmpty()) { notice(c, d, "Nothing else today", left, y + 22, width); return }
        val rowH = ROW_E
        val shown = list.take(Agenda.rowsShown(list.size, rowH, y, bottom, 40f))
        val tp = pen(38f, HEAVY, align = LEFT); val ap = cap(22f, spacing = 0.05f); val np = pen(38f, BOLD, align = LEFT); val rp = thin(22f, RIGHT)
        for (e in shown) {
            val base = baseline(np, y + 22)
            if (e.allDay) c.drawText("ALL DAY", left, base, ap) else c.drawText(hm(d, e.begin), left, base, fit(tp, hm(d, e.begin), 140f))
            val right = if (e.allDay) e.where.orEmpty() else Agenda.duration(e.end - e.begin)
            val rw = if (right.isEmpty()) 0f else minOf(rp.measureText(right), width * 0.3f)
            if (right.isNotEmpty()) c.drawText(ell(rp, right, rw), left + width, base, rp)
            c.drawText(ell(np, e.title, width - 162 - rw - 16), left + 162, base, np)
            y += rowH
            c.drawRect(left, y - 3, left + width, y, fill)
        }
        if (shown.size < list.size) more(c, list.size - shown.size, left, y + 8, 24f)
    }

    /** The cover: the book's own picture, else a drawn book with its title and author (as in the mockup). */
    private fun cover(c: Canvas, r: RectF, b: Book?) {
        val sw = u(3)
        if (b?.cover != null) {
            val src = b.cover
            val scale = minOf(r.width() / src.width, r.height() / src.height)
            val dw = src.width * scale; val dh = src.height * scale
            val dst = RectF(r.centerX() - dw / 2, r.centerY() - dh / 2, r.centerX() + dw / 2, r.centerY() + dh / 2)
            c.drawBitmap(src, null, dst, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) })
            c.drawRect(dst, stroke(u(2)))
            return
        }
        c.drawRoundRect(RectF(r.left + sw / 2, r.top + sw / 2, r.right - sw / 2, r.bottom - sw / 2), u(7), u(7), stroke(sw))
        c.drawLine(r.left + u(10), r.top, r.left + u(10), r.bottom, stroke(u(2.5f)))
        val inner = r.width() - u(40)
        val title = b?.title ?: "No book yet"
        val tp = pen(u(24), SERIF)
        val lines = wrap(tp, title, inner, 3)
        val ap = pen(u(14), Typeface.SERIF)
        val author = b?.author?.let { ell(ap, it, inner) }
        val blockH = lines.size * tp.textSize * 1.15f + (if (author != null) u(8) + ap.textSize else 0f)
        var y = r.centerY() - blockH / 2
        val cx = r.centerX() + u(5)
        for (l in lines) y = c.line(l, cx, y, tp) + tp.textSize * 0.15f
        if (author != null) c.line(author, cx, y + u(8), ap)
    }

    /** Up to [max] lines of [s] that fit [width]; the last is ellipsized. */
    private fun wrap(p: Paint, s: String, width: Float, max: Int): List<String> {
        val out = ArrayList<String>()
        var rest = s.trim()
        while (rest.isNotEmpty() && out.size < max) {
            if (out.size == max - 1 || p.measureText(rest) <= width) { out += ell(p, rest, width); break }
            var n = p.breakText(rest, true, width, null)
            val sp = rest.lastIndexOf(' ', n)
            if (sp > 0) n = sp
            out += rest.substring(0, n).trim()
            rest = rest.substring(n).trim()
        }
        return out
    }

    private fun readingText(c: Canvas, d: Data, cx: Float, width: Float, top: Float) {
        val b = d.book
        val tp = pen(u(26), BOLD)
        var y = c.line(ell(tp, b?.title ?: "No book yet", width), cx, top, tp) + u(10)
        if (b?.progress != null) {
            val bw = width * 0.8f; val bh = u(10); val sw = u(2.5f)
            val bar = RectF(cx - bw / 2 + sw / 2, y + sw / 2, cx + bw / 2 - sw / 2, y + bh - sw / 2)
            c.drawRoundRect(bar, u(6), u(6), stroke(sw))
            c.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * b.progress, bar.bottom), u(6), u(6), fill)
            y += bh + u(8)
            y = c.line("${(b.progress * 100).toInt()}%", cx, y, pen(u(30), HEAVY))
        }
        val sub = listOfNotNull(b?.page?.let { "Page $it" } ?: if (b == null) "Pick a reader in InkPalm+" else null, time(d)).joinToString(" · ")
        c.line(sub, cx, y + u(14), fit(pen(u(16), BOLD), sub, width))
    }

    private fun reading(c: Canvas, w: Int, h: Int, d: Data) {
        if (w > h) {
            val ch = h * 0.8f; val cw = ch * 190 / 270
            cover(c, RectF(w * 0.27f - cw / 2, (h - ch) / 2, w * 0.27f + cw / 2, (h + ch) / 2), d.book)
            readingText(c, d, w * 0.7f, w * 0.48f, h / 2f - u(70))
            return
        }
        val r = RectF(w / 2f - u(95), u(80), w / 2f + u(95), u(80 + 270))
        cover(c, r, d.book)
        readingText(c, d, w / 2f, w * 0.8f, r.bottom + u(22))
    }

    private fun photo(c: Canvas, w: Int, h: Int, d: Data, src: Bitmap) {
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val cw = (w / scale).toInt(); val chh = (h / scale).toInt()
        val from = Rect((src.width - cw) / 2, (src.height - chh) / 2, (src.width + cw) / 2, (src.height + chh) / 2)
        c.drawBitmap(src, from, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        })
        val tp = pen(u(40), HEAVY, -0.02f, align = Paint.Align.RIGHT)
        val dp = pen(u(13), BOLD, align = Paint.Align.RIGHT)
        val sp = pen(u(11), SEMI, align = Paint.Align.RIGHT)
        val stamp = if (d.live) "Updated ${time(d)}" else null
        val cw2 = maxOf(tp.measureText(time(d)), dp.measureText(dayShort(d)), stamp?.let { sp.measureText(it) } ?: 0f) + u(28)
        val chH = u(8) + tp.textSize + u(2) + dp.textSize * 1.2f + (if (stamp != null) sp.textSize * 1.2f else 0f) + u(8)
        val box = RectF(w - u(18) - cw2, h - u(22) - chH, w - u(18), h - u(22))
        c.drawRoundRect(box, u(14), u(14), Paint().apply { color = Color.WHITE })
        c.drawRoundRect(RectF(box.left + u(1.5f), box.top + u(1.5f), box.right - u(1.5f), box.bottom - u(1.5f)), u(14), u(14), stroke(u(3)))
        val x = box.right - u(14)
        var y = c.line(time(d), x, box.top + u(8), tp) + u(2)
        y = c.line(dayShort(d), x, y, dp) + dp.textSize * 0.2f
        if (stamp != null) c.line(stamp, x, y, sp)
    }

    private fun minimal(c: Canvas, w: Int, h: Int, d: Data) {
        val a = pen(u(44), BOLD); val b = bigPen(170f, -0.03f); val m = pen(u(36), BOLD)
        val total = a.textSize * 1.2f + b.textSize + m.textSize * 1.2f
        var y = (h - total) / 2
        y = c.line(fmt("EEEE", d.now), w / 2f, y, a) + a.textSize * 0.2f
        y = c.line(fmt("d", d.now), w / 2f, y, b)
        c.line(fmt("MMMM", d.now), w / 2f, y + m.textSize * 0.2f, m)
    }

    /** Below 10% on battery: the time and a black box; Live updates have stopped (mockup 21). */
    private fun lowBattery(c: Canvas, w: Int, h: Int, d: Data) {
        val big = fit(bigPen(112f), time(d), w - u(30))
        val t1 = pen(u(26), HEAVY, color = Color.WHITE); val t2 = pen(u(15), SEMI, color = Color.WHITE)
        val label = "BATTERY ${d.battery.coerceAtLeast(0)}%"
        val bw = t1.measureText(label) + u(52)
        val bh = u(14) + t1.textSize * 1.2f + t2.textSize * 1.2f + u(14)
        val total = big.textSize + u(14) + bh
        var y = if (w > h) (h - total) / 2 else h * 0.42f - total / 2
        y = c.line(time(d), w / 2f, y, big) + u(14)
        c.drawRect(w / 2f - bw / 2, y, w / 2f + bw / 2, y + bh, fill)
        y = c.line(label, w / 2f, y + u(14), t1) + t1.textSize * 0.2f
        c.line("Charge soon", w / 2f, y, t2)
    }
}
