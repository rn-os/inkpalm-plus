package com.inkpalmplus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * The pictures the vendor HWC draws when the screen goes off (KompaktOS; see InkPalm-KompaktOS docs/sleep-screen.md):
 * `/data/misc/standby*.png` at sleep, `/data/misc/poweroff{,_nopower}.png` at shutdown, falling back to Moaan's own
 * art in /vendor/media. Apps can't write /data/misc, so InkPalm+ saves a PNG in its files dir and the bridge
 * installs it (standby.req: `apply=1` / `reset=1`, `poweroff=1` / `poweroff_reset=1`).
 */
object SleepImages {
    private const val TAG = "InkPalmPlus"
    private const val K_SLEEP = "sleep_screen"
    private const val K_POWEROFF = "poweroff_screen"

    enum class PowerOff(val title: String) { OFF("Moaan's"), PANDA("Panda"), CUSTOM("Your picture") }

    fun supported(): Boolean = Eink.bridgeAvailable()

    /** A picture of the user's is the sleep screen (else Moaan's). */
    fun customSleep(c: Context) = Prefs(c).sp.getBoolean(K_SLEEP, false)
    fun powerOff(c: Context): PowerOff = runCatching { PowerOff.valueOf(Prefs(c).sp.getString(K_POWEROFF, null) ?: "OFF") }.getOrDefault(PowerOff.OFF)

    // ---- Sleep screen -------------------------------------------------------------------------------

    /** Null when done, else what went wrong. Blocking (decodes and writes a PNG). */
    fun setSleep(c: Context, uri: Uri): String? {
        val err = writePicked(c, uri, "standby.png") ?: if (Eink.bridgeWrite("standby.req", "apply=1\n")) null else "the bridge isn't there"
        if (err == null) Prefs(c).sp.edit().putBoolean(K_SLEEP, true).apply()
        return err
    }

    fun resetSleep(c: Context): Boolean = Eink.bridgeWrite("standby.req", "reset=1\n").also {
        if (it) Prefs(c).sp.edit().putBoolean(K_SLEEP, false).apply()
    }

    // ---- Power-off screen ---------------------------------------------------------------------------

    /** Moaan's (bridge removes ours), the panda in English ([composePanda]), or a picture ([uri]). Blocking. */
    fun setPowerOff(c: Context, choice: PowerOff, uri: Uri? = null): String? {
        val err = when (choice) {
            PowerOff.OFF -> if (Eink.bridgeWrite("standby.req", "poweroff_reset=1\n")) null else "the bridge isn't there"
            PowerOff.PANDA -> composePanda(c) ?: install()
            PowerOff.CUSTOM -> if (uri == null) "no picture" else writePicked(c, uri, "poweroff.png") ?: run {
                File(c.filesDir, "poweroff.png").copyTo(File(c.filesDir, "poweroff_nopower.png"), overwrite = true)
                install()
            }
        }
        if (err == null) Prefs(c).sp.edit().putString(K_POWEROFF, choice.name).apply()
        else Log.w(TAG, "power-off screen ${choice.name}: $err")
        return err
    }

    // TODO(bridge): eink-apply.sh installs files/poweroff.png as both poweroff.png and poweroff_nopower.png, so
    //  the "Battery low" variant written here (files/poweroff_nopower.png) isn't used yet; the bridge should copy it
    //  when present. Whether the HWC shows /data/misc/poweroff.png at a KompaktOS shutdown (sys.power.shutdown=1,
    //  set by Moaan's services.jar on stock) is being verified; if it needs a trigger, [onShutdown] is the hook.
    private fun install(): String? = if (Eink.bridgeWrite("standby.req", "poweroff=1\n")) null else "the bridge isn't there"

    /** The device is shutting down (ACTION_SHUTDOWN, from the service). Hook for a bridge-side trigger, see the TODO. */
    fun onShutdown(c: Context) {
        if (powerOff(c) != PowerOff.OFF) Log.i(TAG, "shutdown: power-off screen ${powerOff(c).name}")
    }

    /** The band of Chinese text on Moaan's power-off art (y 1040–1240 on the 720×1440 panel). */
    fun band(height: Int): IntRange = (height * 1040 / 1440)..(height * 1240 / 1440)

    /**
     * Moaan's own panda pictures, read from the device (never shipped in this repo), with the Chinese text band
     * whited out and English drawn in its place: "Powered off" / "Hold the power button to start", and for the
     * low-battery one "Battery low — please charge".
     */
    private fun composePanda(c: Context): String? {
        val pairs = listOf("poweroff" to "Hold the power button to start", "poweroff_nopower" to "Battery low — please charge")
        for ((name, line) in pairs) {
            val src = BitmapFactory.decodeFile("/vendor/media/$name.png") ?: return "can't read /vendor/media/$name.png"
            val out = src.copy(Bitmap.Config.ARGB_8888, true)
            src.recycle()
            val canvas = Canvas(out)
            val b = band(out.height)
            canvas.drawRect(0f, b.first.toFloat(), out.width.toFloat(), b.last.toFloat(), Paint().apply { color = Ink.PAPER })
            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Ink.INK; textAlign = Paint.Align.CENTER
                typeface = Typeface.create("sans-serif", Typeface.BOLD); textSize = out.width * 0.075f
            }
            val sub = Paint(title).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL); textSize = out.width * 0.042f }
            val mid = (b.first + b.last) / 2f
            canvas.drawText("Powered off", out.width / 2f, mid - out.height * 0.006f, title)
            canvas.drawText(line, out.width / 2f, mid + out.height * 0.04f, sub)
            File(c.filesDir, "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out.recycle()
        }
        return null
    }

    /** The picked image, centre-cropped to the panel, gray, as files/[name]. Null when done, else what went wrong. */
    private fun writePicked(c: Context, uri: Uri, name: String): String? = runCatching {
        val dm = c.resources.displayMetrics
        val w = minOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(720)
        val h = maxOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(1440)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return "not a picture"
        val o = BitmapFactory.Options()
        while (bounds.outWidth / (o.inSampleSize * 2) >= w && bounds.outHeight / (o.inSampleSize * 2) >= h) o.inSampleSize *= 2
        val src = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return "couldn't read the picture"
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val cw = (w / scale).toInt(); val ch = (h / scale).toInt()
        val from = Rect((src.width - cw) / 2, (src.height - ch) / 2, (src.width + cw) / 2, (src.height + ch) / 2)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val gray = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        Canvas(out).apply { drawColor(Ink.PAPER); drawBitmap(src, from, Rect(0, 0, w, h), gray) }
        val f = File(c.filesDir, name)
        f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (f.length() > 10L * 1024 * 1024) "the picture is too big" else null
    }.getOrElse { it.toString() }
}
