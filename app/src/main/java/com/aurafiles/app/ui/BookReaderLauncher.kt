package com.aurafiles.app.ui

import android.content.Context
import android.content.Intent
import com.aurafiles.app.model.FileEntry
import ru.chitets.app.store.LibraryStore
import ru.chitets.app.ui.BookReaderContract
import ru.chitets.app.ui.ComicActivity
import ru.chitets.app.ui.DjvuActivity
import ru.chitets.app.ui.PdfActivity
import ru.chitets.app.ui.ReaderActivity

internal fun openBookReader(context: Context, entry: FileEntry) {
    val format = LibraryStore.inferFormat(entry.name, entry.mimeType)
    val activity = when (format) {
        "PDF" -> PdfActivity::class.java
        "DJVU" -> DjvuActivity::class.java
        "CBZ", "CBR" -> ComicActivity::class.java
        else -> ReaderActivity::class.java
    }
    context.startActivity(
        Intent(context, activity).apply {
            putExtra(BookReaderContract.EXTRA_URI, entry.uri.toString())
            putExtra(BookReaderContract.EXTRA_TITLE, entry.name.substringBeforeLast('.', entry.name))
            putExtra(BookReaderContract.EXTRA_FORMAT, format)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    )
}
