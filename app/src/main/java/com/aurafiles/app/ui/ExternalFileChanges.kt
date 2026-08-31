package com.aurafiles.app.ui

import android.net.Uri

/**
 * Tiny in-process handoff for file mutations performed by secondary activities
 * (full-screen image viewer, similar-photo tool, etc.). The main browser consumes
 * these URIs on resume so a successfully deleted file cannot remain as a stale row.
 */
internal object ExternalFileChanges {
    private val deletedUris = LinkedHashSet<String>()

    @Synchronized
    fun recordDeleted(uri: Uri) {
        deletedUris += uri.toString()
    }

    @Synchronized
    fun recordDeleted(uris: Collection<Uri>) {
        uris.forEach { deletedUris += it.toString() }
    }

    @Synchronized
    fun consumeDeleted(): Set<Uri> {
        if (deletedUris.isEmpty()) return emptySet()
        val result = LinkedHashSet<Uri>(deletedUris.size)
        deletedUris.forEach { result += Uri.parse(it) }
        deletedUris.clear()
        return result
    }
}
