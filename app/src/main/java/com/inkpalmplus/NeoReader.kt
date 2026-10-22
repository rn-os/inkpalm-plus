package com.inkpalmplus

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * NeoReader (com.onyx.kreader, ported to the InkPalm) in the side panel (mockup: docs/mockups/13-last-read.png).
 *
 * NeoReader keeps its book list in an exported, permission-free database:
 * `content://com.onyx.content.database.ContentProvider/Metadata`, one row per book with its title, file path and
 * when it was last opened (`lastAccess`, ms). The newest is the "Last read" book. Only those columns are read, and
 * only with query (the provider would also accept writes). The manifest's <queries> makes it visible on Android 11.
 *
 * Opening a book ([Readers.open]): VIEW + file:// + mime to ReaderHomeActivity, which hands it to a reader tab at the last position.
 * NeoReader's own library ("Doc") is a dialog inside an open book and can't be opened from outside, so the panel
 * lists the books itself, and "Browse files…" uses Android's file picker ([BrowseActivity]).
 */
object NeoReader {
    const val PACKAGE = "com.onyx.kreader"
    const val HOME = "com.onyx.kreader.ui.ReaderHomeActivity"
    private const val TAG = "InkPalmPlus"
    private val METADATA: Uri = Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata")

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /** Every book NeoReader has opened, newest first. Call off the main thread when you can (a cross-app query). */
    fun books(context: Context): List<Readers.Book> = runCatching {
        val out = ArrayList<Readers.Book>()
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
                out += Readers.Book(title, path, at)
            }
        }
        out.sortedByDescending { it.at }
    }.onFailure { Log.w(TAG, "NeoReader's book list isn't readable: $it") }.getOrDefault(emptyList())

}
