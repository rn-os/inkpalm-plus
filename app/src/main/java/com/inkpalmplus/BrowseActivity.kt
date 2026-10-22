package com.inkpalmplus

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * "Browse files…" in the side panel's book list: Android's own file picker, and the file you pick opens in
 * your reader app ([Readers]). The panel lives in the accessibility service, which can't receive the picker's result, so this
 * invisible activity does: it starts the picker and finishes once the file is handed over (or the picker is closed).
 */
class BrowseActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return   // recreated while the picker is up: just wait for its result
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, BOOK_TYPES)
        runCatching { startActivityForResult(pick, PICK) }.onFailure { finish() }
    }

    @Deprecated("Activity result API isn't worth a dependency for one picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == PICK && resultCode == RESULT_OK && uri != null) {
            Readers.chosen(this)?.let { Readers.openUri(this, it, uri, contentResolver.getType(uri)) }
        }
        finish()
    }

    companion object {
        private const val PICK = 7
        /** What the readers open (NeoReader's and KOReader's VIEW intent-filters). */
        private val BOOK_TYPES = arrayOf(
            "application/epub+zip", "application/pdf", "application/x-mobipocket-ebook", "application/vnd.amazon.ebook",
            "application/x-fictionbook+xml", "application/vnd.comicbook+zip", "application/x-cbz", "application/zip",
            "text/plain", "text/markdown", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/octet-stream",
        )
    }
}
