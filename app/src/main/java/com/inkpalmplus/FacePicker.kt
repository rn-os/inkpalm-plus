package com.inkpalmplus

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import java.util.concurrent.Executors

/**
 * The sleep-screen picker's pure parts (docs/mockups/aod/face-picker/): one page per face and style, in the order of
 * [Aod.Face] then each face's styles ([Agenda.Style], [Home.Style], [Dither.Style] for Photo), so a new face or style
 * shows up by itself. Photo's styles are the shared Photo style: "Photo · Crisp" is face Photo with Crisp. Picture is
 * one page: it's drawn in the same Photo style, which its own row on the Sleep screen page changes.
 */
object FacePicker {
    data class Entry(val face: Aod.Face, val agenda: Agenda.Style? = null, val home: Home.Style? = null, val photo: Dither.Style? = null) {
        /** The style's name, when the face has styles. */
        val style: String? get() = agenda?.title ?: home?.title ?: photo?.title
        /** "Agenda · Week", or the face's name. */
        val title: String get() = face.title + (style?.let { " · $it" } ?: "")
        /** The "All" grid's label: the style under its face's heading, else the face. */
        val label: String get() = style ?: face.title
        /** Stable name for caches and files: "AGENDA_WEEK". */
        val key: String get() = listOfNotNull(face.name, agenda?.name, home?.name, photo?.name).joinToString("_")
    }

    /** The face in use and the styles saved for each face. */
    data class Current(val face: Aod.Face, val agenda: Agenda.Style, val home: Home.Style, val photo: Dither.Style)

    fun entries(): List<Entry> = Aod.Face.entries.flatMap { f ->
        when (f) {
            Aod.Face.AGENDA -> Agenda.Style.entries.map { Entry(f, agenda = it) }
            Aod.Face.HOME -> Home.Style.entries.map { Entry(f, home = it) }
            Aod.Face.PHOTO -> Dither.Style.entries.map { Entry(f, photo = it) }
            else -> listOf(Entry(f))
        }
    }

    /** [e] is what the sleep screen shows now (Picture whatever its Photo style). */
    fun inUse(e: Entry, now: Current): Boolean = e.face == now.face &&
        (e.agenda == null || e.agenda == now.agenda) && (e.home == null || e.home == now.home) && (e.photo == null || e.photo == now.photo)

    /** The page to open on: the one in use (the first page if none matches). */
    fun indexOf(list: List<Entry>, now: Current): Int = list.indexOfFirst { inUse(it, now) }.coerceAtLeast(0)

    /** The "All" grid: each [Aod.Group] with its pages (and their indexes), empty groups left out. */
    fun groups(list: List<Entry>): List<Pair<Aod.Group, List<IndexedValue<Entry>>>> =
        Aod.Group.entries.map { g -> g to list.withIndex().filter { it.value.face.group == g } }.filter { it.second.isNotEmpty() }

    /** The page's one line under its name. Picture says which Photo style it's drawn in. */
    fun detail(e: Entry, now: Current): String = when {
        e.agenda != null -> e.agenda.detail
        e.home != null -> e.home.detail
        e.photo != null -> "${e.face.detail} · ${e.photo.detail}"
        e.face == Aod.Face.PICTURE -> "${e.face.detail} · in the Photo style (${now.photo.title})"
        else -> e.face.detail
    }

    enum class Setup { CALENDAR, WEATHER, PHOTO, READER }

    /** The line under "Use this": what the face uses, and the [setup] still to do (tappable), if any. */
    data class Need(val text: String, val setup: Setup? = null)

    /** What the faces need, as it is now. */
    data class State(val calendar: Boolean, val city: String?, val picture: Boolean, val reader: String?)

    fun need(e: Entry, s: State): Need = when (e.face) {
        Aod.Face.AGENDA -> if (!s.calendar) Need("Calendar: allow access", Setup.CALENDAR) else Need("Uses your calendar")
        Aod.Face.HOME -> when {
            !s.calendar -> Need("Calendar: allow access", Setup.CALENDAR)
            s.city == null -> Need("Weather: set location", Setup.WEATHER)
            else -> Need("Uses your calendar and the weather for ${s.city}")
        }
        Aod.Face.READING -> if (s.reader == null) Need("Reader app: pick one", Setup.READER) else Need("Shows your current book")
        Aod.Face.PHOTO, Aod.Face.PICTURE -> if (!s.picture) Need("Pick a photo", Setup.PHOTO) else Need("Uses your photo")
        Aod.Face.DEFAULT -> Need("Nothing drawn by InkPalm+")
        else -> Need("Nothing to set up")
    }
}

/** READ_CALENDAR, asked when a calendar face is picked. Once denied for good ([fromSettings]), Android's app settings. */
fun Activity.askCalendar(fromSettings: Boolean, request: Int) {
    if (fromSettings && !shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR) &&
        Prefs(this).sp.getBoolean("calendar_asked", false)) {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
        return
    }
    Prefs(this).sp.edit().putBoolean("calendar_asked", true).apply()
    requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), request)
}

/**
 * A face's picture fitted into the view at its own shape (never stretched), framed; [label] in a frame of the panel's
 * shape ([ratio], width / height) while there's none. [thick]: the frame of the one in use.
 */
class FacePreview(c: Context, private val ratio: Float, private val label: String) : View(c) {
    var bitmap: Bitmap? = null
        set(v) { field = v; invalidate() }
    var thick = false
        set(v) { field = v; invalidate() }
    /** Where the face was drawn last, for the page's left and right taps. */
    val drawn = RectF()
    private val pic = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ink.INK }
    private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ink.INK; textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap
        val a = if (b != null) b.width.toFloat() / b.height else ratio
        val stroke = dp(if (thick) 4 else 2).toFloat()
        val w = width - paddingLeft - paddingRight - 2 * stroke; val h = height - paddingTop - paddingBottom - 2 * stroke
        if (w <= 0 || h <= 0) return
        val (fw, fh) = if (w / h > a) h * a to h else w to w / a
        val left = paddingLeft + stroke + (w - fw) / 2; val top = paddingTop + stroke + (h - fh) / 2
        drawn.set(left, top, left + fw, top + fh)
        if (b != null) canvas.drawBitmap(b, null, drawn, pic)
        else {
            words.textSize = minOf(fw / 7, dp(16).toFloat())
            canvas.drawText(label, drawn.centerX(), drawn.centerY() + words.textSize / 3, words)
        }
        frame.strokeWidth = stroke
        canvas.drawRect(drawn.left - stroke / 2, drawn.top - stroke / 2, drawn.right + stroke / 2, drawn.bottom + stroke / 2, frame)
    }
}

/**
 * The sleep-screen picker (docs/mockups/aod/face-picker/picker-sheet.png): one face and style a page, previewed at
 * its true shape with the user's own data (drawn off the main thread, kept per page while open), ‹ › and swipes or
 * taps on the preview's sides to flip (no animation: the page's content is swapped), "Use this" to pick it, and "All"
 * for a grid of every page. In landscape the preview shows the face's landscape layout, the controls beside it.
 * The faces' own options stay on the Sleep screen page.
 */
class FacePickerActivity : Activity() {
    private val list = FacePicker.entries()
    private var index = 0
    private var grid = false
    private var busy = false
    /** "Use this" was tapped on a Picture page without a picture: picked once the photo is. */
    private var applyAfterPick: FacePicker.Entry? = null
    private var setupOpen = false

    private val land get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val ratio get() = if (land) 2f else 0.5f

    /** Pictures drawn so far (this orientation; the activity is new after a turn): pages at half the face's size, the
     *  grid's at a quarter. A few pages are kept; the grid's are small. */
    private val pages = object : LinkedHashMap<String, Bitmap?>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap?>) = size > 8
    }
    private val tiles = HashMap<String, Bitmap?>()
    private val renderer = Executors.newSingleThreadExecutor { r -> Thread(r, "inkpalm-picker") }
    /** What's wanted now ("p:KEY" pages, "t:KEY" tiles); older queued renders that aren't are skipped. */
    @Volatile private var wanted: Set<String> = emptySet()
    private val queued = HashSet<String>()
    private val shown = HashMap<String, FacePreview>()

    private var downX = 0f
    private var downY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        index = savedInstanceState?.getInt("index") ?: FacePicker.indexOf(list, Aod.current(this))
        grid = savedInstanceState?.getBoolean("grid") ?: false
        render()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("index", index); out.putBoolean("grid", grid)
    }

    override fun onResume() {
        super.onResume()
        // Back from a setup screen (weather, reader): the faces may look different now.
        if (setupOpen) { setupOpen = false; forget(); render() }
    }

    override fun onDestroy() {
        renderer.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("No AndroidX here")
    override fun onBackPressed() {
        if (grid) { grid = false; render() } else super.onBackPressed()
    }

    private fun forget() { pages.clear(); tiles.clear() }

    private fun render() {
        shown.clear()
        setContentView(if (grid) gridView() else pageView())
    }

    private fun flip(by: Int) {
        index = Math.floorMod(index + by, list.size)
        render()
    }

    // ---- Page ----------------------------------------------------------------------------------------

    private fun state() = FacePicker.State(!Aod.needsCalendar(this), Weather.city(this)?.name, Aod.hasPicture(this), Readers.chosen(this)?.label)

    private fun header(title: String, onBack: () -> Unit, all: Boolean): View = hbox().apply {
        setPadding(dp(Space.GUTTER), dp(12), dp(Space.GUTTER), dp(4))
        addView(inkIconButton(R.drawable.ic_back, "Back", 48) { onBack() }, lp(dp(48), dp(48)).apply { marginEnd = dp(10) })
        addView(text(title, Type.XL, bold = true).apply { maxLines = 1 }, lp(0, WRAP, 1f))
        if (all) addView(inkButton("All") { grid = true; render() }.apply { contentDescription = "All sleep screens"; minWidth = dp(64) })
    }

    private fun pageView(): View {
        val e = list[index]
        val now = Aod.current(this)
        val g = dp(Space.GUTTER)
        val preview = FacePreview(this, ratio, if (e.face == Aod.Face.DEFAULT) "Moaan's picture" else if (e.face == Aod.Face.PICTURE && !Aod.hasPicture(this)) "No photo yet" else "…").apply {
            contentDescription = "Preview of the ${e.title} sleep screen"
            setPadding(0, dp(6), 0, dp(6))
            setOnTouchListener { v, ev ->
                if (ev.action == MotionEvent.ACTION_UP && Math.abs(ev.x - downXIn(v)) < dp(20)) {
                    val r = (v as FacePreview).drawn
                    when {
                        ev.x < r.left + r.width() * 0.3f -> flip(-1)
                        ev.x > r.right - r.width() * 0.3f -> flip(1)
                    }
                }
                true
            }
        }
        show("p:${e.key}", preview)
        want(setOf("p:${e.key}", "p:${list[Math.floorMod(index + 1, list.size)].key}", "p:${list[Math.floorMod(index - 1, list.size)].key}"))

        val name = text(e.title, Type.XL, bold = true).apply { maxLines = 1 }
        val detail = text(FacePicker.detail(e, now), Type.S, color = Ink.MUTED).apply { maxLines = 2; setPadding(0, dp(2), 0, 0) }
        val nav = hbox().apply {
            addView(arrow(next = false), lp(dp(84), dp(52)))
            addView(text("${index + 1} / ${list.size}", Type.L, bold = true).apply {
                gravity = Gravity.CENTER; contentDescription = "Screen ${index + 1} of ${list.size}"
            }, lp(0, WRAP, 1f))
            addView(arrow(next = true), lp(dp(84), dp(52)))
        }
        val inUse = FacePicker.inUse(e, now)
        val use = text(if (inUse) "✓ In use" else if (busy) "Applying…" else "Use this", Type.L, bold = true, color = if (inUse) Ink.INK else Ink.PAPER).apply {
            gravity = Gravity.CENTER
            background = if (inUse) outline(2, Radius.SMALL) else StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), outline(2, Radius.SMALL, Ink.MUTED))
                addState(intArrayOf(), outline(2, Radius.SMALL, Ink.INK))
            }
            contentDescription = if (inUse) "${e.title} is in use" else "Use ${e.title} as the sleep screen"
            if (!inUse && !busy) { isClickable = true; setOnClickListener { use(e) } }
        }
        val need = FacePicker.need(e, state())
        val req = hbox().apply {
            setPadding(0, dp(10), 0, dp(10))
            addView(text(need.text, Type.M, bold = need.setup != null, color = if (need.setup != null) Ink.INK else Ink.MUTED).apply {
                if (need.setup != null) paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                maxLines = 2
            }, lp(0, WRAP, 1f))
            if (need.setup != null) {
                addView(text("›", Type.XL, bold = true))
                pressable()
                contentDescription = "${need.text}: set it up"
                setOnClickListener { setup(need.setup, null) }
            }
        }

        val controls = vbox().apply {
            if (land) {
                addView(header("Sleep screen", ::finish, true).apply { setPadding(0, dp(12), 0, dp(4)) })
                addView(name, lp(MATCH, WRAP).apply { topMargin = dp(8) })
                addView(detail)
                addView(View(context), lp(MATCH, 0, 1f))
            }
            addView(nav, lp(MATCH, WRAP).apply { topMargin = dp(4) })
            addView(use, lp(MATCH, dp(56)).apply { topMargin = dp(8) })
            addView(divider(2), lp(MATCH, dp(2)).apply { topMargin = dp(8) })
            addView(req, lp(MATCH, WRAP))
        }
        return if (land) hbox().apply {
            gravity = Gravity.TOP
            setPadding(g, 0, g, dp(4))
            addView(preview, lp(0, MATCH, 1.45f).apply { marginEnd = g; topMargin = dp(12); bottomMargin = dp(12) })
            addView(controls, lp(0, MATCH, 1f))
        } else vbox().apply {
            addView(header("Sleep screen", ::finish, true))
            addView(vbox().apply {
                setPadding(g, dp(6), g, 0)
                addView(name); addView(detail)
            })
            addView(preview, lp(MATCH, 0, 1f).apply { setMargins(g, dp(4), g, 0) })
            addView(controls.apply { setPadding(g, 0, g, dp(4)) }, lp(MATCH, WRAP))
        }
    }

    /** ‹ or › as a drawn chevron, centred (the font's glyphs sit low in the button). */
    private fun arrow(next: Boolean): View = inkButton("") { flip(if (next) 1 else -1) }.apply {
        contentDescription = if (next) "Next sleep screen" else "Previous sleep screen"
        val d = Chevron(dp(3).toFloat(), next)
        foreground = d
    }

    /** A chevron the size of a large glyph, centred in its view. */
    private inner class Chevron(private val stroke: Float, private val right: Boolean) : android.graphics.drawable.Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; color = Ink.INK; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        override fun draw(c: Canvas) {
            val r = bounds; val h = dp(9).toFloat(); val w = dp(5).toFloat()
            val cx = r.exactCenterX(); val cy = r.exactCenterY(); val dx = if (right) w else -w
            c.drawPath(android.graphics.Path().apply { moveTo(cx - dx, cy - h); lineTo(cx + dx, cy); lineTo(cx - dx, cy + h) }, p)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Drawable API") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun downXIn(v: View): Float { val loc = IntArray(2); v.getLocationOnScreen(loc); return downX - loc[0] }

    /** Swipes anywhere on a page flip it; the touch is cancelled for the views under it, so no button fires too. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (grid) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY }
            MotionEvent.ACTION_UP -> {
                val dx = ev.rawX - downX; val dy = ev.rawY - downY
                if (Math.abs(dx) > dp(64) && Math.abs(dx) > 1.5f * Math.abs(dy)) {
                    super.dispatchTouchEvent(MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL })
                    flip(if (dx < 0) 1 else -1)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** "Use this": saves the style and picks the face (installed as the settings always did), then opens its
     *  missing setup. Picture without a picture asks for one first and is picked with it. */
    private fun use(e: FacePicker.Entry) {
        val need = FacePicker.need(e, state())
        if (e.face == Aod.Face.PICTURE && need.setup == FacePicker.Setup.PHOTO) { setup(FacePicker.Setup.PHOTO, e); return }
        busy = true; render()
        Eink.post {
            val err = Aod.choose(this, e)
            Shell.main.post {
                busy = false
                if (isDestroyed) return@post
                if (err != null) toast("Didn't work: $err")
                render()
                if (err == null) need.setup?.let { setup(it, null) }
            }
        }
    }

    /** Opens the setting screen for [s]; [then] is picked once a photo is. */
    private fun setup(s: FacePicker.Setup, then: FacePicker.Entry?) {
        when (s) {
            FacePicker.Setup.CALENDAR -> askCalendar(fromSettings = true, ASK_CALENDAR)
            FacePicker.Setup.WEATHER -> section(MainActivity.SECTION_WEATHER)
            FacePicker.Setup.READER -> section(MainActivity.SECTION_READING)
            FacePicker.Setup.PHOTO -> {
                applyAfterPick = then
                runCatching { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICK_PHOTO) }
                    .onFailure { toast("No file picker") }
            }
        }
    }

    private fun section(name: String) {
        setupOpen = true
        startActivity(Intent(this, SectionActivity::class.java).putExtra(MainActivity.EXTRA_SECTION, name))
    }

    @Deprecated("Activity result API isn't used in this app (no AndroidX)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_PHOTO) return
        val then = applyAfterPick.also { applyAfterPick = null }
        val uri = data?.data?.takeIf { resultCode == RESULT_OK } ?: return
        busy = then != null; render()
        Eink.post {
            // Kept as the picture; the Photo or Picture face in use is installed again with it.
            val now = Aod.face(this)
            val err = SleepImages.savePicture(this, uri) ?: when {
                then != null -> Aod.choose(this, then)
                now == Aod.Face.PHOTO || now == Aod.Face.PICTURE -> Aod.setFace(this, now)
                else -> null
            }
            Shell.main.post {
                busy = false
                if (isDestroyed) return@post
                toast(err?.let { "Didn't work: $it" } ?: "Photo set")
                forget(); render()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != ASK_CALENDAR) return
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) toast("Calendar access needed for this face")
        Aod.prepare(this, force = true)
        forget(); render()
    }

    // ---- All ------------------------------------------------------------------------------------------

    private fun gridView(): View {
        val now = Aod.current(this)
        val cols = 6
        val body = vbox()
        body.addView(header("All screens", { grid = false; render() }, false))
        body.addView(text("Tap a screen to flip to it. Thick frame: in use.", Type.S, color = Ink.MUTED).apply { setPadding(dp(Space.GUTTER), 0, dp(Space.GUTTER), dp(4)) })
        val keys = HashSet<String>()
        for ((group, items) in FacePicker.groups(list)) {
            body.addView(sectionTitle(group.title, 0.8f))
            items.chunked(cols).forEach { row ->
                body.addView(hbox().apply {
                    gravity = Gravity.TOP
                    setPadding(dp(Space.GUTTER) - dp(3), dp(2), dp(Space.GUTTER) - dp(3), dp(2))
                    for ((i, e) in row) {
                        val on = FacePicker.inUse(e, now)
                        addView(vbox().apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                            val p = FacePreview(this@FacePickerActivity, ratio, if (e.face == Aod.Face.DEFAULT) "Moaan" else "…").apply { thick = on; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                            show("t:${e.key}", p); keys += "t:${e.key}"
                            addView(p, lp(MATCH, dp(if (land) 64 else 112)))
                            addView(text(e.label, Type.XS, bold = true).apply { gravity = Gravity.CENTER; maxLines = 2 }, lp(MATCH, WRAP).apply { topMargin = dp(2) })
                            if (on) addView(text("In use", Type.XS, bold = true, color = Ink.PAPER).apply {
                                gravity = Gravity.CENTER; background = outline(1, Radius.SMALL, Ink.INK); setPadding(dp(4), 0, dp(4), 0)
                            }, lp(WRAP, WRAP))
                            pressable()
                            contentDescription = e.title + if (on) ", in use" else ""
                            setOnClickListener { index = i; grid = false; render() }
                        }, lp(0, WRAP, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
                    }
                    repeat(cols - row.size) { addView(View(this@FacePickerActivity), lp(0, 1, 1f).apply { marginStart = dp(3); marginEnd = dp(3) }) }
                })
            }
        }
        body.addView(View(this), lp(MATCH, dp(16)))
        want(keys)
        return ScrollView(this).apply { isFillViewport = true; addView(body) }
    }

    // ---- Drawing --------------------------------------------------------------------------------------

    /** Puts [key]'s picture in [view] when it's drawn, else asks for it. */
    private fun show(key: String, view: FacePreview) {
        shown[key] = view
        val cache = if (key.startsWith("p:")) pages else tiles
        if (cache.containsKey(key)) view.bitmap = cache[key]
    }

    /** Draws the [keys] not drawn yet, one at a time off the main thread (the shown page first). */
    private fun want(keys: Set<String>) {
        wanted = keys
        val land = land
        for (key in keys) {
            val page = key.startsWith("p:")
            if ((if (page) pages else tiles).containsKey(key) || !queued.add(key)) continue
            val e = list.first { it.key == key.substring(2) }
            renderer.execute {
                val bmp = if (key in wanted) runCatching { Aod.thumbnail(this, e.face, e.agenda, e.photo, e.home, land, divisor = if (page) 2 else 4) }.getOrNull() else null
                val skipped = key !in wanted && bmp == null
                Shell.main.post {
                    queued -= key
                    if (isDestroyed || skipped) return@post
                    (if (page) pages else tiles)[key] = bmp
                    shown[key]?.bitmap = bmp
                }
            }
        }
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        private const val PICK_PHOTO = 61
        private const val ASK_CALENDAR = 62

        fun open(c: Context) = c.startActivity(Intent(c, FacePickerActivity::class.java))
    }
}
