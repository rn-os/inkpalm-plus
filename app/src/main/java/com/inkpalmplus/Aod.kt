package com.inkpalmplus

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.util.Log
import android.view.Display
import android.view.Surface
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipFile

/**
 * The always-on sleep screen (KompaktOS; mockups 20, 21): a face drawn by InkPalm+ and handed to the vendor HWC as
 * its sleep picture (files/standby.png → bridge `apply=1` → /data/misc/standby.png).
 *
 * The HWC takes the picture AT the power-off transition, so the face must already be installed: while the screen is
 * on it's drawn again once a minute ([tick], only when what it shows changed) and when the screen turns ([prepare]).
 *
 * **Live** updates while asleep: an exact allow-while-idle alarm (InkPalm+ is Doze-whitelisted on KompaktOS) every
 * 5 / 15 / 30 / 60 min, aligned to the wall clock. [Alarm] draws the face and asks the bridge for `redraw=1`: it wakes
 * the screen with the front light held off and puts it back to sleep (~5 s), so the HWC draws the new picture. That
 * wake reaches InkPalm+ as SCREEN_ON / SCREEN_OFF, which [redrawWake] / [redrawSleep] swallow (no light, no auto
 * power-off reset); [WakeCover] shows the new face over everything during it, so the panel never shows the home
 * screen or the last app. Updates continue while charging (the cost is then free). Below [LOW_BATTERY] % on battery the
 * face becomes the low-battery box, drawn once, and the updates stop.
 */
object Aod {
    private const val TAG = "InkPalmPlus"
    private const val K_FACE = "aod_face"
    private const val K_INTERVAL = "aod_interval"
    private const val K_LOW_SHOWN = "aod_low_shown"
    const val LOW_BATTERY = 10
    val INTERVALS = listOf(0, 5, 15, 30, 60)
    const val DEFAULT_INTERVAL = 5

    /** [DEFAULT] is Moaan's own picture, [PICTURE] the user's picture as it is; the rest are drawn ([drawn]). */
    enum class Face(val title: String, val drawn: Boolean = true) {
        CLOCK("Clock"), CLOCK_DATE("Clock + date"), ANALOG("Analog"), AGENDA("Agenda"), READING("Reading"),
        PHOTO("Photo"), MINIMAL("Minimal"), PICTURE("Picture", false), DEFAULT("Default", false);

        companion object { fun of(s: String?): Face? = entries.firstOrNull { it.name == s } }
    }

    // ---- Pure parts (unit tested) -------------------------------------------------------------------

    /** NeoReader's "417/1367" → 0.305; null when it isn't one. */
    fun progressOf(s: String?): Float? {
        val (a, b) = s?.split("/")?.takeIf { it.size == 2 } ?: return null
        val page = a.trim().toIntOrNull() ?: return null
        val total = b.trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
        return (page.toFloat() / total).coerceIn(0f, 1f)
    }

    /** The shortest wait for a Live update: an alarm armed nearer than this (at a boundary, or just past one) would
     *  fire at once, back to back with the redraw that armed it. */
    const val MIN_DELAY_MS = 60_000L

    /** Milliseconds from [nowMs] to the next local wall-clock multiple of [minutes] (10:15, 10:20, …), never less than
     *  [MIN_DELAY_MS]: a boundary nearer than that (or reached already) is skipped for the one after. */
    fun delayToNext(nowMs: Long, tzOffsetMs: Long, minutes: Int): Long {
        val step = minutes.coerceAtLeast(1) * 60_000L
        val local = nowMs + tzOffsetMs
        var next = Math.floorDiv(local, step) * step
        while (next - local < MIN_DELAY_MS) next += step
        return next - local
    }

    /** Clockwise turn of the drawn face into the panel's portrait buffer, for a [Surface] rotation. Android's rotation
     *  is the rotation of the drawn graphics, so ROTATION_90 (graphics turned 90° clockwise) is +90. */
    fun degreesFor(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    fun isLow(level: Int, plugged: Boolean) = !plugged && level in 0 until LOW_BATTERY

    /** "About 4% of battery a day." and the detail, for an update interval (0 = Frozen). Estimates: ~3 s awake at
     *  ~400 mA per update (~0.33 mAh) plus the idle alarm wake, on a ~2500 mAh battery. */
    fun cost(minutes: Int): Pair<String, String> = when (minutes) {
        0 -> "Free." to "Drawn once, just before the screen goes off. The time shown is when you put the phone to sleep. No battery cost."
        else -> {
            val pct = when (minutes) { 5 -> "4"; 15 -> "1.3"; 30 -> "0.7"; 60 -> "0.3"; else -> "%.1f".format(Locale.US, 0.33 * 1440 / minutes / 25) }
            "About $pct% of battery a day." to "${1440 / minutes} wakes a day, each about 3 s awake plus one screen flash. " +
                "Estimate: ~3 s awake per update at ~400 mA, 2500 mAh battery. Stops below $LOW_BATTERY%."
        }
    }

    // ---- Settings ----------------------------------------------------------------------------------

    fun supported(): Boolean = SleepImages.supported()

    /** The face in use. Before faces existed, a picture of the user's (the flag, or one installed in /data/misc) is [Face.PICTURE]. */
    fun face(c: Context): Face = Face.of(Prefs(c).sp.getString(K_FACE, null))
        ?: if (SleepImages.customSleep(c) || INSTALLED.isFile) Face.PICTURE else Face.DEFAULT

    /** What the HWC shows at sleep (world-readable; written only by the bridge). */
    private val INSTALLED = File("/data/misc/standby.png")

    fun interval(c: Context): Int = Prefs(c).sp.getInt(K_INTERVAL, DEFAULT_INTERVAL)

    fun setInterval(c: Context, minutes: Int) {
        Prefs(c).sp.edit().putInt(K_INTERVAL, minutes).apply()
        prepare(c, force = true)
    }

    /** The user's picture, kept apart from files/standby.png (which holds the face being installed). */
    fun picture(c: Context) = File(c.filesDir, "sleep_picture.png")

    fun hasPicture(c: Context) = picture(c).isFile

    /** The whole picked picture (long side ≤ 1440), for the landscape crop. Pictures picked before it existed have none. */
    fun pictureSource(c: Context) = File(c.filesDir, "sleep_picture_src.png")

    fun hasPictureSource(c: Context) = pictureSource(c).isFile

    fun needsCalendar(c: Context) = c.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED

    /** Picks [face] and installs it. Error or null. Blocking (on [Eink.worker]). */
    fun setFace(c: Context, face: Face): String? {
        migrate(c)
        val err = when (face) {
            Face.DEFAULT -> if (Eink.bridgeWrite("standby.req", "reset=1\n")) null else "the bridge isn't there"
            Face.PICTURE -> if (!hasPicture(c)) "no picture yet" else runCatching {
                picture(c).copyTo(File(c.filesDir, "standby.png"), overwrite = true)
                if (Eink.bridgeWrite("standby.req", "apply=1\n")) null else "the bridge isn't there"
            }.getOrElse { it.toString() }
            else -> null
        }
        if (err != null) return err
        Prefs(c).sp.edit().putString(K_FACE, face.name).putBoolean("sleep_screen", face != Face.DEFAULT).apply()
        lastKey = null
        if (face.drawn) install(c, redraw = false, force = true)
        return null
    }

    /** Before faces: the picked picture lived only in files/standby.png (and /data/misc). Keep it as the picture. */
    private fun migrate(c: Context) {
        if (hasPicture(c) || Face.of(Prefs(c).sp.getString(K_FACE, null)) != null) return
        val old = File(c.filesDir, "standby.png").takeIf { it.isFile } ?: INSTALLED.takeIf { it.isFile } ?: return
        runCatching { old.copyTo(picture(c)) }.onSuccess { Log.i(TAG, "aod: kept the sleep picture ${old.path}") }
    }

    // ---- Drawing and installing --------------------------------------------------------------------

    /** What the installed face shows; drawn again only when it changes. */
    @Volatile private var lastKey: String? = null

    /** The screen is on: draw the face again if what it shows changed (the minute, battery, events, …). */
    fun tick(c: Context) {
        if (!supported() || !face(c).drawn || redrawing() || !interactive(c)) return
        prepare(c)
    }

    /** Draws and installs the face if it changed ([force]: anyway). Queued on [Eink.worker]. */
    fun prepare(c: Context, force: Boolean = false) {
        if (!supported() || !face(c).drawn) return
        val app = c.applicationContext
        Eink.post { install(app, redraw = false, force = force) }
    }

    /** Blocking. True when a new picture was written and handed to the bridge. */
    private fun install(c: Context, redraw: Boolean, force: Boolean, low: Boolean? = null): Boolean {
        val face = face(c)
        if (!face.drawn) return false
        val data = collect(c, face, low)
        val key = data.key(face)
        if (!force && !redraw && key == lastKey) return false
        val t0 = SystemClock.uptimeMillis()
        val bmp = AodFaces.render(face, data)
        val ok = runCatching {
            val tmp = File(c.filesDir, "standby.png.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(File(c.filesDir, "standby.png"))
        }.getOrDefault(false)
        if (!ok) { bmp.recycle(); Log.w(TAG, "aod: couldn't write the face"); return false }
        // The redraw's wake shows the new face at once (not the home screen) until the HWC draws it at the sleep.
        // The cover owns the bitmap from then on.
        val covered = redraw && WakeCover.show(bmp, "aod")
        if (!redraw) bmp.recycle()
        val sent = Eink.bridgeWrite("standby.req", if (redraw) "apply=1\nredraw=1\n" else "apply=1\n")
        if (covered && !sent) WakeCover.hide("request failed")
        if (sent) lastKey = key
        Log.i(TAG, "aod: ${face.name} ${if (redraw) "redraw" else "installed"} rot=${data.rotation} low=${data.low} in ${SystemClock.uptimeMillis() - t0} ms")
        return sent
    }

    /** A small picture of [face] with today's data, portrait, for the settings grid (null for Moaan's). Blocking. */
    fun thumbnail(c: Context, face: Face): Bitmap? {
        val big = when {
            face == Face.DEFAULT -> return null
            face == Face.PICTURE -> { photo(c); BitmapFactory.decodeFile(picture(c).path) ?: return null }
            else -> AodFaces.render(face, collect(c, face, false).copy(rotation = Surface.ROTATION_0))
        }
        return Bitmap.createScaledBitmap(big, AodFaces.W / 4, AodFaces.H / 4, true).also { big.recycle() }
    }

    /** A test face (debug hook): an arrow to the top of the screen as it's held, to check the turn. Blocking. */
    fun installArrow(c: Context, rotation: Int?) {
        val rot = rotation ?: displayRotation(c)
        val bmp = AodFaces.arrow(rot)
        File(c.filesDir, "standby.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        lastKey = null
        Eink.bridgeWrite("standby.req", "apply=1\n")
        Log.i(TAG, "aod: arrow face installed for rotation $rot")
    }

    private fun collect(c: Context, face: Face, lowOverride: Boolean?): AodFaces.Data {
        val bat = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.let { b ->
            val l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l < 0 || s <= 0) -1 else l * 100 / s
        } ?: -1
        val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val now = System.currentTimeMillis()
        val live = interval(c) > 0
        return AodFaces.Data(
            now = now, h24 = DateFormat.is24HourFormat(c), battery = level,
            low = lowOverride ?: isLow(level, plugged),
            live = live,
            events = if (face == Face.AGENDA) events(c, now) else null,
            book = if (face == Face.READING) book(c) else null,
            photo = if (face == Face.PHOTO) photo(c) else null,
            rotation = rotations.current(c),
        )
    }

    /** The orientation the face is drawn in; see [Orientation]. */
    private val rotations = Orientation()

    /** The screen turned while awake (or the strip was rebuilt): remembered as the held orientation. */
    fun noteRotation(c: Context) { if (!redrawing()) rotations.awake(displayRotation(c)) }

    /** The configuration changed. False when it must be ignored: asleep, or the redraw's own wake (the window manager
     *  and keyguard report portrait then). Otherwise the face is drawn again for the new orientation. */
    fun onConfigChanged(c: Context): Boolean {
        if (redrawing() || rotations.asleep) return false
        noteRotation(c)
        prepare(c)
        return true
    }

    fun displayRotation(c: Context): Int =
        c.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

    /** The next 3 events (up to a week ahead), or null without calendar access. */
    private fun events(c: Context, now: Long): List<AodFaces.Event>? {
        if (needsCalendar(c)) return null
        return runCatching {
            val out = ArrayList<AodFaces.Event>()
            val proj = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION)
            CalendarContract.Instances.query(c.contentResolver, proj, now - 86_400_000L, now + 7 * 86_400_000L)?.use { cur ->
                while (cur.moveToNext()) {
                    val allDay = cur.getInt(3) == 1
                    var begin = cur.getLong(1); var end = cur.getLong(2)
                    if (allDay) {   // stored as UTC midnights: the same dates, local
                        begin = utcDayToLocal(begin); end = utcDayToLocal(end)
                    }
                    if (end <= now) continue
                    out += AodFaces.Event(cur.getString(0)?.takeIf { it.isNotBlank() } ?: "(No title)", begin, end, allDay, cur.getString(4)?.takeIf { it.isNotBlank() })
                }
            }
            out.sortedWith(compareBy({ it.begin }, { !it.allDay })).take(3)
        }.onFailure { Log.w(TAG, "aod: calendar: $it") }.getOrDefault(emptyList())
    }

    private fun utcDayToLocal(utc: Long): Long {
        val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
        return Calendar.getInstance().apply { clear(); set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH)) }.timeInMillis
    }

    private var coverCache: Pair<String, Bitmap?>? = null

    private fun book(c: Context): AodFaces.Book? {
        val app = Readers.chosen(c) ?: return null
        val b = runCatching { Readers.lastRead(c, app) }.getOrNull() ?: return null
        val cover = coverCache?.takeIf { it.first == b.path }?.second ?: epubCover(b.path).also { coverCache = b.path to it }
        return AodFaces.Book(b.title, b.author, b.progress, b.page, cover)
    }

    /** An EPUB's cover picture (the OPF's cover item, else an image named "cover"), or null. Needs the file readable. */
    fun epubCover(path: String): Bitmap? = runCatching {
        if (!path.endsWith(".epub", true)) return null
        ZipFile(path).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            val opfPath = zip.getEntry("META-INF/container.xml")?.let { e ->
                Regex("""full-path="([^"]+)"""").find(zip.getInputStream(e).bufferedReader().readText())?.groupValues?.get(1)
            }
            var href: String? = null
            if (opfPath != null) zip.getEntry(opfPath)?.let { e ->
                val opf = zip.getInputStream(e).bufferedReader().readText()
                val items = Regex("""<item\b[^>]*>""").findAll(opf).map { it.value }.toList()
                fun attr(tag: String, a: String) = Regex("""\b$a="([^"]*)"""").find(tag)?.groupValues?.get(1)
                val id = Regex("""<meta\b[^>]*name="cover"[^>]*>""").find(opf)?.value?.let { attr(it, "content") }
                val item = items.firstOrNull { attr(it, "properties")?.contains("cover-image") == true }
                    ?: items.firstOrNull { id != null && attr(it, "id") == id && attr(it, "media-type")?.startsWith("image/") == true }
                href = item?.let { attr(it, "href") }?.let { h ->
                    val base = opfPath.substringBeforeLast('/', "")
                    java.net.URLDecoder.decode(if (base.isEmpty()) h else "$base/$h", "UTF-8")
                }
            }
            val entry = href?.takeIf { it in names }
                ?: names.firstOrNull { it.contains("cover", true) && Regex("""\.(jpe?g|png|gif|webp)$""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                ?: return null
            val bytes = zip.getInputStream(zip.getEntry(entry)).readBytes()
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= 400 && o.outHeight / (sample * 2) >= 560) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    private var photoCache: Pair<String, Bitmap?>? = null

    /** The picture for the Photo face: the whole one when there is one (cropped to the face's shape when drawn),
     *  else the portrait crop. */
    private fun photo(c: Context): Bitmap? {
        migrate(c)
        val f = pictureSource(c).takeIf { it.isFile } ?: picture(c)
        if (!f.isFile) return null
        val key = "${f.name}@${f.lastModified()}"
        photoCache?.takeIf { it.first == key }?.let { return it.second }
        return BitmapFactory.decodeFile(f.path).also { photoCache = key to it }
    }

    /**
     * The orientation the faces are drawn in. Awake it is the display's; the last one seen is kept, and at the user's
     * sleep it is frozen for every render until the user wakes: asleep (and in the bridge's redraw wake) Android reports
     * rotation 0, which would turn a landscape face to portrait.
     */
    class Orientation {
        @Volatile private var last = Surface.ROTATION_0
        @Volatile private var frozen: Int? = null
        val asleep: Boolean get() = frozen != null
        /** Awake, the display's rotation [r]. Ignored while frozen. */
        fun awake(r: Int) { if (frozen == null) last = r }
        /** The user's sleep: freeze the orientation they were holding the phone in. */
        fun sleep() { frozen = last }
        /** The user's wake: back to the display's rotation. */
        fun wake() { frozen = null }
        /** The rotation to draw with, [live] being the display's now (called only when needed). */
        fun current(live: () -> Int): Int = frozen ?: live().also { last = it }
        fun current(c: Context): Int = current { displayRotation(c) }
    }

    // ---- The redraw's own wake ---------------------------------------------------------------------

    /** A redraw (or auto power-off's predraw) InkPalm+ asked for is in flight until then (elapsedRealtime): its
     *  SCREEN_ON is not the user's. */
    @Volatile private var redrawUntil = 0L
    @Volatile private var redrawWoke = false
    /** Auto power-off is underway until then: no more Live updates (they would wake the panel during the shutdown). */
    @Volatile private var poweringOffUntil = 0L

    fun redrawing(): Boolean = SystemClock.elapsedRealtime() < redrawUntil

    /** The bridge is about to wake and sleep the screen for InkPalm+ within [ms]: that wake is not the user's. */
    fun expectWake(ms: Long) {
        redrawWoke = false
        redrawUntil = SystemClock.elapsedRealtime() + ms
    }

    /** Auto power-off fired ([AutoOff]): its predraw wake is ours, and the Live updates stop. */
    fun poweringOff(c: Context) {
        poweringOffUntil = SystemClock.elapsedRealtime() + 120_000L
        cancel(c)
        expectWake(30_000L)
    }

    private fun poweringOff(): Boolean = SystemClock.elapsedRealtime() < poweringOffUntil

    /** The redraw's wake didn't end (the bridge never put the screen back to sleep): it was the user's after all. */
    fun redrawWakeOver() { redrawUntil = 0; redrawWoke = false }

    /** SCREEN_ON: true when it's the redraw's wake (the caller then leaves the light off and the timers alone). */
    fun redrawWake(): Boolean {
        if (!redrawing()) return false
        redrawWoke = true
        return true
    }

    /** SCREEN_OFF: true when it ends the redraw's wake. */
    fun redrawSleep(): Boolean {
        if (!redrawing() || !redrawWoke) return false
        redrawUntil = 0; redrawWoke = false
        return true
    }

    // ---- Live updates ------------------------------------------------------------------------------

    /** Screen off (the user's): arm the next Live update. */
    fun onScreenOff(c: Context) {
        rotations.sleep()
        Prefs(c).sp.edit().putBoolean(K_LOW_SHOWN, false).apply()
        schedule(c)
    }

    /** Screen on (the user's): no updates while it's on; the face is drawn fresh for the next sleep. */
    fun onScreenOn(c: Context) {
        rotations.wake()
        noteRotation(c)
        cancel(c)
        tick(c)
    }

    fun schedule(c: Context, delayMs: Long? = null) {
        val minutes = interval(c)
        if (!supported() || !face(c).drawn || (minutes <= 0 && delayMs == null) || poweringOff()) return
        val now = System.currentTimeMillis()
        val wait = delayMs ?: delayToNext(now, TimeZone.getDefault().getOffset(now).toLong(), minutes)
        val at = SystemClock.elapsedRealtime() + wait
        val am = c.getSystemService(AlarmManager::class.java)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(c))
            else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(c))
        }.onSuccess { Log.i(TAG, "aod: next update in ${wait / 1000} s${if (exact) "" else " (inexact)"}") }
            .onFailure { Log.w(TAG, "aod: couldn't arm: $it") }
    }

    fun cancel(c: Context) = c.getSystemService(AlarmManager::class.java).cancel(pending(c))

    private fun pending(c: Context): PendingIntent = PendingIntent.getBroadcast(c.applicationContext, 1,
        Intent(c.applicationContext, Alarm::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun interactive(c: Context) = c.getSystemService(PowerManager::class.java).isInteractive

    /** One Live update, asleep. Blocking (on [Eink.worker]). */
    fun update(c: Context) {
        if (!supported() || !face(c).drawn || interactive(c) || poweringOff()) return
        val bat = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: -1
        val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val low = isLow(level, plugged)
        val sp = Prefs(c).sp
        if (low && sp.getBoolean(K_LOW_SHOWN, false)) { Log.i(TAG, "aod: battery $level%, updates stopped"); return }
        // The bridge wakes the screen for ~5 s; until its SCREEN_OFF, the wake is ours.
        expectWake(15_000L)
        if (!install(c, redraw = true, force = true, low = low)) { redrawUntil = 0; return }
        if (low) { sp.edit().putBoolean(K_LOW_SHOWN, true).apply(); Log.i(TAG, "aod: battery $level%, last update"); return }
        if (interval(c) > 0) schedule(c)
    }

    /** The Live update alarm. Holds a wake lock while the bridge wakes and sleeps the screen. Not exported: only
     *  InkPalm+'s own PendingIntent reaches it (and root: `am broadcast -n com.inkpalmplus/.Aod\$Alarm` is one
     *  update now, for checks on release builds, which have no test hooks; with `--es cover poweroff`, only the auto
     *  power-off cover for [WakeCover.TIMEOUT_MS], no power-off). */
    class Alarm : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val app = c.applicationContext
            Log.i(TAG, "aod: alarm${i.getStringExtra("cover")?.let { " (cover $it)" } ?: ""}")
            val wl = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InkPalmPlus:aod")
            wl.acquire(12_000L)
            Eink.init(app)
            val done = goAsync()
            Eink.post {
                try {
                    if (i.getStringExtra("cover") == "poweroff") AutoOff.showCover(app) else update(app)
                } finally { done.finish() }
            }
        }
    }
}
