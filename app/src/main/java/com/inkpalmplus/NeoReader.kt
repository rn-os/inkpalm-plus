package com.inkpalmplus

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StrictMode
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File

/**
 * NeoReader (com.onyx.kreader, ported to the InkPalm) in the side panel (mockup: docs/mockups/13-last-read.png).
 *
 * NeoReader keeps its book list in an exported, permission-free database:
 * `content://com.onyx.content.database.ContentProvider/Metadata`, one row per book with its title, file path and
 * when it was last opened (`lastAccess`, ms). The newest is the "Last read" book. Only those columns are read, and
 * only with query (the provider would also accept writes). The manifest's <queries> makes it visible on Android 11.
 *
 * Opening a book: VIEW + file:// + mime to ReaderHomeActivity, which hands it to a reader tab at the last position.
 * NeoReader's own library ("Doc") is a dialog inside an open book and can't be opened from outside, so the panel
 * lists the books itself, and "Browse files…" uses Android's file picker ([BrowseActivity]).
 */
object NeoReader {
    const val PACKAGE = "com.onyx.kreader"
    private const val HOME = "com.onyx.kreader.ui.ReaderHomeActivity"
    private const val TAG = "InkPalmPlus"
    private val METADATA: Uri = Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata")

    class Book(val title: String, val path: String, val at: Long) {
        val format: String get() = File(path).extension.uppercase()
    }

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /** Every book NeoReader has opened, newest first. Call off the main thread when you can (a cross-app query). */
    fun books(context: Context): List<Book> = runCatching {
        val out = ArrayList<Book>()
        context.contentResolver.query(METADATA, arrayOf("title", "name", "nativeAbsolutePath", "lastAccess"),
            null, null, null)?.use { c ->
            val iTitle = c.getColumnIndex("title")
            val iName = c.getColumnIndex("name")
            val iPath = c.getColumnIndex("nativeAbsolutePath")
            val iAt = c.getColumnIndex("lastAccess")
            while (c.moveToNext()) {
                val path = if (iPath >= 0) c.getString(iPath) else null
                if (path.isNullOrBlank()) continue
                val at = if (iAt >= 0) runCatching { c.getLong(iAt) }.getOrDefault(0L) else 0L
                if (at <= 0) continue   // in the library but never opened
                val title = (if (iTitle >= 0) c.getString(iTitle) else null)?.takeIf { it.isNotBlank() }
                    ?: (if (iName >= 0) c.getString(iName) else null)?.takeIf { it.isNotBlank() }
                    ?: File(path).nameWithoutExtension
                out += Book(title, path, at)
            }
        }
        out.sortedByDescending { it.at }
    }.onFailure { Log.w(TAG, "NeoReader's book list isn't readable: $it") }.getOrDefault(emptyList())

    fun lastRead(context: Context): Book? = books(context).firstOrNull()

    /** Opens [path] in NeoReader, at the page it was last on. */
    fun open(context: Context, path: String): Boolean {
        val file = File(path)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_VIEW).setClassName(PACKAGE, HOME)
            .setDataAndType(Uri.fromFile(file), mime)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // NeoReader opens books by their real path; Android refuses to send a file:// Uri from apps targeting
        // SDK 24+ unless the VM policy allows it, so it's relaxed for this one call (as SuperBoox does).
        val policy = StrictMode.getVmPolicy()
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
        return try {
            context.startActivity(intent)
            Log.i(TAG, "opened in NeoReader: ${file.name}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "couldn't open ${file.name} in NeoReader: $e")
            false
        } finally {
            StrictMode.setVmPolicy(policy)
        }
    }

    /** Opens a file picked in Android's file picker (a content:// Uri) in NeoReader. */
    fun openUri(context: Context, uri: Uri, mime: String?): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).setClassName(PACKAGE, HOME)
            .setDataAndType(uri, mime ?: "application/octet-stream")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        Log.i(TAG, "opened a picked file in NeoReader")
        true
    }.onFailure { Log.w(TAG, "couldn't open the picked file in NeoReader: $it") }.getOrDefault(false)
}
