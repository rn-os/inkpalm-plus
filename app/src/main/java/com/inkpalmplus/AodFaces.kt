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
        /** Null: no calendar access. */
        val events: List<Event>?, val book: Book?, val photo: Bitmap?, val rotation: Int,
    ) {
        fun copy(rotation: Int) = Data(now, h24, battery, low, live, events, book, photo, rotation)

        /** Everything the face shows: the minute, battery, events, book, photo and orientation. */
        fun key(face: Aod.Face): String = listOf(face.name, minute(now), battery, low, live, rotation,
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
            Aod.Face.AGENDA -> agenda(c, w, h, d)
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
        val ev = d.events
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
            val count = d.events?.size?.coerceAtLeast(1) ?: 1
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
