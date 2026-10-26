package com.inkpalmplus

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StrictMode
import android.provider.Settings
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File

/**
 * The reader app whose last book sits at the top of the side panel (mockup: docs/mockups/14-reader-app.png).
 * Picked once on the home screen (only readers that are installed are offered), changeable there later.
 * [NeoReader] needs nothing extra; [KOReader] needs Android's All files access to read its history.
 */
object Readers {
    private const val TAG = "InkPalmPlus"

    enum class App(val key: String, val label: String) {
        NEOREADER("neoreader", "NeoReader"),
        KOREADER("koreader", "KOReader");

        companion object {
            fun of(key: String?): App? = entries.firstOrNull { it.key == key }
        }
    }

    /** [author] and [progress] (0..1) when the reader keeps them (NeoReader's `authors` and `progress` "page/total",
     *  KOReader's `percent_finished`); [page] is "417 / 1367" for NeoReader. */
    class Book(val title: String, val path: String, val at: Long, val author: String? = null, val progress: Float? = null, val page: String? = null) {
        val format: String get() = File(path).extension.uppercase()
    }

    fun installed(context: Context, app: App): Boolean = when (app) {
        App.NEOREADER -> NeoReader.installed(context)
        App.KOREADER -> KOReader.packageName(context) != null
    }

    fun installedApps(context: Context): List<App> = App.entries.filter { installed(context, it) }

    /** The reader the user picked, if it's still installed; null for "No reader" or not picked yet. */
    fun chosen(context: Context): App? = App.of(Prefs(context).readerApp)?.takeIf { installed(context, it) }

    /** Whether InkPalm+ may read [app]'s book list. */
    fun hasAccess(app: App): Boolean = when (app) {
        App.NEOREADER -> true
        App.KOREADER -> Environment.isExternalStorageManager()
    }

    /** Android's All files access screen for InkPalm+ (KOReader keeps its history in shared storage). */
    fun accessIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Every book [app] has opened, newest first. Off the main thread when you can. */
    fun books(context: Context, app: App): List<Book> = when (app) {
        App.NEOREADER -> NeoReader.books(context)
        App.KOREADER -> if (hasAccess(app)) KOReader.books() else emptyList()
    }

    /** The [n] latest books (titles looked up for those only). */
    fun recent(context: Context, app: App, n: Int): List<Book> = when (app) {
        App.NEOREADER -> NeoReader.books(context).take(n)
        App.KOREADER -> if (hasAccess(app)) KOReader.books(titles = n).take(n) else emptyList()
    }

    fun lastRead(context: Context, app: App): Book? = when (app) {
        App.NEOREADER -> NeoReader.books(context).firstOrNull()
        App.KOREADER -> if (hasAccess(app)) KOReader.books(titles = 1).firstOrNull() else null
    }

    /** Opens [path] in [app], at the page it was last on. */
    fun open(context: Context, app: App, path: String): Boolean {
        val file = File(path)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val intent = target(context, app).setDataAndType(Uri.fromFile(file), mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Both readers open books by their real path; Android refuses to send a file:// Uri from apps targeting
        // SDK 24+ unless the VM policy allows it, so it's relaxed for this one call (as SuperBoox does).
        val policy = StrictMode.getVmPolicy()
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
        return try {
            context.startActivity(intent)
            Log.i(TAG, "opened in ${app.label}: ${file.name}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "couldn't open ${file.name} in ${app.label}: $e")
            false
        } finally {
            StrictMode.setVmPolicy(policy)
        }
    }

    /** Opens a file picked in Android's file picker (a content:// Uri) in [app]. */
    fun openUri(context: Context, app: App, uri: Uri, mime: String?): Boolean = runCatching {
        context.startActivity(target(context, app).setDataAndType(uri, mime ?: "application/octet-stream")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        Log.i(TAG, "opened a picked file in ${app.label}")
        true
    }.onFailure { Log.w(TAG, "couldn't open the picked file in ${app.label}: $it") }.getOrDefault(false)

    private fun target(context: Context, app: App): Intent = Intent(Intent.ACTION_VIEW).apply {
        when (app) {
            App.NEOREADER -> setClassName(NeoReader.PACKAGE, NeoReader.HOME)
            App.KOREADER -> setPackage(KOReader.packageName(context))
        }
    }
}

/**
 * KOReader keeps its reading history in `/sdcard/koreader/history.lua` (a Lua table of `time` in seconds and
 * `file` paths), and each book's details next to the book, in `<name>.sdr/metadata.<ext>.lua`, where
 * `doc_props.title` is its title. Both are read only, and only with All files access.
 */
object KOReader {
    /** KOReader's builds: Play/GitHub, F-Droid. */
    private val PACKAGES = listOf("org.koreader.launcher", "org.koreader.launcher.fdroid")
    private val TIME = Regex("""\["time"]\s*=\s*(\d+)""")
    private val FILE = Regex("""\["file"]\s*=\s*"((?:[^"\\]|\\.)*)"""")
    private val TITLE = Regex("""\["title"]\s*=\s*"((?:[^"\\]|\\.)*)"""")
    private val AUTHORS = Regex("""\["authors"]\s*=\s*"((?:[^"\\]|\\.)*)"""")
    private val PERCENT = Regex("""\["percent_finished"]\s*=\s*([0-9.eE+-]+)""")

    fun packageName(context: Context): String? =
        PACKAGES.firstOrNull { runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess }

    /** The history, newest first; titles are looked up for the first [titles] books (the rest on demand is cheap too). */
    fun books(titles: Int = Int.MAX_VALUE): List<Readers.Book> = runCatching {
        val history = File(Environment.getExternalStorageDirectory(), "koreader/history.lua")
        // Entries are `[n] = { ["time"] = …, ["file"] = "…", },`; split on the entry openers.
        history.readText().split(Regex("""\[\d+]\s*=\s*\{""")).drop(1).mapNotNull { entry ->
            val time = TIME.find(entry)?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
            val path = FILE.find(entry)?.groupValues?.get(1)?.let(::unescape) ?: return@mapNotNull null
            if (!File(path).exists()) null else path to time * 1000
        }.sortedByDescending { it.second }.mapIndexed { i, (path, at) ->
            if (i < titles) meta(path, at) else Readers.Book(File(path).nameWithoutExtension, path, at)
        }
    }.onFailure { Log.w("InkPalmPlus", "KOReader's history isn't readable: $it") }.getOrDefault(emptyList())

    /** The book's title, author and how far in, from `<name>.sdr/metadata.<ext>.lua`. */
    private fun meta(path: String, at: Long): Readers.Book {
        val f = File(path)
        val text = runCatching { File(f.parentFile, "${f.nameWithoutExtension}.sdr/metadata.${f.extension.lowercase()}.lua").readText() }.getOrNull()
        fun str(r: Regex) = text?.let { r.find(it)?.groupValues?.get(1)?.let(::unescape) }?.takeIf { it.isNotBlank() }
        val pct = text?.let { PERCENT.find(it)?.groupValues?.get(1)?.toFloatOrNull() }?.coerceIn(0f, 1f)
        return Readers.Book(str(TITLE) ?: f.nameWithoutExtension, path, at, str(AUTHORS), pct)
    }

    private fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\")
}
